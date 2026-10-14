# MW2 Crossplay — PC + Android, local/LAN multiplayer

This is a fork of the MW2 Xbox 360 recompilation port
(`OpenPort-Labs/mw2-android`, itself downstream of `PaulCombal/mw2-recompiled`
via `hedge-dev/XenonRecomp`), set up so **campaign and multiplayer both build**,
on **PC (Windows/Linux x86-64)** and **Android (ARM64)**, with **PC ↔ Android
crossplay over LAN** (system-link transported as plain UDP).

> **Bring your own game. NEVER upload or commit game files.**
> No ISO, XEX, PE, fastfile, or title-update file belongs in this repo or in
> any release artifact. The `.gitignore` already excludes `*.iso`, `*.xex`,
> `*.pe`, `/mw2/`, and `/XenonRecomp/`. The CI workflow deletes the game files
> off the runner before building the APK. If you upload the ISO anywhere for
> CI, use a **private, unlisted** link — never a public release attachment.

## What this fork changes vs upstream

1. **Multiplayer actually builds on Android** (`android/app/build.gradle.kts`):
   - `campaign` flavor: `-DMW2_TITLE=sp -DMW2_ONLINE=none`
   - `multiplayer` flavor: `-DMW2_TITLE=mp -DMW2_ONLINE=lan`
   - `tu0` / `tu6` flavors: the game version (`-DMW2_VERSION=tu6` for TU6;
     TU0 keeps the original application IDs, TU6 adds `.tu6`).
    
   (`lan` = plain UDP system-link. `none` leaves MP with single-device
   system-link only. Steam is PC-only and intentionally not used on Android.
   Code: `runtime/online/lan.cpp`, selected in `CMakeLists.txt:112-129`,
   tried in order in `runtime/online/service.cpp`.)

2. **Flavor-aware ISO picker** (`MainActivity.kt`): the MP app
   (`com.mw2.recomp.mp`) looks for the **full** disc first and ignores
   `*lean*.iso` — the 5.14 GB lean campaign ISO pruned the multiplayer
   fastfiles, so it can never satisfy an MP install. SP keeps preferring lean.

3. **MP forces the link up** (`GameActivity.kt` sets `MW2_NET_LINK=1` for the
   `.mp` package; the runtime default is up-for-MP / down-for-SP in
   `runtime/kernel/net.cpp`, this just makes it explicit).

4. Native side already distinguished the titles (`runtime/title.h`,
   `runtime/install/install.cpp:36-42`, `runtime/android/jni.cpp:201-208`).

## Requirements — read before anything else

- Your own legal Xbox 360 disc image: **Call of Duty: Modern Warfare 2,
  version 1.0.557, USA or Europe, full DVD-9 (~7.8 GB / 7,838,695,424 bytes).**
  - For **MP** you need the **full** image (all `.ff`/`.pak`, ~5.9 GB of
    fastfiles — the MP executable picks its own maps/zones and a missing zone
    fails as a "dirty disc").
  - The lean `mw2_campaign_lean.iso` is **SP-only**.
- Disc-version (TU0) hashes the build checks against:
  - `default.xex` (SP): `990e640d3a92544f9d585e71faf1ab65082d37ec6f5865b2599335fa123bf6ff`
  - `default_mp.xex` (MP): `aacc47aff3221f3bd9129e80827fd3bcd74aa43bf74bb5c5ddbd0597c0d5fcde`
- Entry points (disc version): SP `0x82370938`, MP `0x823A7FE0`.
- Android builds come in both game versions (TU0 packages
  `com.mw2.recomp.sp`/`.mp`, TU6 as `.sp.tu6`/`.mp.tu6`) — `CMakeLists.txt`
  takes `-DMW2_VERSION=` from the TU flavor (default `tu0`).
  **Crossplay demands identical versions on both ends** (a TU6 PC build and
  a TU0 Android build are different executables and will not interoperate;
  LAN presence carries the version and mismatches are logged).
  Build the PC side to match: `Build PC TU0` vs `Build PC TU6`.
  TU6 needs the title-update package in `mw2/update/` at build time (CI
  fetches it privately) and downloads it again at install time; TU0 needs
  nothing but the disc.
- Phone: ARM64 (`arm64-v8a`), Android 10+ (APK declares `minSdk 26`),
  Vulkan 1.1+ (1.3 preferred; 1.1 `VkRenderPass` fallback + CPU BC decoder
  included), ~8 GB free.
