package app.aaps.plugins.main.general.overview.boost.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.widget.RemoteViews
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.directionToIcon
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.overview.boost.BoostOverviewHelper
import dagger.android.HasAndroidInjector
import java.util.Locale
import javax.inject.Inject

/**
 * Boost-specific home screen widget showing algorithm data:
 * BG, tier, DynISF, TDD, activity mode, IOB, profile %, delta accel, fast carb.
 */
class BoostWidget : AppWidgetProvider() {

    @Inject lateinit var boostOverviewHelper: BoostOverviewHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var loop: Loop
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var config: Config
    @Inject lateinit var preferences: Preferences

    companion object {

        private var handler = Handler(HandlerThread(BoostWidget::class.simpleName + "Handler").also { it.start() }.looper)

        fun updateWidget(context: Context, from: String) {
            context.sendBroadcast(Intent().also {
                it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, BoostWidget::class.java)))
                it.putExtra("from", from)
                it.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            })
        }
    }

    private val intentAction = "OpenApp"

    override fun onReceive(context: Context, intent: Intent?) {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        aapsLogger.debug(LTag.WIDGET, "BoostWidget onReceive ${intent?.extras?.getString("from")}")
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {}
    override fun onDisabled(context: Context) {}

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.boost_widget_layout)
        val alpha = preferences.get(IntComposedKey.WidgetOpacity, appWidgetId)
        val useBlack = preferences.get(BooleanComposedKey.WidgetUseBlack, appWidgetId)

        val intent = Intent(context, uiInteraction.mainActivity).also { it.action = intentAction }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.boost_widget_layout, pendingIntent)

        if (config.APS || useBlack)
            views.setInt(R.id.boost_widget_layout, "setBackgroundColor", Color.argb(alpha, 0, 0, 0))

        handler.post {
            if (config.appInitialized) {
                updateBg(views)
                updateBoostData(views)
                updateProfile(views)
                updateIob(views)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun updateBg(views: RemoteViews) {
        val bgText = lastBgData.lastBg()?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) }
            ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        views.setTextViewText(R.id.bg, bgText)

        val bgColor = when {
            lastBgData.isLow()  -> rh.gc(app.aaps.core.ui.R.color.widget_low)
            lastBgData.isHigh() -> rh.gc(app.aaps.core.ui.R.color.widget_high)
            else                -> rh.gc(app.aaps.core.ui.R.color.widget_inrange)
        }
        views.setTextColor(R.id.bg, bgColor)

        // Strike through if BG is stale
        if (!lastBgData.isActualBg()) views.setInt(R.id.bg, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
        else views.setInt(R.id.bg, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)

        // Trend arrow
        trendCalculator.getTrendArrow(iobCobCalculator.ads)?.let {
            views.setImageViewResource(R.id.arrow, it.directionToIcon())
        }
        views.setInt(R.id.arrow, "setColorFilter", bgColor)

        // Time ago
        views.setTextViewText(R.id.time_ago, dateUtil.minOrSecAgo(rh, lastBgData.lastBg()?.timestamp))
    }

    private fun updateBoostData(views: RemoteViews) {
        val status = boostOverviewHelper.getBoostStatus()
        val units = profileFunction.getUnits()

        // Tier — use tier-specific color
        views.setTextViewText(R.id.tier_label, status.tierLabel)
        views.setTextColor(R.id.tier_label, status.tier.colorHex.toInt())

        // DynISF — convert from mg/dL if user uses mmol
        val dynIsfText = if (status.variableSens > 0) {
            String.format(Locale.getDefault(), "%.1f", profileUtil.fromMgdlToUnits(status.variableSens, units))
        } else "--"
        views.setTextViewText(R.id.dynisf, dynIsfText)

        // TDD — prefer algorithm's TDD, fall back to debug-parsed, then 7d average
        val tddValue = when {
            status.tddWeighted > 0  -> status.tddWeighted
            status.tddFromDebug > 0 -> status.tddFromDebug
            status.tdd7d > 0        -> status.tdd7d
            else                    -> 0.0
        }
        val tddText = if (tddValue > 0) String.format(Locale.getDefault(), "%.1f U", tddValue) else "--"
        views.setTextViewText(R.id.tdd, tddText)

        // Activity mode
        views.setTextViewText(R.id.activity_mode, status.activityDetail)
        val activityColor = when (status.activityMode) {
            BoostOverviewHelper.ActivityMode.ACTIVE   -> Color.parseColor("#42A5F5")
            BoostOverviewHelper.ActivityMode.INACTIVE  -> Color.parseColor("#FF9800")
            BoostOverviewHelper.ActivityMode.SLEEP_IN  -> Color.parseColor("#AB47BC")
            BoostOverviewHelper.ActivityMode.BOOST_OFF -> Color.parseColor("#78909C")
            BoostOverviewHelper.ActivityMode.NORMAL    -> Color.WHITE
        }
        views.setTextColor(R.id.activity_mode, activityColor)

        // Profile percentage
        val pctText = "${status.profilePercentage}%"
        views.setTextViewText(R.id.profile_pct, pctText)
        if (status.profilePercentage != 100) {
            views.setTextColor(R.id.profile_pct, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
        } else {
            views.setTextColor(R.id.profile_pct, Color.WHITE)
        }

        // Delta acceleration
        val deltaAcclText = if (status.deltaAccl != 0.0) {
            String.format(Locale.getDefault(), "%+.1f", status.deltaAccl)
        } else "--"
        views.setTextViewText(R.id.delta_accl, deltaAcclText)

        // Fast carb protection
        if (status.fastCarbProtection) {
            views.setViewVisibility(R.id.fast_carb_layout, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.fast_carb_layout, View.GONE)
        }

        // Target BG
        val targetText = if (status.targetBgMgdl > 0) {
            profileUtil.fromMgdlToStringInUnits(status.targetBgMgdl)
        } else {
            profileFunction.getProfile()?.let {
                profileUtil.toTargetRangeString(it.getTargetLowMgdl(), it.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
            } ?: "--"
        }
        val tempTarget = loop.lastRun?.constraintsProcessed?.let { cp ->
            val profileTarget = profileFunction.getProfile()?.getTargetMgdl() ?: 0.0
            if (cp.targetBG != 0.0 && kotlin.math.abs(profileTarget - cp.targetBG) > 0.01) cp.targetBG else null
        }
        views.setTextViewText(R.id.temp_target, targetText)
        if (tempTarget != null) {
            views.setTextColor(R.id.temp_target, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
        } else {
            views.setTextColor(R.id.temp_target, Color.WHITE)
        }
    }

    private fun updateProfile(views: RemoteViews) {
        val profileName = profileFunction.getProfileNameWithRemainingTime()
        views.setTextViewText(R.id.active_profile, profileName)

        val profileTextColor = profileFunction.getProfile()?.let {
            if (it is ProfileSealed.EPS) {
                if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                    rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning)
                else rh.gc(app.aaps.core.ui.R.color.widget_ribbonTextDefault)
            } else rh.gc(app.aaps.core.ui.R.color.widget_ribbonTextDefault)
        } ?: rh.gc(app.aaps.core.ui.R.color.widget_ribbonCritical)

        views.setTextColor(R.id.active_profile, profileTextColor)
    }

    private fun updateIob(views: RemoteViews) {
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val totalIob = bolusIob.iob + basalIob.basaliob
        views.setTextViewText(R.id.iob, rh.gs(app.aaps.core.ui.R.string.format_insulin_units, totalIob))
    }
}
