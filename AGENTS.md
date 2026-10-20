# Banana TV agent guide

Read [README.md](README.md) for current limits and docs/roadmap.md for intended features. Before installing, handling Home permissions or diagnosing a device, read [docs/install.md](docs/install.md).

## Source and changes

- Inspect branch and working-tree status before editing. Preserve unrelated changes.
- Keep Java + Android Views; keep Chinese UI strings in resources and English code names.
- Keep `com.sogud.bbtv` for upgrade compatibility. Preserve the signing certificate and local preferences.
- AmbientWallpaperView owns the procedural backgrounds; AmbientColors is deterministic date/time color logic. WeatherController owns opt-in HTTPS weather, coarse location and cache age; never log location/response bodies. RemoteKeys owns app-only key assignments; RemoteKeyPolicy protects essential/typing keys.
- Release signing uses the four external environment variables documented in README. With none set, release builds are unsigned; debug builds use the developer's own key. A signature mismatch is not permission to uninstall or clear data.
- MainActivity owns navigation and settings; AppAdapter owns app discovery; VideoWallpaper owns video surfaces and player lifetime. HomeIntegration and its services own optional Home routing. TvSystemPreferences owns the small system-settings allowlist.
- SystemScreensaver owns Dream selection and restoration. XiaomiScreensaverReceiver handles only the verified firmware's native idle event; its sender permission and device check are required. See [system screen saver setup](docs/install.md#system-screen-saver) before changing idle behavior.
- Use the smallest check that covers the changed behavior. A successful build does not prove a correct picture or Home transition on a TV.

## Device work

- Select the owner's explicit TV serial and ADB server. Inspect model, SDK, firmware, Home resolver and installed version before changing state. Never operate on the first listed device by default.
- Check state before installing or changing a permission. Existing session authorization remains valid within its scope.
- Preserve other accessibility services. Keep Home compatibility reversible through the app.
- Reboots, firmware changes, stock Home removal and system-package cleanup require explicit scope. A request to improve the launcher does not authorize all of them.
- Keep device addresses, keys, captures and logs outside the public source tree. Use placeholders in shared examples.
- ADB write access does not prove APK access. Verify system-key meanings and app-UID write/readback on the target firmware; keep namespace/value backups and the device-profile check. Do not add arbitrary shell execution or settings keys guessed from their names.

## Verification and publication

- Reproduce startup, repeated Home, returning from another app and wallpaper first-frame behavior separately. The existing Home script only verifies the final destination.
- ADB captures can omit the TV's hardware video layer. Record that limit; confirm physical picture before claiming visual success.
- Report measured frame times, sample conditions and unresolved issues. A 60 Hz output is not a 60 fps measurement.
- The bundled Spring video and stills use CC BY 4.0; keep attribution and hashes in WALLPAPERS.md and the APK assets. Check source, assets and all history before publication; only include media with documented redistribution rights.
- Public UI illustrations use demo data and are labeled as illustrations. A real screenshot must come from a clean demo instance.
- Publish only with the owner's authorization for that release. Never commit a signing key, account data or private streaming configuration.
