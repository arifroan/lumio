package com.lumio.player.music

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import android.database.Cursor

/** A lightweight, native representation of an audio item indexed by Android. */
data class NativeMusicTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** Relative path on Android 10+, or a best-effort parent path on older versions. */
    val folderPath: String
)

object NativeMusicLibrary {
    /**
     * Reads only audio indexed by MediaStore. Call from a background dispatcher/thread.
     * Missing metadata is tolerated so one malformed item cannot break the library.
     */
    fun load(context: Context): List<NativeMusicTrack> {
        val resolver: ContentResolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA
        )
        val result = ArrayList<NativeMusicTrack>()
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val rawPath = if (dataCol >= 0 && !c.isNull(dataCol)) c.getString(dataCol) else ""
                    val folder = rawPath.substringBeforeLast('/', "").trimEnd('/')
                    result += NativeMusicTrack(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                        artist = c.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "Unknown artist",
                        album = c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown album",
                        durationMs = if (c.isNull(durationCol)) 0L else c.getLong(durationCol),
                        sizeBytes = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol),
                        folderPath = folder
                    )
                }
            }
        } catch (_: SecurityException) {
            // The caller should request READ_MEDIA_AUDIO / legacy storage permission and retry.
        } catch (_: IllegalArgumentException) {
            // Some OEM MediaStore providers expose a reduced schema; return the readable items.
        } finally {
            cursor?.close()
        }
        return result
    }
}
