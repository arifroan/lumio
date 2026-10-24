package com.lumio.player.music

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** A lightweight native representation of an audio item indexed by Android. */
data class NativeMusicTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folderPath: String
)

object NativeMusicLibrary {
    /** Reads MediaStore audio rows. Run from a background thread. */
    fun load(context: Context): List<NativeMusicTrack> {
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
        try {
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = if (dataCol >= 0 && !cursor.isNull(dataCol)) cursor.getString(dataCol) else ""
                    result += NativeMusicTrack(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                        artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "Unknown artist",
                        album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown album",
                        durationMs = if (cursor.isNull(durationCol)) 0L else cursor.getLong(durationCol),
                        sizeBytes = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol),
                        folderPath = path.substringBeforeLast('/', "").trimEnd('/')
                    )
                }
            }
        } catch (_: SecurityException) {
            // Activity requests READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE before calling this.
        } catch (_: IllegalArgumentException) {
            // Tolerate reduced MediaStore schemas on some OEMs.
        }
        return result
    }
}
