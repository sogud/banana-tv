# Contributing

Open an issue for a bug or proposed feature, then keep the change focused. Small pull requests are easier to review.

Include the TV model, Android version, firmware, steps and expected/actual behavior. Remove device addresses, accounts, keys and private app data from logs and images. Use demo data for public screenshots.

Use Java and Android Views. Keep UI strings in resources; the current UI is Chinese. Source ownership and device setup are in [AGENTS.md](AGENTS.md).

For Java/resource changes, run the relevant Gradle build and lint task. For palette changes, run `python3 tools/check_wallpaper_palette.py`. For profiler changes, run `python3 -m unittest discover -s tools -p test_profile_tv.py`. Documentation-only changes need link and diff checks, not an Android build.

A device check must say which firmware and behavior it actually covered. Keep original settings and a tested recovery path before changing Home or system settings. Only touch a TV you own or have explicit permission to maintain.

AI-assisted contributions are welcome. The contributor is responsible for reviewing the code, explaining the behavior, checking licenses and reporting which tests actually ran. Build success alone does not establish physical-TV behavior.

By contributing, you license your code under the project's MIT terms. Third-party media needs its own documented redistribution license.
