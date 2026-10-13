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
- Android builds target the **disc version (TU0)** — `CMakeLists.txt` forces
  `MW2_VERSION=tu0` when `ANDROID` is set. **Crossplay demands identical
  versions on both ends: build the PC side with `VERSION=tu0` too**
  (a TU6 PC build and a TU0 Android build are different executables and will
  not interoperate). TU6 needs the title-update package in `mw2/update/`;
  TU0 needs nothing but the disc.
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
   every published release** (currently 8, or set `MW2_VERSION_CODE` in CI) —
   Android will not install an update over an APK with an equal or higher
   code. Both flavors share the number (they are separate packages).

## Release policy (standing)

Every update ships through CI; skip only components whose code did not change:

1. Bump Android `versionCode` iff `android/` or `runtime/` changed (the
   native lib ships inside the APK).
2. Push: `Build Android APK` (campaign) runs itself; dispatch `Build MP APK`.
3. New tag (`v0.1.0-alpha.N`), pre-release, with notes. Notes MUST carry an
   `APK versionCode: N` line — the in-app updater reads it.
4. Dispatch `Build PC TU0` with that tag: it attaches the PC archives itself.
5. Attach both APK artifacts to the release by hand (workflows do not).
6. Never attach or commit game files (ISO/XEX/PE/fastfiles) anywhere.
7. Invite protocol (Presence slots/nonce) changes break old builds: both
   ends of a match must run the same alpha. Say so in the notes when it
   changes (unchanged since alpha.6).

## Title update 6 (TU6) and mods

The disc version (TU0) above is what Android and crossplay use. Mods such as
Bot Warfare need **TU6** instead:

- The TU6 package (`TU_10LC20N_…`, 2 patches + 3 fastfiles) lives in the
  **private** assets repo under `title-updates/TU6/` — same place CI reads
  it from. Never publish it.
- PC TU6 builds come from the `Build PC TU6` workflow (sp offline + mp lan,
  stamped with the release tag). **TU6 plays only with TU6** — not with TU0
  copies on PC or phones.
- The PC launcher installs/updates TU6 itself: point it at your ISO and it
  downloads nothing (the package rides with the build's installer flow).
- Loading a mod: drop its `.ff` files into the installed `game/` folder
  beside the others — the engine reads that one flat directory, so a mod
  fastfile with the right name just loads. Keep the originals aside.
- Android stays TU0 for now: a TU6 phone build needs its own recompile +
  on-device update application, which is a separate piece of work.

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

# 3. APKs
cd android
./gradlew assembleCampaignRelease assembleMultiplayerRelease
# -> app/build/outputs/apk/campaign/release/app-campaign-release.apk
# -> app/build/outputs/apk/multiplayer/release/app-multiplayer-release.apk
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
  (Android MP already is; PC needs `ONLINE=lan`), all `VERSION=tu0`.
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
