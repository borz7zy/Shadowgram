/*
 * Adapted from Telegram for Android (DrKLO/Telegram), GPL v2+.
 * Original: org.telegram.messenger.Emoji.java
 * Stripped: animated/restricted/compound/recent-history paths, telegram-specific deps.
 * Copied into Shadowgram from Shadowgram (borz7zy), context taken from init().
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.github.borz7zy.shadowgram.emoji;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.util.SparseIntArray;

import com.github.borz7zy.shadowgram.background.AsyncTask;


import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class Emoji {

    private static final String TAG = "Emoji";

    /** Set once by {@link #init(Context)}, before any other call. */
    private static volatile Context appContext;

    public static void init(Context context) {
        Context application = context.getApplicationContext();
        float density = application.getResources().getDisplayMetrics().density;
        drawImgSize = (int) Math.ceil(20 * density);
        bigImgSize = (int) Math.ceil(34 * density);
        appContext = application;
    }

    private static final java.util.HashMap<CharSequence, DrawableInfo> rects = new java.util.HashMap<>();
    public static int drawImgSize;
    public static int bigImgSize;
    public static Paint placeholderPaint;
    private static final int[] emojiCounts = new int[]{
            EmojiData.data[0].length, EmojiData.data[1].length, EmojiData.data[2].length, EmojiData.data[3].length,
            EmojiData.data[4].length, EmojiData.data[5].length, EmojiData.data[6].length, EmojiData.data[7].length
    };
    private static final int PAGE_COUNT = 8;
    private static final int NOT_LOADING = 0;
    private static final int LOADING = 1;
    private static final long FAILED_RETRY_MS = 5_000L;
    private static final long BUSY_RETRY_MS = 100L;

    private static final ArrayList<AtomicReferenceArray<Bitmap>> emojiBmp = new ArrayList<>(PAGE_COUNT);
    private static final AtomicIntegerArray[] loadingEmoji = new AtomicIntegerArray[PAGE_COUNT];
    private static final AtomicLongArray[] retryAfter = new AtomicLongArray[PAGE_COUNT];

    public static float emojiDrawingYOffset;
    public static boolean emojiDrawingUseAlpha = true;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean invalidationPosted = new AtomicBoolean();
    private static final AtomicBoolean busyRetryPosted = new AtomicBoolean();
    private static final CopyOnWriteArrayList<WeakReference<Runnable>> invalidateCallbacks = new CopyOnWriteArrayList<>();

    private static final Runnable invalidateAllRunnable = () -> {
        invalidationPosted.set(false);
        for (WeakReference<Runnable> ref : invalidateCallbacks) {
            Runnable callback = ref.get();
            if (callback == null) {
                invalidateCallbacks.remove(ref);
            } else {
                try {
                    callback.run();
                } catch (RuntimeException error) {
                    android.util.Log.e(TAG, "Emoji invalidator failed", error);
                }
            }
        }
    };

    // Coalesce completed loads without postponing a pending redraw on each completion.
    private static void requestInvalidation() {
        if (invalidationPosted.compareAndSet(false, true)) {
            if (!MAIN.postDelayed(invalidateAllRunnable, 16L)) {
                invalidationPosted.set(false);
            }
        }
    }

    // The shared media pool has a bounded queue. Never fall back to decoding on UI.
    // Ask visible consumers to draw again after a short, globally coalesced cooldown.
    private static void retryWhenMediaPoolBusy() {
        if (busyRetryPosted.compareAndSet(false, true)) {
            if (!MAIN.postDelayed(() -> {
                busyRetryPosted.set(false);
                requestInvalidation();
            }, BUSY_RETRY_MS)) {
                busyRetryPosted.set(false);
            }
        }
    }

    public static void registerInvalidator(Runnable r) {
        invalidateCallbacks.add(new WeakReference<>(r));
    }

    public static void unregisterInvalidator(Runnable r) {
        for (WeakReference<Runnable> ref : invalidateCallbacks) {
            Runnable existing = ref.get();
            if (existing == null || existing == r) {
                invalidateCallbacks.remove(ref);
            }
        }
    }

    private static int dp(float v) {
        Context ctx = appContext;
        Resources res = ctx != null ? ctx.getResources() : Resources.getSystem();
        return (int) Math.ceil(v * res.getDisplayMetrics().density);
    }

    static {
        for (int a = 0; a < PAGE_COUNT; a++) {
            emojiBmp.add(new AtomicReferenceArray<>(emojiCounts[a]));
            loadingEmoji[a] = new AtomicIntegerArray(emojiCounts[a]);
            retryAfter[a] = new AtomicLongArray(emojiCounts[a]);
        }
        for (int j = 0; j < EmojiData.data.length; j++) {
            for (int i = 0; i < EmojiData.data[j].length; i++) {
                rects.put(EmojiData.data[j][i], new DrawableInfo((byte) j, (short) i, i));
            }
        }
        placeholderPaint = new Paint();
        placeholderPaint.setColor(0x00000000);
    }

    public static void preloadEmoji(CharSequence code) {
        DrawableInfo info = getDrawableInfo(code);
        if (info != null) loadEmoji(info.page, info.page2);
    }

    private static final Object ALPHA_MASKS_LOCK = new Object();
    private static volatile SparseIntArray emojiAlphaMasks;
    private static volatile boolean alphaMasksLoadFailed;

    private static SparseIntArray loadEmojiAlphaMasks() {
        if (alphaMasksLoadFailed) return null;
        Context ctx = appContext;
        if (ctx == null) return null;
        try (InputStream is = ctx.getAssets().open("emoji/metadata.bin")) {
            ArrayList<byte[]> chunks = new ArrayList<>();
            int total = 0;
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                byte[] copy = new byte[read];
                System.arraycopy(buf, 0, copy, 0, read);
                chunks.add(copy);
                total += read;
            }
            byte[] all = new byte[total];
            int pos = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, all, pos, c.length);
                pos += c.length;
            }
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            int pairs = total / 4;
            SparseIntArray map = new SparseIntArray(pairs);
            for (int i = 0; i < pairs; i++) {
                int emojiIndex = bb.getShort() & 0xFFFF;
                int maskId = bb.getShort() & 0xFFFF;
                map.put(emojiIndex, maskId);
            }
            return map;
        } catch (Exception e) {
            android.util.Log.e(TAG, "loadEmojiAlphaMasks failed", e);
            alphaMasksLoadFailed = true;
            return null;
        }
    }

    /** The metadata is built once and is read-only after safe publication. */
    private static SparseIntArray getEmojiAlphaMasks() {
        SparseIntArray masks = emojiAlphaMasks;
        if (masks != null || alphaMasksLoadFailed) return masks;
        synchronized (ALPHA_MASKS_LOCK) {
            if (emojiAlphaMasks == null && !alphaMasksLoadFailed) {
                emojiAlphaMasks = loadEmojiAlphaMasks();
            }
            return emojiAlphaMasks;
        }
    }

    private static Bitmap cachedBitmap(byte page, short page2) {
        return emojiBmp.get(page).get(page2);
    }

    private static void loadEmoji(final byte page, final short page2) {
        if (appContext == null || cachedBitmap(page, page2) != null || busyRetryPosted.get()) return;
        if (SystemClock.elapsedRealtime() < retryAfter[page].get(page2)) return;
        if (!loadingEmoji[page].compareAndSet(page2, NOT_LOADING, LOADING)) return;

        // Another worker may have published the bitmap between the cache read and CAS.
        if (cachedBitmap(page, page2) != null || SystemClock.elapsedRealtime() < retryAfter[page].get(page2)) {
            loadingEmoji[page].set(page2, NOT_LOADING);
            return;
        }
        try {
            AsyncTask.MEDIA_EXECUTOR.execute(() -> decodeEmoji(page, page2));
        } catch (RejectedExecutionException rejected) {
            retryWhenMediaPoolBusy();
            loadingEmoji[page].set(page2, NOT_LOADING);
        } catch (RuntimeException error) {
            loadingEmoji[page].set(page2, NOT_LOADING);
            android.util.Log.e(TAG, "Cannot schedule emoji load", error);
        }
    }

    private static void decodeEmoji(byte page, short page2) {
        Bitmap bitmap = null;
        boolean published = false;
        int oldPriority = Process.THREAD_PRIORITY_DEFAULT;
        boolean restorePriority = false;
        try {
            // Raw executor calls bypass AsyncTask.runWorker's priority handling.
            try {
                oldPriority = Process.getThreadPriority(Process.myTid());
                if (oldPriority != Process.THREAD_PRIORITY_DEFAULT) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    restorePriority = true;
                }
            } catch (RuntimeException ignored) {
                // Continue with the worker's current priority.
            }
            bitmap = loadBitmap("emoji/" + page + "_" + page2 + ".png");
            if (bitmap == null) return;
            SparseIntArray masks = getEmojiAlphaMasks();
            int maskIndex = masks == null ? -1 : masks.get(page * 4096 + page2, -1);
            if (maskIndex != -1) {
                bitmap = applyAlphaMask(bitmap, maskIndex);
            }
            emojiBmp.get(page).set(page2, bitmap);
            published = true;
            retryAfter[page].set(page2, 0L);
            requestInvalidation();
        } catch (RuntimeException | OutOfMemoryError error) {
            android.util.Log.e(TAG, "Emoji decode failed: " + page + "_" + page2, error);
        } finally {
            try {
                if (!published) {
                    retryAfter[page].set(page2, SystemClock.elapsedRealtime() + FAILED_RETRY_MS);
                    if (bitmap != null) bitmap.recycle();
                }
            } finally {
                loadingEmoji[page].set(page2, NOT_LOADING);
                if (restorePriority) {
                    try { Process.setThreadPriority(oldPriority); }
                    catch (RuntimeException ignored) { }
                }
            }
        }
    }

    /** Takes ownership of source only after successfully constructing a replacement. */
    private static Bitmap applyAlphaMask(Bitmap source, int maskIndex) {
        Bitmap mask = null;
        try {
            mask = loadBitmap("emoji/masks/" + maskIndex + ".png");
            if (mask == null) return source;
            int w = source.getWidth();
            int h = source.getHeight();
            if (w != mask.getWidth() || h != mask.getHeight()) {
                android.util.Log.w(TAG, "Emoji alpha mask dimensions differ: " + maskIndex);
                return source;
            }
            int[] rgbPixels = new int[w * h];
            int[] alphaPixels = new int[w * h];
            source.getPixels(rgbPixels, 0, w, 0, 0, w, h);
            mask.getPixels(alphaPixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < rgbPixels.length; i++) {
                rgbPixels[i] = (rgbPixels[i] & 0x00FFFFFF) | ((alphaPixels[i] & 0xFF) << 24);
            }
            Bitmap result = Bitmap.createBitmap(rgbPixels, w, h, Bitmap.Config.ARGB_8888);
            source.recycle();
            return result;
        } catch (RuntimeException | OutOfMemoryError error) {
            android.util.Log.e(TAG, "load mask failed", error);
            return source;
        } finally {
            if (mask != null) mask.recycle();
        }
    }

    public static Bitmap loadBitmap(String path) {
        try {
            Context ctx = appContext;
            if (ctx == null) return null;
            int imageResize = (ctx.getResources().getDisplayMetrics().density <= 1.0f) ? 2 : 1;
            try (InputStream is = ctx.getAssets().open(path)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = imageResize;
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception | OutOfMemoryError x) {
            android.util.Log.e(TAG, "Error loading emoji " + path, x);
            return null;
        }
    }

    public static String fixEmoji(String emoji) {
        char ch;
        int length = emoji.length();
        for (int a = 0; a < length; a++) {
            ch = emoji.charAt(a);
            if (ch >= 0xD83C && ch <= 0xD83E) {
                if (ch == 0xD83C && a < length - 1) {
                    ch = emoji.charAt(a + 1);
                    if (ch == 0xDE2F || ch == 0xDC04 || ch == 0xDE1A || ch == 0xDD7F) {
                        emoji = emoji.substring(0, a + 2) + "️" + emoji.substring(a + 2);
                        length++;
                        a += 2;
                    } else {
                        a++;
                    }
                } else {
                    a++;
                }
            } else if (ch == 0x20E3) {
                return emoji;
            } else if (ch >= 0x203C && ch <= 0x3299) {
                if (EmojiData.emojiToFE0FMap.containsKey(ch)) {
                    emoji = emoji.substring(0, a + 1) + "️" + emoji.substring(a + 1);
                    length++;
                    a++;
                }
            }
        }
        return emoji;
    }

    public static EmojiDrawable getEmojiDrawable(CharSequence code) {
        DrawableInfo info = getDrawableInfo(code);
        if (info == null) return null;
        EmojiDrawable ed = new SimpleEmojiDrawable(info, endsWithRightArrow(code));
        ed.setBounds(0, 0, drawImgSize, drawImgSize);
        return ed;
    }

    public static boolean endsWithRightArrow(CharSequence code) {
        return code != null && code.length() > 2
                && code.charAt(code.length() - 2) == '‍'
                && code.charAt(code.length() - 1) == '➡';
    }

    private static DrawableInfo getDrawableInfo(CharSequence code) {
        if (endsWithRightArrow(code)) code = code.subSequence(0, code.length() - 2);
        DrawableInfo info = rects.get(code);
        if (info == null) {
            CharSequence newCode = EmojiData.emojiAliasMap.get(code);
            if (newCode != null) info = rects.get(newCode);
        }
        return info;
    }

    public static boolean isValidEmoji(CharSequence code) {
        if (TextUtils.isEmpty(code)) return false;
        DrawableInfo info = rects.get(code);
        if (info == null) {
            CharSequence newCode = EmojiData.emojiAliasMap.get(code);
            if (newCode != null) info = rects.get(newCode);
        }
        return info != null;
    }

    public static Drawable getEmojiBigDrawable(String code) {
        EmojiDrawable ed = getEmojiDrawable(code);
        if (ed == null) {
            CharSequence newCode = EmojiData.emojiAliasMap.get(code);
            if (newCode != null) ed = getEmojiDrawable(newCode);
        }
        if (ed == null) return null;
        ed.setBounds(0, 0, bigImgSize, bigImgSize);
        ed.fullSize = true;
        return ed;
    }

    public static abstract class EmojiDrawable extends Drawable {
        public boolean fullSize = false;
        int placeholderColor = 0x10000000;

        public boolean isLoaded() { return false; }
        public void preload() {}
    }

    public static class SimpleEmojiDrawable extends EmojiDrawable {
        private final DrawableInfo info;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect rect = new Rect();
        private final boolean invert;

        public SimpleEmojiDrawable(DrawableInfo i, boolean invert) {
            this.info = i;
            this.invert = invert;
        }

        public Rect getDrawRect() {
            Rect original = getBounds();
            int cX = original.centerX(), cY = original.centerY();
            int s = (fullSize ? bigImgSize : drawImgSize);
            rect.left = cX - s / 2;
            rect.right = cX + s / 2;
            rect.top = cY - s / 2;
            rect.bottom = cY + s / 2;
            return rect;
        }

        @Override
        public void draw(Canvas canvas) {
            Bitmap bitmap = cachedBitmap(info.page, info.page2);
            if (bitmap == null) {
                loadEmoji(info.page, info.page2);
                placeholderPaint.setColor(placeholderColor);
                Rect bounds = getBounds();
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), bounds.width() * .4f, placeholderPaint);
                return;
            }
            Rect b = fullSize ? getDrawRect() : getBounds();
            if (!canvas.quickReject(b.left, b.top, b.right, b.bottom, Canvas.EdgeType.AA)) {
                if (invert) {
                    canvas.save();
                    canvas.scale(-1, 1, b.centerX(), b.centerY());
                }
                canvas.drawBitmap(bitmap, null, b, paint);
                if (invert) canvas.restore();
            }
        }

        @Override public int getOpacity() { return PixelFormat.TRANSPARENT; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) {}

        @Override
        public boolean isLoaded() { return cachedBitmap(info.page, info.page2) != null; }

        @Override
        public void preload() { if (!isLoaded()) loadEmoji(info.page, info.page2); }
    }

    private static class DrawableInfo {
        public byte page;
        public short page2;
        public int emojiIndex;

        DrawableInfo(byte p, short p2, int idx) { page = p; page2 = p2; emojiIndex = idx; }
    }

    public static class EmojiSpanRange {
        public int start;
        public int end;
        public CharSequence code;

        public EmojiSpanRange(int start, int end, CharSequence code) {
            this.start = start;
            this.end = end;
            this.code = code;
        }
    }

    public static boolean fullyConsistsOfEmojis(CharSequence cs) {
        int[] only = new int[1];
        parseEmojis(cs, only);
        return only[0] > 0;
    }

    public static int countEmojisOnly(CharSequence cs) {
        int[] only = new int[1];
        parseEmojis(cs, only);
        return only[0];
    }

    public static ArrayList<EmojiSpanRange> parseEmojis(CharSequence cs) {
        return parseEmojis(cs, null);
    }

    public static ArrayList<EmojiSpanRange> parseEmojis(CharSequence cs, int[] emojiOnly) {
        ArrayList<EmojiSpanRange> emojis = new ArrayList<>();
        if (cs == null || cs.length() <= 0) return emojis;
        long buf = 0;
        char c;
        int startIndex = -1;
        int startLength = 0;
        int previousGoodIndex = 0;
        StringBuilder emojiCode = new StringBuilder(16);
        final int length = cs.length();
        boolean doneEmoji = false;
        boolean notOnlyEmoji;
        boolean resetStartIndex = false;
        try {
            for (int i = 0; i < length; i++) {
                c = cs.charAt(i);
                notOnlyEmoji = false;
                if (c >= 0xD83C && c <= 0xD83E || (buf != 0 && (buf & 0xFFFFFFFF00000000L) == 0 && (buf & 0xFFFF) == 0xD83C && (c >= 0xDDE6 && c <= 0xDDFF))) {
                    if (startIndex == -1) startIndex = i;
                    else if (resetStartIndex) {
                        startIndex = i;
                        startLength = 0;
                        resetStartIndex = false;
                    }
                    emojiCode.append(c);
                    startLength++;
                    buf <<= 16;
                    buf |= c;
                } else if (emojiCode.length() > 0 && (c == 0x2640 || c == 0x2642 || c == 0x2695) || buf > 0 && (c & 0xF000) == 0xD000) {
                    emojiCode.append(c);
                    startLength++;
                    buf = 0;
                    doneEmoji = true;
                } else if (c == 0x20E3) {
                    if (i > 0) {
                        char c2 = cs.charAt(previousGoodIndex);
                        if ((c2 >= '0' && c2 <= '9') || c2 == '#' || c2 == '*') {
                            startIndex = previousGoodIndex;
                            startLength = i - previousGoodIndex + 1;
                            emojiCode.append(c2);
                            emojiCode.append(c);
                            doneEmoji = true;
                            resetStartIndex = false;
                        }
                    }
                } else if ((c == 0x00A9 || c == 0x00AE || c >= 0x203C && c <= 0x3299) && EmojiData.dataCharsMap.containsKey(c)) {
                    if (startIndex == -1) startIndex = i;
                    else if (resetStartIndex) {
                        startIndex = i;
                        startLength = 0;
                        resetStartIndex = false;
                    }
                    startLength++;
                    emojiCode.append(c);
                    doneEmoji = true;
                } else if (startIndex != -1) {
                    emojiCode.setLength(0);
                    startIndex = -1;
                    startLength = 0;
                    doneEmoji = false;
                    resetStartIndex = false;
                } else if (c != 0xfe0f && c != '\n' && c != ' ' && c != '\t') {
                    notOnlyEmoji = true;
                }
                if (doneEmoji && i + 2 < length) {
                    char next = cs.charAt(i + 1);
                    if (next == 0xD83C) {
                        next = cs.charAt(i + 2);
                        if (next >= 0xDFFB && next <= 0xDFFF) {
                            emojiCode.append(cs.subSequence(i + 1, i + 3));
                            startLength += 2;
                            i += 2;
                        }
                    } else if (emojiCode.length() >= 2 && emojiCode.charAt(0) == 0xD83C && emojiCode.charAt(1) == 0xDFF4 && next == 0xDB40) {
                        i++;
                        while (true) {
                            if (i < cs.length()) emojiCode.append(cs.charAt(i));
                            if (i + 1 < cs.length()) emojiCode.append(cs.charAt(i + 1));
                            startLength += 2;
                            i += 2;
                            if (i >= cs.length() || cs.charAt(i) != 0xDB40) {
                                i--;
                                break;
                            }
                        }
                    }
                }
                previousGoodIndex = i;
                char prevCh = c;
                for (int a = 0; a < 3; a++) {
                    if (i + 1 < length) {
                        c = cs.charAt(i + 1);
                        if (a == 1) {
                            if (c == 0x200D && emojiCode.length() > 0) {
                                notOnlyEmoji = false;
                                emojiCode.append(c);
                                i++;
                                startLength++;
                                doneEmoji = false;
                            }
                        } else if (prevCh == '*' || prevCh == '#' || prevCh >= '0' && prevCh <= '9') {
                            if (c >= 0xFE00 && c <= 0xFE0F) {
                                startIndex = previousGoodIndex;
                                resetStartIndex = true;
                                i++;
                                startLength++;
                                if (!doneEmoji) doneEmoji = i + 1 >= length;
                            }
                        } else if (startIndex != -1) {
                            if (c >= 0xFE00 && c <= 0xFE0F) {
                                i++;
                                startLength++;
                                if (!doneEmoji) doneEmoji = i + 1 >= length;
                            }
                        }
                    }
                }
                if (notOnlyEmoji && emojiOnly != null) {
                    emojiOnly[0] = 0;
                    emojiOnly = null;
                }
                if (doneEmoji && i + 2 < length && cs.charAt(i + 1) == 0xD83C) {
                    char next = cs.charAt(i + 2);
                    if (next >= 0xDFFB && next <= 0xDFFF) {
                        emojiCode.append(cs.subSequence(i + 1, i + 3));
                        startLength += 2;
                        i += 2;
                    }
                }
                if (doneEmoji) {
                    if (emojiOnly != null) emojiOnly[0]++;
                    if (startIndex >= 0 && startIndex + startLength <= length) {
                        emojis.add(new EmojiSpanRange(startIndex, startIndex + startLength, emojiCode.subSequence(0, emojiCode.length())));
                    }
                    startLength = 0;
                    startIndex = -1;
                    emojiCode.setLength(0);
                    doneEmoji = false;
                    resetStartIndex = false;
                }
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "parseEmojis", e);
        }
        if (emojiOnly != null && emojiCode.length() != 0) emojiOnly[0] = 0;
        return emojis;
    }

    public static CharSequence replaceEmoji(CharSequence cs, Paint.FontMetricsInt fontMetrics, boolean createNew) {
        return replaceEmoji(cs, fontMetrics, createNew, null);
    }

    public static CharSequence replaceEmoji(CharSequence cs, Paint.FontMetricsInt fontMetrics, boolean createNew, int[] emojiOnly) {
        return replaceEmoji(cs, fontMetrics, createNew, emojiOnly, DynamicDrawableSpan.ALIGN_BOTTOM, 1.0f);
    }

    public static CharSequence replaceEmoji(CharSequence cs, Paint.FontMetricsInt fontMetrics, boolean createNew, int[] emojiOnly, int alignment, float scale) {
        if (cs == null || cs.length() == 0) return cs;
        Spannable s;
        if (!createNew && cs instanceof Spannable) s = (Spannable) cs;
        else s = Spannable.Factory.getInstance().newSpannable(cs.toString());
        ArrayList<EmojiSpanRange> emojis = parseEmojis(s, emojiOnly);
        if (emojis.isEmpty()) return cs;
        EmojiSpan span;
        Drawable drawable;
        int limitCount = 4096;
        for (int i = 0; i < emojis.size(); ++i) {
            try {
                EmojiSpanRange emojiRange = emojis.get(i);
                drawable = getEmojiDrawable(emojiRange.code);
                if (drawable != null) {
                    span = new EmojiSpan(drawable, alignment, fontMetrics);
                    span.emoji = emojiRange.code == null ? null : emojiRange.code.toString();
                    span.scale = scale;
                    s.setSpan(span, emojiRange.start, emojiRange.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "replaceEmoji setSpan", e);
            }
            if ((i + 1) >= limitCount) break;
        }
        return s;
    }

    public static class EmojiSpan extends ImageSpan {
        public Paint.FontMetricsInt fontMetrics;
        public float scale = 1f;
        public int size;
        public String emoji;

        public EmojiSpan(Drawable d, int verticalAlignment, Paint.FontMetricsInt original) {
            super(d, verticalAlignment);
            fontMetrics = original;
            size = computeSize(original);
        }

        private static int computeSize(Paint.FontMetricsInt fm) {
            int s = 0;
            if (fm != null) s = Math.abs(fm.descent) + Math.abs(fm.ascent);
            if (s == 0) {
                Context ctx = appContext;
                Resources res = ctx != null ? ctx.getResources() : Resources.getSystem();
                s = (int) Math.ceil(20 * res.getDisplayMetrics().density);
            }
            return s;
        }

        public void replaceFontMetrics(Paint.FontMetricsInt newMetrics, int newSize) {
            fontMetrics = newMetrics;
            size = newSize;
        }

        public void replaceFontMetrics(Paint.FontMetricsInt newMetrics) {
            fontMetrics = newMetrics;
            size = computeSize(newMetrics);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm == null) fm = new Paint.FontMetricsInt();
            int scaledSize = (int) (scale * size);
            if (fontMetrics == null) {
                int sz = super.getSize(paint, text, start, end, fm);
                Context ctx = appContext;
                Resources res = ctx != null ? ctx.getResources() : Resources.getSystem();
                int offset = (int) Math.ceil(8 * res.getDisplayMetrics().density);
                int w = (int) Math.ceil(10 * res.getDisplayMetrics().density);
                fm.top = -w - offset;
                fm.bottom = w - offset;
                fm.ascent = -w - offset;
                fm.leading = 0;
                fm.descent = w - offset;
                return sz;
            } else {
                fm.ascent = fontMetrics.ascent;
                fm.descent = fontMetrics.descent;
                fm.top = fontMetrics.top;
                fm.bottom = fontMetrics.bottom;
                if (getDrawable() != null) getDrawable().setBounds(0, 0, scaledSize, scaledSize);
                return scaledSize;
            }
        }

        public boolean drawn;
        public float lastDrawX, lastDrawY;

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            lastDrawX = x + scale * size / 2f;
            lastDrawY = top + (bottom - top) / 2f;
            drawn = true;
            boolean restoreAlpha = false;
            if (paint.getAlpha() != 255 && emojiDrawingUseAlpha) {
                restoreAlpha = true;
                getDrawable().setAlpha(paint.getAlpha());
            }
            boolean needRestore = false;
            float ty = emojiDrawingYOffset - (size - scale * size) / 2;
            if (ty != 0) {
                needRestore = true;
                canvas.save();
                canvas.translate(0, ty);
            }
            super.draw(canvas, text, start, end, x, top, y, bottom, paint);
            if (needRestore) canvas.restore();
            if (restoreAlpha) getDrawable().setAlpha(255);
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            if (getDrawable() instanceof EmojiDrawable) {
                ((EmojiDrawable) getDrawable()).placeholderColor = 0x10ffffff & ds.getColor();
            }
            super.updateDrawState(ds);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EmojiSpan that = (EmojiSpan) o;
            return Float.compare(scale, that.scale) == 0 && size == that.size && Objects.equals(emoji, that.emoji);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scale, size, emoji);
        }
    }
}
