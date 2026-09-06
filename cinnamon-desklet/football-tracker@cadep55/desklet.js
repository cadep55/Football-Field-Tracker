const St = imports.gi.St;
const Gio = imports.gi.Gio;
const Cairo = imports.cairo;
const Mainloop = imports.mainloop;

const Desklet = imports.ui.desklet;
const Settings = imports.ui.settings;

const NFL_SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard";
const CFB_SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?groups=80&limit=100";
const NFL_TEAMS = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams";
// This endpoint ignores "groups" and just returns however many teams you ask
// for via "limit", across all divisions (~760 total). limit=200 silently
// dropped teams alphabetically past that cut (e.g. TCU, Alabama were missing).
const CFB_TEAMS = "https://site.api.espn.com/apis/site/v2/sports/football/college-football/teams?limit=1000";
const TEST_GAME_ID = "401671889";

const LIVE_POLL_MS = 30 * 1000;
const IDLE_POLL_MS = 30 * 60 * 1000;

// Mirrors app/src/main/java/com/footballwidget/widget/FieldRenderer.kt's TEAM_COLORS
const TEAM_COLORS = {
    ARI: "97233F", ATL: "A71930", BAL: "241773",
    BUF: "00338D", CAR: "0085CA", CHI: "0B162A",
    CIN: "FB4F14", CLE: "311D00", DAL: "041E42",
    DEN: "FB4F14", DET: "0076B6", GB: "203731",
    HOU: "03202F", IND: "002C5F", JAX: "006778",
    KC: "E31837", LAC: "0080C6", LAR: "003594",
    LV: "000000", MIA: "008E97", MIN: "4F2683",
    NE: "002244", NO: "D3BC8D", NYG: "0B2265",
    NYJ: "125740", PHI: "004C54", PIT: "FFB612",
    SEA: "002244", SF: "AA0000", TB: "D50A0A",
    TEN: "0C2340", WAS: "773141",
    BAMA: "9E1B32", ALA: "9E1B32", OSU: "BB0000",
    OHIO: "BB0000", MICH: "00274C", UGA: "BA0C2F",
    GA: "BA0C2F", LSU: "461D7C", CLEM: "F56600",
    ND: "0C2340", TEX: "BF5700", USC: "990000",
    OU: "841617", OKLA: "841617", PSU: "041E42",
    PENN: "041E42", ORE: "154733", ORST: "DC4405",
    WASH: "4B2E83", FSU: "782F40", TENN: "FF8200",
    AUB: "0C2340", FLA: "0021A5", WISC: "C5050C",
    WIS: "C5050C", IOWA: "FFCD00", MSU: "18453B",
    NCST: "CC0000", VT: "660000", UTAH: "CC0000",
    UCLA: "2D68C4", COLO: "CFB87C", ASU: "8C1D40",
    ARK: "9D2235", MISS: "CE1126", UK: "0033A0",
    KSU: "512888", TCU: "4D1979", BAY: "003015",
    NCAAF: "37474F"
};

// ---------- ESPN fetch + parse (ports of EspnApi.kt) ----------

// ESPN's Akamai edge 403s plain/non-browser clients on these JSON endpoints;
// a desktop UA + Accept/Accept-Language + Referer/Origin gets past it reliably
// (verified against all endpoints used below). curl -A alone is not enough.
function httpGetAsync(url, callback) {
    try {
        let proc = new Gio.Subprocess({
            argv: ['curl', '-s', '--max-time', '15', '--compressed',
                '-H', 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
                '-H', 'Accept: application/json, text/plain, */*',
                '-H', 'Accept-Language: en-US,en;q=0.9',
                '-H', 'Referer: https://www.espn.com/',
                '-H', 'Origin: https://www.espn.com',
                url],
            flags: Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_SILENCE
        });
        proc.init(null);
        proc.communicate_utf8_async(null, null, (source, res) => {
            let stdout = null;
            try {
                const [, out] = source.communicate_utf8_finish(res);
                if (source.get_successful() && out) stdout = out;
            } catch (e) {
                global.logError("FootballTracker httpGet error: " + e);
            }
            callback(stdout);
        });
    } catch (e) {
        global.logError("FootballTracker subprocess spawn error: " + e);
        callback(null);
    }
}

