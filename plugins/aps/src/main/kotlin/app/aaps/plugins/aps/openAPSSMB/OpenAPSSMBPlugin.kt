package app.aaps.plugins.aps.openAPSSMB

import android.content.Context
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.DetermineBasalAdapter
import app.aaps.core.interfaces.aps.DynamicISFPlugin
import app.aaps.core.interfaces.aps.SMBDefaults
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.iob.GlucoseStatus
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.MealData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.main.constraints.ConstraintObject
import app.aaps.core.main.extensions.target
import app.aaps.core.utils.MidnightUtils
import app.aaps.database.ValueWrapper
import app.aaps.database.impl.AppRepository
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.utils.ScriptReader
import dagger.android.HasAndroidInjector
import org.joda.time.LocalTime
import org.joda.time.format.ISODateTimeFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor

@Singleton
open class OpenAPSSMBPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    protected val rxBus: RxBus,
    protected val constraintChecker: ConstraintsChecker,
    rh: ResourceHelper,
    protected val profileFunction: ProfileFunction,
    val context: Context,
    protected val activePlugin: ActivePlugin,
    protected val iobCobCalculator: IobCobCalculator,
    protected val hardLimits: HardLimits,
    protected val profiler: Profiler,
    protected val sp: SP,
    protected val dateUtil: DateUtil,
    protected val repository: AppRepository,
    protected val glucoseStatusProvider: GlucoseStatusProvider,
    protected val bgQualityCheck: BgQualityCheck,
    protected val tddCalculator: TddCalculator
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openapssmb)
        .shortName(app.aaps.core.ui.R.string.smb_shortname)
        .preferencesId(R.xml.pref_openapssmb)
        .description(R.string.description_smb)
        .setDefault(),
    aapsLogger, rh, injector
), APS, PluginConstraints {

    var dynIsfEnabled: Constraint<Boolean> = ConstraintObject(false, aapsLogger)

    // last values
    override var lastAPSRun: Long = 0
    override var lastAPSResult: DetermineBasalResultSMB? = null
    override var lastDetermineBasalAdapter: DetermineBasalAdapter? = null
    override var lastAutosensResult = AutosensResult()

    private var lastNigtModeRun: Long = 0
    private var lastNigtModeResult: Boolean = false

    private var profileShared: Profile? = null
    private var glucoseStatusShared : GlucoseStatus? = null
    private var mealDataShared : MealData? = null

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (ignored: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        val pump = activePlugin.activePump
        return pump.pumpDescription.isTempBasalCapable
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)
        val smbAlwaysEnabled = sp.getBoolean(R.string.key_enableSMB_always, false)
        preferenceFragment.findPreference<SwitchPreference>(rh.gs(R.string.key_enableSMB_with_COB))?.isVisible = !smbAlwaysEnabled
        preferenceFragment.findPreference<SwitchPreference>(rh.gs(R.string.key_enableSMB_with_temptarget))?.isVisible = !smbAlwaysEnabled
        preferenceFragment.findPreference<SwitchPreference>(rh.gs(R.string.key_enableSMB_after_carbs))?.isVisible = !smbAlwaysEnabled
    }

    private fun verifyProfileLoaded(allowCurrent : Boolean = false) : Profile? {
        if (profileShared == null || !allowCurrent) profileFunction.getProfile().also { profileShared = it }
        return profileShared
    }

    private fun verifyGlucoseStatusLoaded(allowCurrent : Boolean = false) : GlucoseStatus? {
        if (glucoseStatusShared == null || !allowCurrent) glucoseStatusProvider.glucoseStatusData.also { glucoseStatusShared = it }
        return glucoseStatusShared
    }

    private fun verifyMealDataLoaded(allowCurrent : Boolean = false) : MealData {
        if (mealDataShared == null || !allowCurrent) mealDataShared = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        return mealDataShared!!
    }

    private fun cleanShared() {
        profileShared = null
        glucoseStatusShared = null
        mealDataShared = null
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val profile = verifyProfileLoaded()
        val glucoseStatus = verifyGlucoseStatusLoaded()
        val mealData = verifyMealDataLoaded()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results
        val maxBasal = constraintChecker.getMaxBasalAllowed(profile).also {
            inputConstraints.copyReasons(it)
        }.value()
        var start = System.currentTimeMillis()
        var startPart = System.currentTimeMillis()
        profiler.log(LTag.APS, "getMealData()", startPart)
        val maxIob = constraintChecker.getMaxIOBAllowed().also { maxIOBAllowedConstraint ->
            inputConstraints.copyReasons(maxIOBAllowedConstraint)
        }.value()

        var minBg =
            hardLimits.verifyHardLimits(
                Round.roundTo(profile.getTargetLowMgdl(), 0.1),
                app.aaps.core.ui.R.string.profile_low_target,
                HardLimits.VERY_HARD_LIMIT_MIN_BG[0],
                HardLimits.VERY_HARD_LIMIT_MIN_BG[1]
            )
        var maxBg =
            hardLimits.verifyHardLimits(
                Round.roundTo(profile.getTargetHighMgdl(), 0.1),
                app.aaps.core.ui.R.string.profile_high_target,
                HardLimits.VERY_HARD_LIMIT_MAX_BG[0],
                HardLimits.VERY_HARD_LIMIT_MAX_BG[1]
            )
        var targetBg =
            hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.VERY_HARD_LIMIT_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TARGET_BG[1])
        var isTempTarget = false
        val tempTarget = repository.getTemporaryTargetActiveAt(dateUtil.now()).blockingGet()
        if (tempTarget is ValueWrapper.Existing) {
            isTempTarget = true
            minBg =
                hardLimits.verifyHardLimits(
                    tempTarget.value.lowTarget,
                    app.aaps.core.ui.R.string.temp_target_low_target,
                    HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[1].toDouble()
                )
            maxBg =
                hardLimits.verifyHardLimits(
                    tempTarget.value.highTarget,
                    app.aaps.core.ui.R.string.temp_target_high_target,
                    HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[1].toDouble()
                )
            targetBg =
                hardLimits.verifyHardLimits(
                    tempTarget.value.target(),
                    app.aaps.core.ui.R.string.temp_target_value,
                    HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[1].toDouble()
                )
        }
        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl(), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return
        startPart = System.currentTimeMillis()

        if (constraintChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                return
            }
            lastAutosensResult = autosensData.autosensResult
        }
        else {
            lastAutosensResult.sensResult = "autosens disabled"
        }
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(lastAutosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        profiler.log(LTag.APS, "calculateIobArrayInDia()", startPart)
        startPart = System.currentTimeMillis()
        val smbAllowed = ConstraintObject(!tempBasalFallback, aapsLogger).also {
            constraintChecker.isSMBModeEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val advancedFiltering = ConstraintObject(!tempBasalFallback, aapsLogger).also {
            constraintChecker.isAdvancedFilteringEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val uam = ConstraintObject(true, aapsLogger).also {
            constraintChecker.isUAMEnabled(it)
            inputConstraints.copyReasons(it)
        }
        dynIsfEnabled = ConstraintObject(true, aapsLogger).also {
            constraintChecker.isDynIsfModeEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT
        profiler.log(LTag.APS, "detectSensitivityAndCarbAbsorption()", startPart)
        profiler.log(LTag.APS, "SMB data gathering", start)
        start = System.currentTimeMillis()

        provideDetermineBasalAdapter().also { determineBasalAdapterSMBJS ->
            determineBasalAdapterSMBJS.setData(
                profile, maxIob, maxBasal, minBg, maxBg, targetBg,
                activePlugin.activePump.baseBasalRate,
                iobArray,
                glucoseStatus,
                mealData,
                lastAutosensResult.ratio,
                isTempTarget,
                smbAllowed.value(),
                uam.value(),
                advancedFiltering.value(),
                flatBGsDetected
            )
            val now = System.currentTimeMillis()
            val determineBasalResultSMB = determineBasalAdapterSMBJS.invoke()
            profiler.log(LTag.APS, "SMB calculation", start)
            if (determineBasalResultSMB == null) {
                aapsLogger.error(LTag.APS, "SMB calculation returned null")
                lastDetermineBasalAdapter = null
                lastAPSResult = null
                lastAPSRun = 0
            } else {
                // TODO still needed with oref1?
                // Fix bug determine basal
                if (determineBasalResultSMB.rate == 0.0 && determineBasalResultSMB.duration == 0 && iobCobCalculator.getTempBasalIncludingConvertedExtended(dateUtil.now()) == null) determineBasalResultSMB.isTempBasalRequested =
                    false
                determineBasalResultSMB.iob = iobArray[0]
                determineBasalResultSMB.json?.put("timestamp", dateUtil.toISOString(now))
                determineBasalResultSMB.inputConstraints = inputConstraints
                lastDetermineBasalAdapter = determineBasalAdapterSMBJS
                lastAPSResult = determineBasalResultSMB as DetermineBasalResultSMB
                lastAPSRun = now
            }
        }
        rxBus.send(EventOpenAPSUpdateGui())
        cleanShared()
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref: Double = sp.getDouble(R.string.key_openapssmb_max_iob, 3.0)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = sp.getDouble(R.string.key_openapsma_max_basal, 1.0)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = sp.getDouble(R.string.key_openapsama_max_daily_safety_multiplier, 3.0)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!sp.getBoolean(R.string.key_use_smb, false)) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        else if (isNightModeActive()) value.set(false, rh.gs(app.aaps.core.ui.R.string.treatment_safety_night_mode_smb_disabled), this)
        return value
    }

    private fun isNightModeActive() : Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        val timeAligned = currentTimeMillis - (currentTimeMillis % 1000)
        if (lastNigtModeRun >= timeAligned) return lastNigtModeResult

        lastNigtModeResult = isNightModeActiveImpl()
        lastNigtModeRun = timeAligned
        return lastNigtModeResult
    }

    private fun isNightModeActiveImpl() : Boolean {
        val bgCurrent = verifyGlucoseStatusLoaded(true)?.glucose
        if (sp.getBoolean(app.aaps.core.utils.R.string.key_treatment_safety_night_mode_enabled, false) && bgCurrent != null) {

            val currentTimeMillis = System.currentTimeMillis()
            val midnight = MidnightTime.calc(currentTimeMillis)
            val startHour = sp.getString(app.aaps.core.utils.R.string.key_treatment_safety_night_mode_start, "22:00")
            val start = midnight + LocalTime.parse(startHour, ISODateTimeFormat.timeElementParser()).millisOfDay
            val endHour = sp.getString(app.aaps.core.utils.R.string.key_treatment_safety_night_mode_end, "7:00");
            val end = midnight + LocalTime.parse(endHour, ISODateTimeFormat.timeElementParser()).millisOfDay
            val bgOffset = sp.getDouble(app.aaps.core.utils.R.string.key_treatment_safety_night_mode_bg_offset, 27.0)
            val active =
                if (end > start) currentTimeMillis in start..<end
                else (currentTimeMillis in (start - 86400000)..<end || currentTimeMillis in start..<(end + 86400000))

            if (!active) return false

            if (sp.getBoolean(app.aaps.core.utils.R.string.key_treatment_safety_night_mode_disable_with_cob, false)) {
                val mealData = verifyMealDataLoaded(true)
                if (mealData.mealCOB > 0) return false
            }

            val profile = verifyProfileLoaded(true)
            val profileTarget = profile?.getTargetMgdl() ?: 99.0

            if (sp.getBoolean(app.aaps.core.utils.R.string.key_treatment_safety_night_mode_disable_with_low_temp_target, false)) {
                var targetBg = profileTarget
                var isTempTarget = false
                val tempTarget = repository.getTemporaryTargetActiveAt(dateUtil.now()).blockingGet()
                if (tempTarget is ValueWrapper.Existing) {
                    isTempTarget = true
                    targetBg =
                        hardLimits.verifyHardLimits(
                            tempTarget.value.target(),
                            app.aaps.core.ui.R.string.temp_target_value,
                            HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[0].toDouble(),
                            HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[1].toDouble()
                        )
                }

                if (isTempTarget && targetBg < profileTarget) return false
            }

            if (bgCurrent < profileTarget + bgOffset) {
                return true
            }
        }
        return false
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = sp.getBoolean(R.string.key_use_uam, false)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = sp.getBoolean(app.aaps.core.utils.R.string.key_use_autosens, false)
        if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        else if (this is DynamicISFPlugin) value.set(false, rh.gs(R.string.autosens_disabled_in_dyn_isf), this)
        return value
    }

    open fun provideDetermineBasalAdapter(): DetermineBasalAdapter = DetermineBasalAdapterSMBJS(ScriptReader(context), injector)
}