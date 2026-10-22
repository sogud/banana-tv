# Full-screen wallpaper

Long-press **Back** on Home to fade out the clock, controls and apps. The current wallpaper stays running in the same view; no video or screen saver is started again. A short exit hint disappears after 2.5 seconds.

Press **Back**, **OK**, a direction key or **Menu**, or click once, to restore Home and its previous focus. The exit press does not launch an app. A short Back press keeps its usual behavior. Home or leaving the activity also restores the controls. Volume and power keep their system behavior; normal system sleep rules still apply.

Transitions follow the existing motion setting and Android's disabled-animation setting. Wallpaper selection and preferences are unchanged. This feature is in source and the local emulator preview, not the public v0.1.11 download. A physical remote that reserves long-press Back may prevent the shortcut from reaching the app.

For the framework instrumentation setup in [install.md](install.md#developer-probes), add `-e wallpaper_peek true` to check real key dispatch, focus restoration, motion-off behavior, Home exit and wallpaper-player continuity on an emulator. The probe restores its temporary motion preference and refuses physical hardware. Select a video wallpaper first to exercise an active decoder.