function parseTeamInfo(team) {
    return {
        id: team.id != null ? String(team.id) : "0",
        abbreviation: team.abbreviation || "??",
        displayName: team.displayName || team.name || team.shortDisplayName || "Unknown",
        shortName: team.shortDisplayName || team.abbreviation || "??",
        color: team.color || "333333",
        record: ""
    };
}

function findTeamsArray(root) {
    try {
        const sportsArr = root.sports;
        if (sportsArr && sportsArr.length > 0) {
            const leaguesArr = sportsArr[0].leagues;
            if (leaguesArr && leaguesArr.length > 0) {
                const teamsArr = leaguesArr[0].teams;
                if (teamsArr && teamsArr.length > 0) return teamsArr;
            }
        }
    } catch (e) {}
    try {
        if (root.teams && root.teams.length > 0) return root.teams;
    } catch (e) {}
    try {
        if (root.league && root.league.teams && root.league.teams.length > 0) return root.league.teams;
    } catch (e) {}
    try {
        if (root.leagues && root.leagues.length > 0 && root.leagues[0].teams && root.leagues[0].teams.length > 0) {
            return root.leagues[0].teams;
        }
    } catch (e) {}
    try {
        if (root.sport && root.sport.leagues && root.sport.leagues.length > 0) {
            const teamsArr = root.sport.leagues[0].teams;
            if (teamsArr && teamsArr.length > 0) return teamsArr;
        }
    } catch (e) {}
    return null;
}

function resolveTeamId(league, abbr, callback) {
    const url = league === "nfl" ? NFL_TEAMS : CFB_TEAMS;
    httpGetAsync(url, (json) => {
        if (!json) { callback(null); return; }
        try {
            const teamsArr = findTeamsArray(JSON.parse(json));
            if (!teamsArr) { callback(null); return; }
            const target = abbr.trim().toUpperCase();
            for (const entry of teamsArr) {
                const team = entry.team || entry;
                if ((team.abbreviation || "").toUpperCase() === target) {
                    callback(parseTeamInfo(team));
                    return;
                }
            }
            callback(null);
        } catch (e) {
            global.logError("FootballTracker resolveTeamId error: " + e);
            callback(null);
        }
    });
}

function parseEspnDate(dateStr) {
    let d = new Date(dateStr);
    if (isNaN(d.getTime())) {
        d = new Date(dateStr.replace(/(\d{2}:\d{2})Z$/, "$1:00Z"));
    }
    return isNaN(d.getTime()) ? null : d;
}

// The Wednesday at/after which a finished game should stop being shown
// in favor of the next matchup on the team's schedule.
function mostRecentWednesdayMidnight() {
    const now = new Date();
    const daysSinceWednesday = (now.getDay() - 3 + 7) % 7;
    return new Date(now.getFullYear(), now.getMonth(), now.getDate() - daysSinceWednesday, 0, 0, 0, 0).getTime();
}

function formatGameDate(d) {
    const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let hours = d.getHours();
    const ampm = hours >= 12 ? "PM" : "AM";
    hours = hours % 12 || 12;
    const mins = String(d.getMinutes()).padStart(2, "0");
    return `${days[d.getDay()]} ${months[d.getMonth()]} ${d.getDate()}, ${hours}:${mins} ${ampm}`;
}

function getBallPositionPct(state) {
    if (state.yardLine == null || !state.possession) return 50;
    return state.possession === state.homeTeam.abbreviation ? 100 - state.yardLine : state.yardLine;
}

