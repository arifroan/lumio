package com.lumio.player;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.media.AudioManager;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.MimeTypes;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PlayerActivity extends Activity {
    public static final String EXTRA_URI = "media_uri";
    public static final String EXTRA_TITLE = "media_title";
    public static final String EXTRA_AUDIO = "media_is_audio";
    public static final String EXTRA_PLAYLIST = "media_playlist";
    public static final String EXTRA_TEMP_VAULT_FILE = "temporary_vault_file";
    public static final String EXTRA_MEDIA_IDENTITY = "media_identity";
    private static final int REQUEST_SUBTITLE = 4301;
    private static final String PREFS = "lumio_playback";
    private static final String WATCH_HISTORY_KEY = "watch_history";
    private static final int MAX_WATCH_HISTORY = 100;
    private static final long PROGRESS_SAVE_INTERVAL_MS = 5_000L;
    private static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};

    private static final long CONTROLS_AUTO_HIDE_MS = 2_000L;
    private PlayerView playerView;
    private LinearLayout topBar;
    private final Handler controlsHandler = new Handler(Looper.getMainLooper());
    private boolean playbackControlsVisible = true;
    private final Runnable hidePlaybackControls = this::hidePlaybackControlsIfNeeded;
    private final Runnable savePlaybackProgress = new Runnable() {
        @Override public void run() {
            if (controller != null && !isFinishing() && !isDestroyed()) {
                persistPosition();
                controlsHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS);
            }
        }
    };
    private final Player.Listener playbackUiListener = new Player.Listener() {
        @Override public void onIsPlayingChanged(boolean isPlaying) {
            // Show controls on pause and resume; the shared timer hides them after two seconds.
            if (!requestedAudio) showPlaybackControlsTemporarily();
        }
        @Override public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED) persistPosition();
            if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE) showLoadingOverlay();
            else if (playbackState == Player.STATE_READY) markLoadingReady();
            if (requestedAudio) return;
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                showPlaybackControlsUntilResumed();
            }
        }
        @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
            hideLoadingOverlayImmediately();
            android.util.Log.e("LUMIO_PLAYBACK", "Playback failed for URI=" + requestedUri
                    + ", code=" + error.getErrorCodeName(), error);
            showPlaybackFailure(error);
        }
    };
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private TextView hint;
    private TextView titleText;
    private Button speedButton;
    private Button pipButton;
    private Button zoomButton;
    private Button lockButton;
    private FrameLayout controlsOverlay;
    private FrameLayout loadingOverlay;
    private long loadingStartedAt;
    private boolean loadingPlaybackReady;
    private static final long LOADING_MIN_VISIBLE_MS = 2300L;
    private final Runnable finishLoadingOverlay = new Runnable() {
        @Override public void run() {
            if (loadingOverlay == null || !loadingPlaybackReady) return;
            long remaining = LOADING_MIN_VISIBLE_MS - (System.currentTimeMillis() - loadingStartedAt);
            if (remaining > 0L) controlsHandler.postDelayed(this, remaining);
            else loadingOverlay.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
                if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
            }).start();
        }
    };
    private LinearLayout timelineRow;
    private LinearLayout bottomRow;
    private LinearLayout centerControls;
    private SeekBar timelineSeekBar;
    private TextView currentTimeText;
    private TextView durationTimeText;
    private Button playPauseButton;
    private boolean userSeeking;
    private final Runnable updateTimeline = new Runnable() {
        @Override public void run() {
            if (controller != null && timelineSeekBar != null && !userSeeking) {
                long duration = controller.getDuration();
                long position = controller.getCurrentPosition();
                if (duration < 0L) duration = 0L;
                timelineSeekBar.setMax((int) Math.min(Integer.MAX_VALUE, duration));
                timelineSeekBar.setProgress((int) Math.min(Integer.MAX_VALUE, Math.max(0L, position)));
                if (currentTimeText != null) currentTimeText.setText(formatClock(position));
                if (durationTimeText != null) durationTimeText.setText(formatClock(duration));
                if (playPauseButton != null) setButtonIcon(playPauseButton, controller.isPlaying() ? "pause" : "play");
            }
            controlsHandler.removeCallbacks(this);
            if (!isFinishing() && !isDestroyed()) controlsHandler.postDelayed(this, 500L);
        }
    };


    /** Thin, consistent outline icons matching the reference playback interface. */
    private static final class PlaybackIconDrawable extends Drawable {
        private final String name;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PlaybackIconDrawable(String name) {
            this.name = name; paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.0f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b=getBounds(); float size=Math.min(b.width(),b.height())*0.76f;
            canvas.save(); canvas.translate(b.centerX()-size/2f,b.centerY()-size/2f); canvas.scale(size/24f,size/24f);
            switch(name) {
                case "back": line(canvas,19,12,5,12); line(canvas,5,12,12,5); line(canvas,5,12,12,19); break;
                case "cast": canvas.drawRoundRect(new RectF(4,4,21,17),1.4f,1.4f,paint); arc(canvas,3,18,9,24,270,90); arc(canvas,3,18,14,24,270,90); break;
                case "cc": canvas.drawRoundRect(new RectF(2.5f,5,21.5f,19),2,2,paint); text(canvas,"CC",6,15.5f,7.2f); break;
                case "music": line(canvas,14,4,14,17); line(canvas,14,4,21,2.5f); line(canvas,21,2.5f,21,15); canvas.drawOval(new RectF(8,15,14,20),paint); canvas.drawOval(new RectF(15,13,21,18),paint); break;
                case "settings": canvas.drawCircle(12,12,7.1f,paint); canvas.drawCircle(12,12,2.5f,paint); for(int a=0;a<8;a++){double t=Math.PI*a/4;line(canvas,(float)(12+8*Math.cos(t)),(float)(12+8*Math.sin(t)),(float)(12+10*Math.cos(t)),(float)(12+10*Math.sin(t)));} break;
                case "pip": canvas.drawRect(new RectF(2.5f,4.5f,21.5f,19.5f),paint); canvas.drawRect(new RectF(12,11,19,17),paint); break;
                case "more": canvas.drawCircle(12,5,1,paint); canvas.drawCircle(12,12,1,paint); canvas.drawCircle(12,19,1,paint); break;
                case "brightness": canvas.drawCircle(12,12,4,paint); for(int a=0;a<8;a++){double t=Math.PI*a/4;line(canvas,(float)(12+6.5*Math.cos(t)),(float)(12+6.5*Math.sin(t)),(float)(12+9*Math.cos(t)),(float)(12+9*Math.sin(t)));} break;
                case "up": line(canvas,5,15,12,8); line(canvas,12,8,19,15); break;
                case "down": line(canvas,5,9,12,16); line(canvas,12,16,19,9); break;
                case "volume": Path sp=new Path(); sp.moveTo(3,9);sp.lineTo(7,9);sp.lineTo(12,5);sp.lineTo(12,19);sp.lineTo(7,15);sp.lineTo(3,15);sp.close();canvas.drawPath(sp,paint);arc(canvas,9,6,19,18,-55,110);arc(canvas,9,3,23,21,-55,110);break;
                case "lock": canvas.drawRoundRect(new RectF(4,10,20,21),2,2,paint);arc(canvas,6,3,18,15,180,180);line(canvas,12,14,12,17);break;
                case "rotate": arc(canvas,3,3,21,21,35,250);line(canvas,3,4,3,10);line(canvas,3,4,9,4);arc(canvas,3,3,21,21,215,250);line(canvas,21,20,21,14);line(canvas,21,20,15,20);break;
                case "playlist": canvas.drawRoundRect(new RectF(3,3,21,21),1.5f,1.5f,paint);for(int y=8;y<=16;y+=4){line(canvas,7,y,9,y);line(canvas,12,y,18,y);}break;
                case "rewind":
                    arc(canvas,4,4,20,20,42,278);
                    line(canvas,4,4,4,10); line(canvas,4,4,10,4);
                    text(canvas,"10",6.1f,15.2f,7.2f);
                    break;
                case "forward":
                    arc(canvas,4,4,20,20,220,278);
                    line(canvas,20,4,20,10); line(canvas,20,4,14,4);
                    text(canvas,"10",6.7f,15.2f,7.2f);
                    break;
                case "play": paint.setStyle(Paint.Style.FILL);Path tri=new Path();tri.moveTo(7,4);tri.lineTo(19,12);tri.lineTo(7,20);tri.close();canvas.drawPath(tri,paint);paint.setStyle(Paint.Style.STROKE);break;
                case "pause": paint.setStrokeWidth(3.3f);line(canvas,8,4,8,20);line(canvas,16,4,16,20);paint.setStrokeWidth(2.05f);break;
                case "headphones": arc(canvas,3,2,21,22,180,180);canvas.drawRoundRect(new RectF(3,12,7,19),1.2f,1.2f,paint);canvas.drawRoundRect(new RectF(17,12,21,19),1.2f,1.2f,paint);break;
                case "fullscreen": line(canvas,4,9,4,4);line(canvas,4,4,9,4);line(canvas,15,4,20,4);line(canvas,20,4,20,9);line(canvas,4,15,4,20);line(canvas,4,20,9,20);line(canvas,15,20,20,20);line(canvas,20,15,20,20);break;
                case "unlock": canvas.drawRoundRect(new RectF(4,10,20,21),2,2,paint);arc(canvas,8,3,20,15,190,160);line(canvas,12,14,12,17);break;
            }
            canvas.restore();
        }
        private void line(Canvas c,float x1,float y1,float x2,float y2){c.drawLine(x1,y1,x2,y2,paint);}
        private void arc(Canvas c,float l,float t,float r,float b,float start,float sweep){c.drawArc(new RectF(l,t,r,b),start,sweep,false,paint);}
        private void text(Canvas c,String value,float x,float y,float size){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.WHITE);p.setTextSize(size);c.drawText(value,x,y,p);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private void setButtonIcon(Button button,String name){if(button!=null){button.setText("");button.setBackground(new PlaybackIconDrawable(name));button.setPadding(0,0,0,0);}}

    private String formatClock(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.US, "%d:%02d", minutes, remainder);
    }

    private Button overlayButton(String label, float sizeSp) {
        Button b = button(label);
        b.setTextSize(sizeSp);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        if (label.startsWith("icon:")) setButtonIcon(b, label.substring(5));
        b.setStateListAnimator(null);
        b.setPadding(dp(7), dp(3), dp(7), dp(3));
        b.setMinHeight(dp(42));
        return b;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(2), dp(12), dp(2));
        row.setBackgroundColor(Color.TRANSPARENT);
        return row;
    }

    private FrameLayout contentFrame;
    private FrameLayout rootFrame;
    private FrameLayout lockOverlay;
    private SharedPreferences prefs;
    private String requestedUri;
    private String requestedTitle;
    private boolean requestedAudio;
    private boolean locked;
    private int speedIndex = 3;
    private float zoom = 1f;
    private float panX = 0f;
    private float panY = 0f;
    private float brightness = 0.7f;
    private float volumeFraction = 0.5f;
    private int subtitleTextSize = 22;
    private int subtitleStylePreset = 0;
    private File generatedSubtitleFile;
    private float touchDownX;
    private float touchDownY;
    private boolean gestureHandled;
    private boolean scaling;
    private AudioManager audioManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 12, 18));
        getWindow().setNavigationBarColor(Color.rgb(12, 12, 18));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        requestedUri = getIntent().getStringExtra(EXTRA_URI);
        requestedTitle = getIntent().getStringExtra(EXTRA_TITLE);
        requestedAudio = getIntent().getBooleanExtra(EXTRA_AUDIO, false);
        // Keep the library in its normal orientation; rotate only video playback to landscape.
        setRequestedOrientation(requestedAudio
                ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        subtitleTextSize = prefs.getInt("subtitle_size", 22);
        subtitleStylePreset = prefs.getInt("subtitle_style", 0);
        buildPlayerUi();
        applySubtitleAppearance();
    }

    private void applyImmersivePlayback() {
        try {
            View decor = getWindow().getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController insetsController = getWindow().getInsetsController();
                if (insetsController != null) {
                    insetsController.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    insetsController.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                }
            } else {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (RuntimeException error) {
            // A system-bar API failure must never prevent video playback from opening.
            android.util.Log.w("LUMIO_FULLSCREEN", "Unable to enter immersive mode", error);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !isInPictureInPictureMode()) {
            getWindow().getDecorView().post(this::applyImmersivePlayback);
        }
    }

    private GradientDrawable rounded(int color, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        if (stroke != 0) d.setStroke(Math.max(1, (int) getResources().getDisplayMetrics().density), stroke);
        return d;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.rgb(246, 243, 255));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(9), dp(4), dp(9), dp(4));
        b.setBackground(rounded(Color.rgb(31, 29, 43), Color.rgb(68, 62, 88), 12));
        return b;
    }

    private void seekBy(long deltaMs) {
        if (controller == null) return;
        long duration = controller.getDuration();
        long target = Math.max(0L, controller.getCurrentPosition() + deltaMs);
        if (duration > 0L) target = Math.min(duration, target);
        controller.seekTo(target);
        showPlaybackControlsTemporarily();
    }

    private float currentVolumeFraction() {
        if (audioManager == null) return volumeFraction;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0f : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private void setVolumeFraction(float fraction) {
        volumeFraction = clamp(fraction, 0f, 1f);
        if (audioManager != null) {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volumeFraction * max), 0);
        }
    }

    private void adjustVolume(int direction) {
        if (audioManager != null) audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        volumeFraction = currentVolumeFraction();
        showHint("Volume " + Math.round(volumeFraction * 100) + "%");
    }

    private void setBrightness(float value) {
        brightness = clamp(value, 0.08f, 1f);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = brightness;
        getWindow().setAttributes(attrs);
    }

    private void adjustBrightness(float delta) {
        setBrightness(brightness + delta);
        showHint("Brightness " + Math.round(brightness * 100) + "%");
    }

    private void showPlaybackSettings() {
        String[] choices = {"Playback speed", "Subtitles", "Audio tracks", "Picture in Picture", "Fit / fill"};
        new AlertDialog.Builder(this).setTitle("Playback settings").setItems(choices, (dialog, which) -> {
            if (which == 0) cycleSpeed();
            else if (which == 1) showSubtitleMenu();
            else if (which == 2) showTrackMenu();
            else if (which == 3) enterPipIfSupported();
            else cycleZoom();
        }).show();
    }

    private void showPlaylistMenu() {
        if (controller == null || controller.getMediaItemCount() <= 1) {
            showHint("No other items in the playback list");
            return;
        }
        String[] labels = new String[controller.getMediaItemCount()];
        for (int i = 0; i < labels.length; i++) {
            MediaItem item = controller.getMediaItemAt(i);
            CharSequence title = item.mediaMetadata.title;
            labels[i] = title == null ? "Media " + (i + 1) : title.toString();
        }
        new AlertDialog.Builder(this).setTitle("Playback list").setItems(labels,
                (dialog, which) -> controller.seekToDefaultPosition(which)).show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showLoadingOverlay() {
        if (loadingOverlay == null || isFinishing() || isDestroyed()) return;
        controlsHandler.removeCallbacks(finishLoadingOverlay);
        if (loadingOverlay.getVisibility() != View.VISIBLE) {
            loadingStartedAt = System.currentTimeMillis();
            loadingPlaybackReady = false;
            loadingOverlay.setAlpha(1f);
            loadingOverlay.setVisibility(View.VISIBLE);
        } else loadingPlaybackReady = false;
    }

    private void markLoadingReady() {
        loadingPlaybackReady = true;
        controlsHandler.removeCallbacks(finishLoadingOverlay);
        controlsHandler.post(finishLoadingOverlay);
    }

    private void hideLoadingOverlayImmediately() {
        controlsHandler.removeCallbacks(finishLoadingOverlay);
        loadingPlaybackReady = true;
        if (loadingOverlay != null) {
            loadingOverlay.animate().cancel();
            loadingOverlay.setVisibility(View.GONE);
            loadingOverlay.setAlpha(1f);
        }
    }

    private void buildPlayerUi() {
        FrameLayout root = new FrameLayout(this);
        rootFrame = root;
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setBackgroundColor(Color.BLACK);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);

        controlsOverlay = new FrameLayout(this);
        controlsOverlay.setBackgroundColor(Color.TRANSPARENT);
        root.addView(controlsOverlay, new FrameLayout.LayoutParams(-1, -1));

        // Minimal loading layer shown while a song/video is being prepared.
        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(0xB8000000);
        loadingOverlay.setClickable(true);
        loadingOverlay.setFocusable(true);
        LinearLayout loadingStack = new LinearLayout(this);
        loadingStack.setOrientation(LinearLayout.VERTICAL);
        loadingStack.setGravity(Gravity.CENTER);
        android.widget.ProgressBar loadingSpinner = new android.widget.ProgressBar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            loadingSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        }
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        spinnerParams.bottomMargin = dp(15);
        loadingStack.addView(loadingSpinner, spinnerParams);
        TextView loadingBrand = new TextView(this);
        loadingBrand.setText("L U M I O");
        loadingBrand.setTextColor(Color.WHITE);
        loadingBrand.setTextSize(13);
        loadingBrand.setLetterSpacing(0.18f);
        loadingBrand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        loadingStack.addView(loadingBrand, new LinearLayout.LayoutParams(-2, -2));
        loadingOverlay.addView(loadingStack, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        loadingOverlay.setVisibility(View.GONE);
        loadingOverlay.setAlpha(1f);
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(12), dp(5), dp(12), dp(5));
        topBar.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP);
        controlsOverlay.addView(topBar, topParams);

        Button back = overlayButton("icon:back", 30);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(42), -1));

        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(14);
        titleText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleText.setText(requestedTitle == null ? "LUMIO" : requestedTitle);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMargins(dp(7), 0, dp(8), 0);
        topBar.addView(titleText, titleParams);

        Button cast = overlayButton("icon:cast", 21);
        cast.setContentDescription("Cast");
        cast.setOnClickListener(v -> showHint("Casting is not available in this build"));
        topBar.addView(cast, new LinearLayout.LayoutParams(dp(44), -1));

        Button cc = overlayButton("icon:cc", 15);
        cc.setContentDescription("Subtitles");
        cc.setOnClickListener(v -> showSubtitleMenu());
        topBar.addView(cc, new LinearLayout.LayoutParams(dp(38), -1));

        Button audio = overlayButton("icon:music", 23);
        audio.setContentDescription("Audio tracks");
        audio.setOnClickListener(v -> showTrackMenu());
        topBar.addView(audio, new LinearLayout.LayoutParams(dp(38), -1));

        Button settings = overlayButton("icon:settings", 22);
        settings.setContentDescription("Playback settings");
        settings.setOnClickListener(v -> showPlaybackSettings());
        topBar.addView(settings, new LinearLayout.LayoutParams(dp(38), -1));

        pipButton = overlayButton("icon:pip", 20);
        pipButton.setContentDescription("Picture in Picture");
        pipButton.setOnClickListener(v -> enterPipIfSupported());
        topBar.addView(pipButton, new LinearLayout.LayoutParams(dp(38), -1));

        Button overflow = overlayButton("icon:more", 24);
        overflow.setContentDescription("More playback options");
        overflow.setOnClickListener(v -> showPlaybackSettings());
        topBar.addView(overflow, new LinearLayout.LayoutParams(dp(44), -1));

        // Side indicators mirror the brightness and volume gesture zones; swipes remain handled by installGestures().
        LinearLayout brightnessRail = new LinearLayout(this);
        brightnessRail.setOrientation(LinearLayout.VERTICAL);
        brightnessRail.setGravity(Gravity.CENTER);
        brightnessRail.setBackgroundColor(Color.TRANSPARENT);
        Button brightUp = overlayButton("icon:up", 24);
        brightUp.setContentDescription("Increase brightness");
        brightUp.setOnClickListener(v -> adjustBrightness(0.08f));
        brightnessRail.addView(brightUp, new LinearLayout.LayoutParams(dp(44), dp(42)));
        TextView sun = new TextView(this);
        sun.setText("");
        sun.setBackground(new PlaybackIconDrawable("brightness"));
        sun.setTextColor(Color.WHITE);
        sun.setTextSize(27);
        sun.setGravity(Gravity.CENTER);
        brightnessRail.addView(sun, new LinearLayout.LayoutParams(dp(44), dp(42)));
        SeekBar brightSeek = new SeekBar(this);
        brightSeek.setMax(100);
        brightSeek.setProgress(Math.round(brightness * 100));
        brightSeek.setRotation(-90f);
        brightSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(91, 190, 255)));
        brightSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        brightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) setBrightness(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        FrameLayout brightSeekHolder = new FrameLayout(this);
        brightSeekHolder.addView(brightSeek, new FrameLayout.LayoutParams(dp(112), dp(36), Gravity.CENTER));
        brightnessRail.addView(brightSeekHolder, new LinearLayout.LayoutParams(dp(44), dp(112)));
        Button brightDown = overlayButton("icon:down", 24);
        brightDown.setContentDescription("Decrease brightness");
        brightDown.setOnClickListener(v -> adjustBrightness(-0.08f));
        brightnessRail.addView(brightDown, new LinearLayout.LayoutParams(dp(44), dp(42)));
        FrameLayout.LayoutParams brightParams = new FrameLayout.LayoutParams(dp(48), dp(250), Gravity.CENTER_VERTICAL | Gravity.LEFT);
        brightParams.leftMargin = dp(12);
        controlsOverlay.addView(brightnessRail, brightParams);

        LinearLayout volumeRail = new LinearLayout(this);
        volumeRail.setOrientation(LinearLayout.VERTICAL);
        volumeRail.setGravity(Gravity.CENTER);
        volumeRail.setBackgroundColor(Color.TRANSPARENT);
        Button volumeUp = overlayButton("icon:up", 24);
        volumeUp.setContentDescription("Increase volume");
        volumeUp.setOnClickListener(v -> adjustVolume(1));
        volumeRail.addView(volumeUp, new LinearLayout.LayoutParams(dp(44), dp(42)));
        Button speaker = overlayButton("icon:volume", 17);
        speaker.setContentDescription("Volume");
        speaker.setOnClickListener(v -> showHint("Volume " + Math.round(currentVolumeFraction() * 100) + "%"));
        volumeRail.addView(speaker, new LinearLayout.LayoutParams(dp(44), dp(42)));
        SeekBar volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(Math.round(currentVolumeFraction() * 100));
        volumeSeek.setRotation(-90f);
        volumeSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(91, 190, 255)));
        volumeSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) setVolumeFraction(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        FrameLayout volumeSeekHolder = new FrameLayout(this);
        volumeSeekHolder.addView(volumeSeek, new FrameLayout.LayoutParams(dp(112), dp(36), Gravity.CENTER));
        volumeRail.addView(volumeSeekHolder, new LinearLayout.LayoutParams(dp(44), dp(112)));
        Button volumeDown = overlayButton("icon:down", 24);
        volumeDown.setContentDescription("Decrease volume");
        volumeDown.setOnClickListener(v -> adjustVolume(-1));
        volumeRail.addView(volumeDown, new LinearLayout.LayoutParams(dp(44), dp(42)));
        FrameLayout.LayoutParams volumeParams = new FrameLayout.LayoutParams(dp(48), dp(250), Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        volumeParams.rightMargin = dp(12);
        controlsOverlay.addView(volumeRail, volumeParams);

        timelineRow = horizontalRow();
        timelineRow.setPadding(dp(22), dp(1), dp(22), dp(1));
        FrameLayout.LayoutParams timelineParams = new FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM);
        timelineParams.bottomMargin = dp(64);
        controlsOverlay.addView(timelineRow, timelineParams);

        currentTimeText = new TextView(this);
        currentTimeText.setTextColor(Color.WHITE);
        currentTimeText.setTextSize(12);
        currentTimeText.setText("0:00");
        timelineRow.addView(currentTimeText, new LinearLayout.LayoutParams(-2, -2));

        timelineSeekBar = new SeekBar(this);
        timelineSeekBar.setMax(1);
        timelineSeekBar.setProgress(0);
        timelineSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(91, 190, 255)));
        timelineSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        timelineSeekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x99FFFFFF));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, dp(32), 1f);
        seekParams.setMargins(dp(10), 0, dp(10), 0);
        timelineRow.addView(timelineSeekBar, seekParams);
        timelineSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && currentTimeText != null) currentTimeText.setText(formatClock(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (controller != null) controller.seekTo(bar.getProgress());
            }
        });

        durationTimeText = new TextView(this);
        durationTimeText.setTextColor(Color.WHITE);
        durationTimeText.setTextSize(12);
        durationTimeText.setText("0:00");
        timelineRow.addView(durationTimeText, new LinearLayout.LayoutParams(-2, -2));

        bottomRow = horizontalRow();
        bottomRow.setPadding(dp(12), 0, dp(12), dp(2));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM);
        controlsOverlay.addView(bottomRow, bottomParams);

        LinearLayout leftTools = new LinearLayout(this);
        leftTools.setOrientation(LinearLayout.HORIZONTAL);
        leftTools.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.addView(leftTools, new LinearLayout.LayoutParams(0, -1, 1f));
        lockButton = overlayButton("icon:lock", 21);
        lockButton.setContentDescription("Lock playback controls");
        lockButton.setOnClickListener(v -> toggleLock());
        leftTools.addView(lockButton, new LinearLayout.LayoutParams(dp(40), -1));
        Button rotate = overlayButton("icon:rotate", 23);
        rotate.setContentDescription("Rotate screen");
        rotate.setOnClickListener(v -> {
            int orientation = getResources().getConfiguration().orientation;
            setRequestedOrientation(orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        });
        leftTools.addView(rotate, new LinearLayout.LayoutParams(dp(40), -1));
        Button playlist = overlayButton("icon:playlist", 23);
        playlist.setContentDescription("Playback list");
        playlist.setOnClickListener(v -> showPlaylistMenu());
        leftTools.addView(playlist, new LinearLayout.LayoutParams(dp(40), -1));

        centerControls = new LinearLayout(this);
        centerControls.setOrientation(LinearLayout.HORIZONTAL);
        centerControls.setGravity(Gravity.CENTER);
        bottomRow.addView(centerControls, new LinearLayout.LayoutParams(-2, -1));
        Button rewind = overlayButton("icon:rewind", 16);
        rewind.setContentDescription("Rewind 10 seconds");
        rewind.setOnClickListener(v -> seekBy(-10_000L));
        centerControls.addView(rewind, new LinearLayout.LayoutParams(dp(64), -1));
        playPauseButton = overlayButton("icon:pause", 30);
        playPauseButton.setContentDescription("Play or pause");
        playPauseButton.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause(); else controller.play();
            showPlaybackControlsTemporarily();
        });
        centerControls.addView(playPauseButton, new LinearLayout.LayoutParams(dp(58), -1));
        Button forward = overlayButton("icon:forward", 16);
        forward.setContentDescription("Forward 10 seconds");
        forward.setOnClickListener(v -> seekBy(10_000L));
        centerControls.addView(forward, new LinearLayout.LayoutParams(dp(58), -1));

        LinearLayout rightTools = new LinearLayout(this);
        rightTools.setOrientation(LinearLayout.HORIZONTAL);
        rightTools.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        bottomRow.addView(rightTools, new LinearLayout.LayoutParams(0, -1, 1f));
        speedButton = overlayButton("1.0x", 13);
        speedButton.setContentDescription("Playback speed");
        speedButton.setOnClickListener(v -> cycleSpeed());
        rightTools.addView(speedButton, new LinearLayout.LayoutParams(dp(48), -1));
        Button headphones = overlayButton("icon:headphones", 22);
        headphones.setContentDescription("Audio track options");
        headphones.setOnClickListener(v -> showTrackMenu());
        rightTools.addView(headphones, new LinearLayout.LayoutParams(dp(38), -1));
        Button pip = overlayButton("icon:pip", 19);
        pip.setContentDescription("Picture in Picture");
        pip.setOnClickListener(v -> enterPipIfSupported());
        rightTools.addView(pip, new LinearLayout.LayoutParams(dp(38), -1));
        Button fullscreen = overlayButton("icon:fullscreen", 23);
        fullscreen.setContentDescription("Toggle fit and fill");
        fullscreen.setOnClickListener(v -> cycleZoom());
        rightTools.addView(fullscreen, new LinearLayout.LayoutParams(dp(38), -1));

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13);
        hint.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(15), dp(10), dp(15), dp(10));
        hint.setBackground(rounded(0xAA101018, 0, 16));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        controlsOverlay.addView(hint, hintParams);

        lockOverlay = new FrameLayout(this);
        lockOverlay.setBackgroundColor(0x33000000);
        lockOverlay.setVisibility(View.GONE);
        Button unlock = overlayButton("Unlock playback", 14);
        unlock.setCompoundDrawablesWithIntrinsicBounds(new PlaybackIconDrawable("unlock"), null, null, null);
        unlock.setOnClickListener(v -> toggleLock());
        FrameLayout.LayoutParams unlockParams = new FrameLayout.LayoutParams(-2, dp(52), Gravity.CENTER);
        lockOverlay.addView(unlock, unlockParams);
        root.addView(lockOverlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        installGestures();
        controlsHandler.post(updateTimeline);
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        // Capture this connection's future. The Activity can stop and reconnect before
        // MediaController finishes connecting; reading the mutable field in the callback
        // could otherwise bind a stale callback to a newer connection attempt.
        final ListenableFuture<MediaController> future =
                new MediaController.Builder(this, token).buildAsync();
        controllerFuture = future;
        future.addListener(() -> {
            if (controllerFuture != future || isFinishing() || isDestroyed()) return;
            try {
                MediaController connectedController = future.get();
                if (controllerFuture != future || isFinishing() || isDestroyed()) return;
                controller = connectedController;
                playerView.setPlayer(connectedController);
                connectedController.addListener(playbackUiListener);
                syncSpeedButton(connectedController.getPlaybackParameters().speed);
                initializeMedia();
                controlsHandler.removeCallbacks(savePlaybackProgress);
                controlsHandler.postDelayed(savePlaybackProgress, PROGRESS_SAVE_INTERVAL_MS);
                if (!requestedAudio) showPlaybackControlsTemporarily();
            } catch (Exception e) {
                if (controllerFuture == future && !isFinishing() && !isDestroyed()) {
                    showHint("Could not start the playback engine");
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void showPlaybackFailure(androidx.media3.common.PlaybackException error) {
        String detail = error.getErrorCodeName() == null ? "unknown decoder or media error" : error.getErrorCodeName();
        android.util.Log.e("LUMIO_PLAYBACK", "Media3 playback failed for URI=" + requestedUri + ", code=" + detail, error);
        showHint("Playback failed: " + detail);
        if (!isFinishing() && !isDestroyed()) {
            new AlertDialog.Builder(PlayerActivity.this).setTitle("Video playback failed")
                    .setMessage("Media3 could not play this video. Error: " + detail + "\n\n"
                            + (error.getMessage() == null ? "No additional details." : error.getMessage()))
                    .setPositiveButton("OK", null).show();
        }
    }

    private void initializeMedia() {
        if (controller == null || requestedUri == null || requestedUri.isEmpty()) return;
        MediaItem current = controller.getCurrentMediaItem();
        if (current != null && current.localConfiguration != null
                && requestedUri.equals(current.localConfiguration.uri.toString())) {
            titleText.setText(requestedTitle == null ? "Lumio Player" : requestedTitle);
            // Reopening the currently loaded item should resume it. If it already ended,
            // restart at the beginning rather than leaving the new PlayerActivity paused at EOF.
            if (controller.getPlaybackState() == Player.STATE_ENDED) controller.seekTo(0L);
            controller.play();
            return;
        }

        PlaybackService.setVolumeBoost(prefs.getFloat("volume_boost", 1.0f));
        List<MediaItem> playlist = new ArrayList<>();
        int selectedIndex = 0;
        String queueJson = getIntent().getStringExtra(EXTRA_PLAYLIST);
        if (queueJson != null) {
            try {
                JSONArray rows = new JSONArray(queueJson);
                int count = Math.min(rows.length(), 500);
                for (int i = 0; i < count; i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String uriText = row.optString("uri", "");
                    if (uriText.isEmpty()) continue;
                    String name = row.optString("name", "Local media");
                    MediaItem item = new MediaItem.Builder()
                            .setUri(Uri.parse(uriText))
                            .setMediaId(uriText)
                            .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder().setTitle(name).build())
                            .build();
                    if (requestedUri.equals(uriText)) selectedIndex = playlist.size();
                    playlist.add(item);
                }
            } catch (Exception ignored) {
                playlist.clear();
            }
        }
        if (playlist.isEmpty()) {
            MediaItem item = new MediaItem.Builder()
                    .setUri(Uri.parse(requestedUri))
                    .setMediaId(requestedUri)
                    .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(requestedTitle == null ? "Local media" : requestedTitle)
                            .build())
                    .build();
            playlist.add(item);
            selectedIndex = 0;
        }

        // Resume each media item independently, not only the most recently opened URI.
        // Completed items are saved at position zero so reopening them starts from the beginning.
        long startPosition = Math.max(0L, prefs.getLong("position:" + requestedUri, 0L));
        showLoadingOverlay();
        controller.setMediaItems(playlist, selectedIndex, startPosition);
        controller.prepare();
        controller.play();
        titleText.setText(requestedTitle == null ? "Lumio Player" : requestedTitle);
    }

    private void persistPosition() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        // Private Vault playback uses an ephemeral decrypted cache file. Do not keep a
        // dangling resume-history entry after that temporary file is deleted.
        String temporaryVaultPath = getIntent().getStringExtra(EXTRA_TEMP_VAULT_FILE);
        if (temporaryVaultPath != null && !temporaryVaultPath.trim().isEmpty()) return;

        MediaItem current = controller.getCurrentMediaItem();
        String uri = current.localConfiguration == null ? "" : current.localConfiguration.uri.toString();
        if (uri.isEmpty()) return;

        long position = Math.max(0L, controller.getCurrentPosition());
        long duration = controller.getDuration();
        if (duration < 0L) duration = 0L;
        boolean completed = controller.getPlaybackState() == Player.STATE_ENDED
                || (duration > 0L && position >= Math.max(0L, duration - Math.min(10_000L, duration / 50L)));
        long savedPosition = completed ? 0L : position;

        CharSequence mediaTitle = current.mediaMetadata == null ? null : current.mediaMetadata.title;
        String title = mediaTitle == null || mediaTitle.toString().trim().isEmpty()
                ? (requestedTitle == null || requestedTitle.trim().isEmpty() ? "Local media" : requestedTitle)
                : mediaTitle.toString();

        JSONArray history = new JSONArray();
        try {
            JSONArray previous = new JSONArray(prefs.getString(WATCH_HISTORY_KEY, "[]"));
            JSONObject entry = new JSONObject();
            entry.put("uri", uri);
            entry.put("title", title);
            entry.put("isAudio", requestedAudio);
            entry.put("positionMs", savedPosition);
            entry.put("durationMs", duration);
            entry.put("lastPlayedAt", System.currentTimeMillis());
            entry.put("completed", completed);
            String identity = prefs.getString("identity:" + uri, "");
            if ((identity == null || identity.isEmpty()) && uri.equals(requestedUri)) {
                identity = getIntent().getStringExtra(EXTRA_MEDIA_IDENTITY);
                if (identity == null) identity = "";
            }
            if (!identity.isEmpty()) {
                prefs.edit().putString("identity:" + uri, identity).apply();
                entry.put("identity", identity);
            }
            history.put(entry);

            for (int i = 0; i < previous.length() && history.length() < MAX_WATCH_HISTORY; i++) {
                JSONObject old = previous.optJSONObject(i);
                if (old == null || uri.equals(old.optString("uri", ""))) continue;
                history.put(old);
            }
        } catch (Exception ignored) {
            // If old history is malformed, retain the current entry rather than breaking playback.
        }

        prefs.edit()
                .putString("last_uri", uri)
                .putLong("position:" + uri, savedPosition)
                .putString(WATCH_HISTORY_KEY, history.toString())
                .apply();
    }

    @Override
    protected void onPause() {
        persistPosition();
        super.onPause();
    }

    @Override
    protected void onStop() {
        persistPosition();
        // Prevent audio-only playback after leaving the player; preserve active PiP playback.
        if (!isInPictureInPictureMode() && controller != null) {
            controller.pause();
            controller.clearMediaItems();
        }
        controlsHandler.removeCallbacks(hidePlaybackControls);
        controlsHandler.removeCallbacks(finishLoadingOverlay);
        controlsHandler.removeCallbacks(savePlaybackProgress);
        if (controller != null) controller.removeListener(playbackUiListener);
        if (playerView != null) playerView.setPlayer(null);
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
            controller = null;
        }
        super.onStop();
    }

    private void syncSpeedButton(float speed) {
        int closestIndex = 0;
        float closestDifference = Float.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            float difference = Math.abs(SPEEDS[i] - speed);
            if (difference < closestDifference) {
                closestDifference = difference;
                closestIndex = i;
            }
        }
        speedIndex = closestIndex;
        if (speedButton != null) speedButton.setText(trimSpeed(speed) + "×");
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float speed = SPEEDS[speedIndex];
        if (controller == null) return;
        controller.setPlaybackParameters(new PlaybackParameters(speed));
        speedButton.setText(trimSpeed(speed) + "×");
        showHint("Playback " + trimSpeed(speed) + "×");
    }

    private String trimSpeed(float speed) {
        if (speed == (int) speed) return Integer.toString((int) speed);
        return Float.toString(speed);
    }

    private void cycleZoom() {
        zoom = zoom == 1f ? 1.25f : zoom == 1.25f ? 1.5f : zoom == 1.5f ? 2f : 1f;
        if (contentFrame != null) {
            contentFrame.setScaleX(zoom);
            contentFrame.setScaleY(zoom);
            if (zoom == 1f) { panX = 0f; panY = 0f; }
            contentFrame.setTranslationX(panX);
            contentFrame.setTranslationY(panY);
        }
        zoomButton.setText(zoom == 1f ? "Fit" : zoom + "×");
        showHint(zoom == 1f ? "Fit to screen" : "Zoom " + zoom + "×");
    }

    private void toggleLock() {
        locked = !locked;
        lockOverlay.setVisibility(locked ? View.VISIBLE : View.GONE);
        playerView.setUseController(!locked);
        lockButton.setText(locked ? "Locked" : "Lock");
        if (locked) {
            if (!requestedAudio) setPlaybackControlsVisible(false);
        } else if (!requestedAudio) {
            showPlaybackControlsTemporarily();
        }
        showHint(locked ? "Touch controls locked" : "Touch controls unlocked");
    }

    private boolean isInPictureInPicture() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode();
    }

    private void hidePlaybackControlsIfNeeded() {
        if (requestedAudio || locked || isInPictureInPicture()) return;
        if (playerView != null) playerView.hideController();
        setPlaybackControlsVisible(false);
    }

    private void togglePlaybackControls() {
        if (requestedAudio || locked || isInPictureInPicture()) return;
        if (playbackControlsVisible) {
            controlsHandler.removeCallbacks(hidePlaybackControls);
            if (playerView != null) playerView.hideController();
            setPlaybackControlsVisible(false);
        } else {
            showPlaybackControlsTemporarily();
        }
    }

    private void setPlaybackControlsVisible(boolean visible) {
        playbackControlsVisible = visible;
        if (topBar == null) return;
        View[] layers = new View[] { topBar, timelineRow, bottomRow };
        for (View layer : layers) {
            if (layer == null) continue;
            layer.animate().cancel();
            if (visible) {
                layer.setVisibility(View.VISIBLE);
                layer.setAlpha(1f);
            } else {
                layer.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
                    if (!playbackControlsVisible) layer.setVisibility(View.GONE);
                }).start();
            }
        }
        if (controlsOverlay != null) {
            for (int i = 0; i < controlsOverlay.getChildCount(); i++) {
                View child = controlsOverlay.getChildAt(i);
                if (child == topBar || child == timelineRow || child == bottomRow || child == hint) continue;
                child.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        }
    }
    private void showPlaybackControlsTemporarily() {
        if (requestedAudio || isInPictureInPicture() || locked) return;
        controlsHandler.removeCallbacks(hidePlaybackControls);
        setPlaybackControlsVisible(true);
        if (playerView != null) playerView.showController();
        controlsHandler.postDelayed(hidePlaybackControls, CONTROLS_AUTO_HIDE_MS);
    }

    private void showPlaybackControlsUntilResumed() {
        showPlaybackControlsTemporarily();
    }

    private void installGestures() {
        android.view.GestureDetector gestureDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { touchDownX = e.getX(); touchDownY = e.getY(); gestureHandled = false; return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (locked) return false;
                        // A tap toggles the whole control layer: reveal if hidden, fade if visible.
                        togglePlaybackControls();
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (locked) return false;
                        showPlaybackControlsTemporarily();
                        boolean forward = e.getX() >= playerView.getWidth() / 2f;
                        if (controller == null || controller.getDuration() <= 0) return false;
                        long target = controller.getCurrentPosition() + (forward ? 10_000L : -10_000L);
                        controller.seekTo(Math.max(0L, Math.min(controller.getDuration(), target)));
                        showHint(forward ? "+10 seconds" : "−10 seconds");
                        gestureHandled = true;
                        return true;
                    }
                    @Override public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
                        if (locked || controller == null || first == null || scaling) return false;
                        if (Math.abs(current.getX() - first.getX()) < dp(14)
                                && Math.abs(current.getY() - first.getY()) < dp(14)) return false;
                        gestureHandled = true;
                        if (zoom > 1f) {
                            panX = clamp(panX - distanceX, -dp(160) * (zoom - 1f), dp(160) * (zoom - 1f));
                            panY = clamp(panY - distanceY, -dp(120) * (zoom - 1f), dp(120) * (zoom - 1f));
                            contentFrame.setTranslationX(panX);
                            contentFrame.setTranslationY(panY);
                            return true;
                        }
                        if (Math.abs(distanceX) > Math.abs(distanceY)) {
                            long duration = controller.getDuration();
                            if (duration > 0) {
                                long delta = (long) (-distanceX / Math.max(1, playerView.getWidth()) * duration * 1.4f);
                                long target = Math.max(0L, Math.min(duration, controller.getCurrentPosition() + delta));
                                controller.seekTo(target);
                                showHint((delta >= 0 ? "+" : "") + Math.round(delta / 1000f) + "s");
                            }
                        } else {
                            float fraction = distanceY / Math.max(1, playerView.getHeight());
                            if (first.getX() < playerView.getWidth() / 2f) {
                                brightness = clamp(brightness + fraction * 1.25f, 0.08f, 1f);
                                WindowManager.LayoutParams attrs = getWindow().getAttributes();
                                attrs.screenBrightness = brightness;
                                getWindow().setAttributes(attrs);
                                showHint("Brightness " + Math.round(brightness * 100) + "%");
                            } else {
                                int max = audioManager == null ? 15 : audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                                int currentVolume = audioManager == null ? 5 : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                                int change = Math.abs(fraction) < 0.01f ? 0 : (fraction > 0 ? 1 : -1);
                                int target = Math.max(0, Math.min(max, currentVolume + change));
                                if (audioManager != null && target != currentVolume) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                                showHint("Volume " + Math.round(target * 100f / Math.max(1, max)) + "%");
                            }
                        }
                        return true;
                    }
                });
        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                        if (locked) return false;
                        scaling = true;
                        return true;
                    }
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        zoom = clamp(zoom * detector.getScaleFactor(), 1f, 3f);
                        if (hasSelectedTextTrack()) {
                            subtitleTextSize = Math.max(14, Math.min(38,
                                    subtitleTextSize + (detector.getScaleFactor() > 1f ? 1 : -1)));
                            applySubtitleAppearance();
                            prefs.edit().putInt("subtitle_size", subtitleTextSize).apply();
                        }
                        if (contentFrame != null) {
                            contentFrame.setScaleX(zoom);
                            contentFrame.setScaleY(zoom);
                        }
                        zoomButton.setText(zoom == 1f ? "Fit" : String.format(Locale.US, "%.1f×", zoom));
                        return true;
                    }
                    @Override public void onScaleEnd(ScaleGestureDetector detector) {
                        scaling = false;
                        showHint("Zoom " + String.format(Locale.US, "%.1f×", zoom));
                    }
                });
        View gestureSurface = contentFrame == null ? playerView : contentFrame;
        // Consume the complete gesture stream so single taps, double taps, and swipes
        // are all delivered reliably even when the video surface itself is not clickable.
        gestureSurface.setClickable(true);
        gestureSurface.setOnTouchListener((v, event) -> {
            if (locked) return true;
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && gestureHandled) {
                v.performClick();
            }
            return true;
        });
    }

    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private void showHint(String message) {
        if (hint == null) return;
        hint.removeCallbacks(hideHint);
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        hint.postDelayed(hideHint, 1100L);
    }
    private final Runnable hideHint = () -> { if (hint != null) hint.setVisibility(View.GONE); };

    private void showTrackMenu() {
        if (controller == null) { showHint("Player is loading"); return; }
        Tracks tracks = controller.getCurrentTracks();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Audio: Automatic");
        actions.add(() -> {
            TrackSelectionParameters.Builder b = controller.getTrackSelectionParameters().buildUpon();
            b.clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
            controller.setTrackSelectionParameters(b.build());
        });
        for (int gi = 0; gi < tracks.getGroups().size(); gi++) {
            Tracks.Group group = tracks.getGroups().get(gi);
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int ti = 0; ti < group.length; ti++) {
                if (!group.isTrackSupported(ti)) continue;
                final Tracks.Group selectedGroup = group;
                final int selectedTrack = ti;
                String language = selectedGroup.getTrackFormat(ti).language;
                String label = selectedGroup.getTrackFormat(ti).label;
                if (label == null || label.isEmpty()) label = readableLanguage(language, "Audio track");
                labels.add(label);
                actions.add(() -> {
                    TrackSelectionParameters.Builder b = controller.getTrackSelectionParameters().buildUpon();
                    b.clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
                    b.addOverride(new TrackSelectionOverride(selectedGroup.getMediaTrackGroup(), selectedTrack));
                    controller.setTrackSelectionParameters(b.build());
                });
            }
        }
        labels.add("Volume boost settings");
        actions.add(this::showVolumeBoost);
        labels.add("Audio/video sync offset");
        actions.add(this::showAvSyncDialog);
        labels.add("Decoder preference");
        actions.add(this::showDecoderMode);
        new AlertDialog.Builder(this).setTitle("Audio and playback settings").setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Close", null).show();
    }

    private void showAvSyncDialog() {
        int savedDelay = prefs.getInt("av_delay_ms", 0);
        final SeekBar seek = new SeekBar(this);
        seek.setMax(80);
        seek.setProgress(Math.max(0, Math.min(80, Math.round(savedDelay / 50f) + 40)));
        final TextView value = new TextView(this);
        value.setTextColor(Color.WHITE);
        value.setTextSize(15);
        value.setPadding(0, 0, 0, dp(8));
        Runnable updateLabel = () -> {
            int milliseconds = (seek.getProgress() - 40) * 50;
            String sign = milliseconds > 0 ? "+" : "";
            String direction = milliseconds > 0 ? "later" : milliseconds < 0 ? "earlier" : "neutral";
            value.setText("Audio offset: " + sign + milliseconds + " ms (" + direction + ")");
        };
        updateLabel.run();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { updateLabel.run(); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(4));
        content.addView(value, new LinearLayout.LayoutParams(-1, -2));
        content.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Audio/video synchronization")
                .setMessage("Adjust audio timestamps in 50 ms steps. Positive values shift audio later; negative values shift it earlier. Device/codec behavior can vary.")
                .setView(content)
                .setNeutralButton("Reset", (dialog, which) -> {
                    prefs.edit().putInt("av_delay_ms", 0).apply();
                    PlaybackService.setAudioDelayMillis(0);
                    showHint("Audio sync reset");
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    int delay = (seek.getProgress() - 40) * 50;
                    prefs.edit().putInt("av_delay_ms", delay).apply();
                    PlaybackService.setAudioDelayMillis(delay);
                    showHint("Audio offset " + (delay > 0 ? "+" : "") + delay + " ms");
                }).show();
    }

    private void showDecoderMode() {
        String[] choices = {"Automatic (recommended)", "Hardware preferred", "Software preferred"};
        String saved = prefs.getString("decoder_mode", "auto");
        int selected = "hardware".equals(saved) ? 1 : "software".equals(saved) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("Decoder preference")
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    String mode = which == 1 ? "hardware" : which == 2 ? "software" : "auto";
                    prefs.edit().putString("decoder_mode", mode).apply();
                    PlaybackService.setDecoderMode(mode);
                    dialog.dismiss();
                    showHint("Decoder preference: " + choices[which]);
                })
                .setMessage("The player prefers the selected decoder type and keeps a fallback list for compatibility. HW+ is vendor-specific, so Lumio uses Android codec selection rather than copying another player's mode names.")
                .setNegativeButton("Close", null)
                .show();
    }

    private void showVolumeBoost() {
        int currentPercent = Math.round((prefs.getFloat("volume_boost", 1.0f) - 1.0f) * 100f);
        final SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(Math.max(0, Math.min(100, currentPercent)));
        final TextView value = new TextView(this);
        value.setTextColor(Color.WHITE);
        value.setTextSize(15);
        value.setPadding(0, 0, 0, dp(8));
        value.setText((100 + seek.getProgress()) + "% output gain");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText((100 + progress) + "% output gain");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(4));
        content.addView(value, new LinearLayout.LayoutParams(-1, -2));
        content.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Volume boost")
                .setMessage("Boost decoded PCM audio up to 200%. Strong boost may distort quiet or already loud recordings.")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    float factor = 1.0f + seek.getProgress() / 100f;
                    prefs.edit().putFloat("volume_boost", factor).apply();
                    PlaybackService.setVolumeBoost(factor);
                    showHint("Volume boost " + Math.round(factor * 100f) + "%");
                }).show();
    }

    private String readableLanguage(String language, String fallback) {
        if (language == null || language.trim().isEmpty() || "und".equals(language)) return fallback;
        Locale locale = Locale.forLanguageTag(language);
        String name = locale.getDisplayLanguage();
        return name == null || name.isEmpty() ? language : name;
    }

    private void showSubtitleMenu() {
        if (controller == null) { showHint("Player is loading"); return; }
        Tracks tracks = controller.getCurrentTracks();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Subtitles off");
        actions.add(() -> {
            TrackSelectionParameters.Builder b = controller.getTrackSelectionParameters().buildUpon();
            b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            controller.setTrackSelectionParameters(b.build());
        });
        for (int gi = 0; gi < tracks.getGroups().size(); gi++) {
            Tracks.Group group = tracks.getGroups().get(gi);
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int ti = 0; ti < group.length; ti++) {
                if (!group.isTrackSupported(ti)) continue;
                final Tracks.Group selectedGroup = group;
                final int selectedTrack = ti;
                String language = selectedGroup.getTrackFormat(ti).language;
                String label = selectedGroup.getTrackFormat(ti).label;
                if (label == null || label.isEmpty()) label = readableLanguage(language, "Subtitle track");
                labels.add(label);
                actions.add(() -> {
                    TrackSelectionParameters.Builder b = controller.getTrackSelectionParameters().buildUpon();
                    b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
                    b.addOverride(new TrackSelectionOverride(selectedGroup.getMediaTrackGroup(), selectedTrack));
                    controller.setTrackSelectionParameters(b.build());
                });
            }
        }
        labels.add("Load external subtitle (SRT/VTT/ASS/SSA)");
        actions.add(this::pickSubtitle);
        labels.add("Subtitle appearance and size");
        actions.add(this::showSubtitleAppearance);
        new AlertDialog.Builder(this).setTitle("Subtitles").setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Close", null).show();
    }

    private void showSubtitleAppearance() {
        String[] choices = {
                "Text size: Small",
                "Text size: Medium",
                "Text size: Large",
                "Text size: Extra large",
                "White text with outline",
                "Yellow text with shadow",
                "White text on translucent black",
                "Serif white text",
                "Monospace yellow text",
                "Android caption defaults"
        };
        new AlertDialog.Builder(this).setTitle("Subtitle appearance")
                .setItems(choices, (dialog, which) -> {
                    if (which <= 3) {
                        int[] sizes = {16, 22, 28, 34};
                        subtitleTextSize = sizes[which];
                        prefs.edit().putInt("subtitle_size", subtitleTextSize).apply();
                    } else if (which == 4) subtitleStylePreset = 0;
                    else if (which == 5) subtitleStylePreset = 1;
                    else if (which == 6) subtitleStylePreset = 2;
                    else if (which == 7) subtitleStylePreset = 3;
                    else if (which == 8) subtitleStylePreset = 4;
                    else subtitleStylePreset = 5;
                    prefs.edit().putInt("subtitle_style", subtitleStylePreset).apply();
                    applySubtitleAppearance();
                    showHint("Subtitle style updated");
                }).setNegativeButton("Close", null).show();
    }

    private boolean hasSelectedTextTrack() {
        if (controller == null) return false;
        Tracks tracks = controller.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return true;
            }
        }
        return false;
    }

    private void applySubtitleAppearance() {
        if (playerView == null) return;
        SubtitleView subtitle = playerView.getSubtitleView();
        if (subtitle == null) return;
        subtitle.setApplyEmbeddedStyles(false);
        subtitle.setApplyEmbeddedFontSizes(false);
        subtitle.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleTextSize);
        CaptionStyleCompat style;
        switch (subtitleStylePreset) {
            case 1:
                style = new CaptionStyleCompat(Color.YELLOW, Color.TRANSPARENT, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, Typeface.DEFAULT);
                break;
            case 2:
                style = new CaptionStyleCompat(Color.WHITE, 0xAA000000, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, Typeface.DEFAULT_BOLD);
                break;
            case 3:
                style = new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.SERIF);
                break;
            case 4:
                style = new CaptionStyleCompat(Color.YELLOW, 0x99000000, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, Typeface.MONOSPACE);
                break;
            case 5:
                subtitle.setUserDefaultStyle();
                subtitle.setUserDefaultTextSize();
                return;
            default:
                style = new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT);
                break;
        }
        subtitle.setStyle(style);
    }

    private void pickSubtitle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-subrip", "text/vtt", "text/x-ssa", "text/plain", "application/octet-stream"});
        try { startActivityForResult(intent, REQUEST_SUBTITLE); }
        catch (Exception e) { showHint("No document picker available"); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SUBTITLE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        // Document providers often return an opaque URI segment (for example
        // "document:primary:Movies%2Fmovie.srt"). Prefer the actual display name
        // so subtitle format detection works with Files, Drive, and other providers.
        String name = uri.getLastPathSegment() == null ? "subtitle" : Uri.decode(uri.getLastPathSegment());
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0 && cursor.getString(nameColumn) != null) {
                    name = cursor.getString(nameColumn);
                }
            }
        } catch (Exception ignored) { }
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mime;
        if ("srt".equals(ext)) mime = MimeTypes.APPLICATION_SUBRIP;
        else if ("vtt".equals(ext)) mime = MimeTypes.TEXT_VTT;
        else if ("ass".equals(ext) || "ssa".equals(ext)) mime = MimeTypes.TEXT_SSA;
        else if ("sub".equals(ext)) {
            generatedSubtitleFile = convertMicroDvdSubtitle(uri, 25.0);
            if (generatedSubtitleFile == null) { showHint("Could not read this SUB subtitle"); return; }
            uri = Uri.fromFile(generatedSubtitleFile);
            mime = MimeTypes.APPLICATION_SUBRIP;
        } else { showHint("Use SRT, VTT, SUB, ASS or SSA subtitles"); return; }
        addSubtitle(uri, mime);
    }

    private File convertMicroDvdSubtitle(Uri uri, double framesPerSecond) {
        if (framesPerSecond < 1.0 || framesPerSecond > 120.0) framesPerSecond = 25.0;
        File output = new File(getCacheDir(), "lumio_sub_" + System.currentTimeMillis() + ".srt");
        Pattern cuePattern = Pattern.compile("^\\s*\\{(\\d+)\\}\\{(\\d+)\\}(.*)$");
        int index = 1;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                     getContentResolver().openInputStream(uri), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(output), StandardCharsets.UTF_8))) {
            if (reader == null) return null;
            String line;
            boolean wrote = false;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = cuePattern.matcher(line);
                if (!matcher.matches()) continue;
                long startFrame = Long.parseLong(matcher.group(1));
                long endFrame = Long.parseLong(matcher.group(2));
                if (endFrame <= startFrame) continue;
                String text = matcher.group(3).replace("|", "\n").trim();
                if (text.isEmpty()) continue;
                long startMs = Math.round(startFrame * 1000.0 / framesPerSecond);
                long endMs = Math.round(endFrame * 1000.0 / framesPerSecond);
                writer.write(Integer.toString(index++)); writer.newLine();
                writer.write(srtTime(startMs) + " --> " + srtTime(endMs)); writer.newLine();
                writer.write(text); writer.newLine(); writer.newLine();
                wrote = true;
            }
            writer.flush();
            if (!wrote) { output.delete(); return null; }
            return output;
        } catch (Exception e) {
            output.delete();
            return null;
        }
    }

    private String srtTime(long milliseconds) {
        long value = Math.max(0L, milliseconds);
        long hours = value / 3600000L;
        long minutes = (value / 60000L) % 60L;
        long seconds = (value / 1000L) % 60L;
        long millis = value % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    private void addSubtitle(Uri uri, String mime) {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        MediaItem current = controller.getCurrentMediaItem();
        ArrayList<MediaItem.SubtitleConfiguration> configurations = new ArrayList<>();
        if (current.localConfiguration != null) configurations.addAll(current.localConfiguration.subtitleConfigurations);
        configurations.add(new MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime).setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
        long position = controller.getCurrentPosition();
        boolean play = controller.getPlayWhenReady();
        MediaItem updated = current.buildUpon().setSubtitleConfigurations(configurations).build();
        controller.setMediaItem(updated, position);
        controller.prepare();
        controller.setPlayWhenReady(play);
        showHint("Subtitle added");
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPipIfSupported();
    }

    private void enterPipIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || controller == null || !controller.isPlaying()) return;
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true);
            enterPictureInPictureMode(builder.build());
        } catch (Exception ignored) { }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        controlsHandler.removeCallbacks(hidePlaybackControls);
        if (isInPictureInPictureMode) {
            if (playerView != null) playerView.setUseController(true);
            setPlaybackControlsVisible(false);
        } else if (!requestedAudio) {
            applyImmersivePlayback();
            if (controller != null && controller.isPlaying()) showPlaybackControlsTemporarily();
            else showPlaybackControlsUntilResumed();
        } else {
            setPlaybackControlsVisible(true);
        }
    }

    @Override
    protected void onDestroy() {
        controlsHandler.removeCallbacks(hidePlaybackControls);
        controlsHandler.removeCallbacks(savePlaybackProgress);
        persistPosition();
        if (isFinishing()) {
            String temporaryPath = getIntent().getStringExtra(EXTRA_TEMP_VAULT_FILE);
            if (temporaryPath != null && !temporaryPath.trim().isEmpty()) new java.io.File(temporaryPath).delete();
            if (generatedSubtitleFile != null) generatedSubtitleFile.delete();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
            controller = null;
        }
        super.onDestroy();
    }
}
