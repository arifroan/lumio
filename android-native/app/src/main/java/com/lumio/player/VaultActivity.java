package com.lumio.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * An encrypted, device-credential-gated local vault. Imported originals are encrypted at rest
 * under an Android Keystore AES-GCM key. Playback uses a short-lived decrypted cache copy.
 */
public final class VaultActivity extends Activity {
    private static final int REQUEST_UNLOCK = 7101;
    private static final int REQUEST_IMPORT = 7102;
    private static final String KEY_ALIAS = "lumio_private_vault_aes_v1";
    private static final String INDEX_NAME = "index.enc";
    private static final int IV_BYTES = 12;

    private File vaultDir;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private LinearLayout rows;
    private TextView status;
    private boolean authenticated;
    private boolean importing;
    private boolean authenticating;

    private static final class Entry {
        String id;
        String name;
        String mime;
        long size;

        Entry(String id, String name, String mime, long size) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 12, 18));
        getWindow().setNavigationBarColor(Color.rgb(12, 12, 18));
        vaultDir = new File(getFilesDir(), "private_vault");
        if (!vaultDir.exists() && !vaultDir.mkdirs()) {
            showFatal("Lumio could not create its private storage.");
            return;
        }
        showLockedScreen();
        requestDeviceAuthentication();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.rgb(18, 18, 25));
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        b.setBackground(rounded(Color.rgb(215, 255, 114), 0, 12));
        return b;
    }

    private LinearLayout base(String titleText, String subtitleText) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(16, 16, 24));
        root.addView(text("lumio  /  PRIVATE VAULT", 11, Color.rgb(215, 255, 114), true));
        TextView title = text(titleText, 27, Color.rgb(246, 243, 255), true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(13), 0, dp(7));
        root.addView(title, tp);
        TextView subtitle = text(subtitleText, 13, Color.rgb(165, 162, 185), false);
        root.addView(subtitle);
        return root;
    }

    private void showLockedScreen() {
        LinearLayout root = base("Your files stay yours.", "Private files are encrypted inside Lumio and protected by your phone's screen lock.");
        TextView note = text("Nothing is uploaded. You must have a PIN, password, pattern, or other secure device lock configured.", 13, Color.rgb(205, 201, 222), false);
        note.setPadding(0, dp(18), 0, dp(18));
        root.addView(note);
        Button unlock = button("Unlock private vault");
        unlock.setOnClickListener(v -> requestDeviceAuthentication());
        root.addView(unlock);
        Button back = button("Back to library");
        back.setBackground(rounded(Color.rgb(34, 34, 48), Color.rgb(60, 58, 78), 12));
        back.setTextColor(Color.rgb(246, 243, 255));
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(10), 0, 0);
        root.addView(back, bp);
        setContentView(root);
    }

    private void requestDeviceAuthentication() {
        KeyguardManager manager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (manager == null || !manager.isDeviceSecure()) {
            new AlertDialog.Builder(this)
                    .setTitle("Secure screen lock required")
                    .setMessage("Set a PIN, password, or pattern in Android Settings before using Lumio Private Vault.")
                    .setPositiveButton("OK", (d, w) -> finish())
                    .show();
            return;
        }
        authenticating = true;
        Intent confirm = manager.createConfirmDeviceCredentialIntent(
                "Unlock Lumio Private Vault", "Confirm your screen lock to access encrypted files");
        if (confirm == null) { authenticating = false;
            showFatal("Android could not start device authentication.");
            return;
        }
        try {
            startActivityForResult(confirm, REQUEST_UNLOCK);
        } catch (Exception e) {
            authenticating = false;
            showFatal("Android could not open the secure authentication screen.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNLOCK) {
            authenticating = false;
            if (resultCode == RESULT_OK) {
                authenticated = true;
                try {
                    loadIndex();
                    showVault();
                } catch (Exception e) {
                    showFatal("The vault could not be opened. Its encrypted data has not been changed.");
                }
            } else {
                finish();
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT) importing = false;
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            try {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        importUri(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    importUri(data.getData());
                }
                saveIndex();
                renderEntries();
                toast("Files encrypted in the private vault");
            } catch (Exception e) {
                toast("A file could not be imported. Other vault files are unchanged.");
                try { saveIndex(); renderEntries(); } catch (Exception ignored) { }
            }
        }
    }

    private void showVault() throws Exception {
        if (!authenticated) { showLockedScreen(); return; }
        LinearLayout root = base("Private Vault", "Encrypted local storage · protected by device credentials");
        Button add = button("＋  Encrypt files into vault");
        add.setOnClickListener(v -> pickFiles());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, -2);
        addParams.setMargins(0, dp(18), 0, dp(12));
        root.addView(add, addParams);
        status = text("", 11, Color.rgb(165, 162, 185), false);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.setMargins(0, dp(12), 0, dp(12));
        scroll.addView(rows);
        root.addView(scroll, scrollParams);
        Button close = button("Lock vault and return");
        close.setBackground(rounded(Color.rgb(34, 34, 48), Color.rgb(60, 58, 78), 12));
        close.setTextColor(Color.rgb(246, 243, 255));
        close.setOnClickListener(v -> { authenticated = false; finish(); });
        root.addView(close);
        setContentView(root);
        renderEntries();
    }

    private void pickFiles() {
        if (!authenticated) { finish(); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        importing = true;
        try { startActivityForResult(intent, REQUEST_IMPORT); }
        catch (Exception e) { importing = false; toast("No file picker is available on this device"); }
    }

    private String displayName(Uri uri) {
        String name = uri.getLastPathSegment() == null ? "Private file" : uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.getString(index) != null) name = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return name;
    }

    private long contentSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0) return cursor.getLong(index);
            }
        } catch (Exception ignored) { }
        return 0L;
    }

    private String mimeFor(Uri uri, String name) {
        String mime = getContentResolver().getType(uri);
        if (mime != null) return mime;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov")) return "video/*";
        if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg")) return "audio/*";
        return "application/octet-stream";
    }

    private void importUri(Uri uri) throws Exception {
        String id = UUID.randomUUID().toString();
        String name = displayName(uri);
        String mime = mimeFor(uri, name);
        File target = new File(vaultDir, id + ".enc");
        File temp = new File(vaultDir, id + ".enc.tmp");
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Could not read selected file");
            encryptToFile(in, temp);
            // Publish the encrypted file only after encryption completed successfully.
            if (!temp.renameTo(target)) throw new IllegalStateException("Could not finish saving encrypted file");
        } catch (Exception e) {
            temp.delete();
            target.delete();
            throw e;
        }
        entries.add(new Entry(id, name, mime, contentSize(uri)));
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private void encryptToFile(InputStream input, File outputFile) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Let Android Keystore generate the GCM IV; randomized-encryption-required keys reject
        // caller-supplied IVs for encryption. Store the generated IV before the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new IllegalStateException("Keystore returned an invalid GCM IV");
        try (OutputStream file = new FileOutputStream(outputFile, false)) {
            file.write(iv);
            try (CipherOutputStream encrypted = new CipherOutputStream(file, cipher)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) encrypted.write(buffer, 0, read);
            }
        }
    }

    private byte[] encryptBytes(byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new IllegalStateException("Keystore returned an invalid GCM IV");
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] output = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
        return output;
    }

    private byte[] decryptBytes(byte[] input) throws Exception {
        if (input.length <= IV_BYTES) throw new IllegalStateException("Encrypted vault index is incomplete");
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(input, 0, iv, 0, IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        byte[] encrypted = new byte[input.length - IV_BYTES];
        System.arraycopy(input, IV_BYTES, encrypted, 0, encrypted.length);
        return cipher.doFinal(encrypted);
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private void loadIndex() throws Exception {
        entries.clear();
        File index = new File(vaultDir, INDEX_NAME);
        if (!index.exists()) return;
        byte[] plaintext;
        try (InputStream in = new FileInputStream(index)) { plaintext = decryptBytes(readAll(in)); }
        JSONArray array = new JSONArray(new String(plaintext, StandardCharsets.UTF_8));
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            String id = object.optString("id", "");
            if (id.isEmpty() || !new File(vaultDir, id + ".enc").exists()) continue;
            entries.add(new Entry(id, object.optString("name", "Private file"),
                    object.optString("mime", "application/octet-stream"), object.optLong("size", 0)));
        }
    }

    private void saveIndex() throws Exception {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            object.put("id", entry.id);
            object.put("name", entry.name);
            object.put("mime", entry.mime);
            object.put("size", entry.size);
            array.put(object);
        }
        byte[] encrypted = encryptBytes(array.toString().getBytes(StandardCharsets.UTF_8));
        File temp = new File(vaultDir, INDEX_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(encrypted);
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            temp.delete();
            throw e;
        }
        File index = new File(vaultDir, INDEX_NAME);
        try {
            // Atomic same-directory replacement keeps the previous valid index intact
            // if writing the new index fails or the process is interrupted.
            android.system.Os.rename(temp.getAbsolutePath(), index.getAbsolutePath());
        } catch (android.system.ErrnoException e) {
            temp.delete();
            throw new java.io.IOException("Could not atomically replace encrypted vault index", e);
        }
    }

    private File decryptToTemp(Entry entry) throws Exception {
        File encrypted = new File(vaultDir, entry.id + ".enc");
        File tempDir = new File(getCacheDir(), "lumio_vault_playback");
        if (!tempDir.exists() && !tempDir.mkdirs()) throw new IllegalStateException("Could not prepare playback cache");
        String name = entry.name == null ? "private-media" : entry.name.replaceAll("[^A-Za-z0-9._-]", "_");
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && name.length() - dot < 12) extension = name.substring(dot);
        File plain = File.createTempFile("lumio_private_", extension.isEmpty() ? ".media" : extension, tempDir);
        try (InputStream in = new FileInputStream(encrypted)) {
            byte[] iv = new byte[IV_BYTES];
            int read = 0;
            while (read < IV_BYTES) {
                int n = in.read(iv, read, IV_BYTES - read);
                if (n < 0) throw new IllegalStateException("Encrypted file is incomplete");
                read += n;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            try (CipherInputStream decrypted = new CipherInputStream(in, cipher);
                 OutputStream out = new FileOutputStream(plain)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = decrypted.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        } catch (Exception e) {
            plain.delete();
            throw e;
        }
        return plain;
    }

    private void renderEntries() {
        if (rows == null || status == null) return;
        rows.removeAllViews();
        long total = 0;
        for (Entry entry : entries) total += entry.size;
        status.setText(entries.size() + " encrypted file" + (entries.size() == 1 ? "" : "s") + " · " + readableSize(total));
        if (entries.isEmpty()) {
            TextView empty = text("Your vault is empty. Import any local file and Lumio encrypts it before saving.", 13, Color.rgb(165, 162, 185), false);
            empty.setPadding(dp(8), dp(28), dp(8), dp(28));
            rows.addView(empty);
            return;
        }
        for (Entry entry : new ArrayList<>(entries)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(rounded(Color.rgb(25, 25, 35), Color.rgb(48, 48, 65), 15));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(0, 0, 0, dp(10));
            rows.addView(card, cp);
            TextView name = text(entry.name, 13, Color.rgb(246, 243, 255), true);
            name.setMaxLines(2);
            card.addView(name);
            TextView detail = text(readableSize(entry.size) + " · ENCRYPTED", 10, Color.rgb(165, 162, 185), false);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.setMargins(0, dp(4), 0, dp(8));
            card.addView(detail, detailParams);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            Button play = button("Open");
            play.setOnClickListener(v -> playEntry(entry));
            buttons.addView(play);
            Button delete = button("Delete");
            delete.setBackground(rounded(Color.rgb(41, 31, 43), Color.rgb(105, 55, 80), 12));
            delete.setTextColor(Color.rgb(255, 180, 215));
            LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(-2, -2);
            delp.setMargins(dp(8), 0, 0, 0);
            buttons.addView(delete, delp);
            delete.setOnClickListener(v -> confirmDelete(entry));
            card.addView(buttons);
        }
    }

    private void playEntry(Entry entry) {
        if (!authenticated) { finish(); return; }
        try {
            File plain = decryptToTemp(entry);
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra(PlayerActivity.EXTRA_URI, Uri.fromFile(plain).toString());
            intent.putExtra(PlayerActivity.EXTRA_TITLE, entry.name);
            intent.putExtra(PlayerActivity.EXTRA_AUDIO, entry.mime != null && entry.mime.startsWith("audio/"));
            intent.putExtra(PlayerActivity.EXTRA_TEMP_VAULT_FILE, plain.getAbsolutePath());
            startActivity(intent);
            authenticated = false;
            finish();
        } catch (Exception e) {
            toast("Could not decrypt this file. It may be damaged or the vault key is unavailable.");
        }
    }

    private void confirmDelete(Entry entry) {
        new AlertDialog.Builder(this).setTitle("Delete private file?")
                .setMessage("This permanently removes the encrypted copy from Lumio's vault.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    new File(vaultDir, entry.id + ".enc").delete();
                    entries.removeIf(x -> x.id.equals(entry.id));
                    try { saveIndex(); renderEntries(); toast("Private file deleted"); }
                    catch (Exception e) { toast("Could not update vault index"); }
                }).show();
    }

    private String readableSize(long bytes) {
        if (bytes >= 1073741824L) return String.format(Locale.US, "%.2f GB", bytes / 1073741824d);
        if (bytes >= 1048576L) return String.format(Locale.US, "%.1f MB", bytes / 1048576d);
        if (bytes >= 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024d);
        return bytes + " B";
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void showFatal(String message) {
        new AlertDialog.Builder(this).setTitle("Private Vault").setMessage(message)
                .setPositiveButton("Close", (d, w) -> finish()).show();
    }

    @Override
    protected void onStop() {
        // Keep the native document picker/authentication flow working, but lock the vault when
        // the user backgrounds the app or switches to another activity.
        if (!importing && !authenticating && authenticated && !isFinishing()) {
            authenticated = false;
            finish();
        }
        super.onStop();
    }
}