function parseScoreboard(jsonText, teamId) {
    try {
        const root = JSON.parse(jsonText);
        const events = root.events;
        if (!events) return null;

        for (const event of events) {
            const competitions = event.competitions;
            if (!competitions || competitions.length === 0) continue;
            const comp = competitions[0];
            const competitors = comp.competitors;
            if (!competitors) continue;

            let ourTeamFound = false;
            for (const c of competitors) {
                const team = c.team || c;
                if (String(team.id) === String(teamId)) { ourTeamFound = true; break; }
            }
            if (!ourTeamFound) continue;

            let homeTeam = null, awayTeam = null, homeScore = 0, awayScore = 0;
            for (const c of competitors) {
                const team = c.team || c;
                const isHome = c.homeAway === "home";
                const score = parseInt(c.score, 10) || 0;
                let record = "";
                if (c.records && c.records.length > 0) record = c.records[0].summary || "";
                const info = Object.assign(parseTeamInfo(team), { record });
                if (isHome) { homeTeam = info; homeScore = score; } else { awayTeam = info; awayScore = score; }
            }
            if (!homeTeam || !awayTeam) continue;

            const statusObj = comp.status || event.status;
            const typeObj = statusObj ? statusObj.type : null;
            const stateStr = (typeObj && (typeObj.state || (typeObj.name && typeObj.name.toLowerCase()))) || "pre";
            const statusText = (typeObj && (typeObj.shortDetail || typeObj.detail)) || "";

            let gameStatus;
            if (stateStr === "in" || stateStr === "live") gameStatus = "LIVE";
            else if (stateStr === "post" || stateStr === "final") gameStatus = "POST";
            else gameStatus = "PRE";

            // ESPN's default "current week" scoreboard can lag rolling over to the
            // next matchup. Once we're at/past the Wednesday following a finished
            // game, ignore it here so the caller falls back to the team schedule.
            if (gameStatus === "POST") {
                const eventDate = event.date ? parseEspnDate(event.date) : null;
                if (eventDate && eventDate.getTime() < mostRecentWednesdayMidnight()) continue;
            }

            const period = statusObj ? (statusObj.period || 0) : 0;
            const clockStr = statusObj ? (statusObj.displayClock || statusObj.clock || "") : "";

            const situation = comp.situation;
            const down = situation && situation.down != null ? situation.down : null;
            const distance = situation && situation.distance != null ? situation.distance : null;
            const yardLine = situation && situation.yardLine != null ? situation.yardLine : null;
            const possTeamId = situation ? situation.possession : null;
            const lastPlay = situation && situation.lastPlay ? situation.lastPlay.text : null;

            let possession = null;
            if (possTeamId != null) {
                if (String(possTeamId) === String(homeTeam.id)) possession = homeTeam.abbreviation;
                else if (String(possTeamId) === String(awayTeam.id)) possession = awayTeam.abbreviation;
            }

            let displayClock;
            if (gameStatus === "PRE") displayClock = statusText;
            else if (gameStatus === "POST") displayClock = "Final";
            else if (period === 0) displayClock = statusText;
            else displayClock = (period <= 4 ? `Q${period}` : "OT") + " " + clockStr;

            return {
                eventId: event.id || "", status: gameStatus, homeTeam, awayTeam, homeScore, awayScore,
                quarter: period, clock: displayClock, possession, down, distance, yardLine, lastPlay, statusText
            };
        }
    } catch (e) {
        global.logError("FootballTracker parseScoreboard error: " + e);
    }
    return null;
}

function fetchNextGameFromSchedule(config, callback) {
    const sport = config.league === "nfl" ? "nfl" : "college-football";
    const url = `https://site.api.espn.com/apis/site/v2/sports/football/${sport}/teams/${config.teamId}/schedule`;
    httpGetAsync(url, (json) => {
        if (!json) { callback(null); return; }
        try {
            const events = JSON.parse(json).events;
            if (!events) { callback(null); return; }
            const now = Date.now();

            for (const event of events) {
                const dateStr = event.date;
                if (!dateStr) continue;
                const gameDate = parseEspnDate(dateStr);
                if (!gameDate || gameDate.getTime() < now) continue;

                const competitions = event.competitions;
                if (!competitions || competitions.length === 0) continue;
                const competitors = competitions[0].competitors;
                if (!competitors) continue;

                let homeTeam = null, awayTeam = null;
                for (const c of competitors) {
                    const team = c.team || c;
                    const isHome = c.homeAway === "home";
                    const info = parseTeamInfo(team);
                    if (isHome) homeTeam = info; else awayTeam = info;
                }
                if (!homeTeam || !awayTeam) continue;

                const dateDisplay = formatGameDate(gameDate);
                callback({
                    eventId: event.id || "", status: "PRE", homeTeam, awayTeam,
                    homeScore: 0, awayScore: 0, quarter: 0, clock: dateDisplay,
                    possession: null, down: null, distance: null, yardLine: null,
                    lastPlay: null, statusText: dateDisplay
                });
                return;
            }
            callback(null);
        } catch (e) {
            global.logError("FootballTracker schedule parse error: " + e);
            callback(null);
        }
    });
}

