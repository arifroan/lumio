package com.lumio.player;

import android.app.Activity;
import android.content.Context;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.jdtech.mpv.MPVLib;

/** Compact embedded libmpv renderer. The Activity keeps LUMIO's existing top toolbar. */
public final class EmbeddedMpvView extends FrameLayout implements SurfaceHolder.Callback, MPVLib.EventObserver, MPVLib.LogObserver {
    private static final String TAG = "LUMIO_MPV";
    private final Context context;
    private final String uri;
    // Keep a provider-granted file descriptor alive while libmpv reads /proc/self/fd/N.
    private ParcelFileDescriptor sourceDescriptor;
    private final long startPositionMs;
    private MPVLib mpv;
    private SurfaceView surface;
    private boolean attached;
    private boolean loaded;
    private boolean released;
    private TextView playPause;
    private TextView speedButton;
    private boolean paused;
    private float speed = 1f;
    private final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};
    private int speedIndex = 2;

    public EmbeddedMpvView(Context context, String uri, long startPositionMs) {
        super(context);
        this.context = context;
        this.uri = uri;
        this.startPositionMs = Math.max(0L, startPositionMs);
        setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(context);
        surface.setBackgroundColor(Color.BLACK);
        addView(surface, new FrameLayout.LayoutParams(-1, -1));
        buildControls();
        initializeEngine();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable panel(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView control(String label, boolean primary) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 15 : 12);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setMinWidth(dp(primary ? 48 : 42));
        view.setPadding(dp(8), dp(5), dp(8), dp(5));
        view.setBackground(panel(primary ? 0xFF343247 : 0xCC202027, primary ? 16 : 12));
        return view;
    }

    private void buildControls() {
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(4), dp(4), dp(4), dp(4));
        // Keep only the essential playback buttons; remove the large decorative overlay panel.
        controls.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(54), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        panelParams.setMargins(dp(14), 0, dp(14), dp(12));
        addView(controls, panelParams);

        TextView back = control("‹", false);
        back.setOnClickListener(v -> {
            if (context instanceof Activity) ((Activity) context).finish();
        });
        add(controls, back, 0.75f, 0);

        TextView back10 = control("−10s", false);
        back10.setOnClickListener(v -> seek(-10));
        add(controls, back10, 1f, dp(5));

        playPause = control("Ⅱ", true);
        playPause.setOnClickListener(v -> togglePause());
        add(controls, playPause, 1f, dp(5));

        TextView forward10 = control("+10s", false);
        forward10.setOnClickListener(v -> seek(10));
        add(controls, forward10, 1f, dp(5));

        speedButton = control("1×", false);
        speedButton.setOnClickListener(v -> cycleSpeed());
        add(controls, speedButton, 0.9f, dp(5));
    }

    private void add(LinearLayout parent, TextView child, float weight, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        params.setMargins(leftMargin, 0, 0, 0);
        parent.addView(child, params);
    }

    private void initializeEngine() {
        try {
            mpv = MPVLib.create(context.getApplicationContext());
            if (mpv == null) throw new IllegalStateException("libmpv could not be created");
            // Native mpv logs and events are essential: a successful Activity build does not
            // imply that the demuxer or renderer has actually started.
            mpv.addObserver(this);
            mpv.addLogObserver(this);
            mpv.setOptionString("msg-level", "all=info");
            mpv.setOptionString("hwdec", "auto-safe");
            // Let libmpv initialize without a video output; create the GPU output only
            // after Android supplies a real Surface, as required by the Android GPU context.
            mpv.setOptionString("gpu-context", "android");
            // Match the upstream mpv-android lifecycle: select the video output before init,
            // keep the window disabled until a real Android Surface is available, and let mpv
            // enter idle once before the first loadfile command.
            mpv.setOptionString("vo", "gpu");
            mpv.setOptionString("keep-open", "yes");
            mpv.setOptionString("osd-level", "0");
            mpv.setOptionString("video-unscaled", "no");
            mpv.setOptionString("keepaspect", "yes");
            mpv.init();
            mpv.setOptionString("force-window", "no");
            mpv.setOptionString("idle", "once");
            // Register only after MPV is initialized so no surface callback can race init().
            surface.getHolder().addCallback(this);
            Log.i(TAG, "libmpv initialized; sourceScheme=" + Uri.parse(uri).getScheme());
        } catch (Throwable error) {
            Log.e(TAG, "libmpv initialization failed", error);
            // Keep failures in Logcat rather than adding a transient UI overlay over the video.
            release();
        }
    }

    /**
     * Android media pickers commonly pass content:// URIs. Native mpv cannot reliably
     * consume those provider URIs directly, so pass its open file descriptor instead.
     * Keeping the descriptor open is essential for seeking and streaming large files.
     */
    private String resolvePlayablePath(String source) throws Exception {
        Uri parsed = Uri.parse(source);
        if (ContentResolver.SCHEME_CONTENT.equals(parsed.getScheme())) {
            sourceDescriptor = context.getContentResolver().openFileDescriptor(parsed, "r");
            if (sourceDescriptor == null) throw new FileNotFoundException("Provider returned no file descriptor for " + source);
            return "/proc/self/fd/" + sourceDescriptor.getFd();
        }
        if (ContentResolver.SCHEME_FILE.equals(parsed.getScheme())) return parsed.getPath();
        return source;
    }

    private void command(String... args) {
        if (mpv == null || released) return;
        try { mpv.command(args); }
        catch (Throwable error) { Log.w(TAG, "mpv command failed: " + args[0], error); }
    }

    private void togglePause() {
        paused = !paused;
        command("set", "pause", paused ? "yes" : "no");
        if (playPause != null) playPause.setText(paused ? "▶" : "Ⅱ");
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.length;
        setPlaybackSpeed(speeds[speedIndex]);
    }

    private void seek(int seconds) {
        command("seek", Integer.toString(seconds), "relative");
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (mpv == null || released || holder.getSurface() == null || !holder.getSurface().isValid()) return;
        try {
            int width = Math.max(1, surface.getWidth());
            int height = Math.max(1, surface.getHeight());
            mpv.setPropertyString("android-surface-size", width + "x" + height);
            mpv.attachSurface(holder.getSurface());
            attached = true;

            // Critical: enable the video output only after a valid Android Surface is attached.
            // Initializing vo=gpu before surface creation can leave audio playing over a black view.
            mpv.setOptionString("force-window", "yes");
            mpv.setPropertyString("vo", "gpu");
            Log.i(TAG, "Surface attached; GPU video output enabled at " + width + "x" + height);

            if (!loaded) {
                String playablePath = resolvePlayablePath(uri);
                Log.i(TAG, "Loadfile requested. sourceScheme="
                        + Uri.parse(uri).getScheme() + ", resolvedPath=" + playablePath);
                command("loadfile", playablePath, "replace");
                loaded = true;
                if (startPositionMs > 0L) command("seek", Double.toString(startPositionMs / 1000.0), "absolute");
            }
        } catch (Throwable error) {
            Log.e(TAG, "Could not attach playback surface or start GPU output", error);
            // Rendering errors are logged; do not cover the playback surface with a toast overlay.
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mpv != null && !released && holder.getSurface() != null && holder.getSurface().isValid()) {
            try {
                mpv.setPropertyString("android-surface-size", Math.max(1, width) + "x" + Math.max(1, height));
                if (!attached) {
                    mpv.attachSurface(holder.getSurface());
                    attached = true;
                    mpv.setOptionString("force-window", "yes");
                    mpv.setPropertyString("vo", "gpu");
                }
                Log.i(TAG, "Surface size updated to " + width + "x" + height);
            } catch (Throwable error) { Log.w(TAG, "Surface update failed", error); }
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (mpv != null && attached) {
            try {
                // Disable the VO before detaching the native window, following mpv-android's
                // lifecycle to avoid retaining a stale EGL/native surface.
                mpv.setPropertyString("vo", "null");
                mpv.setPropertyString("force-window", "no");
                mpv.detachSurface();
            } catch (Throwable error) { Log.w(TAG, "Surface detach failed", error); }
            attached = false;
        }
    }

    @Override public void event(int eventId) {
        Log.i(TAG, "mpv event=" + eventId
                + (eventId == 8 ? " (FILE_LOADED)" : eventId == 21 ? " (PLAYBACK_RESTART)"
                : eventId == 7 ? " (END_FILE)" : ""));
    }

    @Override public void eventProperty(String property) {
        Log.d(TAG, "mpv property changed: " + property);
    }
    @Override public void eventProperty(String property, long value) {
        Log.d(TAG, "mpv property " + property + "=" + value);
    }
    @Override public void eventProperty(String property, boolean value) {
        Log.d(TAG, "mpv property " + property + "=" + value);
    }
    @Override public void eventProperty(String property, double value) {
        Log.d(TAG, "mpv property " + property + "=" + value);
    }
    @Override public void eventProperty(String property, String value) {
        Log.d(TAG, "mpv property " + property + "=" + value);
    }
    @Override public void logMessage(String prefix, int level, String text) {
        if (level <= 40) Log.i(TAG, "mpv/" + prefix + ": " + text);
        else Log.d(TAG, "mpv/" + prefix + ": " + text);
    }

    public boolean isAvailable() { return mpv != null && !released; }
    public void cycleAudioTrack() { command("cycle", "audio"); }
    public void disableSubtitles() { command("set", "sid", "no"); }
    public void cycleSubtitleTrack() { command("cycle", "sub"); }
    public void setPlaybackSpeed(float value) {
        if (value < 0.25f || value > 4f) return;
        speed = value;
        command("set", "speed", Float.toString(value));
        if (speedButton != null) {
            String label = value == (int) value ? Integer.toString((int) value) : Float.toString(value);
            speedButton.setText(label + "×");
        }
    }
    public void seekRelative(long milliseconds) {
        command("seek", Double.toString(milliseconds / 1000.0), "relative");
    }
    public void release() {
        if (released) return;
        released = true;
        if (mpv != null) mpv.removeObserver(this);
        if (mpv != null) mpv.removeLogObserver(this);
        if (mpv != null) {
            if (attached) {
                try { mpv.detachSurface(); } catch (Throwable ignored) {}
                attached = false;
            }
            try { mpv.destroy(); } catch (Throwable error) { Log.w(TAG, "mpv destroy failed", error); }
            mpv = null;
        }
        if (sourceDescriptor != null) {
            try { sourceDescriptor.close(); } catch (Exception error) { Log.w(TAG, "Source descriptor close failed", error); }
            sourceDescriptor = null;
        }
    }
}