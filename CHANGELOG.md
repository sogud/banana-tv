# Changelog

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
