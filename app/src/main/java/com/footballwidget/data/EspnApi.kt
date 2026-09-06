package com.footballwidget.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

data class GameState(
    val eventId: String,
    val status: GameStatus,
    val homeTeam: TeamInfo,
    val awayTeam: TeamInfo,
    val homeScore: Int,
    val awayScore: Int,
    val quarter: Int,
    val clock: String,
    val possession: String?,
    val down: Int?,
    val distance: Int?,
    val yardLine: Int?,
    val lastPlay: String?,
    val statusText: String
)

data class TeamInfo(
    val id: String,
    val abbreviation: String,
    val displayName: String,
    val shortName: String,
    val color: String,
    val record: String
)

enum class GameStatus { PRE, LIVE, POST }

data class WidgetConfig(
    val teamId: String,
    val teamAbbreviation: String,
    val league: String,
    val teamDisplayName: String,
    val testMode: Boolean = false,
    val testGameId: String? = null
)

object EspnApi {

    private const val NFL_SCOREBOARD =
        "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"
    private const val CFB_SCOREBOARD =
        "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?groups=80&limit=100"

    const val USC_TEAM_ID = "30"
    const val USC_ABBREVIATION = "USC"
    const val TEST_GAME_ID = "401671889"

    fun fetchGameForTeam(config: WidgetConfig): GameState? {
        if (config.testMode && config.testGameId != null) {
            return fetchTestGameState(config.testGameId)
        }

        val url = if (config.league == "nfl") NFL_SCOREBOARD else CFB_SCOREBOARD
        val json = httpGet(url)
        if (json != null) {
            val result = parseScoreboard(json, config.teamId)
            if (result != null) return result
        }

        return fetchNextGameFromSchedule(config)
    }

