package com.lumio.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.os.CancellationSignal;
import android.util.Base64;
import android.util.Size;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_PICK_MEDIA = 4107;
    private static final int REQUEST_SCAN_PERMISSION = 4108;
    private static final int REQUEST_SCAN_AFTER_PICK = 4109;
    private WebView webView;
    private boolean pendingScan;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(16, 16, 24));
        window.setNavigationBarColor(Color.rgb(16, 16, 24));
        window.getDecorView().setSystemUiVisibility(0);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(16, 16, 24));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return scheme != null && !(scheme.equals("file") || scheme.equals("content") || scheme.equals("about"));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                publishLibraryState();
            }
        });
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        webView.addJavascriptInterface(new LumioBridge(), "LumioNative");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/player.html");
    }

    private final class LumioBridge {
        @JavascriptInterface
        public void openVault() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, VaultActivity.class)));
        }

        @JavascriptInterface
        public void pickMedia() {
            runOnUiThread(() -> launchMediaPicker());
        }

        @JavascriptInterface
        public void scanLocalMedia() {
            runOnUiThread(() -> beginMediaScan());
        }

        @JavascriptInterface
        public void requestThumbnail(String mediaUri, String key) {
            if (mediaUri == null || mediaUri.trim().isEmpty() || key == null || key.trim().isEmpty()) return;
            final Uri uri;
            try {
                uri = Uri.parse(mediaUri);
                String scheme = uri.getScheme();
                if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) return;
            } catch (Exception e) {
                return;
            }
            thumbnailExecutor.execute(() -> {
                String dataUri = "";
                try {
                    byte[] jpeg = loadOrCreateThumbnail(uri);
                    if (jpeg != null && jpeg.length > 0) {
                        dataUri = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP);
                    }
                } catch (Exception ignored) {
                    // A missing/unsupported video should keep its fallback tile, not crash the library.
                }
                final String callback = "window.onNativeThumbnail && window.onNativeThumbnail("
                        + JSONObject.quote(key) + "," + JSONObject.quote(dataUri) + ")";
                runOnUiThread(() -> {
                    WebView current = webView;
                    if (current != null) current.evaluateJavascript(callback, null);
                });
            });
        }

        @JavascriptInterface
        public String getLibraryState() {
            return buildLibraryStateJson();
        }

        @JavascriptInterface
        public boolean toggleFavorite(String uri) {
            return toggleFavoriteUri(uri);
        }

        @JavascriptInterface
        public void showMediaActions(String uri, String title, long sizeBytes, long durationMs, boolean isAudio) {
            runOnUiThread(() -> showMediaActionsDialog(uri, title, sizeBytes, durationMs, isAudio));
        }

        @JavascriptInterface
        public void playMedia(String uri, String title, boolean isAudio, String playlistJson) {
            runOnUiThread(() -> {
                if (uri == null || uri.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "Choose a media file first", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URI, uri);
                intent.putExtra(PlayerActivity.EXTRA_TITLE, title == null ? "Local media" : title);
                intent.putExtra(PlayerActivity.EXTRA_AUDIO, isAudio);
                intent.putExtra(PlayerActivity.EXTRA_PLAYLIST, playlistJson == null ? "[]" : playlistJson);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not open this media file", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private String buildLibraryStateJson() {
        JSONObject state = new JSONObject();
        JSONArray favorites = new JSONArray();
        JSONArray history;
        try {
            Set<String> savedFavorites = new HashSet<>(getSharedPreferences("lumio_library", MODE_PRIVATE)
                    .getStringSet("favorite_uris", new HashSet<>()));
            List<String> orderedFavorites = new ArrayList<>(savedFavorites);
            java.util.Collections.sort(orderedFavorites);
            for (String uri : orderedFavorites) if (uri != null && !uri.trim().isEmpty()) favorites.put(uri);

            String rawHistory = getSharedPreferences("lumio_playback", MODE_PRIVATE)
                    .getString("watch_history", "[]");
            history = new JSONArray(rawHistory);
        } catch (Exception ignored) {
            history = new JSONArray();
        }
        try {
            state.put("favorites", favorites);
            state.put("history", history);
        } catch (JSONException ignored) { }
        return state.toString();
    }

    private void publishLibraryState() {
        String state = buildLibraryStateJson();
        callJs("window.onNativeLibraryState && window.onNativeLibraryState("
                + JSONObject.quote(state) + ")");
    }

    private boolean isFavoriteUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) return false;
        Set<String> saved = getSharedPreferences("lumio_library", MODE_PRIVATE)
                .getStringSet("favorite_uris", new HashSet<>());
        return saved.contains(uri);
    }

    private boolean toggleFavoriteUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) return false;
        android.content.SharedPreferences preferences = getSharedPreferences("lumio_library", MODE_PRIVATE);
        Set<String> saved = new HashSet<>(preferences.getStringSet("favorite_uris", new HashSet<>()));
        boolean nowFavorite;
        if (saved.contains(uri)) {
            saved.remove(uri);
            nowFavorite = false;
        } else {
            saved.add(uri);
            nowFavorite = true;
        }
        preferences.edit().putStringSet("favorite_uris", saved).apply();
        return nowFavorite;
    }

    private boolean hasHistoryEntry(String uri) {
        if (uri == null || uri.trim().isEmpty()) return false;
        try {
            JSONArray history = new JSONArray(getSharedPreferences("lumio_playback", MODE_PRIVATE)
                    .getString("watch_history", "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row != null && uri.equals(row.optString("uri", ""))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void removeFromWatchHistory(String uri) {
        if (uri == null || uri.trim().isEmpty()) return;
        android.content.SharedPreferences preferences = getSharedPreferences("lumio_playback", MODE_PRIVATE);
        JSONArray keep = new JSONArray();
        try {
            JSONArray history = new JSONArray(preferences.getString("watch_history", "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row == null || !uri.equals(row.optString("uri", ""))) {
                    if (row != null) keep.put(row);
                }
            }
        } catch (Exception ignored) { }
        android.content.SharedPreferences.Editor editor = preferences.edit()
                .putString("watch_history", keep.toString())
                .remove("position:" + uri);
        if (uri.equals(preferences.getString("last_uri", ""))) editor.remove("last_uri");
        editor.apply();
    }

    private void showMediaActionsDialog(String uri, String title, long sizeBytes, long durationMs, boolean isAudio) {
        if (uri == null || uri.trim().isEmpty()) return;
        String safeTitle = title == null || title.trim().isEmpty() ? "Media item" : title;
        List<String> labels = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        labels.add("Play now"); actions.add("play");
        boolean favorite = isFavoriteUri(uri);
        labels.add(favorite ? "Remove from Favorites" : "Add to Favorites"); actions.add("favorite");
        if (hasHistoryEntry(uri)) {
            labels.add("Remove from Recently Played"); actions.add("remove_recent");
        }
        labels.add("File details"); actions.add("details");
        new AlertDialog.Builder(this)
                .setTitle(safeTitle)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String action = actions.get(which);
                    if ("play".equals(action)) {
                        Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                        intent.putExtra(PlayerActivity.EXTRA_URI, uri);
                        intent.putExtra(PlayerActivity.EXTRA_TITLE, safeTitle);
                        intent.putExtra(PlayerActivity.EXTRA_AUDIO, isAudio);
                        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST, "[]");
                        try { startActivity(intent); }
                        catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Could not open this media file", Toast.LENGTH_LONG).show();
                        }
                    } else if ("favorite".equals(action)) {
                        boolean nowFavorite = toggleFavoriteUri(uri);
                        Toast.makeText(MainActivity.this, nowFavorite ? "Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                        publishLibraryState();
                    } else if ("remove_recent".equals(action)) {
                        removeFromWatchHistory(uri);
                        Toast.makeText(MainActivity.this, "Removed from Recently Played", Toast.LENGTH_SHORT).show();
                        publishLibraryState();
                    } else if ("details".equals(action)) {
                        String size = formatFileSize(sizeBytes);
                        String duration = durationMs > 0L
                                ? String.format(Locale.US, "%d:%02d", durationMs / 60000L, (durationMs / 1000L) % 60L)
                                : "Unknown";
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("File details")
                                .setMessage(safeTitle + "\n\nType: " + (isAudio ? "Audio" : "Video")
                                        + "\nSize: " + size + "\nDuration: " + duration)
                                .setPositiveButton("Close", null)
                                .show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String formatFileSize(long bytes) {
        if (bytes >= 1073741824L) return String.format(Locale.US, "%.2f GB", bytes / 1073741824d);
        if (bytes >= 1048576L) return String.format(Locale.US, "%.1f MB", bytes / 1048576d);
        if (bytes >= 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024d);
        return Math.max(0L, bytes) + " B";
    }

    @Override
    protected void onResume() {
        super.onResume();
        publishLibraryState();
    }

    private byte[] loadOrCreateThumbnail(Uri uri) throws Exception {
        File folder = new File(getCacheDir(), "lumio_thumbnails");
        if (!folder.exists() && !folder.mkdirs()) return createThumbnailJpeg(uri);
        String cacheName = sha256(uri.toString()) + ".jpg";
        File cached = new File(folder, cacheName);
        if (cached.isFile() && cached.length() > 0 && cached.length() <= 512 * 1024) {
            try (FileInputStream in = new FileInputStream(cached);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return bytes.toByteArray();
            } catch (IOException ignored) {
                // A partially evicted cache entry can be regenerated below.
            }
        }

        byte[] jpeg = createThumbnailJpeg(uri);
        if (jpeg == null || jpeg.length == 0) return null;
        File temp = new File(folder, cacheName + "." + Thread.currentThread().getId() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(jpeg);
            out.flush();
        } catch (IOException e) {
            temp.delete();
            return jpeg;
        }
        if (!temp.renameTo(cached)) {
            temp.delete();
        }
        return jpeg;
    }

    private byte[] createThumbnailJpeg(Uri uri) throws Exception {
        Bitmap source = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                source = getContentResolver().loadThumbnail(uri, new Size(320, 180), new CancellationSignal());
            } catch (Exception ignored) {
                // Some content providers do not implement loadThumbnail; try the decoder fallback.
            }
        }
        if (source == null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                source = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception ignored) {
                // Keep a neutral fallback tile for unsupported or unavailable media.
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
        }
        if (source == null) return null;

        Bitmap scaled = source;
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= 0 || height <= 0) return null;
            float ratio = Math.min(1f, Math.min(320f / width, 180f / height));
            if (ratio < 1f) {
                scaled = Bitmap.createScaledBitmap(source,
                        Math.max(1, Math.round(width * ratio)),
                        Math.max(1, Math.round(height * ratio)), true);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 74, bytes)) return null;
            return bytes.toByteArray();
        } finally {
            if (scaled != source) scaled.recycle();
            source.recycle();
        }
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 0xff));
        return hex.toString();
    }

    private void launchMediaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_PICK_MEDIA);
        } catch (Exception e) {
            Toast.makeText(this, "This device has no document picker", Toast.LENGTH_LONG).show();
        }
    }

    private boolean canReadVideo() {
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canReadAudio() {
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAnyMediaReadPermission() {
        return canReadVideo() || canReadAudio();
    }

    private void beginMediaScan() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ grants video and audio access separately. Request any
            // missing permission instead of treating one granted permission as full access.
            List<String> missingPermissions = new ArrayList<>();
            if (!canReadVideo()) missingPermissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            if (!canReadAudio()) missingPermissions.add(Manifest.permission.READ_MEDIA_AUDIO);
            if (missingPermissions.isEmpty()) {
                publishScannedMedia();
                return;
            }
            pendingScan = true;
            requestPermissions(missingPermissions.toArray(new String[0]), REQUEST_SCAN_PERMISSION);
            return;
        }

        if (hasAnyMediaReadPermission()) {
            publishScannedMedia();
            return;
        }
        pendingScan = true;
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_SCAN_PERMISSION);
        } else {
            publishScannedMedia();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SCAN_PERMISSION && pendingScan) {
            pendingScan = false;
            if (hasAnyMediaReadPermission()) publishScannedMedia();
            else {
                JSONArray saved = getSavedPicks();
                callJs("window.onNativeMediaScan && window.onNativeMediaScan(" + saved.toString()
                        + ", 'Storage access was not granted. You can still open chosen files or grant access to scan the device.')");
            }
        }
    }

    private void publishScannedMedia() {
        JSONArray array = new JSONArray();
        if (canReadVideo()) appendStore(array, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true);
        if (canReadAudio()) appendStore(array, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false);
        JSONArray saved = getSavedPicks();
        for (int i = 0; i < saved.length(); i++) array.put(saved.optJSONObject(i));
        callJs("window.onNativeMediaScan && window.onNativeMediaScan(" + array.toString() + ", null)");
    }

    private void appendStore(JSONArray results, Uri collection, boolean video) {
        List<String> columns = new ArrayList<>();
        columns.add(MediaStore.MediaColumns._ID);
        columns.add(MediaStore.MediaColumns.DISPLAY_NAME);
        columns.add(MediaStore.MediaColumns.SIZE);
        columns.add(MediaStore.MediaColumns.MIME_TYPE);
        columns.add(MediaStore.MediaColumns.DATE_MODIFIED);
        if (video) columns.add(MediaStore.Video.Media.DURATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) columns.add(MediaStore.MediaColumns.RELATIVE_PATH);
        String sort = MediaStore.MediaColumns.DISPLAY_NAME + " COLLATE NOCASE ASC";
        try (Cursor cursor = getContentResolver().query(collection, columns.toArray(new String[0]), null, null, sort)) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int durationCol = video ? cursor.getColumnIndex(MediaStore.Video.Media.DURATION) : -1;
            while (cursor.moveToNext()) {
                long id = idCol >= 0 ? cursor.getLong(idCol) : -1;
                if (id < 0) continue;
                Uri fileUri = ContentUris.withAppendedId(collection, id);
                String name = nameCol >= 0 ? cursor.getString(nameCol) : (video ? "Video" : "Audio");
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
                JSONObject entry = new JSONObject();
                try {
                    entry.put("key", fileUri.toString());
                    entry.put("uri", fileUri.toString());
                    entry.put("name", name == null ? "Local media" : name);
                    entry.put("size", sizeCol >= 0 ? cursor.getLong(sizeCol) : 0L);
                    entry.put("mime", mime == null ? (video ? "video/*" : "audio/*") : mime);
                    entry.put("type", video ? "video" : "audio");
                    entry.put("folder", pathCol >= 0 && cursor.getString(pathCol) != null ? cursor.getString(pathCol) : "Device storage");
                    entry.put("lastModified", dateCol >= 0 ? cursor.getLong(dateCol) * 1000L : 0L);
                    entry.put("duration", durationCol >= 0 ? cursor.getLong(durationCol) : 0L);
                    results.put(entry);
                } catch (JSONException ignored) { }
            }
        } catch (SecurityException ignored) {
            // A permission may have been revoked while scanning; the picker remains available.
        } catch (Exception ignored) {
            // Leave other file types/results available when a manufacturer-specific media provider fails.
        }
    }

    private JSONArray getSavedPicks() {
        JSONArray results = new JSONArray();
        Set<String> saved = getSharedPreferences("lumio_library", MODE_PRIVATE)
                .getStringSet("picked_uris", new HashSet<>());
        for (String value : new HashSet<>(saved)) {
            if (value == null || value.trim().isEmpty()) continue;
            try { results.put(readDocument(Uri.parse(value))); } catch (Exception ignored) { }
        }
        return results;
    }

    private void rememberPickedUri(Uri uri) {
        Set<String> saved = new HashSet<>(getSharedPreferences("lumio_library", MODE_PRIVATE)
                .getStringSet("picked_uris", new HashSet<>()));
        saved.add(uri.toString());
        getSharedPreferences("lumio_library", MODE_PRIVATE).edit().putStringSet("picked_uris", saved).apply();
    }

    private JSONObject readDocument(Uri uri) {
        JSONObject entry = new JSONObject();
        String name = "Local media";
        long size = 0L;
        String mime = getContentResolver().getType(uri);
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (nameCol >= 0 && cursor.getString(nameCol) != null) name = cursor.getString(nameCol);
                if (sizeCol >= 0) size = cursor.getLong(sizeCol);
            }
        } catch (Exception ignored) { }
        boolean audio = (mime != null && mime.startsWith("audio/")) || looksLikeAudio(name);
        try {
            entry.put("key", uri.toString());
            entry.put("uri", uri.toString());
            entry.put("name", name);
            entry.put("size", size);
            entry.put("mime", mime == null ? (audio ? "audio/*" : "video/*") : mime);
            entry.put("type", audio ? "audio" : "video");
            entry.put("folder", "Chosen files");
            // A document provider's actual modification time is not always available.
            // Keep it unknown instead of falsely presenting the pick time as file age.
            entry.put("lastModified", 0L);
        } catch (JSONException ignored) { }
        return entry;
    }

    private boolean looksLikeAudio(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".flac")
                || n.endsWith(".opus") || n.endsWith(".wma") || n.endsWith(".mid")
                || n.endsWith(".midi");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_MEDIA || resultCode != RESULT_OK || data == null) return;
        JSONArray results = new JSONArray();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) { }
                    rememberPickedUri(uri);
                    results.put(readDocument(uri));
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) { }
                rememberPickedUri(uri);
                results.put(readDocument(uri));
            }
        } catch (Exception ignored) { }
        callJs("window.onNativeMediaPicked && window.onNativeMediaPicked(" + results.toString() + ")");
    }

    private void callJs(String script) {
        if (webView != null) runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    @Override
    protected void onDestroy() {
        thumbnailExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("LumioNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
