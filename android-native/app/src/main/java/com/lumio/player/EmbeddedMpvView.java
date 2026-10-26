package com.lumio.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;
import android.widget.Toast;
import android.util.Log;
import android.view.WindowManager;
import android.app.Activity;

import dev.jdtech.mpv.MPVLib;

/** libmpv renderer hosted inside PlayerActivity, with compact LUMIO playback controls. */
public final class EmbeddedMpvView extends FrameLayout implements SurfaceHolder.Callback {
    private static final String TAG = "LUMIO_MPV";
    private final Context context;
    private final String uri;
    private final long startPositionMs;
    private MPVLib mpv;
    private SurfaceView surface;
    private boolean attached;
    private boolean loaded;
    private boolean released;
    private Button playPause;
    private Button speedButton;
    private float speed = 1.0f;
    private boolean paused;
    private final int[] speeds = {25, 50, 75, 100, 125, 150, 175, 200, 250, 300, 400};
    private int speedIndex = 3;

    public EmbeddedMpvView(Context context, String uri, long startPositionMs) {
        super(context);
        this.context = context;
        this.uri = uri;
        this.startPositionMs = Math.max(0L, startPositionMs);
        setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(context);
        surface.getHolder().addCallback(this);
        addView(surface, new FrameLayout.LayoutParams(-1, -1));
        buildControls();
        initializeEngine();
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button makeButton(String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setMinWidth(dp(44));
        b.setMinimumWidth(dp(44));
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setBackgroundColor(0xCC202027);
        return b;
    }

    private void buildControls() {
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8), dp(6), dp(8), dp(6));
        controls.setBackgroundColor(0xD9000000);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM);
        addView(controls, params);

        Button back = makeButton("‹");
        back.setOnClickListener(v -> {
            if (context instanceof Activity) ((Activity) context).finish();
        });
        controls.addView(back, new LinearLayout.LayoutParams(0, -1, 0.8f));

        Button back10 = makeButton("−10s");
        back10.setOnClickListener(v -> command("seek", "-10", "relative"));
        controls.addView(back10, new LinearLayout.LayoutParams(0, -1, 1f));

        playPause = makeButton("Ⅱ");
        playPause.setOnClickListener(v -> togglePause());
        controls.addView(playPause, new LinearLayout.LayoutParams(0, -1, 1f));

        Button forward10 = makeButton("+10s");
        forward10.setOnClickListener(v -> command("seek", "10", "relative"));
        controls.addView(forward10, new LinearLayout.LayoutParams(0, -1, 1f));

        speedButton = makeButton("1×");
        speedButton.setOnClickListener(v -> cycleSpeed());
        controls.addView(speedButton, new LinearLayout.LayoutParams(0, -1, 1f));
    }

    private void initializeEngine() {
        try {
            mpv = MPVLib.create(context.getApplicationContext());
            if (mpv == null) throw new IllegalStateException("libmpv could not be created");
            // Prefer supported hardware decoding to avoid forcing slow software decode on phones.
            mpv.setOptionString("hwdec", "auto-safe");
            mpv.setOptionString("vo", "gpu");
            mpv.setOptionString("keep-open", "yes");
            mpv.setOptionString("osd-level", "0");
            mpv.setOptionString("force-window", "yes");
            mpv.init();
        } catch (Throwable error) {
            Log.e(TAG, "libmpv initialization failed", error);
            Toast.makeText(context, "LUMIO fallback engine could not start", Toast.LENGTH_LONG).show();
            release();
        }
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
        speed = speeds[speedIndex] / 100f;
        command("set", "speed", Float.toString(speed));
        if (speedButton != null) speedButton.setText((speed == (int)speed ? Integer.toString((int)speed) : Float.toString(speed)) + "×");
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (mpv == null || released || holder.getSurface() == null || !holder.getSurface().isValid()) return;
        try {
            mpv.attachSurface(holder.getSurface());
            attached = true;
            if (!loaded) {
                command("loadfile", uri, "replace");
                loaded = true;
                if (startPositionMs > 0L) command("seek", Double.toString(startPositionMs / 1000.0), "absolute");
            }
        } catch (Throwable error) {
            Log.e(TAG, "Could not attach playback surface", error);
            Toast.makeText(context, "LUMIO could not open this video with the fallback engine", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mpv != null && holder.getSurface() != null && holder.getSurface().isValid()) {
            try { mpv.attachSurface(holder.getSurface()); attached = true; }
            catch (Throwable error) { Log.w(TAG, "Surface update failed", error); }
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (mpv != null && attached) {
            try { mpv.detachSurface(); } catch (Throwable error) { Log.w(TAG, "Surface detach failed", error); }
            attached = false;
        }
    }

    public boolean isAvailable() { return mpv != null && !released; }
    public void cycleAudioTrack() { command("cycle", "audio"); }
    public void disableSubtitles() { command("set", "sid", "no"); }
    public void cycleSubtitleTrack() { command("cycle", "sub"); }
    public void setPlaybackSpeed(float value) {
        if (value < 0.25f || value > 4f) return;
        speed = value;
        command("set", "speed", Float.toString(value));
        if (speedButton != null) speedButton.setText((value == (int)value ? Integer.toString((int)value) : Float.toString(value)) + "×");
    }
    public void seekRelative(long milliseconds) {
        command("seek", Double.toString(milliseconds / 1000.0), "relative");
    }
    public void release() {
        if (released) return;
        released = true;
        if (mpv != null) {
            if (attached) {
                try { mpv.detachSurface(); } catch (Throwable ignored) {}
                attached = false;
            }
            try { mpv.destroy(); } catch (Throwable error) { Log.w(TAG, "mpv destroy failed", error); }
            mpv = null;
        }
    }
}