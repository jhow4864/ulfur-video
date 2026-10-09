package com.jamh.videoplayer;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.integration.android.AndroidKeysetManager;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Encryption for Private videos (1.6.0): Tink StreamingAead, AES256_GCM_HKDF_4KB (4 KB segments,
 * so any byte range can be decrypted without reading the file from the start). The Tink keyset
 * lives in its own SharedPreferences file, wrapped by an AES-GCM master key that never leaves
 * the Android Keystore. The keyset file is excluded from backups: restored without its Keystore
 * key it would be useless (the encrypted backup/export in the Vault menu is the way to move
 * private videos to another phone).
 */
final class VaultCrypto {

    static final String KEYSET_PREFS = "ulfur_vault_keyset";
    private static final String KEYSET_NAME = "vault_streaming_keyset";
    private static final String MASTER_KEY_URI = "android-keystore://ulfur_vault_master_key";
    static final String TEMPLATE = "AES256_GCM_HKDF_4KB";

    private static volatile StreamingAead cached;
    /** Robolectric has no AndroidKeyStore: tests inject an in-memory keyset (see TestVideoPlayerApp). */
    @VisibleForTesting
    static volatile StreamingAead testOverride;

    private VaultCrypto() {
    }

    static StreamingAead get(Context ctx) throws IOException {
        StreamingAead t = testOverride;
        if (t != null) return t;
        StreamingAead c = cached;
        if (c != null) return c;
        synchronized (VaultCrypto.class) {
            if (cached == null) {
                try {
                    StreamingAeadConfig.register();
                    KeysetHandle h = new AndroidKeysetManager.Builder()
                            .withSharedPref(ctx.getApplicationContext(), KEYSET_NAME, KEYSET_PREFS)
                            .withKeyTemplate(KeyTemplates.get(TEMPLATE))
                            .withMasterKeyUri(MASTER_KEY_URI)
                            .build()
                            .getKeysetHandle();
                    cached = h.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
                } catch (GeneralSecurityException | RuntimeException e) {
                    throw new IOException("The private-videos key isn't available: " + e.getMessage(), e);
                }
            }
            return cached;
        }
    }

    /** A fresh random in-memory key (tests, and nothing else). */
    @VisibleForTesting
    static StreamingAead newInMemory() {
        try {
            StreamingAeadConfig.register();
            return KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE))
                    .getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Associated data binds each ciphertext to its entry, so files can't be swapped around. */
    static byte[] aadVideo(String id) {
        return ("ulfur-vault-v1:" + id).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] aadThumb(String id) {
        return ("ulfur-vault-thumb-v1:" + id).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * An output stream over {@code fos} that fsyncs before closing, so a file is on disk before
     * the index (or a rename) refers to it.
     */
    static OutputStream syncing(FileOutputStream fos) {
        return new FilterOutputStream(fos) {
            @Override public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override public void close() throws IOException {
                try {
                    out.flush();
                    FileDescriptor fd = fos.getFD();
                    fd.sync();
                } finally {
                    out.close();
                }
            }
        };
    }
}
