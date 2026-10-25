package com.sogud.bananatv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;

/** Generated images only; never imports fixture photos onto the owner's TV. */
final class WallpaperQualityProbe {
    static Bundle run(Context context) {
        Bundle result = new Bundle();
        MediaLibrary media = new MediaLibrary(context);
        File upload = new File(context.getCacheDir(), "wallpaper-quality-upload.jpg");
        String id = null;
        try {
            if (!Build.MODEL.startsWith("sdk_") && !Build.HARDWARE.equals("ranchu"))
                throw new IllegalStateException("Disposable emulator required");
            Bitmap image = Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888);
            image.eraseColor(0xFF4287B2);
            try (FileOutputStream output = new FileOutputStream(upload)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    throw new AssertionError("Cannot create UHD fixture");
            } finally { image.recycle(); }
            id = media.addPhoto(upload);
            Bitmap full = media.picture(id, 3840, 2160);
            boolean preserved = full.getWidth() == 3840 && full.getHeight() == 2160;
            full.recycle();
            if (!preserved) throw new AssertionError("Upload reduced a UHD wallpaper");
            result.putBoolean("uhd_upload_preserved", true);
            Bitmap preview = media.picture(id, 384, 216);
            boolean bounded = preview.getWidth() <= 768 && preview.getHeight() <= 432;
            preview.recycle();
            if (!bounded) throw new AssertionError("Gallery decoded a full UHD image");
            result.putBoolean("thumbnail_decode_bounded", true);
            try (FileOutputStream output = new FileOutputStream(upload)) { output.write(new byte[]{1, 2, 3}); }
            boolean rejected = false;
            try { media.addPhoto(upload); } catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Invalid image accepted");
            result.putBoolean("invalid_upload_rejected", true);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(context.getResources(), R.drawable.anime_healer, bounds);
            if (bounds.outWidth != 3840 || bounds.outHeight != 2160)
                throw new AssertionError("Bundled illustration is not UHD");
            result.putBoolean("bundled_illustration_uhd", true);
            result.putString("result", "PASS");
        } catch (Exception | AssertionError error) {
            result.putString("result", "FAIL");
            result.putString("error", error.toString());
        } finally {
            if (id != null) media.delete(id);
            upload.delete();
        }
        return result;
    }
}
