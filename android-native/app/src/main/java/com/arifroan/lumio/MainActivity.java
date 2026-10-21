package com.arifroan.lumio;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(9, 11, 18);
    private static final int PANEL = Color.rgb(20, 24, 36);
    private static final int TEXT = Color.rgb(243, 245, 251);
    private static final int MUTED = Color.rgb(151, 160, 180);
    private static final int MINT = Color.rgb(73, 224, 199);
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView nowPlaying;
    private ActivityResultLauncher<String[]> openMedia;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        openMedia = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
                playUri(uri);
            }
        );

        buildNativeInterface();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    nowPlaying.setText("Finished playing");
                }
            }
        });
    }

    private void buildNativeInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(28));
        scroll.addView(root);

        LinearLayout appBar = new LinearLayout(this);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        TextView logo = new TextView(this);
        logo.setText("▶");
        logo.setTextColor(BG);
        logo.setTextSize(22);
        logo.setGravity(Gravity.CENTER);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setBackground(roundRect(MINT, 15));
        appBar.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        TextView title = text("Lumio", 22, TEXT, true);
        TextView subtitle = text("MEDIA PLAYER", 10, MUTED, true);
        subtitle.setLetterSpacing(0.16f);
        brand.addView(title);
        brand.addView(subtitle);
        appBar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        TextView privacy = text("●  PRIVATE", 10, MINT, true);
        appBar.addView(privacy);
        root.addView(appBar, params(-1, dp(58)));
        addSpace(root, 28);

        TextView eyebrow = text("YOUR MEDIA, YOUR SPACE", 11, MINT, true);
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);
        addSpace(root, 8);

        TextView heading = text("Press play on\nyour world.", 34, TEXT, true);
        heading.setLineSpacing(dp(1), 1.0f);
        root.addView(heading);
        addSpace(root, 8);

        TextView intro = text("A beautiful home for your videos and music.", 14, MUTED, false);
        root.addView(intro);
        addSpace(root, 22);

        LinearLayout playerCard = new LinearLayout(this);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setPadding(dp(8), dp(8), dp(8), dp(12));
        playerCard.setBackground(roundRect(PANEL, 24));
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setBackgroundColor(Color.BLACK);
        playerCard.addView(playerView, new LinearLayout.LayoutParams(-1, dp(245)));

        nowPlaying = text("Nothing playing yet", 15, TEXT, true);
        nowPlaying.setMaxLines(1);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlaying.setPadding(dp(8), dp(16), dp(8), dp(4));
        playerCard.addView(nowPlaying);
        TextView helper = text("Choose a local file to begin", 12, MUTED, false);
        helper.setPadding(dp(8), 0, dp(8), dp(8));
        playerCard.addView(helper);
        root.addView(playerCard, params(-1, -2));

        addSpace(root, 18);
        Button open = new Button(this);
        open.setText("＋   Open video or audio");
        open.setTextSize(15);
        open.setAllCaps(false);
        open.setTextColor(BG);
        open.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        open.setBackground(roundRect(MINT, 16));
        open.setOnClickListener(v -> openMedia.launch(new String[]{"video/*", "audio/*"}));
        root.addView(open, params(-1, dp(58)));

        addSpace(root, 20);
        LinearLayout tipCard = new LinearLayout(this);
        tipCard.setOrientation(LinearLayout.VERTICAL);
        tipCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        tipCard.setBackground(roundRect(Color.rgb(17, 25, 32), 18));
        tipCard.addView(text("YOUR PRIVACY MATTERS", 10, MINT, true));
        addSpace(tipCard, 7);
        tipCard.addView(text("Your files stay on your device. Select media with Android's file picker—nothing is uploaded.", 13, MUTED, false));
        root.addView(tipCard, params(-1, -2));
        addSpace(root, 16);
        TextView version = text("LUMIO  •  NATIVE ANDROID PROTOTYPE", 10, MUTED, true);
        version.setGravity(Gravity.CENTER);
        root.addView(version, params(-1, dp(32)));

        setContentView(scroll);
    }

    private void playUri(Uri uri) {
        if (player == null) return;
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
        String name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "Selected media";
        nowPlaying.setText(name);
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
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
