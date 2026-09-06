# Football Field Tracker — Cinnamon Desklet

Desktop-widget port of the Android app for Linux Mint's Cinnamon desktop. Same
ESPN data source, same field-graphic rendering (turf, end zones, yard lines,
ball position, line of scrimmage, first-down line), redrawn with Cairo instead
of Android's Canvas.

## Install

```
ln -s "$(pwd)/football-tracker@cadep55" ~/.local/share/cinnamon/desklets/football-tracker@cadep55
```

Then: **Cinnamon Settings → Desklets → Football Field Tracker → Add to desktop** (the `+` button), or `gsettings set org.cinnamon enabled-desklets "['football-tracker@cadep55:1:100:100']"` to add it programmatically.

Right-click the desklet → **Configure...** to set League (NFL / College Football),
Team abbreviation (must match ESPN's abbreviation, e.g. `USC`, `KC`, `MICH`),
Test mode (replays Super Bowl LIX for verifying rendering), and field width.

Drag the desklet by its header to reposition it anywhere on the desktop.

## Notes

- ESPN's JSON API (`site.api.espn.com`) 403s plain HTTP clients on some
  networks. `httpGetAsync` in `desklet.js` sends browser-like headers
  (User-Agent/Accept/Accept-Language/Referer/Origin) to get past that —
  verified against all endpoints this desklet uses.
- Polls every 30s during a live game, every 30 minutes otherwise — same
  cadence as the Android app.
- Team abbreviation is resolved to ESPN's numeric team ID at runtime via the
  league's team list (there's no offline picker like the Android app's
  RecyclerView search; you type the abbreviation directly in settings).
