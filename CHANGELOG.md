# Changelog

## 0.1.12-dev — source preview

- Add persistent tile ordering, five-column layout, optional app names/large clock, custom icons and pinned inputs.
- Add aerial, photo and moving-clock screen savers, daily pictures and app-colored backgrounds.
- Add temporary QR phone configuration with local wallpaper upload, photo removal and icon/weather controls.
- Add settings PIN, capability-aware audio/display controls, optional recovery and Guard packaging.
- Add full-screen wallpaper viewing with a long Back press, preserving the wallpaper player and focus.
- Correct camera-photo orientation, cancel stale PIN authorization on Home, restore click-to-exit for system screen savers and refresh daily pictures without leaving Home.
- Keep direct backlight writes disabled until a device profile is verified; use native picture settings.
- These features are a source preview. Physical-TV acceptance remains pending; the public v0.1.11 APK is unchanged.

- Replace the solid status bar with adaptive weather/time text and an on-demand Control Center.
- Center the dock when fewer than four apps are available; retain adjustable tile sizing.
- Refine tile corners and settings spacing, put appearance controls first, and restore the selected settings row on Back.
- Remove the Spring video's dark title fade and regenerate visible posters and still previews.

- Add eight offline, 1080p landscape photographs with CC0/public-domain provenance and small gallery previews. Preserve all ten existing wallpaper indices.
- Replace the wallpaper text list with a remote-controlled thumbnail grid.
- Use the selected still for the screen saver, with background decoding and no video player.
- Describe compatibility by Android/API and firmware versions rather than a specific TV model.


## 0.1.11 — 2026-10-20

First public preview.

- Add five date/time-aware procedural wallpaper styles and shared screen saver support.
- Add Dock sizing (75%–110%, default 90%), spring-like focus/press effects, motion control and caption-free app tiles.
- Add opt-in weather with IP-based or manual city selection, a weather glyph and temperature, bounded refresh and stale-cache handling.
- Add app-only remote shortcut assignments with essential-key protection, plus hidden-app management. Physical-device acceptance is pending.
- Publish a clean project snapshot without private workspace history or unlicensed illustration assets.
- Bundle licensed *Spring* stills alongside the existing video loop; retain wallpaper slot order and the dynamic-video slot.
- Configure release signing through external environment variables; preserve the existing preview certificate for upgrade compatibility.
- Add English/Chinese README, installation/recovery guidance, source attribution and contribution/security documents.

Includes the 0.1.10 native Home handling and wallpaper lifetime fix: Home dismisses launcher panels without recreating the video player during Android's brief Activity pause; leaving the launcher releases it in `onStop`.

Build, lint and focused logic checks cover 0.1.11; they do not verify physical key interception, weather network access or the rendered picture. The earlier 0.1.10 device checks remain the integration baseline. A new physical-TV pass, cold boot, wake, actual startup-ad removal, casting, Mi Home and connected HDMI audio/video are pending.
