package io.github.campione01.mineclientbridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;

/**
 * [Arcane Academy fork] Cheaper frames for agents: an optional crop (in GUI-scaled coordinates,
 * like screen widgets), an optional downscale, and a change summary against the previous frame.
 *
 * <p>The change summary compares a small grey thumbnail of the whole frame with the one from the
 * previous capture, so "did anything change" and "where" are numbers instead of a second image.
 * It always covers the whole frame, whatever the crop. The first capture has nothing to compare
 * with and reports {@code changed_fraction} -1.</p>
 *
 * <p>Must run on the render thread, where the frame is captured.</p>
 */
final class FrameShaper {
    static final int THUMB_WIDTH = 64;
    static final int THUMB_HEIGHT = 36;
    /** A thumbnail cell counts as changed when its grey level moves by more than this (0-255). */
    static final int CHANGE_THRESHOLD = 12;

    private static int[] previousThumb;

    record Options(double scale, int x, int y, int width, int height, boolean hasRegion) {
        static Options full() {
            return new Options(1.0, 0, 0, 0, 0, false);
        }
    }

    record Shaped(byte[] png, JsonObject meta) {
    }

    private FrameShaper() {
    }

    static Shaped shape(NativeImage frame, Options options, double guiScale) throws java.io.IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("source_width", frame.getWidth());
        meta.addProperty("source_height", frame.getHeight());
        meta.addProperty("gui_scale", guiScale);
        changeSummary(frame, guiScale, meta);

        int x = 0;
        int y = 0;
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (options.hasRegion()) {
            x = clamp((int) Math.floor(options.x() * guiScale), 0, frame.getWidth() - 1);
            y = clamp((int) Math.floor(options.y() * guiScale), 0, frame.getHeight() - 1);
            width = clamp((int) Math.ceil(options.width() * guiScale), 1, frame.getWidth() - x);
            height = clamp((int) Math.ceil(options.height() * guiScale), 1, frame.getHeight() - y);
        }
        int outWidth = Math.max(1, (int) Math.round(width * options.scale()));
        int outHeight = Math.max(1, (int) Math.round(height * options.scale()));
        meta.addProperty("width", outWidth);
        meta.addProperty("height", outHeight);
        if (!options.hasRegion() && options.scale() == 1.0) {
            return new Shaped(frame.asByteArray(), meta);
        }
        try (NativeImage out = new NativeImage(outWidth, outHeight, false)) {
            frame.resizeSubRectTo(x, y, width, height, out);
            return new Shaped(out.asByteArray(), meta);
        }
    }

    private static void changeSummary(NativeImage frame, double guiScale, JsonObject meta) {
        int[] thumb = thumbnail(frame);
        int[] previous = previousThumb;
        previousThumb = thumb;
        if (previous == null) {
            meta.addProperty("changed_fraction", -1);
            return;
        }
        int changed = 0;
        int minX = THUMB_WIDTH;
        int minY = THUMB_HEIGHT;
        int maxX = -1;
        int maxY = -1;
        for (int ty = 0; ty < THUMB_HEIGHT; ty++) {
            for (int tx = 0; tx < THUMB_WIDTH; tx++) {
                int i = ty * THUMB_WIDTH + tx;
                if (Math.abs(thumb[i] - previous[i]) > CHANGE_THRESHOLD) {
                    changed++;
                    minX = Math.min(minX, tx);
                    minY = Math.min(minY, ty);
                    maxX = Math.max(maxX, tx);
                    maxY = Math.max(maxY, ty);
                }
            }
        }
        meta.addProperty("changed_fraction", Math.round(changed * 10000.0 / (THUMB_WIDTH * THUMB_HEIGHT)) / 10000.0);
        if (changed > 0) {
            // The changed area, converted back to GUI-scaled coordinates so it can be passed as a region.
            double cellWidth = frame.getWidth() / (double) THUMB_WIDTH / guiScale;
            double cellHeight = frame.getHeight() / (double) THUMB_HEIGHT / guiScale;
            JsonObject box = new JsonObject();
            box.addProperty("x", (int) Math.floor(minX * cellWidth));
            box.addProperty("y", (int) Math.floor(minY * cellHeight));
            box.addProperty("width", (int) Math.ceil((maxX - minX + 1) * cellWidth));
            box.addProperty("height", (int) Math.ceil((maxY - minY + 1) * cellHeight));
            meta.add("changed_region", box);
        }
    }

    /** Average grey level of each thumbnail cell, sampled on a 4x4 grid inside the cell. */
    static int[] thumbnail(NativeImage frame) {
        int[] thumb = new int[THUMB_WIDTH * THUMB_HEIGHT];
        for (int ty = 0; ty < THUMB_HEIGHT; ty++) {
            for (int tx = 0; tx < THUMB_WIDTH; tx++) {
                int sum = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        int px = (int) ((tx + (sx + 0.5) / 4.0) * frame.getWidth() / THUMB_WIDTH);
                        int py = (int) ((ty + (sy + 0.5) / 4.0) * frame.getHeight() / THUMB_HEIGHT);
                        sum += grey(frame.getPixelRGBA(clamp(px, 0, frame.getWidth() - 1), clamp(py, 0, frame.getHeight() - 1)));
                    }
                }
                thumb[ty * THUMB_WIDTH + tx] = sum / 16;
            }
        }
        return thumb;
    }

    /** NativeImage pixels are ABGR in an int; the weights are the usual luma ones. */
    static int grey(int abgr) {
        int r = abgr & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
