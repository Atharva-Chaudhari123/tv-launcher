package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final ImageView blurView;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private Bitmap currentBlur;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        blurView = new ImageView(context);
        blurView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blurView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        blurView.setAlpha(0f);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(blurView, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        if (first == null) {
            for (File file : files) {
                if ("morning.jpg".equalsIgnoreCase(file.getName())) {
                    first = file;
                    break;
                }
            }
        }
        while (first != null) {
            WallpaperDecoded decoded = decode(first);
            if (decoded != null && decoded.sharp != null) {
                show(decoded.sharp, decoded.blur);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1], moods.containsKey(WallpaperMood.AFTERNOON)));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
        if (current != null) {
            current.recycle();
            current = null;
        }
        if (currentBlur != null) {
            currentBlur.recycle();
            currentBlur = null;
        }
    }

    void setBlurProgress(float progress) {
        if (blurView == null) return;
        float clamped = Math.max(0f, Math.min(1f, progress));
        blurView.setAlpha(clamped);
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            WallpaperDecoded result = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (result != null) {
                        if (result.sharp != null) result.sharp.recycle();
                        if (result.blur != null) result.blur.recycle();
                    }
                    return;
                }
                if (result == null || result.sharp == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(result.sharp, result.blur);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap sharp, Bitmap blur) {
        Bitmap old = current;
        Bitmap oldBlur = currentBlur;
        current = sharp;
        currentBlur = blur;
        image.setImageBitmap(sharp);
        if (blurView != null) blurView.setImageBitmap(blur);
        if (old != null && old != sharp) image.postDelayed(old::recycle, 500);
        if (oldBlur != null && oldBlur != blur) image.postDelayed(oldBlur::recycle, 500);
    }

    private static int[] fastBoxBlur(int[] src, int w, int h, int radius, boolean horizontal) {
        int[] dst = new int[src.length];
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            int lineOffset = horizontal ? line * w : line;
            int step = horizontal ? 1 : w;

            int r = 0, g = 0, b = 0;
            int firstPixel = src[lineOffset];
            r = android.graphics.Color.red(firstPixel) * (radius + 1);
            g = android.graphics.Color.green(firstPixel) * (radius + 1);
            b = android.graphics.Color.blue(firstPixel) * (radius + 1);
            for (int k = 1; k <= radius; k++) {
                int idx = lineOffset + Math.min(k, length - 1) * step;
                int p = src[idx];
                r += android.graphics.Color.red(p);
                g += android.graphics.Color.green(p);
                b += android.graphics.Color.blue(p);
            }

            for (int i = 0; i < length; i++) {
                dst[lineOffset + i * step] = android.graphics.Color.rgb(r / span, g / span, b / span);
                int removeIdx = lineOffset + Math.max(0, i - radius) * step;
                int addIdx = lineOffset + Math.min(length - 1, i + radius + 1) * step;
                int pRemove = src[removeIdx];
                int pAdd = src[addIdx];
                r += android.graphics.Color.red(pAdd) - android.graphics.Color.red(pRemove);
                g += android.graphics.Color.green(pAdd) - android.graphics.Color.green(pRemove);
                b += android.graphics.Color.blue(pAdd) - android.graphics.Color.blue(pRemove);
            }
        }
        return dst;
    }

    private static Bitmap createBlurredBitmap(Bitmap src) {
        try {
            int bw = 384, bh = 216;
            Bitmap small = Bitmap.createScaledBitmap(src, bw, bh, true);
            int[] px = new int[bw * bh];
            small.getPixels(px, 0, bw, 0, 0, bw, bh);
            for (int pass = 0; pass < 3; pass++) {
                px = fastBoxBlur(px, bw, bh, 7, true);
                px = fastBoxBlur(px, bw, bh, 7, false);
            }
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int r = (android.graphics.Color.red(p) * 205) / 255;
                int g = (android.graphics.Color.green(p) * 205) / 255;
                int b = (android.graphics.Color.blue(p) * 205) / 255;
                px[i] = android.graphics.Color.rgb(r, g, b);
            }
            Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
            if (out != small) small.recycle();
            out.setPixels(px, 0, bw, 0, 0, bw, bh);
            Bitmap gpu = out.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null) {
                out.recycle();
                return gpu;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static class WallpaperDecoded {
        final Bitmap sharp;
        final Bitmap blur;
        WallpaperDecoded(Bitmap sharp, Bitmap blur) {
            this.sharp = sharp;
            this.blur = blur;
        }
    }

    private WallpaperDecoded decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            Bitmap blur = createBlurredBitmap(decoded);
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            Bitmap sharp = gpu != null ? gpu : decoded;
            if (gpu != null && gpu != decoded) decoded.recycle();
            return new WallpaperDecoded(sharp, blur);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
