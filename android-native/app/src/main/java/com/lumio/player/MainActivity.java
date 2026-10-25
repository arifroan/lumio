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
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;
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
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int REQUEST_PICK_MEDIA = 4107;
    private static final int REQUEST_SCAN_PERMISSION = 4108;
    private static final int REQUEST_SCAN_AFTER_PICK = 4109;
    private static final int REQUEST_PICK_FOLDER = 4110;
    private static final int MAX_TREE_MEDIA_FILES = 10000;
    private static final int MAX_TREE_DEPTH = 32;
    private WebView webView;
    private OnBackInvokedCallback backInvokedCallback;
    private boolean pendingScan;
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private volatile boolean incompleteSavedPicksScan;

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
                // Start the native media scan only after the WebView page and
                // JavaScript bridge are ready. This makes scanning independent
                // of a JavaScript timer and ensures the library refreshes on launch.
                if ("file:///android_asset/player.html".equals(url)
                        || url != null && url.startsWith("file:///android_asset/player.html?")) {
                    // The WebView uses a recent successful cache when available; otherwise scan before showing the app.
                    view.evaluateJavascript("window.requestNativeStartupScan && window.requestNativeStartupScan()", null);
                }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = this::dispatchBackToLumio;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        }
        webView.loadUrl("file:///android_asset/player.html");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        dispatchBackToLumio();
    }

    private void dispatchBackToLumio() {
        WebView current = webView;
        if (current == null) {
            finishAfterTransition();
            return;
        }
        current.evaluateJavascript(
                "(function(){return typeof window.handleLumioBack === 'function' ? window.handleLumioBack() : false;})()",
                result -> {
                    if (!"true".equals(result) && !isFinishing()) {
                        finishAfterTransition();
                    }
                });
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
        public void pickFolder() {
            runOnUiThread(() -> launchFolderPicker());
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
        public boolean toggleFavorite(String uri, String identity) {
            return toggleFavoriteUri(uri, identity);
        }

        @JavascriptInterface
        public void showMediaActions(String uri, String title, long sizeBytes, long durationMs, boolean isAudio, String identity) {
            runOnUiThread(() -> showMediaActionsDialog(uri, title, sizeBytes, durationMs, isAudio, identity));
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
        JSONArray favoriteIdentities = new JSONArray();
        JSONArray history;
        try {
            android.content.SharedPreferences libraryPrefs = getSharedPreferences("lumio_library", MODE_PRIVATE);
            Set<String> savedFavorites = new HashSet<>(libraryPrefs.getStringSet("favorite_uris", new HashSet<>()));
            Set<String> savedIdentities = new HashSet<>(libraryPrefs.getStringSet("favorite_identities", new HashSet<>()));
            List<String> orderedFavorites = new ArrayList<>(savedFavorites);
            List<String> orderedIdentities = new ArrayList<>(savedIdentities);
            java.util.Collections.sort(orderedFavorites);
            java.util.Collections.sort(orderedIdentities);
            for (String uri : orderedFavorites) if (uri != null && !uri.trim().isEmpty()) favorites.put(uri);
            for (String identity : orderedIdentities) if (identity != null && !identity.trim().isEmpty()) favoriteIdentities.put(identity);

            String rawHistory = getSharedPreferences("lumio_playback", MODE_PRIVATE)
                    .getString("watch_history", "[]");
            history = new JSONArray(rawHistory);
        } catch (Exception ignored) {
            history = new JSONArray();
        }
        try {
            state.put("favorites", favorites);
            state.put("favoriteIdentities", favoriteIdentities);
            state.put("history", history);
        } catch (JSONException ignored) { }
        return state.toString();
    }

    private void publishLibraryState() {
        String state = buildLibraryStateJson();
        callJs("window.onNativeLibraryState && window.onNativeLibraryState("
                + JSONObject.quote(state) + ")");
    }

    private boolean isFavoriteUri(String uri, String identity) {
        android.content.SharedPreferences preferences = getSharedPreferences("lumio_library", MODE_PRIVATE);
        Set<String> savedUris = preferences.getStringSet("favorite_uris", new HashSet<>());
        Set<String> savedIdentities = preferences.getStringSet("favorite_identities", new HashSet<>());
        return (uri != null && savedUris.contains(uri))
                || (identity != null && !identity.trim().isEmpty() && savedIdentities.contains(identity));
    }

    private boolean toggleFavoriteUri(String uri, String identity) {
        if (uri == null || uri.trim().isEmpty()) return false;
        android.content.SharedPreferences preferences = getSharedPreferences("lumio_library", MODE_PRIVATE);
        Set<String> saved = new HashSet<>(preferences.getStringSet("favorite_uris", new HashSet<>()));
        Set<String> savedIdentities = new HashSet<>(preferences.getStringSet("favorite_identities", new HashSet<>()));
        boolean hasIdentity = identity != null && !identity.trim().isEmpty();
        boolean wasFavorite = saved.contains(uri) || (hasIdentity && savedIdentities.contains(identity));
        if (wasFavorite) {
            saved.remove(uri);
            if (hasIdentity) {
                savedIdentities.remove(identity);
                // A prior version may have stored this same physical file under a
                // different provider URI. Remove those URI aliases too.
                for (String savedUri : new HashSet<>(saved)) {
                    try {
                        if (identity.equals(savedMediaIdentity(Uri.parse(savedUri)))) saved.remove(savedUri);
                    } catch (Exception ignored) { }
                }
            }
        } else {
            saved.add(uri);
            if (hasIdentity) savedIdentities.add(identity);
        }
        preferences.edit().putStringSet("favorite_uris", saved)
                .putStringSet("favorite_identities", savedIdentities).apply();
        return !wasFavorite;
    }

    private boolean hasHistoryEntry(String uri, String identity) {
        if ((uri == null || uri.trim().isEmpty()) && (identity == null || identity.trim().isEmpty())) return false;
        try {
            JSONArray history = new JSONArray(getSharedPreferences("lumio_playback", MODE_PRIVATE)
                    .getString("watch_history", "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                if (row == null) continue;
                if (uri != null && uri.equals(row.optString("uri", ""))) return true;
                if (identity != null && !identity.trim().isEmpty()
                        && identity.equals(row.optString("identity", ""))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void removeFromWatchHistory(String uri, String identity) {
        if ((uri == null || uri.trim().isEmpty()) && (identity == null || identity.trim().isEmpty())) return;
        android.content.SharedPreferences preferences = getSharedPreferences("lumio_playback", MODE_PRIVATE);
        JSONArray keep = new JSONArray();
        try {
            JSONArray history = new JSONArray(preferences.getString("watch_history", "[]"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject row = history.optJSONObject(i);
                boolean sameUri = row != null && uri != null && uri.equals(row.optString("uri", ""));
                boolean sameIdentity = row != null && identity != null && !identity.trim().isEmpty()
                        && identity.equals(row.optString("identity", ""));
                if (row != null && !sameUri && !sameIdentity) keep.put(row);
            }
        } catch (Exception ignored) { }
        android.content.SharedPreferences.Editor editor = preferences.edit().putString("watch_history", keep.toString());
        if (uri != null && !uri.trim().isEmpty()) editor.remove("position:" + uri);
        if (uri != null && uri.equals(preferences.getString("last_uri", ""))) editor.remove("last_uri");
        editor.apply();
    }

    private void showMediaActionsDialog(String uri, String title, long sizeBytes, long durationMs, boolean isAudio, String identity) {
        if (uri == null || uri.trim().isEmpty()) return;
        String safeTitle = title == null || title.trim().isEmpty() ? "Media item" : title;
        List<String> labels = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        labels.add("Play now"); actions.add("play");
        boolean favorite = isFavoriteUri(uri, identity);
        labels.add(favorite ? "Remove from Favorites" : "Add to Favorites"); actions.add("favorite");
        if (hasHistoryEntry(uri, identity)) {
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
                        intent.putExtra(PlayerActivity.EXTRA_MEDIA_IDENTITY, identity == null ? "" : identity);
                        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST, "[]");
                        try { startActivity(intent); }
                        catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Could not open this media file", Toast.LENGTH_LONG).show();
                        }
                    } else if ("favorite".equals(action)) {
                        boolean nowFavorite = toggleFavoriteUri(uri, identity);
                        Toast.makeText(MainActivity.this, nowFavorite ? "Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                        publishLibraryState();
                    } else if ("remove_recent".equals(action)) {
                        removeFromWatchHistory(uri, identity);
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
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_PICK_MEDIA);
        } catch (Exception e) {
            Toast.makeText(this, "This device has no document picker", Toast.LENGTH_LONG).show();
        }
    }

    private void launchFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "This device has no folder picker", Toast.LENGTH_LONG).show();
        }
    }

    private void rememberTreeUri(Uri uri) {
        if (uri == null) return;
        android.content.SharedPreferences preferences = getSharedPreferences("lumio_library", MODE_PRIVATE);
        Set<String> saved = new HashSet<>(preferences.getStringSet("picked_tree_uris", new HashSet<>()));
        saved.add(uri.toString());
        preferences.edit().putStringSet("picked_tree_uris", saved).apply();
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
            // Even without broad media permissions, previously granted document and
            // folder picks may remain readable. Scan those sources without erasing old rows.
            publishScannedMedia();
        }
    }

    private void publishScannedMedia() {
        final int generation = scanGeneration.incrementAndGet();
        final boolean completeMediaStoreAccess = canReadVideo() && canReadAudio();
        scanExecutor.execute(() -> {
            JSONArray array = new JSONArray();
            boolean videoScanFailed = canReadVideo() && !appendAllStoreVolumes(array, true);
            boolean audioScanFailed = canReadAudio() && !appendAllStoreVolumes(array, false);

            int[] treeCount = new int[]{0};
            boolean treesComplete = appendSavedTreeMedia(array, treeCount);
            JSONArray saved = getSavedPicks();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item != null) array.put(item);
            }

            String warning = null;
            if (videoScanFailed || audioScanFailed) {
                if (videoScanFailed && audioScanFailed) {
                    warning = "Android could not read the video or music library. Check permissions, then scan again.";
                } else if (videoScanFailed) {
                    warning = "Android could not read the video library. Try Browse this device or Add folder, then scan again.";
                } else {
                    warning = "Android could not read the music library. Check audio permission, then try scanning again.";
                }
            }
            if (!completeMediaStoreAccess) {
                warning = "Some storage permissions are unavailable. Existing library entries were preserved; grant access and scan again to fully refresh.";
            }
            if (!treesComplete) {
                warning = warning == null
                        ? "Some folder contents could not be read or exceeded the scan limit. Existing entries were preserved."
                        : warning + " Some selected folders could not be read completely.";
            }
            if (incompleteSavedPicksScan) {
                warning = warning == null
                        ? "Some previously selected files are temporarily unavailable. Existing library entries were preserved."
                        : warning + " Some selected files remain unavailable.";
            }
            if (generation != scanGeneration.get()) return;
            JSONObject scanStatus = new JSONObject();
            try {
                scanStatus.put("videoStoreComplete", canReadVideo() && !videoScanFailed);
                scanStatus.put("audioStoreComplete", canReadAudio() && !audioScanFailed);
                scanStatus.put("treesComplete", treesComplete);
                scanStatus.put("savedPicksComplete", !incompleteSavedPicksScan);
            } catch (JSONException ignored) { }
            String errorJson = warning == null ? "null" : JSONObject.quote(warning);
            callJs("window.onNativeMediaScan && window.onNativeMediaScan("
                    + array.toString() + ", " + errorJson + ", " + scanStatus.toString() + ")");
        });
    }

    private static final class TreeFolder {
        final String documentId;
        final String folderPath;
        final int depth;

        TreeFolder(String documentId, String folderPath, int depth) {
            this.documentId = documentId;
            this.folderPath = folderPath;
            this.depth = depth;
        }
    }

    private boolean appendSavedTreeMedia(JSONArray results, int[] totalCount) {
        Set<String> trees = new HashSet<>(getSharedPreferences("lumio_library", MODE_PRIVATE)
                .getStringSet("picked_tree_uris", new HashSet<>()));
        boolean complete = true;
        for (String value : trees) {
            if (value == null || value.trim().isEmpty()) continue;
            if (totalCount[0] >= MAX_TREE_MEDIA_FILES) return false;
            try {
                Uri treeUri = Uri.parse(value);
                DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
                if (root == null || !root.isDirectory()) {
                    complete = false;
                    continue;
                }
                if (!appendTreeMedia(results, treeUri, root, totalCount)) complete = false;
            } catch (SecurityException | IllegalArgumentException e) {
                // A revoked or unavailable folder grant must not erase cached library items.
                complete = false;
            } catch (Exception e) {
                complete = false;
            }
        }
        return complete;
    }

    private boolean appendTreeMedia(JSONArray results, Uri treeUri, DocumentFile root, int[] totalCount) {
        String initialPath = folderPathForTreeRoot(treeUri, root);
        final String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return false;
        }
        Deque<TreeFolder> pending = new ArrayDeque<>();
        pending.push(new TreeFolder(rootDocumentId, initialPath, 0));
        boolean complete = true;
        Map<String, String> identityUpdates = new HashMap<>();
        final String[] projection = new String[] {
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED
        };

        while (!pending.isEmpty()) {
            if (totalCount[0] >= MAX_TREE_MEDIA_FILES) return false;
            TreeFolder current = pending.pop();
            Uri childrenUri;
            try {
                childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, current.documentId);
            } catch (Exception e) {
                complete = false;
                continue;
            }

            try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
                if (cursor == null) {
                    complete = false;
                    continue;
                }
                int idCol = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
                int nameCol = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
                int mimeCol = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
                int sizeCol = cursor.getColumnIndex(Document.COLUMN_SIZE);
                int modifiedCol = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED);

                while (cursor.moveToNext()) {
                    String documentId = idCol >= 0 ? cursor.getString(idCol) : null;
                    String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
                    String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
                    if (documentId == null || name == null || name.trim().isEmpty() || name.startsWith(".")) continue;

                    if (Document.MIME_TYPE_DIR.equals(mime)) {
                        if (current.depth >= MAX_TREE_DEPTH) {
                            complete = false;
                            continue;
                        }
                        pending.push(new TreeFolder(documentId,
                                appendPath(current.folderPath, name, true), current.depth + 1));
                        continue;
                    }

                    boolean audio = isAudioMedia(name, mime);
                    boolean video = isVideoMedia(name, mime);
                    if (!audio && !video) continue;

                    Uri mediaUri;
                    try {
                        mediaUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    } catch (Exception e) {
                        complete = false;
                        continue;
                    }
                    String identity = identityForDocumentUri(mediaUri);
                    long size = sizeCol >= 0 && !cursor.isNull(sizeCol) ? Math.max(0L, cursor.getLong(sizeCol)) : 0L;
                    long lastModified = modifiedCol >= 0 && !cursor.isNull(modifiedCol)
                            ? Math.max(0L, cursor.getLong(modifiedCol)) : 0L;
                    JSONObject entry = new JSONObject();
                    try {
                        entry.put("key", mediaUri.toString());
                        entry.put("uri", mediaUri.toString());
                        entry.put("name", name);
                        entry.put("size", size);
                        entry.put("mime", mime == null || mime.trim().isEmpty()
                                ? (audio ? "audio/*" : "video/*") : mime);
                        entry.put("type", audio ? "audio" : "video");
                        entry.put("folder", current.folderPath);
                        entry.put("lastModified", lastModified);
                        entry.put("duration", 0L);
                        entry.put("source", "folder");
                        if (!identity.isEmpty()) {
                            entry.put("identity", identity);
                            identityUpdates.put(mediaUri.toString(), identity);
                        }
                        results.put(entry);
                        totalCount[0]++;
                    } catch (JSONException ignored) { }
                    if (totalCount[0] >= MAX_TREE_MEDIA_FILES) return false;
                }
            } catch (Exception e) {
                // SAF providers vary; preserve existing entries if a folder cannot be read.
                complete = false;
            }
        }
        rememberMediaIdentities(identityUpdates);
        return complete;
    }

    private String folderPathForTreeRoot(Uri treeUri, DocumentFile root) {
        try {
            if ("com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
                String documentId = DocumentsContract.getTreeDocumentId(treeUri);
                int colon = documentId.indexOf(':');
                if (colon > 0) {
                    String volume = documentId.substring(0, colon);
                    String relativePath = cleanRelativePath(documentId.substring(colon + 1));
                    String prefix = storageLabel(volume);
                    if (!relativePath.isEmpty()) prefix += "/" + relativePath;
                    return prefix + "/";
                }
            }
        } catch (Exception ignored) { }
        String rootName = root.getName();
        if (rootName == null || rootName.trim().isEmpty()) rootName = "Folder";
        return "Selected folders/" + rootName.replace("/", "_").replace("\\", "_") + "/";
    }

    private String appendPath(String parent, String name, boolean directory) {
        String base = parent == null ? "" : parent.replace("\\", "/");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String safeName = name == null ? "" : name.replace("/", "_").replace("\\", "_");
        return base + (base.isEmpty() ? "" : "/") + safeName + (directory ? "/" : "");
    }

    private String cleanRelativePath(String path) {
        if (path == null) return "";
        String cleaned = path.replace("\\", "/");
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    private String normalizeVolumeName(String volume) {
        if (volume == null || volume.trim().isEmpty()
                || "primary".equalsIgnoreCase(volume)
                || "external_primary".equalsIgnoreCase(volume)
                || "external".equalsIgnoreCase(volume)) return "external_primary";
        return volume.trim().toLowerCase(Locale.ROOT);
    }

    private String storageLabel(String volume) {
        String normalized = normalizeVolumeName(volume);
        if ("external_primary".equals(normalized)) return "Internal storage";
        return "External storage (" + volume + ")";
    }

    private String buildMediaIdentity(String volume, String relativePathAndName) {
        String path = cleanRelativePath(relativePathAndName);
        if (path.isEmpty()) return "";
        return normalizeVolumeName(volume) + ":" + path;
    }

    private String identityForDocumentUri(Uri uri) {
        if (uri == null || !"com.android.externalstorage.documents".equals(uri.getAuthority())) return "";
        try {
            String documentId = DocumentsContract.getDocumentId(uri);
            int colon = documentId.indexOf(':');
            if (colon <= 0 || colon == documentId.length() - 1) return "";
            return buildMediaIdentity(documentId.substring(0, colon), documentId.substring(colon + 1));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String folderForDocumentUri(Uri uri) {
        if (uri == null || !"com.android.externalstorage.documents".equals(uri.getAuthority())) return "Chosen files";
        try {
            String documentId = DocumentsContract.getDocumentId(uri);
            int colon = documentId.indexOf(':');
            if (colon <= 0) return "Chosen files";
            String volume = documentId.substring(0, colon);
            String path = cleanRelativePath(documentId.substring(colon + 1));
            int lastSlash = path.lastIndexOf('/');
            String parentPath = lastSlash >= 0 ? path.substring(0, lastSlash) : "";
            String folder = storageLabel(volume);
            if (!parentPath.isEmpty()) folder += "/" + parentPath;
            return folder + "/";
        } catch (Exception ignored) {
            return "Chosen files";
        }
    }

    private void rememberMediaIdentity(Uri uri, String identity) {
        if (uri == null || identity == null || identity.trim().isEmpty()) return;
        Map<String, String> updates = new HashMap<>();
        updates.put(uri.toString(), identity);
        rememberMediaIdentities(updates);
    }

    private void rememberMediaIdentities(Map<String, String> identities) {
        if (identities == null || identities.isEmpty()) return;
        android.content.SharedPreferences.Editor editor = getSharedPreferences("lumio_library", MODE_PRIVATE).edit();
        for (Map.Entry<String, String> entry : identities.entrySet()) {
            String uri = entry.getKey();
            String identity = entry.getValue();
            if (uri != null && !uri.trim().isEmpty() && identity != null && !identity.trim().isEmpty()) {
                editor.putString("identity:" + uri, identity);
            }
        }
        editor.apply();
    }

    private String savedMediaIdentity(Uri uri) {
        if (uri == null) return "";
        String stored = getSharedPreferences("lumio_library", MODE_PRIVATE)
                .getString("identity:" + uri.toString(), "");
        return stored == null ? "" : stored;
    }

    private boolean isAudioMedia(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".oga")
                || n.endsWith(".flac") || n.endsWith(".opus") || n.endsWith(".wma")
                || n.endsWith(".mid") || n.endsWith(".midi") || n.endsWith(".aiff")
                || n.endsWith(".aif") || n.endsWith(".amr") || n.endsWith(".ape")
                || n.endsWith(".alac") || n.endsWith(".mka");
    }

    private boolean isVideoMedia(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mkv")
                || n.endsWith(".webm") || n.endsWith(".mov") || n.endsWith(".avi")
                || n.endsWith(".3gp") || n.endsWith(".3g2") || n.endsWith(".ts")
                || n.endsWith(".mts") || n.endsWith(".m2ts") || n.endsWith(".mpeg")
                || n.endsWith(".mpg") || n.endsWith(".ogv") || n.endsWith(".flv")
                || n.endsWith(".wmv") || n.endsWith(".vob") || n.endsWith(".asf");
    }

    private boolean appendAllStoreVolumes(JSONArray results, boolean video) {
        // On Android 10+, query each visible external volume so music stored on
        // removable SD cards is not missed. Older Android versions use the
        // legacy aggregate external collection.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return appendStore(results, video
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, video);
        }
        Set<String> volumes;
        try {
            volumes = MediaStore.getExternalVolumeNames(this);
        } catch (Exception ignored) {
            return appendStore(results, video
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, video);
        }
        if (volumes == null || volumes.isEmpty()) {
            return appendStore(results, video
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, video);
        }
        boolean complete = true;
        for (String volume : volumes) {
            if (volume == null || volume.trim().isEmpty()) continue;
            Uri collection = video
                    ? MediaStore.Video.Media.getContentUri(volume)
                    : MediaStore.Audio.Media.getContentUri(volume);
            if (!appendStore(results, collection, video)) complete = false;
        }
        return complete;
    }

    private boolean appendStore(JSONArray results, Uri collection, boolean video) {
        List<String> columns = new ArrayList<>();
        columns.add(MediaStore.MediaColumns._ID);
        columns.add(MediaStore.MediaColumns.DISPLAY_NAME);
        columns.add(MediaStore.MediaColumns.SIZE);
        columns.add(MediaStore.MediaColumns.MIME_TYPE);
        columns.add(MediaStore.MediaColumns.DATE_MODIFIED);
        columns.add(video ? MediaStore.Video.Media.DURATION : MediaStore.Audio.Media.DURATION);
        if (!video) {
            columns.add(MediaStore.Audio.Media.ARTIST);
            columns.add(MediaStore.Audio.Media.ALBUM);
        }
        if (video) {
            columns.add(MediaStore.MediaColumns.WIDTH);
            columns.add(MediaStore.MediaColumns.HEIGHT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns.add(MediaStore.MediaColumns.RELATIVE_PATH);
            columns.add(MediaStore.MediaColumns.VOLUME_NAME);
        }
        // Do not ask MediaStore to sort the full library: LUMIO sorts the merged
        // result once after scanning, avoiding a second potentially expensive sort.
        // Batch identity writes instead of scheduling one SharedPreferences write per file.
        Map<String, String> identityUpdates = new HashMap<>();
        try (Cursor cursor = getContentResolver().query(collection, columns.toArray(new String[0]), null, null, null)) {
            if (cursor == null) return false;
            int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int volumeCol = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME);
            int durationCol = cursor.getColumnIndex(video ? MediaStore.Video.Media.DURATION : MediaStore.Audio.Media.DURATION);
            int artistCol = !video ? cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) : -1;
            int albumCol = !video ? cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM) : -1;
            int widthCol = video ? cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH) : -1;
            int heightCol = video ? cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT) : -1;
            int frameRateCol = -1;
            while (cursor.moveToNext()) {
                long id = idCol >= 0 ? cursor.getLong(idCol) : -1;
                if (id < 0) continue;
                Uri fileUri = ContentUris.withAppendedId(collection, id);
                String name = nameCol >= 0 ? cursor.getString(nameCol) : (video ? "Video" : "Audio");
                if (name == null || name.trim().isEmpty()) name = video ? "Video" : "Audio";
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : null;
                String relativePath = pathCol >= 0 ? cursor.getString(pathCol) : null;
                String volume = volumeCol >= 0 ? cursor.getString(volumeCol) : null;
                String folder = "Device storage";
                String identity = "";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                    String label = storageLabel(volume);
                    String cleanedPath = cleanRelativePath(relativePath);
                    folder = label + (cleanedPath.isEmpty() ? "/" : "/" + cleanedPath + "/");
                    identity = buildMediaIdentity(volume, appendPath(cleanedPath, name, false));
                }
                JSONObject entry = new JSONObject();
                try {
                    entry.put("key", fileUri.toString());
                    entry.put("uri", fileUri.toString());
                    entry.put("name", name);
                    entry.put("size", sizeCol >= 0 ? cursor.getLong(sizeCol) : 0L);
                    entry.put("mime", mime == null ? (video ? "video/*" : "audio/*") : mime);
                    entry.put("type", video ? "video" : "audio");
                    entry.put("folder", folder);
                    entry.put("lastModified", dateCol >= 0 ? cursor.getLong(dateCol) * 1000L : 0L);
                    entry.put("duration", durationCol >= 0 && !cursor.isNull(durationCol) ? cursor.getLong(durationCol) : 0L);
                    if (!video) {
                        entry.put("artist", artistCol >= 0 && !cursor.isNull(artistCol) ? cursor.getString(artistCol) : "");
                        entry.put("album", albumCol >= 0 && !cursor.isNull(albumCol) ? cursor.getString(albumCol) : "");
                        entry.put("genre", "");
                    }
                    long width = widthCol >= 0 && !cursor.isNull(widthCol) ? Math.max(0L, cursor.getLong(widthCol)) : 0L;
                    long height = heightCol >= 0 && !cursor.isNull(heightCol) ? Math.max(0L, cursor.getLong(heightCol)) : 0L;
                    double frameRate = 0d;
                    if (frameRateCol >= 0) {
                        try {
                            String value = cursor.getString(frameRateCol);
                            if (value != null) {
                                double candidate = Double.parseDouble(value);
                                if (Double.isFinite(candidate) && candidate > 0d) frameRate = candidate;
                            }
                        } catch (Exception ignored) { }
                    }
                    entry.put("width", width);
                    entry.put("height", height);
                    entry.put("frameRate", frameRate);
                    entry.put("resolution", width > 0L && height > 0L ? width + "x" + height : "");
                    entry.put("source", "mediastore");
                    if (!identity.isEmpty()) {
                        entry.put("identity", identity);
                        identityUpdates.put(fileUri.toString(), identity);
                    }
                    results.put(entry);
                } catch (JSONException ignored) { }
            }
            rememberMediaIdentities(identityUpdates);
            return true;
        } catch (SecurityException ignored) {
            // A permission may have been revoked while scanning; the picker remains available.
            return false;
        } catch (Exception ignored) {
            // Report a failed query rather than silently presenting an empty library.
            return false;
        }
    }

    private JSONArray getSavedPicks() {
        JSONArray results = new JSONArray();
        incompleteSavedPicksScan = false;
        Set<String> saved = new HashSet<>(getSharedPreferences("lumio_library", MODE_PRIVATE)
                .getStringSet("picked_uris", new HashSet<>()));
        for (String value : saved) {
            if (value == null || value.trim().isEmpty()) continue;
            try {
                JSONObject item = readDocument(Uri.parse(value));
                if (item != null) {
                    if (!item.optBoolean("available", true)) incompleteSavedPicksScan = true;
                    results.put(item);
                }
            } catch (Exception ignored) {
                incompleteSavedPicksScan = true;
            }
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
        if (uri == null) return null;
        JSONObject entry = new JSONObject();
        String name = uri.getLastPathSegment() == null ? "Local media" : Uri.decode(uri.getLastPathSegment());
        long size = 0L;
        String mime = null;
        boolean metadataRead = false;
        try { mime = getContentResolver().getType(uri); } catch (Exception ignored) { }
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                metadataRead = true;
                int nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (nameCol >= 0 && cursor.getString(nameCol) != null) name = cursor.getString(nameCol);
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = Math.max(0L, cursor.getLong(sizeCol));
            }
        } catch (Exception ignored) { }

        boolean readable = false;
        try (java.io.InputStream probe = getContentResolver().openInputStream(uri)) {
            readable = probe != null;
        } catch (Exception ignored) { }
        // A saved picker URI whose grant was revoked or whose file disappeared is
        // kept as unavailable metadata during incomplete scans, instead of causing a
        // partial scan to erase unrelated library entries.
        if (!metadataRead && !readable) return null;

        boolean audio = isAudioMedia(name, mime);
        String identity = identityForDocumentUri(uri);
        if (identity.isEmpty()) identity = savedMediaIdentity(uri);
        if (!identity.isEmpty()) rememberMediaIdentity(uri, identity);
        try {
            entry.put("key", uri.toString());
            entry.put("uri", uri.toString());
            entry.put("name", name == null || name.trim().isEmpty() ? "Local media" : name);
            entry.put("size", size);
            entry.put("mime", mime == null ? (audio ? "audio/*" : "video/*") : mime);
            entry.put("type", audio ? "audio" : "video");
            entry.put("folder", folderForDocumentUri(uri));
            entry.put("lastModified", 0L);
            entry.put("source", "document");
            entry.put("available", readable);
            if (!identity.isEmpty()) entry.put("identity", identity);
            if (!audio && readable) addVideoMetadata(entry, uri);
            else {
                entry.put("width", 0L);
                entry.put("height", 0L);
                entry.put("frameRate", 0d);
                entry.put("resolution", "");
            }
        } catch (JSONException ignored) { }
        return entry;
    }

    private void addVideoMetadata(JSONObject entry, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        long width = 0L;
        long height = 0L;
        double frameRate = 0d;
        try {
            retriever.setDataSource(this, uri);
            String widthValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String frameRateValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            try { if (widthValue != null) width = Math.max(0L, Long.parseLong(widthValue)); } catch (NumberFormatException ignored) { }
            try { if (heightValue != null) height = Math.max(0L, Long.parseLong(heightValue)); } catch (NumberFormatException ignored) { }
            try {
                if (frameRateValue != null) {
                    double candidate = Double.parseDouble(frameRateValue);
                    if (Double.isFinite(candidate) && candidate > 0d) frameRate = candidate;
                }
            } catch (NumberFormatException ignored) { }
        } catch (Exception ignored) {
            // Some document providers do not expose container metadata to MediaMetadataRetriever.
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        try {
            entry.put("width", width);
            entry.put("height", height);
            entry.put("frameRate", frameRate);
            entry.put("resolution", width > 0L && height > 0L ? width + "x" + height : "");
        } catch (JSONException ignored) { }
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
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_PICK_FOLDER) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            int grantFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(treeUri, grantFlags);
                }
            } catch (Exception ignored) { }
            rememberTreeUri(treeUri);
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            String name = root == null || root.getName() == null ? "selected folder" : root.getName();
            String rootPath = root == null ? "" : folderPathForTreeRoot(treeUri, root);
            callJs("window.onNativeFolderPicked && window.onNativeFolderPicked("
                    + JSONObject.quote(name) + "," + JSONObject.quote(rootPath) + ")");
            publishScannedMedia();
            return;
        }

        if (requestCode != REQUEST_PICK_MEDIA) return;
        JSONArray results = new JSONArray();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) { }
                    rememberPickedUri(uri);
                    JSONObject item = readDocument(uri);
                    if (item != null) results.put(item);
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) { }
                rememberPickedUri(uri);
                JSONObject item = readDocument(uri);
                if (item != null) results.put(item);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        scanGeneration.incrementAndGet();
        scanExecutor.shutdownNow();
        thumbnailExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("LumioNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