function fetchTestGameState(gameId, callback) {
    const url = `https://site.api.espn.com/apis/site/v2/sports/football/nfl/summary?event=${gameId}`;
    httpGetAsync(url, (json) => {
        if (!json) { callback(null); return; }
        try {
            const root = JSON.parse(json);
            const comp = root.header.competitions[0];
            const competitors = comp.competitors;

            let homeTeam = null, awayTeam = null;
            for (const c of competitors) {
                const team = c.team || c;
                const isHome = c.homeAway === "home";
                const info = parseTeamInfo(team);
                if (isHome) homeTeam = info; else awayTeam = info;
            }
            if (!homeTeam || !awayTeam) { callback(null); return; }

            const previousDrives = root.drives ? root.drives.previous : null;
            if (!previousDrives || previousDrives.length === 0) { callback(null); return; }

            const allPlays = [];
            for (const drive of previousDrives) {
                if (!drive.plays) continue;
                for (const play of drive.plays) {
                    if (play.start && play.start.yardLine !== undefined) allPlays.push(play);
                }
            }
            if (allPlays.length === 0) { callback(null); return; }

            const selectedPlay = allPlays[Math.floor(Date.now() / 60000) % allPlays.length];
            const start = selectedPlay.start;
            const period = (selectedPlay.period && selectedPlay.period.number) || 1;
            const clock = (selectedPlay.clock && selectedPlay.clock.displayValue) || "15:00";
            const possessionTeamId = start.team ? start.team.id : null;

            let possession = null;
            if (possessionTeamId != null) {
                if (String(possessionTeamId) === String(homeTeam.id)) possession = homeTeam.abbreviation;
                else if (String(possessionTeamId) === String(awayTeam.id)) possession = awayTeam.abbreviation;
            }

            callback({
                eventId: gameId, status: "LIVE", homeTeam, awayTeam,
                homeScore: selectedPlay.homeScore || 0, awayScore: selectedPlay.awayScore || 0,
                quarter: period, clock: (period <= 4 ? `Q${period}` : "OT") + " " + clock,
                possession,
                down: start.down !== undefined ? start.down : null,
                distance: start.distance !== undefined ? start.distance : null,
                yardLine: start.yardLine !== undefined ? start.yardLine : null,
                lastPlay: `[TEST] ${selectedPlay.text || ""}`,
                statusText: "Test Mode"
            });
        } catch (e) {
            global.logError("FootballTracker test game error: " + e);
            callback(null);
        }
    });
}

function fetchGameForTeam(config, callback) {
    if (config.testMode && config.testGameId) {
        fetchTestGameState(config.testGameId, callback);
        return;
    }
    const url = config.league === "nfl" ? NFL_SCOREBOARD : CFB_SCOREBOARD;
    httpGetAsync(url, (json) => {
        if (json) {
            const result = parseScoreboard(json, config.teamId);
            if (result) { callback(result); return; }
        }
        fetchNextGameFromSchedule(config, callback);
    });
}

function getTeamColorRgb(abbreviation, espnColor) {
    const hex = TEAM_COLORS[(abbreviation || "").toUpperCase()] || espnColor || "37474F";
    const clean = /^[0-9A-Fa-f]{6}$/.test(hex) ? hex : "37474F";
    return [
        parseInt(clean.substring(0, 2), 16) / 255,
        parseInt(clean.substring(2, 4), 16) / 255,
        parseInt(clean.substring(4, 6), 16) / 255
    ];
}

// ---------- Desklet ----------

