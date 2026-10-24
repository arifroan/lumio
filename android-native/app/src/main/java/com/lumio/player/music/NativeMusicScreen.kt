package com.lumio.player.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Isolated Compose implementation for LUMIO's Music library.
 *
 * It intentionally does not replace MainActivity/WebView yet. Wire it into the
 * existing navigation only after the app builds and the native library is tested.
 */
@Composable
fun NativeMusicScreen(
    tracks: List<NativeMusicTrack>,
    onPlay: (NativeMusicTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var showFolders by remember { mutableStateOf(false) }

    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val visibleTracks = tracks.filter { track ->
        (selectedFolder == null || track.folderPath.trimEnd('/') == selectedFolder) &&
            (normalizedQuery.isEmpty() ||
                track.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                track.artist.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                track.album.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                track.folderPath.lowercase(Locale.ROOT).contains(normalizedQuery))
    }

    val childFolders = if (selectedFolder == null) {
        tracks.map { it.folderPath.trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase(Locale.ROOT) }
    } else {
        tracks.map { it.folderPath.trimEnd('/') }
            .filter { it.startsWith("$selectedFolder/") }
            .map { it.removePrefix("$selectedFolder/").substringBefore('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .map { "$selectedFolder/$it" }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Music",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (selectedFolder == null) "Offline library" else selectedFolder!!.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text("Search songs, artists, albums") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showFolders = false }) { Text("Songs") }
                Button(onClick = { showFolders = true }) { Text("Folders") }
                if (selectedFolder != null) {
                    Button(onClick = { selectedFolder = null; showFolders = true }) { Text("All folders") }
                }
            }

            if (showFolders) {
                Text("Folders · ${childFolders.size}", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(childFolders, key = { it }) { folder ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedFolder = folder
                                showFolders = true
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folder.substringAfterLast('/'), fontWeight = FontWeight.Medium)
                                    Text(
                                        folder,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("›", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            } else {
                Text("Songs · ${visibleTracks.size}", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(visibleTracks, key = { it.id }) { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onPlay(track) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${track.artist}  ·  ${track.album}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.padding(2.dp))
                            Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