- PC: 64-bit Windows 10/11 or Linux, Vulkan 1.2+ GPU.

## Build — Android APKs (CI, recommended)

No toolchain needed locally. The `.github/workflows/android.yml` workflow
recompiles and builds on a GitHub runner:

1. Zip **only the two executables** (NOT the ISO — ~10 MB, not ~8 GB):
   extract `default.xex` + `default_mp.xex` from your ISO
   (`python tools/xdvdfs.py "<your.iso>" mw2/tu0 default.xex default_mp.xex`),
   zip those two files, push the zip to a **private** assets repo.
2. Two Actions secrets in the public repo (Settings → Secrets → Actions),
   both auto-masked in logs:
   - `GAME_ARCHIVE_URL` = the **plain** raw URL, no credentials:
     `https://raw.githubusercontent.com/<you>/mw2-assets/main/mw2-xex-tu0.zip`
   - `MW2_ASSETS_TOKEN` = fine-grained PAT, **Contents read-only** on the
     assets repo only. Sent as an `Authorization` header — never embed tokens
     in the URL (URL parsers choke on them and they leak into request lines).
3. Android `versionCode` (`android/app/build.gradle.kts`) **must go up with
   every published release** (currently 15, or set `MW2_VERSION_CODE` in CI) —
   Android will not install an update over an APK with an equal or higher
   code. All four flavors share the number (they are separate packages).

## Release policy (standing)

Every update ships through CI; skip only components whose code did not change:

1. Bump Android `versionCode` iff `android/` or `runtime/` changed (the
   native lib ships inside the APK).
2. Push: `Build Android APK` (campaign TU0) runs itself; dispatch it again
   with `tu: tu6`, plus `Build MP APK` with `tu: tu0` and `tu: tu6`.
   (Two flavor dimensions now: variant tasks are `assembleCampaignTu0Release`,
   `assembleCampaignTu6Release`, `assembleMultiplayerTu0Release`,
   `assembleMultiplayerTu6Release`.)
3. New tag (`v0.1.0-alpha.N`), pre-release, with notes. Notes MUST carry an
   `APK versionCode: N` line — the in-app updater reads it.
4. Dispatch `Build PC TU0` and `Build PC TU6` with that tag: they attach the
   PC archives themselves.
5. Attach all four APK artifacts to the release by hand (workflows do not;
   `Build Android APK` only self-attaches the campaign TU0 to `v0.0.1`).
6. Never attach or commit game files (ISO/XEX/PE/fastfiles) anywhere.
7. Invite protocol (Presence slots/nonce) changes break old builds: both
   ends of a match must run the same alpha. Say so in the notes when it
   changes (unchanged since alpha.6; the TU byte reuses padding, same size).

## Title update 6 (TU6) and mods

TU6 builds are green on PC (linux + windows, sp offline + mp lan) and on
Android (campaign + multiplayer TU6 APKs), and ride on the releases next to
the TU0 packs. **Same-version rule: TU6 plays ONLY with TU6 — not with TU0
PCs or phones, and TU0 only with TU0.** The launcher, the HUD and `game.log`
all name the version (`lan as "name" (TU6) ...`; a heard mismatch logs a
warning naming both sides).

- Four phone apps, two per title: `com.mw2.recomp.sp` / `.mp` (TU0, the
  original packages, so updates install over them) beside
  `com.mw2.recomp.sp.tu6` / `.mp.tu6` (TU6). Cross-launch stays within its
  own version, and the updater never offers the other version's APK.
- The TU6 package (`TU_10LC20N_…`, 2 patches + 3 fastfiles) lives in the
  **private** assets repo under `title-updates/TU6/` for CI — same place CI
  reads it from. Never publish it. On the phone it is **downloaded at
  install time** from the public title-updates archive (pinned commit, same
  URL the PC launcher uses) and applied on the device after the disc copy;
  the APK itself carries no game files.
