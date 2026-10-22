# Aerial feature parity

Requested scope: all user-facing Aerial capabilities, with Banana TV branding and upgrade compatibility.

Reference: [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv), inspected on 2026-10-22,
commit [`9e353aa9aec9277521bade4894c7efe2d261e953`](https://github.com/pengchujin/aerial-tv/tree/9e353aa9aec9277521bade4894c7efe2d261e953),
version 1.22. Code is MIT, copyright 2026 pengchujin. The catalogue contains 114 films.

This is a source comparison and implementation checklist. “Present” does not imply physical-TV acceptance.

## Feature inventory

| Capability | Banana TV before this request | Current source (unreleased) |
| --- | --- | --- |
| Large clock, date, compact clock while browsing | Compact clock present | Optional left/right clock and date; compact header clock during grid browsing. |
| Five-column grid and one-row Home | Four-column one-row Home present | Four-column Dock and five-column grid choices; existing Dock stays the default. |
| Focus elevation, parallax, optional high-contrast outline | Elevation present | Cached artwork, focus lift/scale, pointer parallax and an optional contrast outline. |
| Focused/all/hidden app-name styles | Names hidden | All three name styles; hidden remains the upgrade default. |
| Banner, adaptive and square icon artwork | Partial | General banner/adaptive/square rendering and custom-icon invalidation. |
| App-store category and unlisted firmware apps | Standard launcher categories | APPSTORE_LAUNCHER plus verified exported Xiaomi media/Mi Home activities. |
| Move tiles with automatic reordering | Fixed favourites only | Persisted order, shifted neighbours and retained positions for temporarily missing packages. |
| Long-press/Menu app actions | App information only | Context menu with move, hide, information, system uninstall and reset-icon actions. |
| Restore hidden apps | Present | Existing restoration UI protected by the optional settings PIN. |
| HDMI/AV/tuner tiles on Home | Input chooser present | Pinned Android TV input IDs, including tuner routes; physical switching awaits device tests. |
| CEC connection labels and boot input | Connection state present | CEC child labels and optional boot/wake source routing; physical acceptance pending. |
| Control Center, network and TV settings | Present | Floating Control Center with inputs, audio/display, network, screen saver, phone setup and updates. |
| Volume slider | Absent | AudioManager slider with hardware-volume synchronization; fixed outputs show guidance. |
| Brightness slider or OEM picture-settings fallback | Absent | Standard manual-brightness capability/readback checks, with OEM picture-settings fallback. |
| Back returns to first row, then optionally starts the screen saver | Different behavior | Back returns to the first row; a separate option starts the screen saver there. |
| Aerial screen saver with timed captions and previous/next | Absent | 114-film HTTPS playlist, captions, previous/next, 48 MiB cache, bounded buffer, timeouts and local fallback. |
| Photo screen saver with slow motion | Selected still present | Cached daily/personal images, minute rotation and optional slow zoom. |
| Moving clock screen saver | Absent | Large clock placed within the screen and relocated every minute. |
| Android system screen saver and foreground idle takeover | Present | Existing Dream and verified Xiaomi idle integration retained; both use the expanded scene. |
| Bing daily wallpaper and latest-daily selection | Absent | Opt-in latest/dated daily pictures, eight-day cache, captions and offline fallback. |
| Eight built-in gradient presets | Five date/time styles | Eight additional light-field palettes; existing adaptive styles and indices retained. |
| Personal photos, daily rotation and removal | Private local override only | Managed re-encoded photos, daily rotation, selection and deletion via phone setup. |
| App-colored background | Absent | Focused artwork supplies a cached-color backdrop and Dock/header material. |
| Phone configuration page and QR pairing | Absent | Explicit ten-minute LAN session with a fresh 192-bit QR token; closes with its panel or Home. |
| Phone photo upload and wallpaper/screen-saver selection | Absent | Validated, size-limited multipart uploads and source/mode choices. |
| Phone custom app icons and reset | Absent | Installed-package validation, custom artwork caching and reset. |
| Phone weather city selection and automatic location | TV-side weather present | Phone city/auto/off actions reuse WeatherController and its existing opt-in preferences. |
| Parent PIN for settings and restoring hidden apps | Absent | Salted PBKDF2 PIN, confirmation, retry delay, ten-minute unlock and Home relock. |
| Process revival through notification-listener binding | Foreground recovery present | Explicit notification binding, no content callbacks, unbinding on disable; real revival pending. |
| Optional ADB keep-alive | Absent | Explicit Android ADB switch observer with permission, backup and readback; vendor network switches remain separate. |
| Standard/guard packaging and reversible installation | Standard package present | Standard plus Guard build type without CATEGORY_HOME; same package/data/signing and documented recovery. |
| Version notification and upgrade instructions | Absent | Banana GitHub release metadata, cached version notice and manual browser upgrade guidance. |
| Touch and mouse navigation | Basic View support | Native scrolling, long press, move destinations and slider input; emulator checks tracked below. |

## Implementation choice

The existing project rule says **“Keep Java + Android Views.”** Aerial uses Kotlin + Compose:
39 Kotlin files and about 8,100 lines, plus Media3, NanoHTTPD and ZXing.

Selected on 2026-10-22: retain Java + Android Views and implement the missing capabilities in the existing application. Keep the current package, signing and preferences. Kotlin or Compose is not required for feature parity and would not by itself improve frame times.

The listed capabilities are implemented in the unreleased source. Focused emulator checks are recorded below; physical-TV acceptance remains separate.

## Upgrade contract

- Keep `com.sogud.bbtv`, the existing signing certificate and the `MainActivity` component.
- Migrate favourites, hidden apps, wallpaper selection, Dock size, weather choices and remote-key assignments without clearing data.
- Preserve existing wallpaper indices or translate them through an explicit versioned mapping.
- Keep local video wallpaper, date/time wallpapers, the licensed offline pack and verified system settings available.
- Preserve existing accessibility services, Dream selection and their restoration records.
- Keep the current minimal layout as the default. Expose Aerial's additional layouts as choices.
- Keep Aerial's MIT notice for reused code. Film URLs do not grant redistribution rights to Apple's videos; do not bundle the films.
- Phone access is opt-in, short-lived and LAN-only. Validate exact token paths, methods, image dimensions, upload sizes and installed package IDs.
- Point update checks and installation instructions at Banana TV. Do not reuse upstream telemetry or network fallbacks without review.

## Acceptance

1. Test preference migration using synthetic legacy fixtures, including missing apps and invalid wallpaper indices.
2. Test D-pad focus, move/reorder persistence, app menus, hidden-app restoration and PIN handling.
3. Test phone pairing, token rotation, session expiry, malformed uploads and photo/icon operations using disposable demo images.
4. Test screen-saver modes, captions/next/previous, connection failure, no-network fallback and media release on exit.
5. Inspect release-build rendering and frame times on the existing isolated emulator. Keep actual 60 fps claims separate from a 60 Hz request.
6. Retest Home, boot/wake, notification revival, optional ADB persistence, real input switching and backlight on the owner's TV when available. No TV reboot or system-state changes are part of the current emulator work.

Aerial also has a [MiTV-ASTP0 / Android 9 Home-flash report](https://github.com/pengchujin/aerial-tv/issues/48).
Its feature set is not evidence that the existing physical-TV flicker is resolved.

## Verification — 2026-10-22

- Debug and Guard APKs build; lint reports zero errors and 33 warnings. The merged Guard activity has no Home registration. Both variants retain the package and signing requirements.
- The updated emulator APK matches its previous certificate. The first application draw includes its wallpaper. Twenty-four disposable fixture checks pass for preference defaults/order, PIN, images, phone authentication, upload limits, setting allowlists and session expiry.
- Native UI checks pass for five columns, non-overlapping clock, app-store discovery, remote/pointer reordering and restart persistence, capability-aware audio/display panels, phone multipart upload/thumbnail/delete, panel-close shutdown, PIN keypad and Home relocking after Activity recreation. System uninstall opens a confirmation and cancels correctly. The Dock picker reflects five actual positions; hide/restore also passes.
- Photo and clock screen savers render and exit. Aerial films actually play over HTTPS with captions; next/exit work. A controlled GSM-speed emulator network triggers the local fallback and still permits exit; normal networking is restored afterward.
- Bing downloads and caches eight dated pictures; the generated thumbnail is a valid JPEG. The opt-in setting and original Home preferences are restored after testing.
- A debug emulator Dock sample on a static wallpaper, 11 app tiles and no concurrent build: 311 frames, 24 D-pad events, p50 18 ms, p95 20 ms, one Android-reported janky frame. This is a gfxinfo duration histogram, not average FPS, remote latency or a steady-60-fps guarantee.

Boot/wake, the physical Home transition, CEC/input video and audio, actual backlight response, notification revival, vendor ADB persistence and Guard installation on restrictive firmware still require device acceptance. No physical-TV state was changed for this work. The public v0.1.11 download is unchanged.
