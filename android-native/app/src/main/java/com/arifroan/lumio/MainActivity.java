package com.arifroan.lumio;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.content.SharedPreferences;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.MediaItem.SubtitleConfiguration;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

@UnstableApi
public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(9, 11, 18);
    private static final int PANEL = Color.rgb(20, 24, 36);
    private static final int TEXT = Color.rgb(243, 245, 251);
    private static final int MUTED = Color.rgb(151, 160, 180);
    private static final int MINT = Color.rgb(73, 224, 199);

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView nowPlaying;
    private TextView libraryCount;
    private LinearLayout rootLayout;
    private LinearLayout playerCard;
    private Uri currentMediaUri;
    private Uri selectedSubtitleUri;
    private boolean fullscreen = false;
    private float playbackSpeed = 1.0f;
    private TextView speedButton;
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};
    private int normalPlayerHeight;
    private LinearLayout libraryList;
    private final ArrayList<Uri> mediaItems = new ArrayList<>();
    private ActivityResultLauncher<String[]> openMedia;
    private ActivityResultLauncher<String[]> openSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        openSubtitle = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            selectedSubtitleUri = uri;
            if (currentMediaUri != null) playUri(currentMediaUri);
            Toast.makeText(this, "Subtitle selected", Toast.LENGTH_SHORT).show();
        });

        openMedia = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (uris == null || uris.isEmpty()) return;
                int added = 0;
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) { }
                    if (!mediaItems.contains(uri)) {
                        mediaItems.add(uri);
                        added++;
                    }
                }
                saveLibrary();
                refreshLibrary();
                if (added > 0) {
                    Toast.makeText(this, added + " file(s) added to your library", Toast.LENGTH_SHORT).show();
                }
                if (!mediaItems.isEmpty() && player != null && player.getMediaItemCount() == 0) {
                    playUri(mediaItems.get(mediaItems.size() - 1));
                }
            }
        );

        loadLibrary();
        buildNativeInterface();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) nowPlaying.setText("Finished playing");
            }
            @Override public void onPlayerError(PlaybackException error) {
                nowPlaying.setText("Can't play this file");
                Toast.makeText(MainActivity.this, "Playback failed. The file may be unsupported or unavailable.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void buildNativeInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        rootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(22));
        scroll.addView(root);

        // Quiet, compact brand header: typography and alignment do the work.
        LinearLayout appBar = new LinearLayout(this);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        TextView mark = text("L", 23, BG, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundRect(MINT, 10));
        appBar.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(text("lumio", 20, TEXT, true));
        TextView descriptor = text("YOUR PERSONAL MEDIA PLAYER", 9, MUTED, true);
        descriptor.setLetterSpacing(0.10f);
        brand.addView(descriptor);
        appBar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView local = text("LOCAL LIBRARY", 9, MINT, true);
        local.setLetterSpacing(0.08f);
        appBar.addView(local);
        root.addView(appBar, params(-1, dp(44)));
        addSpace(root, 12);

        LinearLayout collectionHeader = new LinearLayout(this);
        collectionHeader.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("YOUR LIBRARY", 10, MINT, true);
        eyebrow.setLetterSpacing(0.12f);
        collectionHeader.addView(eyebrow);
        addSpace(collectionHeader, 4);
        collectionHeader.addView(text("Your media, ready to play.", 21, TEXT, true));
        addSpace(collectionHeader, 3);
        collectionHeader.addView(text("Audio and video stored on this device.", 12, MUTED, false));
        root.addView(collectionHeader);
        addSpace(root, 14);

        // Playback stays central; the library and controls remain immediately available.
        playerCard = new LinearLayout(this);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setPadding(dp(8), dp(8), dp(8), dp(8));
        playerCard.setBackground(roundRect(PANEL, 18));
        normalPlayerHeight = dp(196);
        playerView = new PlayerView(this);
        playerView.setVisibility(View.GONE);
        playerView.setUseController(true);
        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setBackgroundColor(Color.BLACK);
        playerCard.addView(playerView, new LinearLayout.LayoutParams(-1, normalPlayerHeight));
        nowPlaying = text("Ready when you are", 15, TEXT, true);
        nowPlaying.setMaxLines(1);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlaying.setPadding(dp(10), dp(14), dp(10), dp(3));
        playerCard.addView(nowPlaying);
        TextView helper = text("Select a track or video from your library", 12, MUTED, false);
        helper.setPadding(dp(10), 0, dp(10), dp(8));
        playerCard.addView(helper);

        LinearLayout trackActions = new LinearLayout(this);
        trackActions.setOrientation(LinearLayout.HORIZONTAL);
        trackActions.setGravity(Gravity.CENTER_VERTICAL);
        addAction(trackActions, "‹  Previous", () -> playAdjacent(-1), 1f);
        speedButton = new Button(this);
        speedButton.setText("1× speed");
        styleActionButton(speedButton);
        speedButton.setOnClickListener(v -> cyclePlaybackSpeed());
        trackActions.addView(speedButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        addAction(trackActions, "Next  ›", () -> playAdjacent(1), 1f);
        LinearLayout.LayoutParams trackParams = params(-1, dp(42));
        trackParams.topMargin = dp(6);
        playerCard.addView(trackActions, trackParams);

        LinearLayout playbackActions = new LinearLayout(this);
        playbackActions.setOrientation(LinearLayout.HORIZONTAL);
        playbackActions.setGravity(Gravity.CENTER_VERTICAL);
        addAction(playbackActions, "Subtitles", () -> openSubtitle.launch(
            new String[]{"text/*", "application/x-subrip", "application/octet-stream"}), 1f);
        addAction(playbackActions, "Fullscreen", this::toggleFullscreen, 1f);
        LinearLayout.LayoutParams playbackParams = params(-1, dp(42));
        playbackParams.topMargin = dp(5);
        playerCard.addView(playbackActions, playbackParams);
        root.addView(playerCard, params(-1, -2));

        addSpace(root, 20);
        LinearLayout libraryHeader = new LinearLayout(this);
        libraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        libraryHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView libraryTitle = text("Library", 21, TEXT, true);
        libraryHeader.addView(libraryTitle, new LinearLayout.LayoutParams(0, -2, 1));
        libraryCount = text("0 files", 12, MUTED, true);
        libraryHeader.addView(libraryCount);
        root.addView(libraryHeader, params(-1, dp(34)));
        addSpace(root, 4);
        root.addView(text("Your files, in one place.", 12, MUTED, false));
        addSpace(root, 13);

        libraryList = new LinearLayout(this);
        libraryList.setOrientation(LinearLayout.VERTICAL);
        root.addView(libraryList, params(-1, -2));
        refreshLibrary();

        addSpace(root, 12);
        Button open = new Button(this);
        open.setText("+   Add media");
        open.setTextSize(14);
        open.setAllCaps(false);
        open.setTextColor(BG);
        open.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        open.setBackground(roundRect(MINT, 12));
        open.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
        root.addView(open, params(-1, dp(48)));

        addSpace(root, 18);
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(12), dp(12), dp(12), dp(12));
        footer.setBackground(roundRect(Color.rgb(16, 28, 31), 12));
        TextView privacyMark = text("●", 11, MINT, true);
        footer.addView(privacyMark);
        TextView privacyText = text("  ON-DEVICE BY DESIGN", 10, TEXT, true);
        privacyText.setLetterSpacing(0.06f);
        footer.addView(privacyText);
        View spacer = new View(this);
        footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        footer.addView(text("LUMIO 0.7", 9, MUTED, true));
        root.addView(footer, params(-1, -2));
        setContentView(scroll);
    }

    private void addAction(LinearLayout parent, String label, Runnable action, float weight) {
        Button button = new Button(this);
        button.setText(label);
        styleActionButton(button);
        button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(0, dp(44), weight));
    }

    private void styleActionButton(TextView button) {
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setTextColor(TEXT);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setMinWidth(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setBackground(roundRect(Color.rgb(31, 36, 48), 10));
    }

    private void loadLibrary() {
        SharedPreferences preferences = getSharedPreferences("lumio_library", MODE_PRIVATE);
        for (String value : preferences.getStringSet("media_uris", new java.util.HashSet<>())) {
            try {
                Uri uri = Uri.parse(value);
                getContentResolver().openFileDescriptor(uri, "r").close();
                mediaItems.add(uri);
            } catch (Exception ignored) { }
        }
    }

    private void saveLibrary() {
        java.util.HashSet<String> values = new java.util.HashSet<>();
        for (Uri uri : mediaItems) values.add(uri.toString());
        getSharedPreferences("lumio_library", MODE_PRIVATE)
            .edit().putStringSet("media_uris", values).apply();
    }

    private void refreshLibrary() {
        if (libraryList == null || libraryCount == null) return;
        libraryList.removeAllViews();
        libraryCount.setText(mediaItems.size() + (mediaItems.size() == 1 ? " item" : " items"));
        if (mediaItems.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(dp(16), dp(20), dp(16), dp(20));
            empty.setBackground(roundRect(PANEL, 14));
            empty.addView(text("No media added yet", 15, TEXT, true));
            addSpace(empty, 5);
            empty.addView(text("Add audio or video files to build your library.", 12, MUTED, false));
            libraryList.addView(empty, params(-1, -2));
            return;
        }
        for (int i = 0; i < mediaItems.size(); i++) {
            final Uri uri = mediaItems.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(roundRect(Color.rgb(17, 21, 31), 12));

            TextView icon = text(isVideo(uri) ? "▶" : "♫", 17, MINT, true);
            icon.setGravity(Gravity.CENTER);
            row.addView(icon, params(dp(38), dp(38)));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(10), 0, dp(8), 0);
            TextView name = text(displayName(uri), 13, TEXT, true);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(name);
            TextView type = text(isVideo(uri) ? "VIDEO FILE" : "AUDIO FILE", 9, MUTED, true);
            type.setLetterSpacing(0.10f);
            labels.addView(type);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView play = text("PLAY  ›", 10, MINT, true);
            play.setLetterSpacing(0.03f);
            row.addView(play);
            row.setOnClickListener(v -> playUri(uri));
            libraryList.addView(row, params(-1, -2));
            if (i < mediaItems.size() - 1) addSpace(libraryList, 6);
        }
    }

    private boolean isVideo(Uri uri) {
        String mime = getContentResolver().getType(uri);
        return mime != null && mime.startsWith("video/");
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) { }
        String name = uri.getLastPathSegment();
        return name == null || name.trim().isEmpty() ? "Selected media" : name;
    }

    private void playUri(Uri uri) {
        if (player == null) return;
        currentMediaUri = uri;
        boolean video = isVideo(uri);
        playerView.setVisibility(video ? View.VISIBLE : View.GONE);
        nowPlaying.setText(displayName(uri));
        MediaItem item;
        if (selectedSubtitleUri != null && isVideo(uri)) {
            String path = selectedSubtitleUri.getLastPathSegment();
            String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
            String detectedMime = getContentResolver().getType(selectedSubtitleUri);
            String mime = MimeTypes.TEXT_VTT.equals(detectedMime) || lower.endsWith(".vtt")
                ? MimeTypes.TEXT_VTT
                : lower.endsWith(".ssa") || lower.endsWith(".ass")
                    ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
            SubtitleConfiguration subtitle = new SubtitleConfiguration.Builder(selectedSubtitleUri)
                .setMimeType(mime).setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
            item = new MediaItem.Builder().setUri(uri).setSubtitleConfigurations(Collections.singletonList(subtitle)).build();
        } else {
            item = MediaItem.fromUri(uri);
        }
        SharedPreferences playbackPrefs = getSharedPreferences("lumio_playback", MODE_PRIVATE);
        long startPosition = uri.toString().equals(playbackPrefs.getString("last_uri", ""))
            ? Math.max(0L, playbackPrefs.getLong("position_ms", 0L)) : 0L;
        playbackPrefs.edit().putString("last_uri", uri.toString()).apply();
        player.setMediaItem(item, startPosition);
        player.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
        player.prepare();
        player.play();
        nowPlaying.setText(displayName(uri));
    }

    private void playAdjacent(int direction) {
        if (mediaItems.isEmpty()) {
            Toast.makeText(this, "Add media to your library first", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = currentMediaUri == null ? -1 : mediaItems.indexOf(currentMediaUri);
        int nextIndex = index < 0 ? (direction > 0 ? 0 : mediaItems.size() - 1) : index + direction;
        if (nextIndex < 0 || nextIndex >= mediaItems.size()) {
            Toast.makeText(this, direction < 0 ? "This is the first item" : "This is the last item", Toast.LENGTH_SHORT).show();
            return;
        }
        playUri(mediaItems.get(nextIndex));
    }

    private void cyclePlaybackSpeed() {
        int next = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - playbackSpeed) < 0.01f) {
                next = (i + 1) % SPEEDS.length;
                break;
            }
        }
        playbackSpeed = SPEEDS[next];
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
        if (speedButton != null) speedButton.setText("Speed " + (playbackSpeed == 1.0f ? "1" : playbackSpeed) + "×");
    }

    private void savePlaybackState() {
        if (player == null || currentMediaUri == null) return;
        getSharedPreferences("lumio_playback", MODE_PRIVATE).edit()
            .putString("last_uri", currentMediaUri.toString())
            .putLong("position_ms", Math.max(0L, player.getCurrentPosition()))
            .apply();
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        if (fullscreen) {
            for (int i = 0; i < rootLayout.getChildCount(); i++) {
                View child = rootLayout.getChildAt(i);
                child.setVisibility(child == playerCard ? View.VISIBLE : View.GONE);
            }
            rootLayout.setPadding(0, 0, 0, 0);
            playerCard.setPadding(0, 0, 0, 0);
            playerCard.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
            playerView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            for (int i = 0; i < rootLayout.getChildCount(); i++) rootLayout.getChildAt(i).setVisibility(View.VISIBLE);
            rootLayout.setPadding(dp(20), dp(12), dp(20), dp(28));
            playerCard.setPadding(dp(8), dp(8), dp(8), dp(12));
            playerCard.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            playerView.setLayoutParams(new LinearLayout.LayoutParams(-1, normalPlayerHeight));
            getWindow().getDecorView().setSystemUiVisibility(0);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View space = new View(this);
        parent.addView(space, params(1, dp(heightDp)));
    }

    private android.graphics.drawable.GradientDrawable roundRect(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        savePlaybackState();
        super.onPause();
    }

    @Override
    protected void onStop() {
        savePlaybackState();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        savePlaybackState();
        if (fullscreen) toggleFullscreen();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
