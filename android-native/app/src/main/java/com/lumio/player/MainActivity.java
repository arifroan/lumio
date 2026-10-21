package com.lumio.player;

import android.Manifest;
import android.app.Activity;
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
import android.graphics.Color;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_PICK_MEDIA = 4107;
    private static final int REQUEST_SCAN_PERMISSION = 4108;
    private static final int REQUEST_SCAN_AFTER_PICK = 4109;
    private WebView webView;
    private boolean pendingScan;

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
        public void pickMedia() {
            runOnUiThread(() -> launchMediaPicker());
        }

        @JavascriptInterface
        public void scanLocalMedia() {
            runOnUiThread(() -> beginMediaScan());
        }

        @JavascriptInterface
        public void playMedia(String uri, String title, boolean isAudio) {
            runOnUiThread(() -> {
                if (uri == null || uri.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "Choose a media file first", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URI, uri);
                intent.putExtra(PlayerActivity.EXTRA_TITLE, title == null ? "Local media" : title);
                intent.putExtra(PlayerActivity.EXTRA_AUDIO, isAudio);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not open this media file", Toast.LENGTH_LONG).show();
                }
            });
        }
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
        if (hasAnyMediaReadPermission()) {
            publishScannedMedia();
            return;
        }
        pendingScan = true;
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO}, REQUEST_SCAN_PERMISSION);
        } else if (Build.VERSION.SDK_INT >= 23) {
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
            entry.put("lastModified", System.currentTimeMillis());
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
        if (webView != null) {
            webView.removeJavascriptInterface("LumioNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