class FootballTrackerDesklet extends Desklet.Desklet {
    constructor(metadata, desklet_id) {
        super(metadata, desklet_id);

        this._generation = 0;
        this._timeoutId = null;
        this._config = null;
        this._lastState = null;
        this.fieldWidth = 460;

        this._buildUI();
        this.setHeader("Football Tracker");

        this.settings = new Settings.DeskletSettings(this, metadata.uuid, desklet_id);
        this.settings.bind("league", "league", this._onSettingsChanged);
        this.settings.bind("team-abbr", "teamAbbr", this._onSettingsChanged);
        this.settings.bind("test-mode", "testMode", this._onSettingsChanged);
        this.settings.bind("field-width", "fieldWidth", this._onSizeChanged);

        this._onSettingsChanged();
    }

    _buildUI() {
        this.window = new St.BoxLayout({ vertical: true, style_class: "football-tracker-desklet" });
        this.setContent(this.window);

        const header = new St.BoxLayout({ vertical: false, style_class: "football-header" });
        this.awayLabel = new St.Label({ text: "", style_class: "football-team-label" });
        this.clockLabel = new St.Label({ text: "", style_class: "football-clock-label" });
        this.homeLabel = new St.Label({ text: "", style_class: "football-team-label" });
        header.add(this.awayLabel, { expand: true, x_fill: false, x_align: St.Align.START });
        header.add(this.clockLabel, { expand: false, x_align: St.Align.MIDDLE });
        header.add(this.homeLabel, { expand: true, x_fill: false, x_align: St.Align.END });
        this.window.add(header);

        this.downDistanceLabel = new St.Label({ text: "", style_class: "football-dd-label" });
        this.window.add(this.downDistanceLabel, { x_fill: false, x_align: St.Align.MIDDLE });

        this.fieldArea = new St.DrawingArea({ style_class: "football-field-area" });
        this.fieldArea.connect("repaint", (area) => this._onRepaint(area));
        this.window.add(this.fieldArea);

        this.footerLabel = new St.Label({ text: "Loading…", style_class: "football-footer-label" });
        this.window.add(this.footerLabel, { x_fill: false, x_align: St.Align.MIDDLE });

        this._applySize();
    }

    _applySize() {
        this.fieldHeight = Math.round(this.fieldWidth * 0.37);
        this.fieldArea.set_size(this.fieldWidth, this.fieldHeight);
    }

    _onSizeChanged() {
        this._applySize();
        this.fieldArea.queue_repaint();
    }

    on_desklet_removed() {
        this._clearTimer();
        this._clearDebounce();
    }

    _clearTimer() {
        if (this._timeoutId) {
            Mainloop.source_remove(this._timeoutId);
            this._timeoutId = null;
        }
    }

    _clearDebounce() {
        if (this._debounceId) {
            Mainloop.source_remove(this._debounceId);
            this._debounceId = null;
        }
    }

    // Cinnamon's "entry" setting widget writes to disk on every keystroke
    // (binds directly to the GTK Entry's "text" property), so this fires
    // once per character typed into the team-abbr field. Debounce so we
    // only actually resolve/poll once typing settles.
    _onSettingsChanged() {
        this._clearDebounce();
        this._debounceId = Mainloop.timeout_add(700, () => {
            this._debounceId = null;
            this._applySettingsChange();
            return false;
        });
    }

    _applySettingsChange() {
        this._generation++;
        const gen = this._generation;
        this._clearTimer();

        if (this.testMode) {
            this._config = {
                league: "nfl", teamId: "21", teamAbbreviation: "TEST",
                teamDisplayName: "Super Bowl LIX (Test)", testMode: true, testGameId: TEST_GAME_ID
            };
            this.setHeader("Football Tracker — Test Mode");
            this._pollLoop(gen);
            return;
        }

        if (!this.teamAbbr || !this.teamAbbr.trim()) {
            this.footerLabel.set_text("Right-click → Configure to pick a team");
            this._renderState(null);
            return;
        }

        this.footerLabel.set_text("Loading…");
        resolveTeamId(this.league, this.teamAbbr, (info) => {
            if (gen !== this._generation) return;
            if (!info) {
                this.footerLabel.set_text(`Team "${this.teamAbbr}" not found for ${this.league === "nfl" ? "NFL" : "college football"}`);
                return;
            }
            this._config = {
                league: this.league, teamId: info.id, teamAbbreviation: info.abbreviation,
                teamDisplayName: info.displayName, testMode: false, testGameId: null
            };
            this.setHeader(info.displayName);
            this._pollLoop(gen);
        });
    }

