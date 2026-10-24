package com.lumio.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lumio.player.music.NativeMusicLibrary
import com.lumio.player.music.NativeMusicScreen
import com.lumio.player.music.NativeMusicTrack
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** First-stage native Music library, separate from the existing Video/WebView screen. */
class NativeMusicActivity : ComponentActivity() {
    private val musicExecutor = Executors.newSingleThreadExecutor()
    private var tracks by mutableStateOf<List<NativeMusicTrack>>(emptyList())
    private var loading by mutableStateOf(true)
    private var permissionDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        if (granted) loadMusic() else loading = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(245, 247, 249)
        window.navigationBarColor = AndroidColor.rgb(245, 247, 249)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF397FB3),
                    onPrimary = Color(0xFFFFFFFF),
                    background = Color(0xFFF5F7F9),
                    onBackground = Color(0xFF202833),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF202833),
                    surfaceVariant = Color(0xFFE9EEF3),
                    onSurfaceVariant = Color(0xFF5C6774),
                    secondary = Color(0xFF69B7E8)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text("Loading your music…")
                            }
                        }
                        permissionDenied -> Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Music access is needed to show songs stored on this device.")
                            Button(onClick = { requestMusicPermission() }) { Text("Allow music access") }
                            Button(onClick = { finish() }) { Text("Back to LUMIO") }
                        }
                        else -> NativeMusicScreen(
                            tracks = tracks,
                            onPlay = { playTrack(it) },
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
        requestMusicPermission()
    }

    private fun requestMusicPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            loadMusic()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun loadMusic() {
        loading = true
        permissionDenied = false
        musicExecutor.execute {
            val scanned = NativeMusicLibrary.load(this)
            runOnUiThread {
                tracks = scanned
                loading = false
            }
        }
    }

    private fun playTrack(track: NativeMusicTrack) {
        val playlist = JSONArray()
        tracks.forEach { item ->
            try {
                playlist.put(JSONObject().apply {
                    put("uri", item.uri.toString())
                    put("name", item.title)
                    put("type", "audio")
                })
            } catch (_: Exception) { }
        }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URI, track.uri.toString())
            putExtra(PlayerActivity.EXTRA_TITLE, track.title)
            putExtra(PlayerActivity.EXTRA_AUDIO, true)
            putExtra(PlayerActivity.EXTRA_PLAYLIST, playlist.toString())
        })
    }

    override fun onDestroy() {
        musicExecutor.shutdownNow()
        super.onDestroy()
    }
}
