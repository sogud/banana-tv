# Security

This is an early preview. Security fixes target the current preview; there is no long-term support branch yet.

Do not put credentials, ADB keys, personal video sources or unredacted device logs in public issues. For a vulnerability involving private information, use [GitHub private vulnerability reporting](https://github.com/sogud/banana-tv/security/advisories/new).

The published preview uses Internet access for optional weather. Automatic location queries ipwho.is from the TV's public IP; forecasts and manual city search use Open-Meteo. See [weather](docs/install.md#weather) for data and service limits. The unreleased source also requests configured media catalogs, posters and streams when the media center is opened, and supports the documented release, daily-picture and aerial requests. Bundled media configuration loads from the APK; its public provider URLs carry no account credentials. Only explicit mirror endpoints receive a source's configured headers. Custom sources may execute code with the app's permissions.

Its optional accessibility service observes configured shortcut keys, optional Home keys and Xiaomi Home window changes without retrieving screen contents or keeping input history. An explicit `WRITE_SECURE_SETTINGS` grant enables selected recovery, screen saver and system-preference operations; it is not root access.

Network debugging grants powerful access to the TV. Use it on a trusted network, keep ADB off the public Internet and turn debugging off when maintenance is complete. Keep a way to restore the stock launcher before disabling it. A disabled system launcher currently needs ADB for recovery on the supported Xiaomi profile.

APK updates must keep the application ID and signing certificate. Check the release checksum and source before installing. The public preview retains the original development certificate; no automatic updater is included.