    _pollLoop(gen) {
        fetchGameForTeam(this._config, (state) => {
            if (gen !== this._generation) return;
            this._renderState(state);
            const interval = (state && state.status === "LIVE") ? LIVE_POLL_MS : IDLE_POLL_MS;
            this._timeoutId = Mainloop.timeout_add(interval, () => {
                this._pollLoop(gen);
                return false;
            });
        });
    }

    _renderState(state) {
        this._lastState = state;

        if (!state) {
            this.awayLabel.set_text(this._config ? this._config.teamAbbreviation : "");
            this.homeLabel.set_text("");
            this.clockLabel.set_text("No game");
            this.downDistanceLabel.set_text("Check back on game day");
            this.fieldArea.queue_repaint();
            return;
        }

        const awayDot = state.possession === state.awayTeam.abbreviation ? "● " : "";
        this.awayLabel.set_text(`${awayDot}${state.awayTeam.abbreviation}  ${state.awayScore}`);

        const homeDot = state.possession === state.homeTeam.abbreviation ? " ●" : "";
        this.homeLabel.set_text(`${state.homeScore}  ${state.homeTeam.abbreviation}${homeDot}`);

        this.clockLabel.set_text(state.clock);

        let dd = "";
        if (state.status === "PRE") {
            dd = `${state.awayTeam.displayName} @ ${state.homeTeam.displayName}`;
        } else if (state.status === "POST") {
            dd = `${state.awayTeam.record}  |  ${state.homeTeam.record}`;
        } else if (state.down != null && state.distance != null) {
            const ordinals = { 1: "1st", 2: "2nd", 3: "3rd", 4: "4th" };
            const ordinal = ordinals[state.down] || `${state.down}th`;
            const yl = state.yardLine != null ? ` at ${state.yardLine}` : "";
            dd = `${ordinal} & ${state.distance}${yl}`;
        }
        this.downDistanceLabel.set_text(dd);
        this.footerLabel.set_text(state.lastPlay || "");

        this.fieldArea.queue_repaint();
    }

    // ---------- Field rendering (port of FieldRenderer.kt) ----------

    _onRepaint(area) {
        const cr = area.get_context();
        const w = this.fieldWidth, h = this.fieldHeight;
        const state = this._lastState;

        if (!state) {
            this._drawTurf(cr, w, h);
            this._drawYardLines(cr, w, h);
            this._drawYardNumbers(cr, w, h);
            this._drawHashMarks(cr, w, h);
            this._drawOverlayText(cr, w, h, "No game — check back on game day");
        } else {
            this._drawTurf(cr, w, h);
            this._drawEndZones(cr, w, h, state);
            this._drawYardLines(cr, w, h);
            this._drawYardNumbers(cr, w, h);
            this._drawHashMarks(cr, w, h);

            if (state.status === "LIVE") {
                this._drawLineOfScrimmage(cr, w, h, state);
                this._drawFirstDownLine(cr, w, h, state);
                this._drawBall(cr, w, h, state);
            } else if (state.status === "PRE") {
                this._drawOverlayText(cr, w, h, state.clock);
            } else if (state.status === "POST") {
                this._drawOverlayText(cr, w, h,
                    `FINAL  ${state.awayTeam.abbreviation} ${state.awayScore}  -  ${state.homeTeam.abbreviation} ${state.homeScore}`);
            }
        }

        cr.$dispose();
    }

    _drawCenteredText(cr, text, cx, cy) {
        const ext = cr.textExtents(text);
        cr.moveTo(cx - ext.width / 2 - ext.xBearing, cy - ext.height / 2 - ext.yBearing);
        cr.showText(text);
    }

    _drawRotatedCenteredText(cr, text, cx, cy, angleDeg) {
        const ext = cr.textExtents(text);
        cr.save();
        cr.translate(cx, cy);
        cr.rotate(angleDeg * Math.PI / 180);
        cr.moveTo(-ext.width / 2 - ext.xBearing, -ext.height / 2 - ext.yBearing);
        cr.showText(text);
        cr.restore();
    }

