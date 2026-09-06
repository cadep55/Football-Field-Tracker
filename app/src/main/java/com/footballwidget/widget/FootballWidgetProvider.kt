package com.footballwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.footballwidget.R
import com.footballwidget.data.*
import java.util.concurrent.Executors

class FootballWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            WidgetPrefs.deleteConfig(context, widgetId)
        }
    }

    override fun onEnabled(context: Context) {
        LiveUpdateService.scheduleCheck(context)
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()

        fun updateWidget(context: Context, widgetId: Int) {
            executor.execute {
                val config = WidgetPrefs.getConfig(context, widgetId) ?: return@execute

                val gameState: GameState? = try {
                    EspnApi.fetchGameForTeam(config)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

                if (gameState != null) {
                    WidgetPrefs.saveGameStatus(context, widgetId, gameState.status)
                }

                val views = RemoteViews(context.packageName, R.layout.widget_layout)
                populateViews(views, gameState, config)

                val bitmap = FieldRenderer.renderField(gameState)
                views.setImageViewBitmap(R.id.field_image, bitmap)

                try {
                    val mgr = AppWidgetManager.getInstance(context)
                    mgr.updateAppWidget(widgetId, views)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (gameState?.status == GameStatus.LIVE) {
                    LiveUpdateService.startIfLive(context)
                }
            }
        }

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, FootballWidgetProvider::class.java)
            val widgetIds = mgr.getAppWidgetIds(component)
            for (widgetId in widgetIds) {
                updateWidget(context, widgetId)
            }
        }

        private fun populateViews(views: RemoteViews, state: GameState?, config: WidgetConfig) {
            if (state == null) {
                views.setTextViewText(R.id.away_info, config.teamAbbreviation)
                views.setTextViewText(R.id.home_info, "")
                views.setTextViewText(R.id.game_clock, "No game")
                views.setTextViewText(R.id.down_distance, "Check back on game day")
                views.setTextViewText(R.id.last_play, "")
                return
            }

            // Away info with possession dot
            val awayDot = if (state.possession == state.awayTeam.abbreviation) "● " else ""
            val awayText = "$awayDot${state.awayTeam.abbreviation}  ${state.awayScore}"
            views.setTextViewText(R.id.away_info, awayText)

            // Home info with possession dot
            val homeDot = if (state.possession == state.homeTeam.abbreviation) " ●" else ""
            val homeText = "${state.homeScore}  ${state.homeTeam.abbreviation}$homeDot"
            views.setTextViewText(R.id.home_info, homeText)

            views.setTextViewText(R.id.game_clock, state.clock)

            // Down and distance
            val ddText = when {
                state.status == GameStatus.PRE -> "${state.awayTeam.displayName} @ ${state.homeTeam.displayName}"
                state.status == GameStatus.POST -> "${state.awayTeam.record}  |  ${state.homeTeam.record}"
                state.down != null && state.distance != null -> {
                    val ordinal = when (state.down) {
                        1 -> "1st"
                        2 -> "2nd"
                        3 -> "3rd"
                        4 -> "4th"
                        else -> "${state.down}th"
                    }
                    val yl = if (state.yardLine != null) " at ${state.yardLine}" else ""
                    "$ordinal & ${state.distance}$yl"
                }
                else -> ""
            }
            views.setTextViewText(R.id.down_distance, ddText)

            views.setTextViewText(R.id.last_play, state.lastPlay ?: "")
        }
    }
}
