# MW2 Crossplay lobby host (24/7)

A lobby that is always there to join: `mw2-mp` (LAN build, disc TU0) idling
in a hosted match, restarted automatically when it ends. There is no
dedicated-server mode in this engine (it is a console game, not a server
binary), so the host is a full client sitting in a lobby — that is what this
kit runs.

## Setup (5 minutes, Windows PC)

1. Unzip the server bundle anywhere with room (the game files want ~8 GB).
   A second drive is fine: the game folder does not have to be on C:.
2. Run `setup_server.bat "path\to\Call of Duty - Modern Warfare 2.iso" MyServerName`.
   It installs the game files and writes `.env`.
3. Allow `mw2-mp.exe` through Windows Firewall when asked (inbound UDP, else
   nobody can see or join this machine).
4. Run `start_server.bat`. It loops forever; close the window to stop it.
5. **Once**, in the game window: MULTIPLAYER → SYSTEM LINK → host a match
   (or PLAY ONLINE → PRIVATE MATCH for a Live lobby). From then on the lobby
   is joinable until the process ends; the wrapper restarts the process, and
   the lobby is re-created by hand if it ever drops.

Linux: same layout, `mw2-mp` + `game/` + `.env`; loop it with
`while true; do ./mw2-mp; sleep 10; done`. Install the game files with the
Linux `mw2-launcher --install <iso> --install-dir <dir>` first.

## Config (.env beside the executables, one NAME=value per line)

| Key | What |
|---|---|
| `MW2_NAME` | Name other players see (15 chars). Default: the profile's. |
| `MW2_LAN_PORT` | Shared broadcast port both ends use. Default `3074`; change it on **all** machines or none. |
| `MW2_LAN_ACCEPT` | `1` joins lobby invitations without asking. |
| `MW2_LAN_JOIN` | Joins that player's lobby as soon as it is seen. |
| `MW2_NO_AUDIO` | `1` disables audio (headless-friendly). |
| `MW2_LOG_FILE` | Log path (`server.log`). Watch it for `online: lan as …` and `lan discovered …`. |
| `MW2_FPS_LIMIT` | Cap frame rate to stay cool (`30` for an idle host sips power). |

## 24/7 tips

- Power: disable sleep/hibernate; `MW2_FPS_LIMIT=30` + minimise the window.
- Auto-start: Task Scheduler → run `start_server.bat` at logon.
- Port: keep every machine on UDP 3074 (or one custom port everywhere).
- Hosting from a phone works (MP app hosts while open) but Android kills
  background apps — fine for a match, not for 24/7. Use a PC for always-up.
- If guests loop on "attempt #N": compare the guest's `joining a … session,
  host …` line with this host's `lan as …` line in the logs — same address
  means packets flow; different means discovery crossed networks.

## Files

`mw2-mp.exe` (the host), `mw2-sp.exe`, `mw2-launcher.exe` (installs/updates
the game files), `game/` (your ISO's files, installed once), `saves/`
(profiles and ranks), `.env` (this config), `server.log` (the log),
`install_location.txt` (where `game/` lives, if not beside these files).
Nothing here phones home; multiplayer is plain UDP on your LAN.