    _drawTurf(cr, w, h) {
        const dark = [0x1B / 255, 0x5E / 255, 0x20 / 255];
        const light = [0x2E / 255, 0x7D / 255, 0x32 / 255];
        const stripeWidth = w / 20;
        for (let i = 0; i < 20; i++) {
            const c = (i % 2 === 0) ? dark : light;
            cr.setSourceRGBA(c[0], c[1], c[2], 1);
            cr.rectangle(i * stripeWidth, 0, stripeWidth, h);
            cr.fill();
        }
    }

    _drawEndZones(cr, w, h, state) {
        const endZoneWidth = w * 0.08;

        const awayColor = getTeamColorRgb(state.awayTeam.abbreviation, state.awayTeam.color);
        cr.setSourceRGBA(awayColor[0], awayColor[1], awayColor[2], 1);
        cr.rectangle(0, 0, endZoneWidth, h);
        cr.fill();

        const homeColor = getTeamColorRgb(state.homeTeam.abbreviation, state.homeTeam.color);
        cr.setSourceRGBA(homeColor[0], homeColor[1], homeColor[2], 1);
        cr.rectangle(w - endZoneWidth, 0, endZoneWidth, h);
        cr.fill();

        cr.selectFontFace("sans-serif", Cairo.FontSlant.NORMAL, Cairo.FontWeight.BOLD);
        cr.setFontSize(14);
        cr.setSourceRGBA(1, 1, 1, 0.78);
        this._drawRotatedCenteredText(cr, state.awayTeam.abbreviation, endZoneWidth / 2, h / 2, -90);
        this._drawRotatedCenteredText(cr, state.homeTeam.abbreviation, w - endZoneWidth / 2, h / 2, 90);
    }

    _drawYardLines(cr, w, h) {
        const endZoneWidth = w * 0.08;
        const playableWidth = w - 2 * endZoneWidth;
        for (let i = 0; i <= 20; i++) {
            const x = endZoneWidth + (i / 20) * playableWidth;
            const major = i % 2 === 0;
            cr.setLineWidth(major ? 1.5 : 0.8);
            cr.setSourceRGBA(1, 1, 1, major ? 0.63 : 0.39);
            cr.moveTo(x, 0);
            cr.lineTo(x, h);
            cr.stroke();
        }
    }

    _drawYardNumbers(cr, w, h) {
        const endZoneWidth = w * 0.08;
        const playableWidth = w - 2 * endZoneWidth;
        const yardNumbers = [10, 20, 30, 40, 50, 40, 30, 20, 10];
        cr.selectFontFace("sans-serif", Cairo.FontSlant.NORMAL, Cairo.FontWeight.BOLD);
        cr.setFontSize(11);
        cr.setSourceRGBA(1, 1, 1, 0.47);
        yardNumbers.forEach((num, idx) => {
            const yardPos = (idx + 1) * 10;
            const x = endZoneWidth + (yardPos / 100) * playableWidth;
            this._drawCenteredText(cr, String(num), x, 12);
            this._drawCenteredText(cr, String(num), x, h - 10);
        });
    }

    _drawHashMarks(cr, w, h) {
        const endZoneWidth = w * 0.08;
        const playableWidth = w - 2 * endZoneWidth;
        const topHash = h * 0.33;
        const bottomHash = h * 0.67;
        cr.setLineWidth(0.8);
        cr.setSourceRGBA(1, 1, 1, 0.31);
        for (let i = 0; i <= 100; i++) {
            if (i % 5 === 0) continue;
            const x = endZoneWidth + (i / 100) * playableWidth;
            cr.moveTo(x, topHash - 3); cr.lineTo(x, topHash + 3); cr.stroke();
            cr.moveTo(x, bottomHash - 3); cr.lineTo(x, bottomHash + 3); cr.stroke();
        }
    }

