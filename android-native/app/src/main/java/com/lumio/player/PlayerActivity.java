package com.lumio.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
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
import android.media.AudioManager;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.MimeTypes;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PlayerActivity extends Activity {
    public static final String EXTRA_URI = "media_uri";
    public static final String EXTRA_TITLE = "media_title";
    public static final String EXTRA_AUDIO = "media_is_audio";
    private static final int REQUEST_SUBTITLE = 4301;
    private static final String PREFS = "lumio_playback";
    private static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};

    private PlayerView playerView;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private TextView hint;
    private TextView titleText;
    private Button speedButton;
    private Button zoomButton;
    private Button lockButton;
    private FrameLayout contentFrame;
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
        buildPlayerUi();
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

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildPlayerUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 8, 13));

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setBackgroundColor(Color.BLACK);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(10), dp(10), dp(10));
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackground(rounded(0xDD101018, 0, 0));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(62), Gravity.TOP);
        root.addView(topBar, topParams);

        Button back = button("‹");
        back.setTextSize(26);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(42), -1));

        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(13);
        titleText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleText.setText(requestedTitle == null ? "Lumio Player" : requestedTitle);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMargins(dp(9), 0, dp(4), 0);
        topBar.addView(titleText, titleParams);

        Button tracks = button("Tracks");
        tracks.setOnClickListener(v -> showTrackMenu());
        topBar.addView(tracks, new LinearLayout.LayoutParams(-2, dp(40)));

        Button cc = button("CC");
        cc.setOnClickListener(v -> showSubtitleMenu());
        LinearLayout.LayoutParams smallGap = new LinearLayout.LayoutParams(-2, dp(40));
        smallGap.setMargins(dp(4), 0, 0, 0);
        topBar.addView(cc, smallGap);

        speedButton = button("1×");
        speedButton.setOnClickListener(v -> cycleSpeed());
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(-2, dp(40));
        speedParams.setMargins(dp(4), 0, 0, 0);
        topBar.addView(speedButton, speedParams);

        zoomButton = button("Fit");
        zoomButton.setOnClickListener(v -> cycleZoom());
        LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(-2, dp(40));
        zoomParams.setMargins(dp(4), 0, 0, 0);
        topBar.addView(zoomButton, zoomParams);

        lockButton = button("Lock");
        lockButton.setOnClickListener(v -> toggleLock());
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(-2, dp(40));
        lockParams.setMargins(dp(4), 0, 0, 0);
        topBar.addView(lockButton, lockParams);

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13);
        hint.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(15), dp(10), dp(15), dp(10));
        hint.setBackground(rounded(0xE6222030, 0x665C547A, 20));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(hint, hintParams);

        lockOverlay = new FrameLayout(this);
        lockOverlay.setBackgroundColor(0x55000000);
        lockOverlay.setVisibility(View.GONE);
        Button unlock = button("🔒  Unlock playback");
        unlock.setTextSize(14);
        unlock.setOnClickListener(v -> toggleLock());
        FrameLayout.LayoutParams unlockParams = new FrameLayout.LayoutParams(-2, dp(52), Gravity.CENTER);
        lockOverlay.addView(unlock, unlockParams);
        root.addView(lockOverlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        installGestures();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                playerView.setPlayer(controller);
                initializeMedia();
            } catch (Exception e) {
                showHint("Could not start the playback engine");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void initializeMedia() {
        if (controller == null || requestedUri == null || requestedUri.isEmpty()) return;
        MediaItem current = controller.getCurrentMediaItem();
        if (current != null && current.localConfiguration != null
                && requestedUri.equals(current.localConfiguration.uri.toString())) {
            titleText.setText(requestedTitle == null ? "Lumio Player" : requestedTitle);
            return;
        }
        Uri uri = Uri.parse(requestedUri);
        MediaItem item = new MediaItem.Builder()
                .setUri(uri)
                .setMediaId(requestedUri)
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(requestedTitle == null ? "Local media" : requestedTitle)
                        .build())
                .build();
        long startPosition = 0L;
        String lastUri = prefs.getString("last_uri", "");
        if (requestedUri.equals(lastUri)) {
            startPosition = Math.max(0L, prefs.getLong("position:" + requestedUri, 0L));
        }
        controller.setMediaItem(item, startPosition);
        controller.prepare();
        controller.play();
        titleText.setText(requestedTitle == null ? "Lumio Player" : requestedTitle);
    }

    private void persistPosition() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        MediaItem current = controller.getCurrentMediaItem();
        String uri = current.localConfiguration == null ? "" : current.localConfiguration.uri.toString();
        if (uri.isEmpty()) return;
        prefs.edit().putString("last_uri", uri)
                .putLong("position:" + uri, Math.max(0L, controller.getCurrentPosition()))
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
        if (playerView != null) playerView.setPlayer(null);
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
            controller = null;
        }
        super.onStop();
    }

    private void cycleSpeed() {
        if (controller == null) return;
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float speed = SPEEDS[speedIndex];
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
        showHint(locked ? "Touch controls locked" : "Touch controls unlocked");
    }

    private void installGestures() {
        android.view.GestureDetector gestureDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { touchDownX = e.getX(); touchDownY = e.getY(); gestureHandled = false; return true; }
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (locked || controller == null || controller.getDuration() <= 0) return false;
                        boolean forward = e.getX() >= playerView.getWidth() / 2f;
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
                            float fraction = -distanceY / Math.max(1, playerView.getHeight());
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
        gestureSurface.setOnTouchListener((v, event) -> {
            if (locked) return true;
            scaleDetector.onTouchEvent(event);
            boolean handled = gestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && gestureHandled) {
                v.performClick();
                return true;
            }
            return handled && event.getActionMasked() != MotionEvent.ACTION_DOWN;
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
        new AlertDialog.Builder(this).setTitle("Audio track").setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Close", null).show();
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
        labels.add("Load external subtitle (SRT/VTT/SSA)");
        actions.add(this::pickSubtitle);
        new AlertDialog.Builder(this).setTitle("Subtitles").setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Close", null).show();
    }

    private void pickSubtitle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-subrip", "text/vtt", "text/x-ssa", "application/octet-stream"});
        try { startActivityForResult(intent, REQUEST_SUBTITLE); }
        catch (Exception e) { showHint("No document picker available"); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SUBTITLE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        String name = uri.getLastPathSegment() == null ? "subtitle" : uri.getLastPathSegment();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String mime;
        if ("srt".equals(ext)) mime = MimeTypes.APPLICATION_SUBRIP;
        else if ("vtt".equals(ext)) mime = MimeTypes.TEXT_VTT;
        else if ("ass".equals(ext) || "ssa".equals(ext)) mime = MimeTypes.TEXT_SSA;
        else { showHint("Use an SRT, VTT, ASS or SSA file"); return; }
        addSubtitle(uri, mime);
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
        if (isInPictureInPictureMode) {
            if (playerView != null) playerView.setUseController(true);
            if (titleText != null && titleText.getParent() instanceof View) {
                View parent = (View) titleText.getParent();
                if (parent instanceof ViewGroup) parent.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onDestroy() {
        persistPosition();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
            controller = null;
        }
        super.onDestroy();
    }
}