- PC TU6 builds come from the `Build PC TU6` workflow (sp offline + mp lan,
  stamped with the release tag). The PC launcher installs/updates TU6
  itself: point it at your ISO and it downloads nothing extra (the package
  rides with the build's installer flow).
- Android TU6 builds come from `Build Android APK` / `Build MP APK` with the
  `tu: tu6` input (recompiled on the runner from the patched executables;
  the committed `ppc/` tree stays TU0 for the campaign TU0 build).
- Loading a mod: drop its `.ff` files into the installed `game/` folder
  beside the others — the engine reads that one flat directory, so a
  same-version fastfile just loads. Keep the originals aside. Each phone app
  has its own `game/` folder, so TU6 mods go to the TU6 app's.

## Shader seed (refreshing the bundled cache)

The APK ships `android/app/src/main/assets/shader_cache.bin`; at first
start (and whenever the bundled one records more pipelines than the
installed one) it becomes the cache. To ship a fuller seed: play through
campaign maps + common MP maps on a device, pull
`/sdcard/Android/data/com.mw2.recomp.<sp|mp>/files/shader_cache.bin`,
drop it over `android/app/src/main/assets/shader_cache.bin`, commit, and
ship it in the next alpha — every install then starts warm.

## Install location (PC)

The launcher no longer forces the ~7 GB of game files beside itself on C:.
`INSTALL LOCATION` in the launcher (or `mw2-launcher --install --install-dir
D:\Games\MW2`) names any absolute folder, on any drive; installs go to
`game/` under it, remembered in `install_location.txt` beside the
executables, which the game reads at start. Saves stay beside the launcher.
Files already installed stay where they are — the choice applies going
forward. Space is checked at install time either way.
2. Push this branch to your fork.
3. Actions → **`Build MP APK`** → Run workflow (no inputs — it builds the
   multiplayer flavor from `GAME_ARCHIVE_URL`, disc TU0, LAN backend).
   For campaign-only, Actions → `Build Android APK` (triggered automatically
   on push too, from the committed `ppc/` tree). The upstream `android`
   workflow is kept for reference but is not registered by GitHub on this
   fork.
4. Download the signed APK artifact (`mw2-multiplayer-android-arm64-*.apk`,
   `mw2-campaign-android-arm64-*.apk`) + check `SHA256SUMS.txt`.
5. Copy your **full** ISO to the phone's `Download` folder, install the MP
   APK (`com.mw2.recomp.mp`), open it → **⚡ INSTALL & PLAY** → **▶ LAUNCH**.

First run takes a while (2M lines of generated C++ compiled for ARM64 with
ccache); re-runs are minutes. Artifacts expire after 14 days.

## Build — local (WSL2 / Linux + Android SDK)

```sh
# toolchain
sudo apt install clang-18 lld-18 ninja-build ccache cmake python3 p7zip-full
# JDK 17, Android SDK with NDK 28.2.13676358 (platforms;android-35)

# 1. recompile both titles, DISC VERSION (tu0) for crossplay
VERSION=tu0 RECOMPILE_ONLY=1 TITLE=sp ISO="<path-to-your.iso>" ./build.sh
VERSION=tu0 RECOMPILE_ONLY=1 TITLE=mp ISO="<path-to-your.iso>" ./build.sh

# 2. adrenotools (Turnip driver support, optional)
./android/fetch_deps.sh

# 3. APKs (TU flavor second: CampaignTu0/CampaignTu6/MultiplayerTu0/MultiplayerTu6 + Debug/Release)
cd android
./gradlew assembleCampaignTu0Release assembleMultiplayerTu0Release
# -> app/build/outputs/apk/campaignTu0/release/*.apk
# -> app/build/outputs/apk/multiplayerTu0/release/*.apk
```

PC (same tree, TU0 + LAN for crossplay):

```sh
VERSION=tu0 TITLE=mp ONLINE=lan ./build.sh
# -> build-mp-tu0/mw2  (run: ./build-mp-tu0/mw2 mw2/tu0/default_mp.pe mw2/tu0/game)

VERSION=tu0 TITLE=sp ./build.sh
# -> build-tu0/mw2
```

## Crossplay: PC ↔ Android over LAN

- All players on the **same network**, all builds `ONLINE=lan`
  (Android MP already is; PC needs `ONLINE=lan`), all the **same game
  version**: TU0 with TU0, TU6 with TU6. The HUD names it (`LAN: N · TU6`)
  and a heard mismatch is logged naming both sides.
- Same broadcast port: `MW2_LAN_PORT` (default `3074`); one copy per
  machine per port slot — a second copy on one PC is a second player.
- Flow: host starts a system-link lobby (PLAY ONLINE → PRIVATE MATCH with
  Live sign-in via lan, or SYSTEM LINK). Every copy announces once a second
  on the shared port. Join via server browser, `MW2_LAN_JOIN=<name>`, or
  lobby "Invite friends" (invites everyone seen) + `MW2_LAN_ACCEPT=1`.
- Names: `MW2_NAME` overrides the profile name on lan.
- Android needs `INTERNET`/`ACCESS_NETWORK_STATE` (already in the manifest).
- SP and MP installs are separate apps/dirs — install the full ISO in **both**
  the `.sp` and `.mp` apps if you play both on one phone.

### When the other machine does not appear

Discovery is one UDP broadcast a second; no heard broadcasts means an empty
server list *and* dead invites — one failure, two symptoms. Check in order:

1. **Same network.** Phone off mobile data, on the same WiFi as the PC.
   Most reliable: phone hotspot, PC joins it. Guest WiFis and AP isolation
   block phone↔PC traffic entirely.
2. **Windows Firewall.** Allow `mw2-mp` on first launch (inbound UDP
   3074) or no other machine can see or join the PC.
3. **Look at the HUD/logs, not the list.** The MP app's HUD shows
   `LAN: N` (copies heard). `game.log` says `lan as "name" at ip:port`,
   `also announces to <subnet>` and `lan discovered "name" at ip:port`.
   If the count stays 0 with a host beside it, broadcasts are being
   filtered — go back to 1.
4. **Names/invites (LIVE party).** Set a network name in Settings
   (otherwise the phone is "Player 1"); turn on auto-accept, or accept from
   the title screen. The pause menu's **Invite network players** invites
   everyone heard. Fallback with no discovery at all: the host's `game.log`
   prints the exact `connect …` line — the guest types it into the HUD
   console.
5. **Several networks at once (VPN + WiFi).** Sessions invite to the
   announced address, and the default is the first connection up — on a VPN
   that is the VPN address, unreachable from plain WiFi. Set the adapter
   explicitly: PC launcher NETWORK → ADAPTER (`WiFi`, `wlan0`, `192.168.1`),
   phone Settings → announced adapter, or `MW2_LAN_IFACE` directly. The
   `network connections:` log line lists what the machine sees; the guest's
   `joining a … session, host …` line must name a door on a network both
   share.

## Mods (fastfiles) and what does not run

- Extra maps, weapons and modes shipped as `.ff` fastfiles (built for TU6)
  drop straight into the installed `game/` folder beside the others — the
  engine reads that one flat directory, so a same-version fastfile just
  loads. Keep the originals aside. TU6 fastfiles go to TU6 installs (PC TU6
  or the TU6 phone apps), TU0 fastfiles to TU0 installs.
- Bot Warfare's **bots do not run here**: their logic is scripts for the
  CoD Xe **plugin** (`codxe.xex`), and this port has no plugin system — it
  statically recompiles the game alone, so there is nothing for the plugin
  or its scripts to load into. What does carry over from those packs are
  the **fastfiles** (maps/weapons/modes above), which need no plugin.
  Real bots remain a separate AI project.

## Troubleshooting

- `vulkan: the device lacks dynamic rendering…` → stock driver is Vulkan 1.1
  without the extensions: on Adreno, import a Turnip zip via the in-app
  **Graphics Driver** menu; on Mali the 1.1 fallback path handles it.
- MP dirty-disc / missing zone → you installed from the lean ISO or an
  incomplete copy: reinstall the MP app from the **full** ISO.
- Installer refuses the disc → hash mismatch: wrong region/version
  (need USA/Europe 1.0.557, hashes above).
- Logs: Android `/sdcard/Android/data/com.mw2.recomp.m[p|s]/files/game.log`,
  PC `MW2_LOG_FILE=mw2.log`. `MW2_TRACE_ONLINE=1` (diagnostic builds) logs
  every LAN datagram.
- Two copies on one PC with `ONLINE=none` fight over the title ports — use
  `lan` for that too.

## Credits / legal

- `PaulCombal/mw2-recompiled`, `hedge-dev/XenonRecomp`, KisakCOD, IW4x,
  OpenAssetTools, `bylaws/libadrenotools` — see README Credits.
- License: GPL-3.0-only. Not affiliated with Activision/Infinity Ward/Microsoft.
