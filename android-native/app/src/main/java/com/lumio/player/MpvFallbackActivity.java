package com.lumio.player;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.jdtech.mpv.MPVLib;

/**
 * Experimental software-decoding fallback. Kept separate from the Media3 player
 * so the existing playback UI and service remain unchanged unless HEVC decoding fails.
 */
public final class MpvFallbackActivity extends Activity implements SurfaceHolder.Callback {
    private MPVLib mpv;
    private SurfaceView surfaceView;
    private String mediaUri;
    private boolean surfaceAttached;
    private boolean mediaLoaded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        mediaUri = getIntent().getStringExtra(PlayerActivity.EXTRA_URI);
        if (mediaUri == null || mediaUri.trim().isEmpty()) {
            Toast.makeText(this, "No video URI was provided", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        TextView label = new TextView(this);
        label.setText("LUMIO · Software Decoder");
        label.setTextColor(Color.WHITE);
        label.setTextSize(12);
        label.setPadding(18, 14, 18, 14);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        root.addView(label, labelParams);
        TextView back = new TextView(this);
        back.setText("✕");
        back.setTextColor(Color.WHITE);
        back.setTextSize(22);
        back.setGravity(Gravity.CENTER);
        back.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(56, 56, Gravity.TOP | Gravity.END);
        root.addView(back, backParams);
        back.setOnClickListener(v -> finish());
        surfaceView.setOnClickListener(v -> {
            if (mpv != null) {
                try { mpv.command(new String[]{"cycle", "pause"}); }
                catch (Exception e) { android.util.Log.e("LUMIO_MPV", "Pause toggle failed", e); }
            }
        });
        setContentView(root);

        try {
            mpv = MPVLib.create(getApplicationContext());
            if (mpv == null) throw new IllegalStateException("libmpv could not be created");
            // Force FFmpeg software decoding for this diagnostic fallback.
            mpv.setOptionString("hwdec", "no");
            mpv.setOptionString("vo", "gpu");
            mpv.setOptionString("keep-open", "yes");
            mpv.setOptionString("osd-level", "1");
            mpv.init();
        } catch (Throwable error) {
            android.util.Log.e("LUMIO_MPV", "Unable to initialize libmpv", error);
            Toast.makeText(this, "Software playback engine failed to initialize", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (mpv == null || holder.getSurface() == null || !holder.getSurface().isValid()) return;
        try {
            mpv.attachSurface(holder.getSurface());
            surfaceAttached = true;
            if (!mediaLoaded) {
                // MPV accepts file/content URIs and decodes supported codecs independently of MediaCodec.
                mpv.command(new String[]{"loadfile", mediaUri, "replace"});
                mediaLoaded = true;
            }
        } catch (Throwable error) {
            android.util.Log.e("LUMIO_MPV", "Unable to attach surface or load media", error);
            Toast.makeText(this, "Software playback failed to start", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mpv != null && holder.getSurface() != null && holder.getSurface().isValid()) {
            try { mpv.attachSurface(holder.getSurface()); surfaceAttached = true; }
            catch (Exception e) { android.util.Log.e("LUMIO_MPV", "Surface update failed", e); }
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (mpv != null && surfaceAttached) {
            try { mpv.detachSurface(); } catch (Exception e) {
                android.util.Log.w("LUMIO_MPV", "Surface detach failed", e);
            }
            surfaceAttached = false;
        }
    }

    @Override protected void onDestroy() {
        if (mpv != null) {
            if (surfaceAttached) {
                try { mpv.detachSurface(); } catch (Exception ignored) {}
                surfaceAttached = false;
            }
            try { mpv.destroy(); } catch (Exception e) {
                android.util.Log.w("LUMIO_MPV", "Destroy failed", e);
            }
            mpv = null;
        }
        super.onDestroy();
    }
}
