package com.arifroan.lumio;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
    private static final int BG = Color.rgb(12, 13, 16);
    private static final int SURFACE = Color.rgb(23, 24, 29);
    private static final int SURFACE_2 = Color.rgb(34, 35, 41);
    private static final int TEXT = Color.rgb(247, 245, 241);
    private static final int MUTED = Color.rgb(157, 157, 155);
    private static final int LIME = Color.rgb(255, 104, 68);

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
        TextView mark = text("L", 18, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(LIME, 11));
        bar.addView(mark, lp(dp(36), dp(36)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(10), 0, 0, 0);
        names.addView(text("lumio", 22, TEXT, true));
        TextView tagline = text("WATCH. LISTEN. REPEAT.", 8, MUTED, true);
        tagline.setLetterSpacing(0.11f);
        names.addView(tagline);
        bar.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView search = text("⌕", 27, TEXT, false);
        search.setGravity(Gravity.CENTER);
        search.setBackground(shape(SURFACE, 13));
        search.setOnClickListener(v -> showLibrarySearch());
        LinearLayout.LayoutParams sp = lp(dp(42), dp(42)); sp.rightMargin = dp(8);
        bar.addView(search, sp);
        TextView add = text("＋", 25, BG, true);
        add.setGravity(Gravity.CENTER);
        add.setBackground(shape(LIME, 13));
        add.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
        bar.addView(add, lp(dp(42), dp(42)));
        page.addView(bar, lp(-1, dp(44)));
        space(page, 25);
    }

    private void buildHome(LinearLayout page) {
        TextView eyebrow = text("YOUR PERSONAL MEDIA SPACE", 9, LIME, true);
        eyebrow.setLetterSpacing(0.15f);
        page.addView(eyebrow);
        space(page, 8);
        page.addView(text("What are we\nin the mood for?", 31, TEXT, true));
        space(page, 9);
        page.addView(text("Your favourite stories, right here on your phone.", 12, MUTED, false));
        space(page, 19);

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        addModeChip(modes, "For you", true, () -> renderTab("Home"));
        addModeChip(modes, "Videos", false, () -> renderTab("Videos"));
        addModeChip(modes, "Music", false, () -> renderTab("Music"));
        page.addView(modes, lp(-1, dp(36)));
        space(page, 20);

        sectionTitle(page, currentMediaUri != null ? "Pick up where you left off" : "Your featured space", "PLAY");
        Uri featured = currentMediaUri != null ? currentMediaUri : latestMedia();
        FrameLayout feature = mediaArtwork(featured, 242, featured == null || isVideo(featured), 22);
        if (featured != null) {
            View shade = new View(this);
            shade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF0000000, 0x00000000}));
            feature.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout overlay = new LinearLayout(this);
            overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setPadding(dp(17), 0, dp(17), dp(16));
            overlay.setGravity(Gravity.BOTTOM);
            TextView pill = text(isVideo(featured) ? "  VIDEO  /  CONTINUE  " : "  AUDIO  /  NOW PLAYING  ", 9, TEXT, true);
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(shape(0x66333333, 12));
            overlay.addView(pill, lp(-2, dp(25)));
            space(overlay, 8);
            TextView title = text(displayName(featured), 20, Color.WHITE, true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            overlay.addView(title);
            space(overlay, 5);
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            TextView meta = text(isVideo(featured) ? "Continue watching" : "Continue listening", 11, Color.rgb(225,225,225), false);
            actions.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            TextView play = text("▶  PLAY", 10, BG, true);
            play.setGravity(Gravity.CENTER);
            play.setBackground(shape(LIME, 17));
            play.setPadding(dp(13), 0, dp(13), 0);
            actions.addView(play, lp(-2, dp(35)));
            overlay.addView(actions);
            feature.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            feature.setOnClickListener(v -> showPlayer(featured));
        } else {
            LinearLayout emptyOverlay = new LinearLayout(this);
            emptyOverlay.setOrientation(LinearLayout.VERTICAL);
            emptyOverlay.setGravity(Gravity.CENTER);
            emptyOverlay.setPadding(dp(22), dp(20), dp(22), dp(20));
            TextView badge = text("LUMIO ORIGINAL", 9, Color.rgb(255, 190, 168), true);
            badge.setLetterSpacing(0.18f);
            emptyOverlay.addView(badge);
            space(emptyOverlay, 15);
            TextView title = text("Your next favourite\nstarts here.", 24, Color.WHITE, true);
            title.setGravity(Gravity.CENTER);
            emptyOverlay.addView(title);
            space(emptyOverlay, 16);
            Button importButton = button("＋  Add your first media", true);
            importButton.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
            emptyOverlay.addView(importButton, lp(-2, dp(44)));
            feature.addView(emptyOverlay, new FrameLayout.LayoutParams(-1, -1));
            feature.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
        }
        page.addView(feature, lp(-1, dp(242)));
        space(page, 25);

        LinearLayout libraryHeading = new LinearLayout(this);
        libraryHeading.setGravity(Gravity.CENTER_VERTICAL);
        libraryHeading.addView(text("Explore your library", 19, TEXT, true),
            new LinearLayout.LayoutParams(0, -2, 1));
        libraryHeading.addView(text("ALL MEDIA  ›", 9, LIME, true));
        libraryHeading.setOnClickListener(v -> renderTab("Videos"));
        page.addView(libraryHeading, lp(-1, dp(32)));
        space(page, 12);

        LinearLayout shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.HORIZONTAL);
        shelf.addView(createCollectionCard("MOVIES & CLIPS", mediaCount(true) + " videos", "▶", Color.rgb(32, 35, 47), () -> renderTab("Videos")),
            new LinearLayout.LayoutParams(0, dp(112), 1));
        shelf.addView(new View(this), lp(dp(10), 1));
        shelf.addView(createCollectionCard("MUSIC & AUDIO", mediaCount(false) + " tracks", "♫", Color.rgb(48, 32, 34), () -> renderTab("Music")),
            new LinearLayout.LayoutParams(0, dp(112), 1));
        page.addView(shelf, lp(-1, dp(112)));
        space(page, 25);

        LinearLayout recentHead = new LinearLayout(this);
        recentHead.setGravity(Gravity.CENTER_VERTICAL);
        recentHead.addView(text("Recently added", 19, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        recentHead.addView(text(String.format(Locale.ROOT, "%02d ITEMS", mediaItems.size()), 9, MUTED, true));
        page.addView(recentHead, lp(-1, dp(30)));
        space(page, 10);
        if (mediaItems.isEmpty()) {
            emptyState(page, "Your collection is waiting", "Add videos and music from your phone. Lumio plays them locally.");
        } else {
            LinearLayout recent = new LinearLayout(this);
            recent.setOrientation(LinearLayout.HORIZONTAL);
            HorizontalScrollView shelfScroll = new HorizontalScrollView(this);
            shelfScroll.setHorizontalScrollBarEnabled(false);
            int start = Math.max(0, mediaItems.size() - 8);
            for (int i = mediaItems.size() - 1; i >= start; i--) addPosterCard(recent, mediaItems.get(i), 150, 100);
            shelfScroll.addView(recent);
            page.addView(shelfScroll, lp(-1, dp(157)));
        }
    }

    private void buildLibrary(LinearLayout page, boolean videos) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(text(videos ? "CINEMA / LIBRARY" : "SOUND / LIBRARY", 9, LIME, true));
        space(heading, 6);
        heading.addView(text(videos ? "Your videos" : "Your music", 31, TEXT, true));
        space(heading, 4);
        heading.addView(text(videos ? "The big screen, wherever you are." : "Every track, all in one place.", 12, MUTED, false));
        page.addView(heading);
        space(page, 19);

        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(15), dp(13), dp(15), dp(13));
        summary.setBackground(gradientShape(videos ? Color.rgb(31, 38, 52) : Color.rgb(53, 32, 38),
            Color.rgb(24, 24, 29), 17));
        LinearLayout counts = new LinearLayout(this);
        counts.setOrientation(LinearLayout.VERTICAL);
        counts.addView(text(videos ? "YOUR SCREENING ROOM" : "YOUR SOUNDTRACK", 9, videos ? Color.rgb(177, 195, 255) : Color.rgb(255, 176, 171), true));
        space(counts, 5);
        counts.addView(text(mediaCount(videos) + (videos ? (mediaCount(videos) == 1 ? " video" : " videos") :
            (mediaCount(videos) == 1 ? " audio file" : " audio files")), 17, TEXT, true));
        summary.addView(counts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView plus = text("＋", 25, BG, true);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(shape(LIME, 12));
        plus.setOnClickListener(v -> openMedia.launch(new String[]{videos ? "video/*" : "audio/*"}));
        summary.addView(plus, lp(dp(42), dp(42)));
        page.addView(summary, lp(-1, -2));
        space(page, 23);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(text(videos ? "All videos" : "All tracks", 19, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(text("NEWEST FIRST  ⌄", 8, MUTED, true));
        page.addView(bar, lp(-1, dp(30)));
        space(page, 12);

        ArrayList<Uri> filtered = new ArrayList<>();
        for (int i = mediaItems.size() - 1; i >= 0; i--) {
            Uri uri = mediaItems.get(i);
            if (isVideo(uri) == videos) filtered.add(uri);
        }
        if (filtered.isEmpty()) {
            emptyState(page, videos ? "Nothing on screen yet" : "It's quiet in here",
                videos ? "Import movies, clips or recordings and their cover frames will appear here." :
                "Import songs, albums or audio files to build your listening library.");
            return;
        }
        if (videos) {
            for (int i = 0; i < filtered.size(); i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(createVideoTile(filtered.get(i)), new LinearLayout.LayoutParams(0, dp(190), 1));
                row.addView(new View(this), lp(dp(11), 1));
                if (i + 1 < filtered.size()) row.addView(createVideoTile(filtered.get(i + 1)), new LinearLayout.LayoutParams(0, dp(190), 1));
                else row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                LinearLayout.LayoutParams rp = lp(-1, dp(190)); rp.bottomMargin = dp(18);
                page.addView(row, rp);
            }
        } else {
            for (Uri uri : filtered) addMusicRow(page, uri);
        }
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

    private Uri latestMedia() {
        return mediaItems.isEmpty() ? null : mediaItems.get(mediaItems.size() - 1);
    }

    private void addModeChip(LinearLayout parent, String label, boolean selected, Runnable action) {
        TextView chip = text(label, 11, selected ? BG : MUTED, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setBackground(shape(selected ? LIME : SURFACE, 16));
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = lp(-2, dp(34));
        p.rightMargin = dp(8);
        parent.addView(chip, p);
    }

    private LinearLayout createCollectionCard(String title, String count, String glyph, int color, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(10));
        card.setBackground(gradientShape(color, SURFACE, 16));
        TextView icon = text(glyph, 25, LIME, true);
        card.addView(icon);
        space(card, 8);
        card.addView(text(title, 9, TEXT, true));
        space(card, 3);
        card.addView(text(count, 10, MUTED, false));
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private FrameLayout mediaArtwork(Uri uri, int heightDp, boolean video, int radius) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(gradientShape(video ? Color.rgb(38, 47, 65) : Color.rgb(64, 34, 43),
            Color.rgb(21, 22, 27), radius));
        frame.setClipToOutline(true);
        if (uri != null) {
            Bitmap art = extractArtwork(uri, video);
            if (art != null) {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageBitmap(art);
                frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            } else {
                addArtworkFallback(frame, video);
            }
        } else addArtworkFallback(frame, true);
        if (heightDp > 0) frame.setMinimumHeight(dp(heightDp));
        return frame;
    }

    private void addArtworkFallback(FrameLayout frame, boolean video) {
        TextView monogram = text(video ? "L / FRAME" : "L / SOUND", 10, 0xAAFFFFFF, true);
        monogram.setLetterSpacing(0.16f);
        monogram.setRotation(-8f);
        FrameLayout.LayoutParams mark = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        frame.addView(monogram, mark);
        TextView big = text(video ? "PLAY" : "SIDE A", 37, 0x22FFFFFF, true);
        big.setRotation(-8f);
        FrameLayout.LayoutParams bg = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        frame.addView(big, bg);
        View accent = new View(this);
        accent.setBackground(shape(LIME, 3));
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(46), dp(4), Gravity.BOTTOM | Gravity.LEFT);
        ap.leftMargin = dp(15); ap.bottomMargin = dp(17);
        frame.addView(accent, ap);
    }

    private Bitmap extractArtwork(Uri uri, boolean video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            if (video) return retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            byte[] bytes = retriever.getEmbeddedPicture();
            if (bytes != null) return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private void addPosterCard(LinearLayout parent, Uri uri, int width, int height) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout art = mediaArtwork(uri, height, isVideo(uri), 13);
        card.addView(art, lp(dp(width), dp(height)));
        space(card, 7);
        TextView name = text(displayName(uri), 11, TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name);
        space(card, 2);
        card.addView(text(isVideo(uri) ? "VIDEO" : "AUDIO", 8, MUTED, true));
        card.setOnClickListener(v -> playUri(uri));
        LinearLayout.LayoutParams p = lp(dp(width), -2);
        p.rightMargin = dp(10);
        parent.addView(card, p);
    }

    private LinearLayout createVideoTile(Uri uri) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        FrameLayout art = mediaArtwork(uri, 132, true, 14);
        TextView play = text("▶", 15, Color.WHITE, true);
        play.setGravity(Gravity.CENTER);
        play.setBackground(shape(0x77000000, 18));
        FrameLayout.LayoutParams playp = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER);
        art.addView(play, playp);
        tile.addView(art, lp(-1, dp(132)));
        space(tile, 8);
        TextView name = text(displayName(uri), 12, TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(name);
        space(tile, 3);
        tile.addView(text("TAP TO WATCH", 8, MUTED, true));
        tile.setOnClickListener(v -> playUri(uri));
        return tile;
    }

    private void addMusicRow(LinearLayout page, Uri uri) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(9), dp(12), dp(9));
        row.setBackground(shape(SURFACE, 15));
        FrameLayout art = mediaArtwork(uri, 54, false, 11);
        row.addView(art, lp(dp(54), dp(54)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(5), 0);
        TextView name = text(displayName(uri), 12, TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        space(info, 4);
        info.addView(text("LOCAL AUDIO  ·  READY TO PLAY", 8, MUTED, true));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("▶", 13, LIME, true));
        row.setOnClickListener(v -> playUri(uri));
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(8);
        page.addView(row, p);
    }

    private void showLibrarySearch() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("Search your media");
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Find something to play")
            .setView(input)
            .setPositiveButton("Search", (dialog, which) -> {
                String query = input.getText().toString().trim().toLowerCase(Locale.ROOT);
                if (query.isEmpty()) return;
                Uri match = null;
                for (Uri uri : mediaItems) if (displayName(uri).toLowerCase(Locale.ROOT).contains(query)) { match = uri; break; }
                if (match != null) playUri(match);
                else Toast.makeText(this, "No matching media found", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
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
        bottomNav.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottomNav.setBackgroundColor(BG);
        String[] tabs = {"Home", "Videos", "Music", "Settings"};
        String[] icons = {"⌂", "▣", "♫", "⚙"};
        for (int i = 0; i < tabs.length; i++) {
            final String tab = tabs[i];
            boolean selected = tab.equals(activeTab);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(5), 0, dp(5), 0);
            if (selected) item.setBackground(shape(Color.rgb(39, 29, 29), 16));
            TextView glyph = text(icons[i], 17, selected ? LIME : MUTED, true);
            glyph.setGravity(Gravity.CENTER);
            item.addView(glyph, lp(dp(27), dp(36)));
            if (selected) {
                TextView label = text(tab, 10, TEXT, true);
                item.addView(label);
            }
            item.setOnClickListener(v -> renderTab(tab));
            bottomNav.addView(item, new LinearLayout.LayoutParams(0, -1, selected ? 1.35f : 0.85f));
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
        page.setPadding(dp(17), dp(10), dp(17), dp(16));
        page.setBackgroundColor(BG);
        screenHost.addView(page, lp(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 31, TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> renderTab(isVideo(uri) ? "Videos" : "Music"));
        top.addView(back, lp(dp(40), dp(42)));
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setGravity(Gravity.CENTER);
        titleBlock.addView(text(isVideo(uri) ? "NOW SHOWING" : "NOW PLAYING", 9, LIME, true));
        titleBlock.addView(text("LUMIO", 8, MUTED, true));
        top.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text("•••", 19, TEXT, true);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> showPlayerOptions());
        top.addView(more, lp(dp(40), dp(42)));
        page.addView(top, lp(-1, dp(42)));
        space(page, 8);

        if (isVideo(uri)) {
            playerView = new PlayerView(this);
            playerView.setPlayer(player);
            playerView.setUseController(true);
            playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
            playerView.setShowNextButton(false);
            playerView.setShowPreviousButton(false);
            playerView.setBackground(shape(Color.BLACK, 17));
            playerView.setClipToOutline(true);
            LinearLayout.LayoutParams vp = lp(-1, 0);
            vp.weight = 1f;
            page.addView(playerView, vp);
            space(page, 15);
            page.addView(text("YOUR VIDEO", 9, LIME, true));
            space(page, 5);
            TextView name = text(displayName(uri), 20, TEXT, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            page.addView(name);
            space(page, 6);
            page.addView(text("Stored and played locally on this device.", 11, MUTED, false));
            space(page, 13);
            LinearLayout actions = new LinearLayout(this);
            addAction(actions, "Subtitles", () -> openSubtitle.launch(new String[]{"text/*", "application/x-subrip", "application/octet-stream"}), 1f);
            actions.addView(new View(this), lp(dp(9), 1));
            addAction(actions, "⛶  Fullscreen", this::toggleFullscreen, 1f);
            page.addView(actions, lp(-1, dp(46)));
        } else {
            FrameLayout art = mediaArtwork(uri, 0, false, 26);
            LinearLayout.LayoutParams artP = lp(-1, 0);
            artP.weight = 1f;
            artP.topMargin = dp(4);
            artP.bottomMargin = dp(18);
            page.addView(art, artP);
            TextView flag = text("LUMIO / AUDIO", 9, LIME, true);
            flag.setLetterSpacing(0.12f);
            page.addView(flag);
            space(page, 7);
            TextView title = text(displayName(uri), 22, TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            page.addView(title);
            space(page, 5);
            page.addView(text("Your own soundtrack.", 12, MUTED, false));
            space(page, 15);
            LinearLayout progressShell = new LinearLayout(this);
            progressShell.setOrientation(LinearLayout.VERTICAL);
            View track = new View(this);
            track.setBackground(shape(Color.rgb(59, 60, 65), 4));
            progressShell.addView(track, lp(-1, dp(3)));
            page.addView(progressShell, lp(-1, dp(3)));
            space(page, 17);
            LinearLayout transport = new LinearLayout(this);
            transport.setGravity(Gravity.CENTER);
            addAction(transport, "◀|", () -> playAdjacent(-1), 0.85f);
            Button play = button(player != null && player.isPlaying() ? "Ⅱ" : "▶", true);
            play.setTextSize(20);
            play.setOnClickListener(v -> {
                if (player == null) return;
                if (player.isPlaying()) player.pause(); else player.play();
                showPlayer(currentMediaUri);
            });
            LinearLayout.LayoutParams playp = new LinearLayout.LayoutParams(0, dp(56), 1.2f);
            playp.leftMargin = dp(8); playp.rightMargin = dp(8);
            transport.addView(play, playp);
            addAction(transport, "|▶", () -> playAdjacent(1), 0.85f);
            page.addView(transport, lp(-1, dp(56)));
            space(page, 14);
            LinearLayout options = new LinearLayout(this);
            options.setGravity(Gravity.CENTER_VERTICAL);
            TextView speed = text("SPEED  " + playbackSpeed + "×", 10, TEXT, true);
            speed.setGravity(Gravity.CENTER);
            speed.setBackground(shape(SURFACE, 13));
            speed.setOnClickListener(v -> { cyclePlaybackSpeed(); speed.setText("SPEED  " + playbackSpeed + "×"); });
            options.addView(speed, new LinearLayout.LayoutParams(0, dp(42), 1));
            options.addView(new View(this), lp(dp(8), 1));
            TextView queue = text("OPEN MUSIC LIBRARY  ›", 9, TEXT, true);
            queue.setGravity(Gravity.CENTER);
            queue.setBackground(shape(SURFACE, 13));
            queue.setOnClickListener(v -> renderTab("Music"));
            options.addView(queue, new LinearLayout.LayoutParams(0, dp(42), 1.4f));
            page.addView(options, lp(-1, dp(42)));
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
        b.setBackground(shape(primary ? LIME : SURFACE_2, primary ? 14 : 12));
        return b;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setBackground(shape(SURFACE, 15));
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
