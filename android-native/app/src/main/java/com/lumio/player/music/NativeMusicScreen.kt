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

/** Native Music library, isolated from the existing Video/WebView implementation. */
@Composable
fun NativeMusicScreen(
    tracks: List<NativeMusicTrack>,
    onPlay: (NativeMusicTrack) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var showFolders by remember { mutableStateOf(false) }

    val activeFolder = selectedFolder
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val visibleTracks = tracks.filter { track ->
        (activeFolder == null || track.folderPath.trimEnd('/') == activeFolder ||
            track.folderPath.trimEnd('/').startsWith("$activeFolder/")) &&
            (normalizedQuery.isEmpty() ||
                track.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                track.artist.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                track.album.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                track.folderPath.lowercase(Locale.ROOT).contains(normalizedQuery))
    }

    val allFolders = tracks.map { it.folderPath.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }

    val childFolders = if (activeFolder == null) {
        allFolders
    } else {
        allFolders.filter { it.startsWith("$activeFolder/") }
            .map { it.removePrefix("$activeFolder/").substringBefore('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .map { "$activeFolder/$it" }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }

    val directTracks = visibleTracks.filter { it.folderPath.trimEnd('/') == activeFolder }
    val folderTracks = if (activeFolder == null) emptyList() else visibleTracks

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LUMIO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                }
                Button(onClick = onBack) {
                    Text("Back")
                }
            }
            Text(
                text = if (activeFolder == null) "Your local audio library"
                    else activeFolder.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text("Search songs, artists, albums, folders") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showFolders = false; selectedFolder = null }) { Text("Songs") }
                Button(onClick = { showFolders = true }) { Text("Folders") }
                if (selectedFolder != null) {
                    Button(onClick = { selectedFolder = null; showFolders = true }) { Text("All folders") }
                }
            }

            if (showFolders) {
                Text(
                    if (activeFolder == null) "Folders · ${childFolders.size}" else
                        "${activeFolder.substringAfterLast('/')} · ${directTracks.size} tracks · ${childFolders.size} subfolders",
                    style = MaterialTheme.typography.titleMedium
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(childFolders, key = { "folder:$it" }) { folder ->
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
                    items(folderTracks, key = { "track:${it.id}" }) { track ->
                        TrackRow(track = track, onClick = { onPlay(track) })
                    }
                }
            } else {
                Text("Songs · ${visibleTracks.size}", style = MaterialTheme.typography.titleMedium)
                if (visibleTracks.isEmpty()) {
                    Text(
                        "No audio tracks were found. Add music to device storage or check the audio permission.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(visibleTracks, key = { it.id }) { track ->
                        TrackRow(track = track, onClick = { onPlay(track) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: NativeMusicTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
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

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
