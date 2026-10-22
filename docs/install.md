# Installation and troubleshooting

This is a public preview. Read the limits in [README](../README.md#compatibility-and-limits) before installing. Focused 0.1.10 Home and first-draw probes provide the integration baseline; 0.1.11 adds appearance, weather, key assignments and distribution changes. New-feature physical-TV acceptance is pending. Cold boot, wake, actual startup-ad removal and the physical picture still need acceptance.

## 1. Connect the correct TV

Enable network debugging in the TV settings, then accept its authorization prompt. The command and port depend on the firmware; the example below is for a TV that explicitly exposes legacy ADB on port 5555.

```sh
adb devices -l
adb connect TV_IP:5555
adb -s TV_IP:5555 get-state
adb -s TV_IP:5555 shell getprop ro.build.version.sdk
adb -s TV_IP:5555 shell getprop ro.product.cpu.abilist
```

Use your selected ADB server consistently. If you need a separate server, put `-P SERVER_PORT` before `-s` in every command. Do not change another tool's server or keys.

A DHCP reservation on the router is usually enough to keep the address stable; manual IP settings on the TV are not required for the launcher. After a restart, some firmware disables network debugging or changes the address. Check the TV before changing the computer's ADB setup.

## 2. Build and install

Download the APK from [GitHub Releases](https://github.com/sogud/banana-tv/releases), or build with the requirements in [README](../README.md#build). Compare its SHA-256 with the release's SHA256SUMS. Before updating, inspect the installed version and compare the APK's signing certificate with the existing one.

```sh
adb -s TV_IP:5555 shell dumpsys package com.sogud.bbtv
apksigner verify --print-certs PATH_TO_APK
adb -s TV_IP:5555 install -r PATH_TO_APK
adb -s TV_IP:5555 shell am start -n com.sogud.bbtv/.MainActivity
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` usually means a signing mismatch. Find the original key; do not uninstall to bypass the error without the owner's agreement, because that removes preferences and permissions. Never copy a key into the repository.

## 3. Optional Home compatibility

Start in trial mode. Check the UI and existing apps before enabling **设置 → 主屏幕 → 使用 Banana TV**. Open the system accessibility page from that panel and enable Banana TV if the firmware offers it.

With the owner's authorization, this additional permission allows the app to restore its own accessibility service if firmware removes it:

```sh
adb -s TV_IP:5555 shell pm grant com.sogud.bbtv android.permission.WRITE_SECURE_SETTINGS
```

The permission alone does not enable Home compatibility. Enable the in-app switch as well. The app appends its own service while preserving other enabled services, such as Xiao AI. Do not overwrite `enabled_accessibility_services` with a single component.

To stop compatibility mode, turn off the in-app switch first. Removing the service from system accessibility settings while recovery remains enabled can cause the app to restore it. If the system screen saver is enabled, turn that off too before revoking the shared optional permission:

```sh
adb -s TV_IP:5555 shell pm revoke com.sogud.bbtv android.permission.WRITE_SECURE_SETTINGS
```

Do not assume a successful `set-home-activity` command changed the actual Home resolver. Test the physical remote and the full transition. Stock Xiaomi Home remains installed; on the development TV it is disabled after the separately approved native switch below.

### Native Home on the development TV

On the verified Android 9 / `mulan` firmware, `set-home-activity` reports success while Xiaomi Home stays enabled, but the resolver still chooses Xiaomi. Disabling the **whole** `com.mitv.tvhome` package after Banana TV is installed and working allows the native resolver to select `com.sogud.bbtv/.MainActivity`. The package remains installed for restoration. This behavior is specific to the tested HyperOS `OS3.0.101.0.PSTAATV` profile.

With 0.1.10, native remote-Home checks returned from another app and closed launcher settings while preserving the same wallpaper player, with Home compatibility switched off. The restore commands below were also tested. Cold boot has not been verified; this result does not establish compatibility for a different firmware.

A permanent switch needs the owner's explicit approval and working ADB access. Xiaomi Home contains integrations beyond its launcher; disabling it may affect casting, Mi Home or voice flows. Those functions need device acceptance. The ordinary APK does not have permission to enable or disable this system package.

Before changing anything, record the current package state and Home resolver. Keep the installed Banana TV APK and its signing key available for recovery:

```sh
adb -s TV_IP:5555 shell dumpsys package com.mitv.tvhome
adb -s TV_IP:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Only for an approved switch on this verified profile:

```sh
adb -s TV_IP:5555 shell pm disable-user --user 0 com.mitv.tvhome
adb -s TV_IP:5555 shell cmd package set-home-activity --user 0 com.sogud.bbtv/.MainActivity
adb -s TV_IP:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s TV_IP:5555 shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME
```

The tested recovery restores Xiaomi Home's original `enabled=0` default state and its Home preference:

If Home compatibility was enabled again, turn it off in Banana TV first; otherwise it can redirect Home back to Banana TV after restoring Xiaomi. Leave the shared system-settings permission in place if system screen saver or system preferences still need it.

```sh
adb -s TV_IP:5555 shell pm default-state --user 0 com.mitv.tvhome
adb -s TV_IP:5555 shell cmd package set-home-activity --user 0 com.mitv.tvhome/.MainActivityUserMode
adb -s TV_IP:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s TV_IP:5555 shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME
```

Do not uninstall Xiaomi Home or reboot as part of this procedure. If it was disabled through ADB, Banana TV's “小米桌面” item explains that it must first be restored through ADB; it does not pretend an ordinary APK can undo the package change.

## 4. Verify the actual behavior

Check each case separately:

1. Start the app after its process has stopped. Watch the wallpaper from the first visible frame through video start.
2. Press physical Home while already on Banana TV, then from TVBox or another app. Watch the entire transition, not just its final screen.
3. Open settings and dismiss them with Home. Check focus and video continuity.
4. With explicit authorization, test standby/wake and a reboot. Note when any ad appears relative to the system logo and Banana TV.
5. Check HDMI, casting and Mi Home remote control separately before reporting full TV integration.

`tools/check_home.py` checks the final Activity and focused window after a delay. It cannot prove that no intermediate flash occurred. Linux input nodes and key codes are device-specific; verify the key layout before using its kernel-input option.

`tools/check_home_continuity.py` starts on the visible home with an active video wallpaper. It opens the launcher's settings, presses Home and requires the panel to close while the same player remains connected. Requiring a visible Home action prevents a disconnected key service from producing a false pass merely because nothing happened.

For native Home, turn compatibility off and add `--native-home`. This mode requires the actual system resolver to be Banana TV. Android may briefly pause the Home Activity while delivering its Intent; the probe records those events but still requires the same player, a closed panel and no Xiaomi Home resume. Without this flag, the compatibility-mode check still rejects Activity pauses. Native Home is handled in `onNewIntent`; wallpaper release happens in `onStop`, so a transient pause does not restart it.

For navigation performance, `tools/profile_tv.py` reports real frame-time samples. Record the wallpaper, output mode, scenario and sample size. Inspect physical video too: a black ADB capture can be a missing hardware video layer rather than a black TV picture.

## System screen saver

Open **设置 → 屏幕保护程序 → 电视系统屏保**. This opt-in mode selects Banana TV's Android `DreamService`. It requires the same optional `WRITE_SECURE_SETTINGS` grant. The previous screen saver component, enabled state, sleep trigger and standard display timeout are saved in private app preferences for restoration; the current state is read from Android.

On standard Android, choose a start time from 1 to 30 minutes. The system owns the idle trigger, so applications that keep the screen on can prevent it from starting. **立即预览** uses the available SystemUI screen saver entry. Home dismisses it and returns to the launcher; the home wallpaper's decoder stops while the screen saver runs.

The verified Xiaomi `mulan` firmware `OS3.0.101.0.PSTAATV` replaces Android's automatic Dream transition with its own idle broadcast, `com.duokan.intent.action.show.screensaver`. This adapter requires that exact build fingerprint. Its receiver accepts senders holding the system `DEVICE_POWER` permission; Banana TV does not request that permission. Other applications' idle eligibility remains the TV's decision. No foreground polling or system-package removal is used.

On this Xiaomi build, **开始时间** reads the actual `Settings.System.screen_saver_timeout` and opens the TV settings for adjustment. The app leaves this custom System key unchanged. Xiaomi also counts a playing video wallpaper as video, so the home screen reuses its existing local idle timer to start the same system Dream. It reads the TV's configured time when Home resumes and does not run a second global timer. The standard `screen_off_timeout` is not used to control Xiaomi's screen saver.

Turn **电视系统屏保** off before uninstalling Banana TV, clearing its data or revoking the optional permission. This restores the saved values; if another screen saver was selected outside Banana TV, that selection takes precedence. In the standard time chooser, **从不** also restores the system configuration and disables the local idle timer. A successful preview does not by itself prove an automatic idle trigger or physical video output.

If the app has already been removed, restore `Settings.Secure` keys `screensaver_components`, `screensaver_enabled`, `screensaver_activate_on_sleep` and `Settings.System.screen_off_timeout` from your device backup. Restore absent values with `settings delete`; do not copy another TV's component or timeout. Keep the backup outside public source.

## Xiaomi inputs and settings

The standard Android TV input Intent is tried first. The verified Android 9 / `mulan` TV player version `30000561` needs its native `com.xiaomi.mitv.tvplayer.EXTSRC_PLAY` action with an integer `input` extra. The implementation accepts only the observed HDMI 1–3 and AV input IDs; unknown IDs or firmware versions do not receive guessed source numbers. HDMI 1–3 were checked against their native on-screen labels and selected source values. Actual HDMI video/audio and AV still need connected hardware.

Wi-Fi and system settings also try standard Android actions first, then the installed Xiaomi settings actions. The native settings page uses a window named `settings_window`; verify its UI package and contents instead of assuming the window title contains the package name.

## System preferences

Open **设置 → 系统偏好**. The same optional `WRITE_SECURE_SETTINGS` grant above permits these writes; Home compatibility does not have to be enabled. The app reads actual system values each time and checks writes by reading them back. It does not store a second copy of the switch state.

- **开机广告** offers **关闭** or **跟随电视设置**. Only the verified Android 9 / `mulan` device profile with Xiaomi TV Home version code `100413` can change this policy. Other builds show it as unsupported.
- **系统动画** adjusts the three Android animation scales to standard (`1`), faster (`0.5`) or off (`0`). This changes animation duration, not the display refresh rate or a promise of 60 fps.

The boot-ad policy uses `Settings.Global` key `mitv.settings.advertise.state`: `1` prevents the verified boot-ad manager from playing an ad; `0` follows the TV's separate preference. It does not force ads on. If `Settings.System.boot_ad_switch` is already `0`, following the TV setting still leaves boot ads disabled. A new startup must be observed before reporting that an ad was actually removed; do not reboot without authorization.

`Settings.System.boot_ad_switch` (`0` off, default `1`) and `boot_ad_sound_enable` (`0` mute, default `1`) are distinct keys. On the development firmware, a same-value write to `boot_ad_switch` from the app UID throws `IllegalArgumentException`, even with the optional permission. Do not add a fake sound toggle or lower the app's target SDK to bypass this restriction. The current app leaves those two keys unchanged. An ADB shell write succeeding does not establish APK access.

Before manual device changes, record the namespace, exact previous value and whether the key was absent. Restore an absent key by deleting it rather than inventing a default. Keep backups outside the public source tree. The implementation is an allowlist in `TvSystemPreferences`; do not expose an arbitrary settings editor, shell endpoint or Root command.

The Android permission behavior is described by the [Android 9 SettingsProvider source](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java): Global writes require `WRITE_SECURE_SETTINGS`; custom System keys also face caller/target-SDK restrictions. Xiaomi-specific meanings were checked in the installed TV Home package; they are not standard Android APIs.

## Developer probes

The framework-only startup probe targets the Release build and records whether the first actual application draw already has a wallpaper. It does not capture hardware video output.

```sh
./gradlew --no-daemon :app:assembleRelease :app:assembleReleaseAndroidTest
adb -s TV_IP:5555 install -r app/build/outputs/apk/release/app-release.apk
adb -s TV_IP:5555 install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb -s TV_IP:5555 shell am instrument -w com.sogud.bbtv.test/com.sogud.bbtv.WallpaperStartupProbe
```

Require `first_draw_has_wallpaper=true` and `result=PASS`; the ADB command's process exit code alone is insufficient. Verify compatible signing before installing either APK.

With the owner's authorization to disable boot ads, `-e global_settings true` instead checks the app's actual Global write and readback, plus its missing-permission branch. It leaves the boot-ad policy at `1` (off), so back up the previous value first. It does not reboot or test physical ad removal. The diagnostic `-e system_settings true` only attempts a same-value System write and requires `boot_ad_switch` already be `0`; its expected rejection on this firmware is evidence of the access limit, not successful configuration.

`-e system_screensaver true` checks screen saver selection and exact restoration from the app UID, plus missing-permission and invalid-time rejection. On the verified Xiaomi profile it also requires the standard display timeout to remain unchanged. It restores the four original values in a `finally` block. It does not wait for idle or verify a physical picture. Run it with the system screen saver disabled first, so it can safely own and restore its temporary selection.

Ending instrumentation stops the target process. On this Xiaomi firmware that can leave an accessibility component enabled with a dead Binder connection. Launch Banana TV again and allow its recovery service to reconnect, then test Home from another app. The recovery waits three seconds for a normal bind, cycles only Banana TV's component if needed, and limits reconnect attempts to one per 30 seconds. Other accessibility services remain enabled. No reboot is part of this probe.

```sh
adb -s TV_IP:5555 shell am start -n com.sogud.bbtv/.MainActivity
adb -s TV_IP:5555 shell dumpsys activity services com.sogud.bbtv
adb -s TV_IP:5555 uninstall com.sogud.bbtv.test
```

In the service dump, check the actual HomeRedirectService binding; its presence in `enabled_accessibility_services` alone is insufficient. Remove the temporary test package when finished, leaving the launcher installed.

## Troubleshooting

| Symptom | Check and next action |
| --- | --- |
| Connection refused | Recheck network debugging, current IP and the firmware's port. Reconnect; a stale device list is not proof of a working connection. |
| Unauthorized | Accept the TV's authorization prompt. Do not delete or replace existing keys automatically. |
| Home reaches Xiaomi or does nothing | Check the in-app switch, optional recovery permission and actual accessibility binding. A component can be enabled while its connection is dead. Allow reconnection, then verify with the physical remote. Avoid bulk package removal. |
| Home or wallpaper flashes | Compare first-draw readiness and player continuity, then inspect the physical picture. Final focus, one screenshot and frame-time percentiles are insufficient. The owner reports much improved Home transitions after the fix; cold boot and wake still need their own checks. |
| A system preference cannot be changed | Check the optional permission, supported firmware profile and readback. Do not infer APK access from a successful ADB write. Boot-ad sound remains outside the editable menu on this firmware. |
| Startup ad remains | Identify the boot stage, responsible component and the installed firmware's actual opt-out behavior. A system setting is separate from Home compatibility. Back up its exact prior value and verify a new boot only with authorization; do not guess a package to disable. |
| Local video fails | Select the dynamic wallpaper item. Check that `wallpaper.mp4` is readable and decodable; `wallpaper.jpg` supplies its poster. Keep the built-in fallback available. |
| Home works until reboot | Inspect the new boot session and permissions after the TV is online. ADB itself may need to be enabled again; launcher autostart should not depend on a connected computer. |

Local media files go in the app-specific directory `Android/data/com.sogud.bbtv/files/wallpapers/`. The directory contains `wallpaper.mp4` and `wallpaper.jpg`; no in-app import UI exists yet. Use media you may lawfully use and redistribute. Keep private media and streaming configuration outside the public source tree.

## Appearance and apps

In **设置 → 外观**, adjust Dock size (75%, 85%, 90%, 100%, 110%; default 90%) and icon motion. Android's disabled-animation setting also disables continuous background movement. Tiles keep accessibility names while hiding visual captions.

**设置 → 壁纸** includes five procedural color backgrounds driven by the TV's local date/time, four licensed stills and a local video option. Existing wallpaper slots 0–4 retain their order. The procedural styles also work in the screen saver.

**设置 → 应用管理** can hide and restore tiles, or open native app management. It does not uninstall apps or disable system packages automatically. Confirm exact package names and prepare recovery before a cleanup.

## Weather

Weather is off by default. Select **设置 → 天气 → 自动定位城市** to use IP-based city lookup, or **选择城市** to search manually. The clock area shows a vector weather glyph and Celsius temperature. A dot marks a cached/failed update; readings older than two hours disappear.

Automatic city lookup contacts [ipwho.is](https://ipwhois.io/documentation). The provider sees the TV's public IP; a proxy, VPN or ISP location can produce the wrong city. Manual search uses [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api). Forecasts use [Open-Meteo](https://open-meteo.com/), CC BY 4.0. City names, rounded coordinates and recent weather stay in private app preferences. No GPS permission, location history or analytics is used. Turning weather off clears that cache.

The free Open-Meteo endpoint is for [non-commercial use](https://open-meteo.com/en/terms). Commercial deployments need an appropriate provider plan. Forecast refreshes are limited to roughly every 30 minutes while Home is active, with a five-minute retry delay on failure. Automatic city lookup is cached for 24 hours. Provider access from the owner's TV network is unverified.

## Remote button assignments

Open **设置 → 遥控器 → 添加按键**, press a supported shortcut/media key, and choose an installed app. Enable Banana TV under **按键权限** if required. The existing accessibility service handles these assignments even with Home compatibility off; an existing optional WRITE_SECURE_SETTINGS grant allows its usual recovery service to maintain the connection. Other accessibility services are preserved.

Use **恢复原功能** for one assignment or **恢复全部按键** for all of them. Clear assignments as well as Home compatibility before disabling the shared accessibility service or revoking its recovery permission. Unavailable target apps leave the original key behavior intact when a key press begins. Power, volume, voice, Home, Back, navigation and text-entry keys cannot be assigned.

The app can only receive keys Android forwards to accessibility services. Firmware-owned buttons may launch their original app before, or instead of, a key event. Learning a key does not establish successful global interception. Test with the physical remote in other apps; the new assignment UI has not yet passed this device check. No root, keylayout replacement, binary patch, bind mount or shell-command execution is included.

## Unreleased configuration features

Open **控制中心 → 手机配置** and scan the QR code from a phone on the same trusted LAN. The session expires after ten minutes and stops when the panel closes or Home relocks. It uses HTTP on the LAN; anyone with the temporary link can change these settings, so keep it private. It does not expose ADB or a shell. Photos are limited to 20 MB each, 40 million input pixels and 50 saved images; the app downsamples and re-encodes them without original metadata. Custom icons can target only discovered, installed applications.

For personal wallpapers, open **设置 → 壁纸 → 上传壁纸**, scan the QR code and choose a JPG, PNG or WebP image on the phone page. An uploaded image is saved locally and selected automatically. Tap another thumbnail to switch, or delete an uploaded picture from that page. This upload flow is in the development source; it is not in the public v0.1.11 APK.

Choose **设置 → 壁纸 → 更新每日图片** to opt into Bing downloads. Online daily pictures and Apple's aerial films are cached/streamed for display and are not redistributed in the APK. Aerial playback uses HTTPS, a 48 MiB opener cache and a 24 MiB target playback buffer. Connection/read/no-frame failures advance through films and then show a local photo. Left/Right switches films; other navigation leaves the screen saver.

**设置 → 设置访问密码** protects settings and hidden apps. It is a local 4–8 digit PIN with a salted hash, a ten-minute unlock and Home relocking; it does not control other applications. If forgotten, a debuggable build can use `run-as` to back up the `home` preferences and remove only `pin_hash` and `pin_salt`. An ordinary ADB shell cannot edit a non-debuggable release's private preferences; without Root, recovery requires clearing app data, which also removes all settings and needs the owner's explicit agreement.

**设置 → 启动与保活** offers optional notification binding and ADB restoration. Notification contents are never read. ADB restoration requires the optional secure-settings permission and only watches Android's `adb_enabled`; it cannot guarantee a vendor network-debugging switch, port or authorization survives a reboot. Turning the option off stops restoration and leaves Android's current debugging state unchanged; disable debugging in the TV settings when finished. Source, wake and physical-TV behavior still require device acceptance.

## Guard build

Use the normal release first. Only when firmware rejects a package declaring `CATEGORY_HOME`, build `:app:assembleGuard` with the same four signing variables as a release. The output is `app/build/outputs/apk/guard/app-guard.apk`. Its main activity remains `com.sogud.bbtv/.MainActivity`, but it does not register as native Home. The owner must enable the reversible accessibility-based Home compatibility service. Updating a working native-Home installation to Guard can return Home to the stock launcher, so prepare ADB recovery first.

To return to the standard build, install a standard APK signed by the same key and restore the system's default-Home selection. Keep the stock launcher installed. This variant is not a firmware replacement.

For disposable emulator checks, add `-e feature_parity true` to the framework instrumentation command above. It tests settings migration, ordering, PIN, image validation and the phone session using generated fixtures and restores preferences. It refuses non-emulator hardware. Sign both APKs with the emulator's existing key; an unsigned instrumentation APK is not installable. Physical-TV tests remain separate.