    private fun fetchNextGameFromSchedule(config: WidgetConfig): GameState? {
        try {
            val sport = if (config.league == "nfl") "nfl" else "college-football"
            val scheduleUrl =
                "https://site.api.espn.com/apis/site/v2/sports/football/$sport/teams/${config.teamId}/schedule"
            val json = httpGet(scheduleUrl) ?: return null
            val root = JsonParser.parseString(json).asJsonObject

            val events = root.getAsJsonArray("events") ?: return null
            val now = System.currentTimeMillis()

            for (eventEl in events) {
                val event = eventEl.asJsonObject
                val dateStr = event.get("date")?.asString ?: continue

                try {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val gameDate = sdf.parse(dateStr)
                    if (gameDate != null && gameDate.time < now) continue
                } catch (e: Exception) {
                    try {
                        val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        sdf2.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        val gameDate2 = sdf2.parse(dateStr)
                        if (gameDate2 != null && gameDate2.time < now) continue
                    } catch (e2: Exception) {
                        continue
                    }
                }

                val competitions = event.getAsJsonArray("competitions") ?: continue
                val comp = competitions[0].asJsonObject
                val competitors = comp.getAsJsonArray("competitors") ?: continue

                var homeTeam: TeamInfo? = null
                var awayTeam: TeamInfo? = null

                for (c in competitors) {
                    val cObj = c.asJsonObject
                    val team = cObj.getAsJsonObject("team")
                        ?: cObj // some formats inline the team fields
                    val isHome = cObj.get("homeAway")?.asString == "home"
                    val info = parseTeamInfo(team)
                    if (isHome) homeTeam = info else awayTeam = info
                }

                if (homeTeam == null || awayTeam == null) continue

                val displaySdf = java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.US)
                val dateDisplay = try {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val parsed = sdf.parse(dateStr)
                    if (parsed != null) displaySdf.format(parsed) else "TBD"
                } catch (e: Exception) {
                    try {
                        val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        sdf2.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        val parsed2 = sdf2.parse(dateStr)
                        if (parsed2 != null) displaySdf.format(parsed2) else "TBD"
                    } catch (e2: Exception) { "TBD" }
                }

                return GameState(
                    eventId = event.get("id")?.asString ?: "",
                    status = GameStatus.PRE,
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    homeScore = 0,
                    awayScore = 0,
                    quarter = 0,
                    clock = dateDisplay,
                    possession = null,
                    down = null,
                    distance = null,
                    yardLine = null,
                    lastPlay = null,
                    statusText = dateDisplay
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun fetchTestGameState(gameId: String): GameState? {
        try {
            val summaryUrl =
                "https://site.api.espn.com/apis/site/v2/sports/football/nfl/summary?event=$gameId"
            val json = httpGet(summaryUrl) ?: return null

            val root = JsonParser.parseString(json).asJsonObject
            val header = root.getAsJsonObject("header")
            val competitions = header.getAsJsonArray("competitions")
            val comp = competitions[0].asJsonObject
            val competitors = comp.getAsJsonArray("competitors")

            var homeTeam: TeamInfo? = null
            var awayTeam: TeamInfo? = null

            for (c in competitors) {
                val cObj = c.asJsonObject
                val team = cObj.getAsJsonObject("team")
                    ?: cObj
                val isHome = cObj.get("homeAway")?.asString == "home"
                val info = parseTeamInfo(team)
                if (isHome) homeTeam = info else awayTeam = info
            }
            if (homeTeam == null || awayTeam == null) return null

            val drives = root.getAsJsonObject("drives")
            val previousDrives = drives?.getAsJsonArray("previous")
            if (previousDrives == null || previousDrives.size() == 0) return null

            val allPlays = mutableListOf<JsonObject>()
            for (drive in previousDrives) {
                val plays = drive.asJsonObject.getAsJsonArray("plays") ?: continue
                for (play in plays) {
                    val pObj = play.asJsonObject
                    val start = pObj.getAsJsonObject("start")
                    if (start != null && start.has("yardLine")) {
                        allPlays.add(pObj)
                    }
                }
            }

            if (allPlays.isEmpty()) return null

            val playIndex = ((System.currentTimeMillis() / 60000) % allPlays.size).toInt()
            val selectedPlay = allPlays[playIndex]

            val start = selectedPlay.getAsJsonObject("start")
            val awayScore = selectedPlay.get("awayScore")?.asInt ?: 0
            val homeScore = selectedPlay.get("homeScore")?.asInt ?: 0
            val period = selectedPlay.getAsJsonObject("period")?.get("number")?.asInt ?: 1
            val clock = selectedPlay.getAsJsonObject("clock")?.get("displayValue")?.asString ?: "15:00"
            val playText = selectedPlay.get("text")?.asString ?: ""

            val down = if (start.has("down")) start.get("down").asInt else null
            val distance = if (start.has("distance")) start.get("distance").asInt else null
            val yardLine = if (start.has("yardLine")) start.get("yardLine").asInt else null
            val possessionTeamId = start.getAsJsonObject("team")?.get("id")?.asString

            val possession = when (possessionTeamId) {
                homeTeam.id -> homeTeam.abbreviation
                awayTeam.id -> awayTeam.abbreviation
                else -> null
            }

            val qtr = if (period <= 4) "Q$period" else "OT"
            val displayClock = "$qtr $clock"

            return GameState(
                eventId = gameId,
                status = GameStatus.LIVE,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                homeScore = homeScore,
                awayScore = awayScore,
                quarter = period,
                clock = displayClock,
                possession = possession,
                down = down,
                distance = distance,
                yardLine = yardLine,
                lastPlay = "[TEST] $playText",
                statusText = "Test Mode"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun fetchNflTeams(): List<TeamInfo> {
        return fetchTeamList("https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams")
    }

    fun fetchCfbTeams(): List<TeamInfo> {
        return fetchTeamList("https://site.api.espn.com/apis/site/v2/sports/football/college-football/teams?limit=200")
    }

    /**
     * Resilient team list parser — tries multiple known ESPN response formats.
     */
    private fun fetchTeamList(url: String): List<TeamInfo> {
        val json = httpGet(url) ?: return emptyList()
        val teams = mutableListOf<TeamInfo>()
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val teamsArr = findTeamsArray(root) ?: return emptyList()

            for (entry in teamsArr) {
                val entryObj = entry.asJsonObject
                val team = entryObj.getAsJsonObject("team") ?: entryObj
                teams.add(parseTeamInfo(team))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return teams.sortedBy { it.displayName }
    }

    private fun findTeamsArray(root: JsonObject): JsonArray? {
        // Format 1 (original): sports[0].leagues[0].teams
        try {
            val sportsArr = root.getAsJsonArray("sports")
            if (sportsArr != null && sportsArr.size() > 0) {
                val leaguesArr = sportsArr[0].asJsonObject.getAsJsonArray("leagues")
                if (leaguesArr != null && leaguesArr.size() > 0) {
                    val teamsArr = leaguesArr[0].asJsonObject.getAsJsonArray("teams")
                    if (teamsArr != null && teamsArr.size() > 0) return teamsArr
                }
            }
        } catch (_: Exception) {}

        // Format 2: root.teams directly
        try {
            val teamsArr = root.getAsJsonArray("teams")
            if (teamsArr != null && teamsArr.size() > 0) return teamsArr
        } catch (_: Exception) {}

        // Format 3: root.league.teams or root.leagues[0].teams
        try {
            val league = root.getAsJsonObject("league")
            if (league != null) {
                val teamsArr = league.getAsJsonArray("teams")
                if (teamsArr != null && teamsArr.size() > 0) return teamsArr
            }
        } catch (_: Exception) {}
        try {
            val leaguesArr = root.getAsJsonArray("leagues")
            if (leaguesArr != null && leaguesArr.size() > 0) {
                val teamsArr = leaguesArr[0].asJsonObject.getAsJsonArray("teams")
                if (teamsArr != null && teamsArr.size() > 0) return teamsArr
            }
        } catch (_: Exception) {}

        // Format 4: root.sport.leagues[0].teams
        try {
            val sport = root.getAsJsonObject("sport")
            if (sport != null) {
                val leaguesArr = sport.getAsJsonArray("leagues")
                if (leaguesArr != null && leaguesArr.size() > 0) {
                    val teamsArr = leaguesArr[0].asJsonObject.getAsJsonArray("teams")
                    if (teamsArr != null && teamsArr.size() > 0) return teamsArr
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun parseTeamInfo(team: JsonObject): TeamInfo {
        return TeamInfo(
            id = team.get("id")?.asString ?: "0",
            abbreviation = team.get("abbreviation")?.asString ?: "??",
            displayName = team.get("displayName")?.asString
                ?: team.get("name")?.asString
                ?: team.get("shortDisplayName")?.asString
                ?: "Unknown",
            shortName = team.get("shortDisplayName")?.asString
                ?: team.get("abbreviation")?.asString
                ?: "??",
            color = team.get("color")?.asString ?: "333333",
            record = ""
        )
    }

    private fun parseScoreboard(json: String, teamId: String): GameState? {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val events = root.getAsJsonArray("events") ?: return null

            for (eventEl in events) {
                val event = eventEl.asJsonObject
                val competitions = event.getAsJsonArray("competitions") ?: continue
                val comp = competitions[0].asJsonObject
                val competitors = comp.getAsJsonArray("competitors") ?: continue

                var ourTeamFound = false
                for (c in competitors) {
                    val cObj = c.asJsonObject
                    val team = cObj.getAsJsonObject("team") ?: cObj
                    val tid = team.get("id")?.asString ?: ""
                    if (tid == teamId) { ourTeamFound = true; break }
                }
                if (!ourTeamFound) continue

                var homeTeam: TeamInfo? = null
                var awayTeam: TeamInfo? = null
                var homeScore = 0
                var awayScore = 0

                for (c in competitors) {
                    val cObj = c.asJsonObject
                    val team = cObj.getAsJsonObject("team") ?: cObj
                    val isHome = cObj.get("homeAway")?.asString == "home"
                    val score = cObj.get("score")?.asString?.toIntOrNull() ?: 0

                    val records = cObj.getAsJsonArray("records")
                    val record = if (records != null && records.size() > 0) {
                        records[0].asJsonObject.get("summary")?.asString ?: ""
                    } else ""

                    val info = parseTeamInfo(team).copy(record = record)

                    if (isHome) { homeTeam = info; homeScore = score }
                    else { awayTeam = info; awayScore = score }
                }

                if (homeTeam == null || awayTeam == null) continue

                val statusObj = comp.getAsJsonObject("status")
                    ?: event.getAsJsonObject("status")
                val typeObj = statusObj?.getAsJsonObject("type")
                val stateStr = typeObj?.get("state")?.asString
                    ?: typeObj?.get("name")?.asString?.lowercase()
                    ?: "pre"
                val statusText = typeObj?.get("shortDetail")?.asString
                    ?: typeObj?.get("detail")?.asString
                    ?: ""

                val gameStatus = when {
                    stateStr == "in" || stateStr == "live" -> GameStatus.LIVE
                    stateStr == "post" || stateStr == "final" -> GameStatus.POST
                    else -> GameStatus.PRE
                }

                val period = statusObj?.get("period")?.asInt ?: 0
                val clockStr = statusObj?.get("displayClock")?.asString
                    ?: statusObj?.get("clock")?.asString
                    ?: ""

                val situation = comp.getAsJsonObject("situation")
                val down = situation?.get("down")?.asInt
                val distance = situation?.get("distance")?.asInt
                val yardLine = situation?.get("yardLine")?.asInt
                val possTeamId = situation?.get("possession")?.asString
                val lastPlayObj = situation?.getAsJsonObject("lastPlay")
                val lastPlay = lastPlayObj?.get("text")?.asString

                val possession = when (possTeamId) {
                    homeTeam.id -> homeTeam.abbreviation
                    awayTeam.id -> awayTeam.abbreviation
                    else -> null
                }

                val displayClock = when {
                    gameStatus == GameStatus.PRE -> statusText
                    gameStatus == GameStatus.POST -> "Final"
                    period == 0 -> statusText
                    else -> {
                        val qtr = if (period <= 4) "Q$period" else "OT"
                        "$qtr $clockStr"
                    }
                }

                return GameState(
                    eventId = event.get("id")?.asString ?: "",
                    status = gameStatus,
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    homeScore = homeScore,
                    awayScore = awayScore,
                    quarter = period,
                    clock = displayClock,
                    possession = possession,
                    down = down,
                    distance = distance,
                    yardLine = yardLine,
                    lastPlay = lastPlay,
                    statusText = statusText
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "FootballWidget/1.1")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
