package com.lumio.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.compose.setContent;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.compose.foundation.layout.Arrangement;
import androidx.compose.foundation.layout.Box;
import androidx.compose.foundation.layout.Column;
import androidx.compose.foundation.layout.fillMaxSize;
import androidx.compose.foundation.layout.padding;
import androidx.compose.material3.Button;
import androidx.compose.material3.CircularProgressIndicator;
import androidx.compose.material3.MaterialTheme;
import androidx.compose.material3.Surface;
import androidx.compose.material3.Text;
import androidx.compose.material3.lightColorScheme;
import androidx.compose.runtime.getValue;
import androidx.compose.runtime.mutableStateOf;
import androidx.compose.runtime.setValue;
import androidx.compose.ui.Alignment;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.graphics.Color as ComposeColor;
import androidx.compose.ui.unit.dp;
import androidx.core.content.ContextCompat;

import com.lumio.player.music.NativeMusicLibrary;
import com.lumio.player.music.NativeMusicScreen;
import com.lumio.player.music.NativeMusicTrack;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-stage native Music library. Kept separate from MainActivity so the
 * existing WebView-based Video experience and playback bridge remain intact.
 */
public final class NativeMusicActivity extends ComponentActivity {
    private final ExecutorService musicExecutor = Executors.newSingleThreadExecutor();
    private List<NativeMusicTrack> tracks = new ArrayList<>();
    private boolean loading = true;
    private boolean permissionDenied = false;
    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(245, 247, 249));
        getWindow().setNavigationBarColor(Color.rgb(245, 247, 249));
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    permissionDenied = !granted;
                    if (granted) loadMusic();
                    else loading = false;
                });

        setContent(() -> {
            MaterialTheme(
                    colorScheme = lightColorScheme(
                            primary = ComposeColor(0xFF397FB3),
                            onPrimary = ComposeColor(0xFFFFFFFF),
                            background = ComposeColor(0xFFF5F7F9),
                            onBackground = ComposeColor(0xFF202833),
                            surface = ComposeColor(0xFFFFFFFF),
                            onSurface = ComposeColor(0xFF202833),
                            surfaceVariant = ComposeColor(0xFFE9EEF3),
                            onSurfaceVariant = ComposeColor(0xFF5C6774),
                            secondary = ComposeColor(0xFF69B7E8)
                    ),
                    content = () -> {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.getBackground()) {
                            if (loading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CircularProgressIndicator()
                                        Text("Loading your music…")
                                    }
                                }
                            } else if (permissionDenied) {
                                Column(
                                        modifier = Modifier.fillMaxSize().padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                ) {
                                    Text("Music access is required to show local songs.")
                                    Button(onClick = () -> requestMusicPermission()) {
                                        Text("Allow music access")
                                    }
                                    Button(onClick = () -> finish()) { Text("Back to LUMIO") }
                                }
                            } else {
                                NativeMusicScreen(
                                        tracks = tracks,
                                        onPlay = track -> playTrack(track)
                                )
                            }
                        }
                    }
            );
        });

        requestMusicPermission();
    }

    private String requiredPermission() {
        if (Build.VERSION.SDK_INT >= 33) return Manifest.permission.READ_MEDIA_AUDIO;
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void requestMusicPermission() {
        String permission = requiredPermission();
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false;
            loadMusic();
        } else {
            permissionLauncher.launch(permission);
        }
    }

    private void loadMusic() {
        loading = true;
        permissionDenied = false;
        musicExecutor.execute(() -> {
            List<NativeMusicTrack> scanned = NativeMusicLibrary.load(this);
            runOnUiThread(() -> {
                tracks = scanned;
                loading = false;
            });
        });
    }

    private void playTrack(NativeMusicTrack track) {
        if (track == null) return;
        JSONArray playlist = new JSONArray();
        for (NativeMusicTrack item : tracks) {
            try {
                JSONObject row = new JSONObject();
                row.put("uri", item.getUri().toString());
                row.put("name", item.getTitle());
                row.put("type", "audio");
                playlist.put(row);
            } catch (Exception ignored) { }
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URI, track.getUri().toString());
        intent.putExtra(PlayerActivity.EXTRA_TITLE, track.getTitle());
        intent.putExtra(PlayerActivity.EXTRA_AUDIO, true);
        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST, playlist.toString());
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        musicExecutor.shutdownNow();
        super.onDestroy();
    }
}
