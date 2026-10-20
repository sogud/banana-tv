# Wallpapers and video

The public package includes five original procedural styles (流光、潮汐、玫瑰暮色、暖砂、夜紫), four still frames and one muted video loop from *Spring* (2019). The five procedural styles vary with local date/time and work without a decoder or network. They use the application code license. Choose them in **设置 → 壁纸**. Playback is local.

## Included media

© Blender Foundation | cloud.blender.org/spring. Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), as stated on the [Spring rights page](https://studio.blender.org/projects/spring/pages/about/).

The video is an excerpt at 00:22.6–00:29.0, without audio, scaled to 1280 × 536 and re-encoded as H.264 at 30 fps. Forward and reverse playback form a 12.8-second loop. The poster and four JPEG stills were extracted from that edited excerpt. No title sequence or project logos are included.

Exact provenance, modifications and hashes:

- [Video and poster](app/src/main/assets/video-wallpaper-source.json)
- [Four stills](wallpaper-sources.json), also included in the APK
- [Bundled attribution and licenses](app/src/main/assets/OPEN_SOURCE_LICENSES.txt)

The previous privately used illustration pack had no verified redistribution permission. Those images are excluded from the public source snapshot, Git history and APK. The application's MIT license does not grant rights to third-party media.

## Personal video

For a video you have permission to use, copy `wallpaper.mp4` and a matching `wallpaper.jpg` into the app-specific `Android/data/com.sogud.bbtv/files/wallpapers/` directory with ADB. There is no in-app importer yet:

```sh
adb -s TV_IP:5555 shell mkdir -p /sdcard/Android/data/com.sogud.bbtv/files/wallpapers
adb -s TV_IP:5555 push YOUR_VIDEO.mp4 /sdcard/Android/data/com.sogud.bbtv/files/wallpapers/wallpaper.mp4
adb -s TV_IP:5555 push YOUR_POSTER.jpg /sdcard/Android/data/com.sogud.bbtv/files/wallpapers/wallpaper.jpg
```

Select **云海山谷**; it becomes **本地动态壁纸** when a local video exists. Relaunch after replacing files. Removing these two files restores the bundled video. File access through ADB depends on the TV firmware.

Home and the screen saver share the video component. Playback is muted; the player is released when the screen is no longer visible. The poster remains if decoding fails. Video frame rate and interaction frame times are separate measurements.