    _drawLineOfScrimmage(cr, w, h, state) {
        if (state.yardLine == null || !state.possession) return;
        const endZoneWidth = w * 0.08;
        const playableWidth = w - 2 * endZoneWidth;
        const x = endZoneWidth + (getBallPositionPct(state) / 100) * playableWidth;
        cr.setLineWidth(3);
        cr.setSourceRGBA(0x21 / 255, 0x96 / 255, 0xF3 / 255, 0.78);
        cr.moveTo(x, 0); cr.lineTo(x, h); cr.stroke();
    }

    _drawFirstDownLine(cr, w, h, state) {
        if (state.yardLine == null || !state.possession) return;
        if (state.down == null || state.distance == null || state.down > 3) return;

        const ballPct = getBallPositionPct(state);
        const isHomePoss = state.possession === state.homeTeam.abbreviation;
        let firstDownPct = isHomePoss ? ballPct - state.distance : ballPct + state.distance;
        firstDownPct = Math.max(0, Math.min(100, firstDownPct));

        const endZoneWidth = w * 0.08;
        const playableWidth = w - 2 * endZoneWidth;
        const x = endZoneWidth + (firstDownPct / 100) * playableWidth;
        cr.setLineWidth(3);
        cr.setSourceRGBA(0xFF / 255, 0xC1 / 255, 0x07 / 255, 0.78);
        cr.moveTo(x, 0); cr.lineTo(x, h); cr.stroke();
    }

    _drawBall(cr, w, h, state) {
        if (state.yardLine == null || !state.possession) return;
        const endZoneWidth = w * 0.08;
        const playableWidth = w - 2 * endZoneWidth;
        const cx = endZoneWidth + (getBallPositionPct(state) / 100) * playableWidth;
        const cy = h / 2;
        const ballW = Math.max(10, w * 0.02);
        const ballH = ballW * 0.625;

        const glow = new Cairo.RadialGradient(cx, cy, 0, cx, cy, ballW * 2);
        glow.addColorStopRGBA(0, 1, 0.78, 0.2, 0.39);
        glow.addColorStopRGBA(1, 1, 0.78, 0.2, 0);
        cr.setSource(glow);
        cr.arc(cx, cy, ballW * 2, 0, 2 * Math.PI);
        cr.fill();

        // Build the elliptical path under a scale transform, then restore the
        // CTM before stroking so the outline width isn't squashed on one axis.
        cr.save();
        cr.translate(cx, cy);
        cr.scale(ballW, ballH);
        cr.arc(0, 0, 1, 0, 2 * Math.PI);
        cr.restore();

        const body = new Cairo.RadialGradient(cx - ballW * 0.2, cy - ballH * 0.2, 0, cx, cy, ballW);
        body.addColorStopRGBA(0, 0x8D / 255, 0x6E / 255, 0x4A / 255, 1);
        body.addColorStopRGBA(1, 0x5D / 255, 0x3A / 255, 0x1A / 255, 1);
        cr.setSource(body);
        cr.fillPreserve();
        cr.setLineWidth(1);
        cr.setSourceRGBA(0x3E / 255, 0x21 / 255, 0x08 / 255, 1);
        cr.stroke();

        cr.setSourceRGBA(1, 1, 1, 1);
        cr.setLineWidth(1.2);
        cr.moveTo(cx - ballW * 0.4, cy); cr.lineTo(cx + ballW * 0.4, cy); cr.stroke();
        const laceSpacing = ballW * 0.15;
        for (let i = -2; i <= 2; i++) {
            const lx = cx + i * laceSpacing;
            cr.moveTo(lx, cy - 2.5); cr.lineTo(lx, cy + 2.5); cr.stroke();
        }
    }

    _drawOverlayText(cr, w, h, text) {
        cr.setSourceRGBA(0, 0, 0, 0.55);
        cr.rectangle(0, h * 0.35, w, h * 0.3);
        cr.fill();

        cr.selectFontFace("sans-serif", Cairo.FontSlant.NORMAL, Cairo.FontWeight.BOLD);
        cr.setFontSize(Math.max(12, w * 0.032));
        cr.setSourceRGBA(1, 1, 1, 1);
        this._drawCenteredText(cr, text, w / 2, h / 2);
    }
}

function main(metadata, desklet_id) {
    return new FootballTrackerDesklet(metadata, desklet_id);
}
