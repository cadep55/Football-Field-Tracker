package com.footballwidget.data

import android.content.Context

object WidgetPrefs {

    private const val PREFS_NAME = "football_widget_prefs"

    fun saveConfig(context: Context, widgetId: Int, config: WidgetConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("team_id_$widgetId", config.teamId)
            putString("team_abbr_$widgetId", config.teamAbbreviation)
            putString("league_$widgetId", config.league)
            putString("team_name_$widgetId", config.teamDisplayName)
            putBoolean("test_mode_$widgetId", config.testMode)
            putString("test_game_id_$widgetId", config.testGameId)
            apply()
        }
    }

    fun getConfig(context: Context, widgetId: Int): WidgetConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val teamId = prefs.getString("team_id_$widgetId", null) ?: return null
        return WidgetConfig(
            teamId = teamId,
            teamAbbreviation = prefs.getString("team_abbr_$widgetId", "??") ?: "??",
            league = prefs.getString("league_$widgetId", "nfl") ?: "nfl",
            teamDisplayName = prefs.getString("team_name_$widgetId", "") ?: "",
            testMode = prefs.getBoolean("test_mode_$widgetId", false),
            testGameId = prefs.getString("test_game_id_$widgetId", null)
        )
    }

    fun deleteConfig(context: Context, widgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove("team_id_$widgetId")
            remove("team_abbr_$widgetId")
            remove("league_$widgetId")
            remove("team_name_$widgetId")
            remove("test_mode_$widgetId")
            remove("test_game_id_$widgetId")
            remove("game_status_$widgetId")
            remove("cached_state_$widgetId")
            apply()
        }
    }

    fun saveGameStatus(context: Context, widgetId: Int, status: GameStatus) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("game_status_$widgetId", status.name).apply()
    }

    fun getGameStatus(context: Context, widgetId: Int): GameStatus? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString("game_status_$widgetId", null) ?: return null
        return try { GameStatus.valueOf(str) } catch (_: Exception) { null }
    }

    fun hasAnyLiveGame(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.entries.any { (key, value) ->
            key.startsWith("game_status_") && value == GameStatus.LIVE.name
        }
    }

    fun getAllWidgetIds(context: Context): List<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.keys
            .filter { it.startsWith("team_id_") }
            .mapNotNull { it.removePrefix("team_id_").toIntOrNull() }
    }
}
