# Roadmap

Banana TV provides a Chinese Android TV home screen on the existing firmware. The 0.1.12-dev source adds app ordering/layout, personal and daily wallpapers, aerial/photo/clock screen savers, QR phone configuration and settings PIN. See [implemented features and verification](aerial-parity.md).

## Next

1. Verify cold boot, wake, physical Home transitions, casting, Mi Home and HDMI audio/video on the development firmware. Improve stock-Home recovery before broad device rollout.
2. Build a media home around one accessible, authorized source: real titles and posters, search, playback links and source attribution. Launching TVBox alone does not expose its private library or watch progress.
3. Add playback and genuine resume data, then phone text input and optional Home Assistant controls. Temporary LAN phone configuration is implemented in the unreleased source; see [Aerial parity](aerial-parity.md).

Additional languages and TV profiles need contributors and device tests. New profiles must have a recovery procedure; do not copy another model's package-disable list blindly.

A firmware replacement would require bootloader, driver, signing and recovery support. This APK does not provide a replacement OS or Apple services.
