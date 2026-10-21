package com.arifroan.lumio;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.MediaItem.SubtitleConfiguration;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

@UnstableApi
public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(15, 16, 18);
    private static final int SURFACE = Color.rgb(28, 29, 31);
    private static final int SURFACE_2 = Color.rgb(39, 40, 42);
    private static final int TEXT = Color.rgb(247, 245, 241);
    private static final int MUTED = Color.rgb(157, 157, 155);
    private static final int LIME = Color.rgb(255, 91, 46);

    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout appRoot, screenHost, bottomNav;
    private Uri currentMediaUri, selectedSubtitleUri;
    private String activeTab = "Home";
    private boolean playerScreen = false, fullscreen = false;
    private float playbackSpeed = 1.0f;
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};
    private TextView speedButton;
    private final ArrayList<Uri> mediaItems = new ArrayList<>();
    private ActivityResultLauncher<String[]> openMedia, openSubtitle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        loadLibrary();

        openSubtitle = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
            selectedSubtitleUri = uri;
            if (currentMediaUri != null && isVideo(currentMediaUri)) playUri(currentMediaUri);
            Toast.makeText(this, "Subtitle selected", Toast.LENGTH_SHORT).show();
        });
        openMedia = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris == null || uris.isEmpty()) return;
            int added = 0;
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
                if (!mediaItems.contains(uri)) { mediaItems.add(uri); added++; }
            }
            saveLibrary();
            renderTab(activeTab);
            if (added > 0) Toast.makeText(this, added + " file(s) added", Toast.LENGTH_SHORT).show();
            if (currentMediaUri == null && !mediaItems.isEmpty()) playUri(mediaItems.get(mediaItems.size() - 1));
        });

        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(MainActivity.this, "Can't play this file. It may be unsupported or unavailable.", Toast.LENGTH_LONG).show();
            }
        });
        appRoot = new LinearLayout(this);
        appRoot.setOrientation(LinearLayout.VERTICAL);
        appRoot.setBackgroundColor(BG);
        screenHost = new LinearLayout(this);
        screenHost.setOrientation(LinearLayout.VERTICAL);
        appRoot.addView(screenHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(8), dp(7), dp(8), dp(7));
        bottomNav.setBackgroundColor(Color.rgb(21, 22, 23));
        appRoot.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(66)));
        setContentView(appRoot);
        renderTab("Home");
    }

    private void renderTab(String tab) {
        activeTab = tab;
        playerScreen = false;
        screenHost.removeAllViews();
        if (playerView != null) { playerView.setPlayer(null); playerView = null; }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(14), dp(20), dp(22));
        scroll.addView(page);
        screenHost.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        addBrandHeader(page);
        if ("Home".equals(tab)) buildHome(page);
        else if ("Videos".equals(tab)) buildLibrary(page, true);
        else if ("Music".equals(tab)) buildLibrary(page, false);
        else buildSettings(page);
        updateBottomNav();
    }

    private void addBrandHeader(LinearLayout page) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("L", 18, TEXT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(LIME, 10));
        bar.addView(mark, lp(dp(34), dp(34)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(10), 0, 0, 0);
        names.addView(text("lumio", 21, TEXT, true));
        TextView sub = text("YOUR PERSONAL MEDIA SPACE", 8, MUTED, true);
        sub.setLetterSpacing(0.08f);
        names.addView(sub);
        bar.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = text("＋  ADD MEDIA", 10, TEXT, true);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(13), 0, dp(13), 0);
        add.setBackground(outlineShape(LIME, 18));
        add.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
        bar.addView(add, lp(-2, dp(36)));
        page.addView(bar, lp(-1, dp(42)));
        View rule = new View(this);
        rule.setBackgroundColor(Color.rgb(48, 48, 48));
        LinearLayout.LayoutParams rp = lp(-1, dp(1)); rp.topMargin = dp(15);
        page.addView(rule, rp);
        space(page, 21);
    }

    private void buildHome(LinearLayout page) {
        TextView eyebrow = text("YOUR LIBRARY, REIMAGINED", 10, LIME, true);
        eyebrow.setLetterSpacing(0.12f);
        page.addView(eyebrow);
        space(page, 8);
        page.addView(text("Press play.\nLeave the world behind.", 30, TEXT, true));
        space(page, 8);
        page.addView(text("Movies, memories and music — all on your device.", 12, MUTED, false));
        space(page, 20);
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(19), dp(18), dp(19), dp(18));
        hero.setBackground(gradientShape(Color.rgb(69, 34, 27), Color.rgb(31, 29, 29), 22));
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView kicker = text("THE LUMIO EXPERIENCE", 9, Color.rgb(255, 177, 151), true);
        kicker.setLetterSpacing(0.1f);
        heroTop.addView(kicker, new LinearLayout.LayoutParams(0, -2, 1));
        heroTop.addView(text("●  ON DEVICE", 8, Color.rgb(235, 207, 195), true));
        hero.addView(heroTop);
        space(hero, 18);
        hero.addView(text(mediaItems.size() == 1 ? "1 title in your library" : mediaItems.size() + " titles in your library", 22, TEXT, true));
        space(hero, 5);
        hero.addView(text("Private by design. Ready when you are.", 12, Color.rgb(208, 193, 187), false));
        space(hero, 18);
        LinearLayout heroActions = new LinearLayout(this);
        heroActions.setGravity(Gravity.CENTER_VERTICAL);
        Button browse = button("＋  Import media", true);
        browse.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
        heroActions.addView(browse, new LinearLayout.LayoutParams(0, dp(45), 1.1f));
        heroActions.addView(new View(this), lp(dp(9), 1));
        Button explore = button("Explore library  ›", false);
        explore.setOnClickListener(v -> renderTab("Videos"));
        heroActions.addView(explore, new LinearLayout.LayoutParams(0, dp(45), 1f));
        hero.addView(heroActions);
        page.addView(hero, lp(-1, -2));
        space(page, 25);
        sectionTitle(page, "Find your next play", "EXPLORE");
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        addFeatureTile(tiles, "▶", "Videos", mediaCount(true) + " titles", Color.rgb(32, 34, 36), 1f, () -> renderTab("Videos"));
        tiles.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        addFeatureTile(tiles, "♫", "Music", mediaCount(false) + " tracks", Color.rgb(35, 32, 31), 1f, () -> renderTab("Music"));
        page.addView(tiles, lp(-1, dp(126)));
        space(page, 25);
        sectionTitle(page, "Recently added", mediaItems.isEmpty() ? "EMPTY" : String.format(Locale.ROOT, "%02d ITEMS", mediaItems.size()));
        if (mediaItems.isEmpty()) emptyState(page, "Your story starts here", "Bring in videos or music from your phone. Lumio keeps your library on-device.");
        else {
            int start = Math.max(0, mediaItems.size() - 5);
            for (int i = mediaItems.size() - 1; i >= start; i--) addMediaRow(page, mediaItems.get(i), true);
        }
        if (currentMediaUri != null) {
            space(page, 15);
            LinearLayout recent = card();
            recent.setOrientation(LinearLayout.HORIZONTAL);
            recent.setGravity(Gravity.CENTER_VERTICAL);
            recent.setPadding(dp(13), dp(12), dp(13), dp(12));
            TextView icon = text(isVideo(currentMediaUri) ? "▶" : "♫", 20, LIME, true);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(shape(Color.rgb(58, 36, 31), 12));
            recent.addView(icon, lp(dp(42), dp(42)));
            LinearLayout label = new LinearLayout(this);
            label.setOrientation(LinearLayout.VERTICAL);
            label.setPadding(dp(11), 0, dp(8), 0);
            label.addView(text("PICK UP WHERE YOU LEFT OFF", 8, LIME, true));
            label.addView(text(displayName(currentMediaUri), 12, TEXT, true));
            recent.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            recent.addView(text("PLAY  ›", 9, TEXT, true));
            recent.setOnClickListener(v -> showPlayer(currentMediaUri));
            page.addView(recent, lp(-1, -2));
        }
    }

    private void buildLibrary(LinearLayout page, boolean videos) {
        page.addView(text(videos ? "Videos" : "Music", 30, TEXT, true));
        space(page, 5);
        page.addView(text(videos ? "Your films, clips and recordings." : "Your songs, tracks and audio files.", 13, MUTED, false));
        space(page, 18);
        LinearLayout info = card();
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(15), dp(14), dp(15), dp(14));
        TextView glyph = text(videos ? "▶" : "♫", 25, LIME, true);
        info.addView(glyph, lp(dp(42), dp(42)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(videos ? "VIDEO LIBRARY" : "AUDIO LIBRARY", 10, LIME, true));
        labels.addView(text(mediaCount(videos) + (videos ? " videos" : " audio files"), 16, TEXT, true));
        info.addView(labels);
        page.addView(info, lp(-1, -2));
        space(page, 17);
        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.addView(text("All " + (videos ? "videos" : "audio"), 18, TEXT, true),
            new LinearLayout.LayoutParams(0, -2, 1));
        TextView addAction = text("＋ Add", 11, LIME, true);
        addAction.setOnClickListener(v -> openMedia.launch(new String[]{videos ? "video/*" : "audio/*"}));
        listHeader.addView(addAction);
        page.addView(listHeader, lp(-1, dp(32)));
        space(page, 9);
        int count = 0;
        for (int i = mediaItems.size() - 1; i >= 0; i--) {
            Uri uri = mediaItems.get(i);
            if (isVideo(uri) == videos) { addMediaRow(page, uri, false); count++; }
        }
        if (count == 0) emptyState(page, videos ? "No videos yet" : "No audio yet",
            videos ? "Add video files from your phone to see them here." : "Add music or other audio files from your phone to see them here.");
    }

    private void buildSettings(LinearLayout page) {
        page.addView(text("Settings", 30, TEXT, true));
        space(page, 5);
        page.addView(text("Your playback, your preferences.", 13, MUTED, false));
        space(page, 22);
        settingRow(page, "On-device library", "Your selected files stay in your Android library.", "LOCAL");
        settingRow(page, "Playback speed", "Current default: " + playbackSpeed + "×. Change it from the player.", "1×–2×");
        settingRow(page, "Subtitles", "Choose an external subtitle file while playing a video.", "SRT / VTT");
        settingRow(page, "Resume playback", "Lumio remembers the last file and playback position.", "ON");
        space(page, 20);
        Button add = button("＋   Add media from device", true);
        add.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
        page.addView(add, lp(-1, dp(50)));
        space(page, 14);
        page.addView(text("LUMIO  •  PERSONAL MEDIA PLAYER", 10, MUTED, true));
        page.addView(text("Built for local video and audio playback.", 12, MUTED, false));
    }

    private void settingRow(LinearLayout page, String title, String detail, String badge) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(14), dp(15), dp(14));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(title, 14, TEXT, true));
        space(copy, 4);
        copy.addView(text(detail, 11, MUTED, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(badge, 9, LIME, true));
        LinearLayout.LayoutParams p = lp(-1, -2); p.bottomMargin = dp(8);
        page.addView(row, p);
    }

    private void addFeatureTile(LinearLayout parent, String icon, String title, String subtitle, int color, float weight, Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(15), dp(13), dp(12), dp(13));
        tile.setBackground(shape(color, 15));
        tile.addView(text(icon, 25, LIME, true));
        space(tile, 7);
        tile.addView(text(title, 16, TEXT, true));
        tile.addView(text(subtitle, 10, MUTED, false));
        tile.setOnClickListener(v -> action.run());
        parent.addView(tile, new LinearLayout.LayoutParams(0, -1, weight));
    }

    private void sectionTitle(LinearLayout page, String title, String action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(title, 18, TEXT, true);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(action, 10, LIME, true));
        page.addView(row, lp(-1, dp(32)));
        space(page, 9);
    }

    private void addMediaRow(LinearLayout page, Uri uri, boolean compact) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView artwork = text(isVideo(uri) ? "▶" : "♫", 18, LIME, true);
        artwork.setGravity(Gravity.CENTER);
        artwork.setBackground(shape(isVideo(uri) ? Color.rgb(43, 48, 64) : Color.rgb(48, 52, 35), 13));
        row.addView(artwork, lp(dp(48), dp(48)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(displayName(uri), 13, TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name);
        space(copy, 3);
        copy.addView(text(isVideo(uri) ? "VIDEO  •  TAP TO PLAY" : "AUDIO  •  TAP TO PLAY", 9, MUTED, true));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 23, MUTED, false));
        row.setOnClickListener(v -> playUri(uri));
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(compact ? 7 : 9);
        page.addView(row, p);
    }

    private void emptyState(LinearLayout page, String title, String detail) {
        LinearLayout empty = card();
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(22), dp(26), dp(22), dp(26));
        empty.addView(text("＋", 30, LIME, true));
        space(empty, 9);
        empty.addView(text(title, 16, TEXT, true));
        space(empty, 5);
        TextView description = text(detail, 12, MUTED, false);
        description.setGravity(Gravity.CENTER);
        empty.addView(description);
        LinearLayout.LayoutParams p = lp(-1, -2); p.topMargin = dp(8);
        page.addView(empty, p);
    }

    private void updateBottomNav() {
        bottomNav.removeAllViews();
        String[] tabs = {"Home", "Videos", "Music", "Settings"};
        String[] icons = {"⌂", "▶", "♫", "⚙"};
        for (int i = 0; i < tabs.length; i++) {
            final String tab = tabs[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            boolean selected = tab.equals(activeTab);
            item.addView(text(icons[i], 20, selected ? LIME : MUTED, true));
            TextView label = text(tab, 10, selected ? LIME : MUTED, selected);
            item.addView(label);
            item.setOnClickListener(v -> renderTab(tab));
            bottomNav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        bottomNav.setVisibility(playerScreen || fullscreen ? View.GONE : View.VISIBLE);
    }

    private void showPlayer(Uri uri) {
        currentMediaUri = uri;
        playerScreen = true;
        screenHost.removeAllViews();
        if (playerView != null) { playerView.setPlayer(null); playerView = null; }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(10), dp(16), dp(18));
        page.setBackgroundColor(BG);
        screenHost.addView(page, lp(-1, -1));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 34, TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> renderTab(isVideo(uri) ? "Videos" : "Music"));
        top.addView(back, lp(dp(42), dp(46)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER);
        titles.addView(text(isVideo(uri) ? "VIDEO PLAYER" : "NOW PLAYING", 10, LIME, true));
        titles.addView(text("LUMIO PLAYER", 9, MUTED, true));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text("•••", 20, TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> showPlayerOptions());
        top.addView(more, lp(dp(42), dp(46)));
        page.addView(top, lp(-1, dp(48)));
        space(page, 10);

        if (isVideo(uri)) {
            playerView = new PlayerView(this);
            playerView.setPlayer(player);
            playerView.setUseController(true);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
            playerView.setShowNextButton(false);
            playerView.setShowPreviousButton(false);
            playerView.setBackgroundColor(Color.BLACK);
            LinearLayout.LayoutParams video = lp(-1, 0);
            video.weight = 1f;
            page.addView(playerView, video);
            space(page, 14);
            TextView title = text(displayName(uri), 17, TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            page.addView(title);
            space(page, 4);
            page.addView(text("Local video • playback stays on your device", 11, MUTED, false));
            space(page, 12);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            addAction(actions, "Subtitles", () -> openSubtitle.launch(new String[]{"text/*", "application/x-subrip", "application/octet-stream"}), 1f);
            View gap = new View(this); actions.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
            addAction(actions, "Fullscreen", this::toggleFullscreen, 1f);
            page.addView(actions, lp(-1, dp(46)));
        } else {
            LinearLayout art = new LinearLayout(this);
            art.setOrientation(LinearLayout.VERTICAL);
            art.setGravity(Gravity.CENTER);
            art.setBackground(shape(Color.rgb(43, 52, 35), 28));
            TextView glyph = text("♫", 90, LIME, true);
            glyph.setGravity(Gravity.CENTER);
            art.addView(glyph, lp(-1, dp(190)));
            art.addView(text("LUMIO  •  AUDIO", 10, Color.rgb(215, 227, 194), true));
            LinearLayout.LayoutParams artP = lp(-1, 0);
            artP.weight = 1f;
            page.addView(art, artP);
            space(page, 22);
            TextView title = text(displayName(uri), 21, TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            page.addView(title);
            space(page, 5);
            page.addView(text("Playing from your local library", 12, MUTED, false));
            space(page, 22);
            LinearLayout transport = new LinearLayout(this);
            transport.setGravity(Gravity.CENTER);
            addAction(transport, "‹ Previous", () -> playAdjacent(-1), 1f);
            addAction(transport, player != null && player.isPlaying() ? "Ⅱ Pause" : "▶ Play", () -> {
                if (player == null) return;
                if (player.isPlaying()) player.pause(); else player.play();
                showPlayer(currentMediaUri);
            }, 1f);
            addAction(transport, "Next ›", () -> playAdjacent(1), 1f);
            page.addView(transport, lp(-1, dp(50)));
            space(page, 8);
            speedButton = button("Playback speed  " + playbackSpeed + "×", false);
            speedButton.setOnClickListener(v -> { cyclePlaybackSpeed(); speedButton.setText("Playback speed  " + playbackSpeed + "×"); });
            page.addView(speedButton, lp(-1, dp(44)));
            space(page, 8);
            Button queue = button("Open video library", false);
            queue.setOnClickListener(v -> renderTab("Music"));
            page.addView(queue, lp(-1, dp(44)));
        }
        updateBottomNav();
    }

    private void showPlayerOptions() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Playback options")
            .setItems(new String[]{"1× speed", "1.25× speed", "1.5× speed", "2× speed", "Choose subtitles"}, (dialog, which) -> {
                if (which == 4) openSubtitle.launch(new String[]{"text/*", "application/x-subrip", "application/octet-stream"});
                else {
                    playbackSpeed = SPEEDS[which];
                    if (player != null) player.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
                    Toast.makeText(this, "Playback speed " + playbackSpeed + "×", Toast.LENGTH_SHORT).show();
                }
            }).show();
    }

    private void addAction(LinearLayout parent, String label, Runnable action, float weight) {
        Button b = button(label, false);
        b.setTextSize(11);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, new LinearLayout.LayoutParams(0, dp(44), weight));
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? BG : TEXT);
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(shape(primary ? LIME : SURFACE_2, 14));
        return b;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setBackground(shape(SURFACE, 14));
        return box;
    }

    private int mediaCount(boolean videos) {
        int count = 0;
        for (Uri uri : mediaItems) if (isVideo(uri) == videos) count++;
        return count;
    }

    private boolean isVideo(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("video/")) return true;
        String name = displayName(uri).toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") ||
            name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".3gp") || name.endsWith(".mpeg");
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
        } catch (Exception ignored) {}
        String name = uri.getLastPathSegment();
        return name == null || name.trim().isEmpty() ? "Selected media" : name;
    }

    private void playUri(Uri uri) {
        if (player == null) return;
        currentMediaUri = uri;
        MediaItem item;
        if (selectedSubtitleUri != null && isVideo(uri)) {
            String path = selectedSubtitleUri.getLastPathSegment();
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            String detectedMime = getContentResolver().getType(selectedSubtitleUri);
            String mime = MimeTypes.TEXT_VTT.equals(detectedMime) || lower.endsWith(".vtt") ? MimeTypes.TEXT_VTT :
                lower.endsWith(".ssa") || lower.endsWith(".ass") ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
            SubtitleConfiguration subtitle = new SubtitleConfiguration.Builder(selectedSubtitleUri)
                .setMimeType(mime).setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
            item = new MediaItem.Builder().setUri(uri).setSubtitleConfigurations(Collections.singletonList(subtitle)).build();
        } else item = MediaItem.fromUri(uri);

        SharedPreferences prefs = getSharedPreferences("lumio_playback", MODE_PRIVATE);
        long position = uri.toString().equals(prefs.getString("last_uri", "")) ? Math.max(0L, prefs.getLong("position_ms", 0L)) : 0L;
        prefs.edit().putString("last_uri", uri.toString()).apply();
        player.setMediaItem(item, position);
        player.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
        player.prepare();
        player.play();
        showPlayer(uri);
    }

    private void playAdjacent(int direction) {
        if (mediaItems.isEmpty()) {
            Toast.makeText(this, "Add media to your library first", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = currentMediaUri == null ? -1 : mediaItems.indexOf(currentMediaUri);
        int next = index < 0 ? (direction > 0 ? 0 : mediaItems.size() - 1) : index + direction;
        if (next < 0 || next >= mediaItems.size()) {
            Toast.makeText(this, direction < 0 ? "This is the first item" : "This is the last item", Toast.LENGTH_SHORT).show();
            return;
        }
        playUri(mediaItems.get(next));
    }

    private void cyclePlaybackSpeed() {
        int next = 0;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - playbackSpeed) < 0.01f) { next = (i + 1) % SPEEDS.length; break; }
        playbackSpeed = SPEEDS[next];
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
    }

    private void loadLibrary() {
        SharedPreferences prefs = getSharedPreferences("lumio_library", MODE_PRIVATE);
        for (String value : prefs.getStringSet("media_uris", new HashSet<>())) {
            try (android.os.ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(Uri.parse(value), "r")) {
                mediaItems.add(Uri.parse(value));
            } catch (Exception ignored) {}
        }
    }

    private void saveLibrary() {
        HashSet<String> values = new HashSet<>();
        for (Uri uri : mediaItems) values.add(uri.toString());
        getSharedPreferences("lumio_library", MODE_PRIVATE).edit().putStringSet("media_uris", values).apply();
    }

    private void savePlaybackState() {
        if (player == null || currentMediaUri == null) return;
        getSharedPreferences("lumio_playback", MODE_PRIVATE).edit()
            .putString("last_uri", currentMediaUri.toString())
            .putLong("position_ms", Math.max(0L, player.getCurrentPosition())).apply();
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        if (fullscreen) {
            bottomNav.setVisibility(View.GONE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            bottomNav.setVisibility(playerScreen ? View.GONE : View.VISIBLE);
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private android.graphics.drawable.GradientDrawable outlineShape(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), color);
        return d;
    }

    private android.graphics.drawable.GradientDrawable gradientShape(int top, int bottom, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        d.setCornerRadius(dp(radius));
        return d;
    }

    private android.graphics.drawable.GradientDrawable shape(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private void space(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, lp(1, dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (fullscreen) { toggleFullscreen(); return; }
        if (playerScreen) { renderTab(currentMediaUri != null && isVideo(currentMediaUri) ? "Videos" : "Music"); return; }
        if (!"Home".equals(activeTab)) { renderTab("Home"); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() { savePlaybackState(); super.onPause(); }
    @Override protected void onStop() { savePlaybackState(); super.onStop(); }
    @Override protected void onDestroy() {
        savePlaybackState();
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
