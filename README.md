# Football Field Tracker

Android home screen widget that shows live NFL and college football games as a miniature football field with real-time ball positioning, line of scrimmage, and first-down line.

## Features

- **Live football field** with ball marker at the actual yard line, blue line-of-scrimmage, and yellow first-down line
- **Scoreboard header** with team abbreviations, scores, quarter/clock, down & distance, possession indicator
- **All 32 NFL teams + D1 college football** teams in the team picker
- **Multiple widgets** supported independently (one team per widget)
- **Smart polling**: 30-second updates during live games, 30-minute background checks otherwise
- **Offline resilience**: keeps last known state when network fails
- **Test Mode**: replays Super Bowl LIX play-by-play to verify rendering

## Data Source

Uses ESPN's public (unofficial) JSON endpoints. No API key required.

## Building

Open in Android Studio and build as a standard Android project.

- Min SDK: 26 (Android 8.0)
- Target SDK: 34

## Architecture

```
app/src/main/java/com/footballwidget/
  data/
    EspnApi.kt           - ESPN API fetch + JSON parsing (resilient multi-format parser)
    WidgetPrefs.kt        - SharedPreferences for per-widget config + cached game status
    LiveUpdateService.kt  - Foreground service (live) + WorkManager (periodic checks)
    BootReceiver.kt       - Restarts polling after phone reboot
  ui/
    WidgetConfigActivity.kt - Team picker shown when widget is added
  widget/
    FootballWidgetProvider.kt - AppWidgetProvider orchestration
    FieldRenderer.kt          - Canvas-based bitmap renderer for the field
```
