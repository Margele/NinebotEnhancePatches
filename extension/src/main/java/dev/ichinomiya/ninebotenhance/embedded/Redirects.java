package dev.ichinomiya.ninebotenhance.embedded;

import android.content.Context;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaDescrambler;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Platform methods the module hooks under LSPosed. Their code is not in the APK, so the patch rewrites Ninebot's call sites to
 * the static methods below; each one calls the platform method directly until {@link StaticHooks#hook} fills its slot. Calls the
 * platform makes internally are not seen, which is enough here: the vehicle page's cards are inflated, the capture view is drawn
 * and the cast encoder is driven from Ninebot's own code.
 */
public final class Redirects {
    private static final int INFLATE = 0, DRAW = 1, CONFIGURE = 2, CONFIGURE_DESCRAMBLER = 3, CREATE_ENCODER = 4, CREATE_BY_NAME = 5,
            SET_CALLBACK = 6, SET_CALLBACK_HANDLER = 7, OUTPUT_FORMAT = 8, OUTPUT_FORMAT_INDEX = 9, DEQUEUE_OUTPUT = 10, START = 11,
            SET_PARAMETERS = 12, STOP = 13, RESET = 14, RELEASE = 15, COUNT = 16;
    static final Object[] slots = new Object[COUNT];
    private static final Method[] METHODS = new Method[COUNT];
    static {
        method(INFLATE, LayoutInflater.class, "inflate", int.class, ViewGroup.class, boolean.class);
        method(DRAW, View.class, "draw", Canvas.class);
        method(CONFIGURE, MediaCodec.class, "configure", MediaFormat.class, Surface.class, MediaCrypto.class, int.class);
        method(CONFIGURE_DESCRAMBLER, MediaCodec.class, "configure", MediaFormat.class, Surface.class, int.class, MediaDescrambler.class);
        method(CREATE_ENCODER, MediaCodec.class, "createEncoderByType", String.class);
        method(CREATE_BY_NAME, MediaCodec.class, "createByCodecName", String.class);
        method(SET_CALLBACK, MediaCodec.class, "setCallback", MediaCodec.Callback.class);
        method(SET_CALLBACK_HANDLER, MediaCodec.class, "setCallback", MediaCodec.Callback.class, Handler.class);
        method(OUTPUT_FORMAT, MediaCodec.class, "getOutputFormat");
        method(OUTPUT_FORMAT_INDEX, MediaCodec.class, "getOutputFormat", int.class);
        method(DEQUEUE_OUTPUT, MediaCodec.class, "dequeueOutputBuffer", MediaCodec.BufferInfo.class, long.class);
        method(START, MediaCodec.class, "start");
        method(SET_PARAMETERS, MediaCodec.class, "setParameters", Bundle.class);
        method(STOP, MediaCodec.class, "stop");
        method(RESET, MediaCodec.class, "reset");
        method(RELEASE, MediaCodec.class, "release");
    }
    private static void method(int slot, Class<?> owner, String name, Class<?>... parameters) {
        try { METHODS[slot] = owner.getDeclaredMethod(name, parameters); } catch (NoSuchMethodException | RuntimeException ignored) {}
    }
    /** The slot of a platform method that has a redirect, or -1. */
    static int slot(Executable executable) {
        for (int i = 0; i < COUNT; i++) if (executable.equals(METHODS[i])) return i;
        return -1;
    }
    private static Object call(Object slot, Object self, Object... args) {
        try { return StaticHooks.dispatch(slot, self, args); }
        catch (Throwable e) { throw StaticHooks.<RuntimeException>sneak(e); }
    }

    @Redirect(owner = "Landroid/view/LayoutInflater;", scope = {"Lcn/ninebot/", "Landroidx/databinding/", "Landroidx/asynclayoutinflater/"})
    public static View inflate(LayoutInflater inflater, int resource, ViewGroup root, boolean attachToRoot) {
        Object slot = slots[INFLATE];
        return slot == null ? inflater.inflate(resource, root, attachToRoot) : (View) call(slot, inflater, resource, root, attachToRoot);
    }
    /** The platform's two-argument form is the three-argument one with {@code root != null}. */
    @Redirect(owner = "Landroid/view/LayoutInflater;", scope = {"Lcn/ninebot/", "Landroidx/databinding/", "Landroidx/asynclayoutinflater/"})
    public static View inflate(LayoutInflater inflater, int resource, ViewGroup root) { return inflate(inflater, resource, root, root != null); }
    @Redirect(owner = "Landroid/view/View;", isStatic = true)
    public static View inflate(Context context, int resource, ViewGroup root) { return inflate(LayoutInflater.from(context), resource, root, root != null); }

    /** Only the capture code's own draw calls: that is where the module swaps in its frame. */
    @Redirect(owner = "Landroid/view/View;", scope = {"Lcn/ninebot/capture/"})
    public static void draw(View view, Canvas canvas) {
        Object slot = slots[DRAW];
        if (slot == null) view.draw(canvas); else call(slot, view, canvas);
    }

    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void configure(MediaCodec codec, MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
        Object slot = slots[CONFIGURE];
        if (slot == null) codec.configure(format, surface, crypto, flags); else call(slot, codec, format, surface, crypto, flags);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void configure(MediaCodec codec, MediaFormat format, Surface surface, int flags, MediaDescrambler descrambler) {
        Object slot = slots[CONFIGURE_DESCRAMBLER];
        if (slot == null) codec.configure(format, surface, flags, descrambler); else call(slot, codec, format, surface, flags, descrambler);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;", isStatic = true)
    public static MediaCodec createEncoderByType(String type) throws IOException {
        Object slot = slots[CREATE_ENCODER];
        return slot == null ? MediaCodec.createEncoderByType(type) : (MediaCodec) call(slot, null, type);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;", isStatic = true)
    public static MediaCodec createByCodecName(String name) throws IOException {
        Object slot = slots[CREATE_BY_NAME];
        return slot == null ? MediaCodec.createByCodecName(name) : (MediaCodec) call(slot, null, name);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void setCallback(MediaCodec codec, MediaCodec.Callback callback) {
        Object slot = slots[SET_CALLBACK];
        if (slot == null) codec.setCallback(callback); else call(slot, codec, callback);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void setCallback(MediaCodec codec, MediaCodec.Callback callback, Handler handler) {
        Object slot = slots[SET_CALLBACK_HANDLER];
        if (slot == null) codec.setCallback(callback, handler); else call(slot, codec, callback, handler);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static MediaFormat getOutputFormat(MediaCodec codec) {
        Object slot = slots[OUTPUT_FORMAT];
        return slot == null ? codec.getOutputFormat() : (MediaFormat) call(slot, codec);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static MediaFormat getOutputFormat(MediaCodec codec, int index) {
        Object slot = slots[OUTPUT_FORMAT_INDEX];
        return slot == null ? codec.getOutputFormat(index) : (MediaFormat) call(slot, codec, index);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static int dequeueOutputBuffer(MediaCodec codec, MediaCodec.BufferInfo info, long timeoutUs) {
        Object slot = slots[DEQUEUE_OUTPUT];
        return slot == null ? codec.dequeueOutputBuffer(info, timeoutUs) : (Integer) call(slot, codec, info, timeoutUs);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void start(MediaCodec codec) {
        Object slot = slots[START];
        if (slot == null) codec.start(); else call(slot, codec);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void setParameters(MediaCodec codec, Bundle parameters) {
        Object slot = slots[SET_PARAMETERS];
        if (slot == null) codec.setParameters(parameters); else call(slot, codec, parameters);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void stop(MediaCodec codec) {
        Object slot = slots[STOP];
        if (slot == null) codec.stop(); else call(slot, codec);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void reset(MediaCodec codec) {
        Object slot = slots[RESET];
        if (slot == null) codec.reset(); else call(slot, codec);
    }
    @Redirect(owner = "Landroid/media/MediaCodec;")
    public static void release(MediaCodec codec) {
        Object slot = slots[RELEASE];
        if (slot == null) codec.release(); else call(slot, codec);
    }
    private Redirects() {}
}
