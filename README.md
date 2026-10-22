<div align="center">
<img src="app/src/main/res/drawable-nodpi/banana_logo.png" width="80" alt="Banana TV icon">

# Banana TV

**A quieter home for your Android TV.**

Chinese menus, an adjustable dock, adaptive wallpapers and a matching screen saver.<br>
Native Android. Open source. Built for the remote.

[Download v0.1.11](https://github.com/sogud/banana-tv/releases/tag/v0.1.11) · [Install](#install) · [中文](README.zh-CN.md) · [Roadmap](docs/roadmap.md)

<img src="docs/images/home-preview.svg" width="880" alt="Layout illustration with a clock, settings controls and four demo app tiles">

<sub>Layout illustration with demo apps, not a TV screenshot. The current interface is Chinese.</sub>
</div>

## What you get

- **Your apps, up front.** Four editable shortcuts, plus an installed-app grid. Adjust the dock from 75% to 110% (default 90%). Tiles have no visible name captions; accessibility labels remain.
- **A quiet home screen.** No sponsored rows, account login or recommendations to dismiss inside Banana TV.
- **Moving backgrounds.** Five native gradient styles change with the local date and time, alongside a muted local video loop, four stills and an idle screen saver. The included film animation is from Blender's *Spring*.
- **Made for the remote.** Spring-like focus/press animation, wallpaper-tinted panels, and shortcuts to inputs, network and TV settings. Motion follows Android's animation setting and can be switched off.
- **Optional system integration.** Default Home, a recoverable Home compatibility service, Android screen saver integration and a small, firmware-checked settings allowlist. [Setup and recovery](docs/install.md).
- **Weather when you want it.** Enable automatic IP-based city lookup, or search for a city. A small glyph and temperature appear beside the clock; failed or old readings are marked. Weather is off by default.
- **Your remote shortcuts.** Assign supported shortcut/media keys to installed apps through the optional accessibility service. Power, audio, voice, Home, Back, navigation and typing keys keep their original behavior. Manufacturer keys may never reach Android apps.
- **Less clutter.** Hide unwanted apps or open the TV's app-management screen. Hiding does not uninstall packages.
- **Local preferences.** No login or analytics SDK. Optional weather contacts Open-Meteo and, in automatic mode, ipwho.is. It sends coarse city coordinates; it does not request GPS access. [Weather and privacy](docs/install.md#weather).

**v0.1.11 is the first public preview.** It runs on the TV's existing Android firmware. It does not include a film catalog, streaming subscriptions, TVBox sources, or an in-app player. See [current limits](#compatibility-and-limits).

## Source preview: 0.1.12-dev

The current source reimplements [Aerial](https://github.com/pengchujin/aerial-tv)'s launcher features in Java and Android Views: tile ordering, five-column layout, optional names/clock/focus outline, pinned inputs, volume controls, a local settings PIN and temporary QR phone setup for photos and custom icons. Wallpapers add eight offline photographs, eight light-field presets, opt-in Bing daily pictures and app colors. Screen savers add aerial streaming with timed captions, moving photos and a moving clock.

Long-press Back on Home for [full-screen wallpaper viewing](docs/wallpaper-peek.md). Open **设置 → 壁纸 → 上传壁纸** to upload a JPG, PNG or WebP picture from a phone on the same LAN (20 MB maximum). Uploads are saved on the TV and selected automatically.

The source checks Banana TV releases on GitHub at most daily; Bing downloads are opt-in and aerial streams run only when selected. Phone setup stays on the LAN.

These changes are not in the v0.1.11 download. [Feature comparison and test limits](docs/aerial-parity.md) · [Media licenses](WALLPAPERS.md).


## Install

You need Android 9 / API 28 or newer, a computer on the same private network, and ADB access enabled on the TV.

1. Download [banana-tv-0.1.11.apk](https://github.com/sogud/banana-tv/releases/download/v0.1.11/banana-tv-0.1.11.apk) and its `SHA256SUMS` from the [release page](https://github.com/sogud/banana-tv/releases/tag/v0.1.11).
2. Enable the TV's developer options and network debugging. Accept its authorization prompt. These examples use legacy ADB port 5555; use your TV's actual address and debugging method.
3. Install and open it:

```sh
adb connect TV_IP:5555
adb -s TV_IP:5555 install -r banana-tv-0.1.11.apk
adb -s TV_IP:5555 shell am start -n com.sogud.bbtv/.MainActivity
```

Try the launcher before changing Home. Configure the dock, wallpaper and idle time in **设置**. If Android provides a default-home picker, select Banana TV there. Some Xiaomi firmware requires a separate, reversible ADB setup: read [native Home and recovery](docs/install.md#native-home-on-the-development-tv) first. Installing the APK alone does not take over the Home key or remove startup ads.

Keep network debugging on a trusted network; never forward ADB to the Internet. Turn it off when maintenance is finished. If you disabled the stock launcher, keep the documented recovery method available.

### Install with an agent

Clone the repository, open it in Codex, Claude Code or another coding assistant, and use:

> Read AGENTS.md and docs/install.md. Inspect my TV at TV_IP first. Help me install and test Banana TV. Preserve the stock launcher and other accessibility services. Ask before changing Home, system packages or restarting the TV, and prepare recovery before any system change.

## Compatibility and limits

| Area | Current status |
| --- | --- |
| Software requirement | Android 9 / API 28 or newer. Xiaomi system integrations are enabled only for supported firmware profiles; a shared brand or Android version does not establish compatibility. |
| Home | Native Home routing and wallpaper-player continuity passed focused 0.1.10 checks with Xiaomi Home disabled and preserved. On this firmware, restoring the stock Home requires ADB. |
| Startup and ads | A supported Xiaomi boot-ad preference is exposed. Cold boot, wake and actual startup-ad removal remain unverified. Ads inside other apps are outside the launcher. |
| Wallpaper | First-draw and player-continuity probes passed before this packaging release. Hardware video layers can be absent from ADB captures; that is not proof of a black picture. |
| TV integration | Native input/settings routes and system screen saver startup were checked on the development firmware. HDMI audio/video with a connected source, casting and Mi Home remote control remain unverified. |
| Performance | The window requests 60 Hz; bundled video is 30 fps. Navigation samples are not a guarantee of steady 60 fps. |
| Media center | App launching works. Film search, playback and watch progress are planned. The unreleased source includes LAN phone configuration, not a media remote. |

v0.1.11 adds the appearance, weather and remote settings described above. Its APK is build-checked; these new features still need physical-TV acceptance. The earlier 0.1.10 checks establish only the Home/integration baseline. Firmware-specific code stays limited to the tested profiles.

## Build

Use JDK 17, Android SDK Platform 35 and Build Tools 34.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to your installations and accept the Android SDK terms before installing SDK packages.

```sh
git clone https://github.com/sogud/banana-tv.git
cd banana-tv
./gradlew --no-daemon :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. The package is `com.sogud.bbtv`; keep it and the same certificate when upgrading. The public preview retains the original development certificate for existing installations. Your local debug key will usually differ; do not uninstall an existing version just to bypass a signature mismatch.

Release builds are unsigned unless all four signing variables are set: `BANANA_TV_KEYSTORE` (an external file), `BANANA_TV_STORE_PASSWORD`, `BANANA_TV_KEY_ALIAS`, `BANANA_TV_KEY_PASSWORD`. Keep the key and passwords outside Git. Run `:app:assembleRelease :app:lintRelease` with those variables for a signed build.

For firmware that refuses APKs declaring `CATEGORY_HOME`, `:app:assembleGuard` builds a package without that declaration. It uses the same package/data and signing requirements; enable Home compatibility explicitly. Use the standard build when native Home works. [Guard setup and recovery](docs/install.md#guard-build).

Source pointers and device diagnostics: [AGENTS.md](AGENTS.md). Contribution checks: [CONTRIBUTING.md](CONTRIBUTING.md). Release changes: [CHANGELOG.md](CHANGELOG.md).

## Sources and licenses

Code: [MIT](LICENSE), with the original upstream copyright retained. Outfit font: [SIL OFL 1.1](FONT-LICENSE-OFL.txt). *Spring* video and stills: CC BY 4.0, © Blender Foundation; changes and exact sources are in [WALLPAPERS.md](WALLPAPERS.md). The earlier private illustration pack is excluded from this release and its public history. [Full provenance](UPSTREAM.md).

Report bugs through [GitHub Issues](https://github.com/sogud/banana-tv/issues). For sensitive reports, follow [SECURITY.md](SECURITY.md).
