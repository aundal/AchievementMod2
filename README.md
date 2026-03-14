# Crappy Achievements

A Fabric mod for Minecraft 1.21.11 that adds an in-game achievements leaderboard and per-player achievement inspector.

## Features

- `/achievements` — shows a ranked leaderboard of all players sorted by number of completed advancements
- `/achievements <username>` — shows a full list of all advancements for a specific player, green if completed, red if not
- Resolves player names for offline players via `usercache.json`
- Forces a save before scanning so results are always up to date

## Commands

| Command | Description |
|---|---|
| `/achievements` | Leaderboard of all players |
| `/achievements <username>` | Per-player advancement list |

### Leaderboard output
```
--- Achievements Leaderboard ---
#   Spiller             Adv     Sidst
--------------------------------------------
1   Steve            95/100   12/03-2025 14:22
2   Alex             87/100   11/03-2025 09:15
3   Notch             3/100   Aldrig
--------------------------------------------
```

### Per-player output
```
--- Steve's achievements (95/100) ---
[+] Stone Age          (green)
[+] Getting an Upgrade (green)
[-] The End?           (red)
...
```

## Requirements

- Minecraft 1.21.11
- Fabric Loader >= 0.18.2
- Fabric API
- Java 21

## Installation

1. Download the latest `.jar` from [Releases](https://github.com/aundal/AchievementMod2/releases)
2. Place it in your server's `mods/` folder
3. Start the server

No client-side installation needed — this is a server-side only mod.

## Building from source

```bash
git clone https://github.com/aundal/AchievementMod2.git
cd AchievementMod2
./gradlew build
```

The built jar will be in `build/libs/`.

## License

CC0-1.0 — public domain, do whatever you want with it.

## Author

Daniel Aundal — [aundal.dl](https://aundal.dl/)
