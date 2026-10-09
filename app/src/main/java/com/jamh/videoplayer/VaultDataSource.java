package com.jamh.videoplayer;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Map;

/**
 * Media3 DataSource for Private videos (1.6.0). Opens at any {@code dataSpec.position}
 * by positioning Tink's seekable decrypting channel, so seeking costs one 4 KB segment
 * decrypt instead of reading from the start: the 1.4.2 fast-seek behaviour is unchanged.
 * Not-yet-migrated (plaintext) entries are read through a plain FileChannel.
 */
@UnstableApi
final class VaultDataSource extends BaseDataSource {

    private final Context ctx;
    @Nullable private SeekableByteChannel channel;
    @Nullable private Uri uri;
    private long bytesRemaining;
    private boolean opened;

    VaultDataSource(Context ctx) {
        super(/* isNetwork= */ false);
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        Vault.Entry e = Vault.findByUri(ctx, spec.uri);
        if (e == null) {
            throw new DataSourceException(new java.io.FileNotFoundException("Private video not found"),
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND);
        }
        try {
            channel = Vault.openSeekable(ctx, e);
            long size = channel.size();
            if (spec.position > size) {
                throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }
            channel.position(spec.position);
            bytesRemaining = spec.length != C.LENGTH_UNSET ? Math.min(spec.length, size - spec.position)
                    : size - spec.position;
        } catch (DataSourceException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DataSourceException(ex, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        opened = true;
        transferStarted(spec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        SeekableByteChannel ch = channel;
        if (ch == null) throw new IOException("not open");
        int n;
        try {
            n = ch.read(ByteBuffer.wrap(buffer, offset, (int) Math.min(length, bytesRemaining)));
        } catch (IOException ex) {
            throw new DataSourceException(ex, PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        if (n < 0) return C.RESULT_END_OF_INPUT;
        bytesRemaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        uri = null;
        try {
            if (channel != null) channel.close();
        } finally {
            channel = null;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    // ------------------------------------------------------------------ routing

    /** Vault Uris go to {@link VaultDataSource}; everything else to {@code upstream}. */
    static DataSource.Factory routing(Context ctx, DataSource.Factory upstream) {
        Context app = ctx.getApplicationContext();
        return () -> new Routing(new VaultDataSource(app), upstream.createDataSource());
    }

    /** DefaultMediaSourceFactory that can read Private videos (clip editor preview, Transformer). */
    static DefaultMediaSourceFactory mediaSourceFactory(Context ctx) {
        return new DefaultMediaSourceFactory(routing(ctx, new DefaultDataSource.Factory(ctx)));
    }

    private static final class Routing implements DataSource {
        private final DataSource vault, other;
        @Nullable private DataSource active;

        Routing(DataSource vault, DataSource other) {
            this.vault = vault;
            this.other = other;
        }

        @Override public void addTransferListener(TransferListener l) {
            vault.addTransferListener(l);
            other.addTransferListener(l);
        }

        @Override public long open(DataSpec spec) throws IOException {
            active = Vault.isVaultUri(spec.uri) ? vault : other;
            return active.open(spec);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (active == null) throw new IOException("not open");
            return active.read(b, off, len);
        }

        @Nullable @Override public Uri getUri() {
            return active == null ? null : active.getUri();
        }

        @Override public Map<String, List<String>> getResponseHeaders() {
            return active == null ? java.util.Collections.emptyMap() : active.getResponseHeaders();
        }

        @Override public void close() throws IOException {
            DataSource a = active;
            active = null;
            if (a != null) a.close();
        }
    }
}
