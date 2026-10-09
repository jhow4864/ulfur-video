package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.6.0: Private videos ⋮ → Back up (encrypted) / Restore from backup / Export unencrypted copies.
 * Files go through the Storage Access Framework (any folder, SD card, Drive...), streamed.
 */
final class VaultBackupFlow {

    static final int MIN_PASSWORD = 8;

    private final AppCompatActivity activity;
    private final Runnable onChanged;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> createDoc;
    private final ActivityResultLauncher<String[]> openDoc;
    private final ActivityResultLauncher<Uri> openTree;
    @Nullable private char[] pendingPassword;
    @Nullable private List<Vault.Entry> pendingEntries;

    /** Construct in onCreate (registers activity-result launchers). */
    VaultBackupFlow(AppCompatActivity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        createDoc = activity.registerForActivityResult(new ActivityResultContracts.CreateDocument(VaultBackup.MIME), uri -> {
            VaultLock.pickerClosed();
            char[] pw = pendingPassword;
            List<Vault.Entry> list = pendingEntries;
            pendingPassword = null;
            pendingEntries = null;
            if (uri != null && pw != null && list != null) runExport(uri, list, pw);
            else if (pw != null) Arrays.fill(pw, '\0');
        });
        openDoc = activity.registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            VaultLock.pickerClosed();
            if (uri != null) askRestorePassword(uri);
        });
        openTree = activity.registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            VaultLock.pickerClosed();
            List<Vault.Entry> list = pendingEntries;
            pendingEntries = null;
            if (uri != null && list != null) runPlainExport(uri, list);
        });
    }

    void shutdown() {
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ encrypted backup

    static String backupSummary(List<Vault.Entry> list) {
        long bytes = 0;
        for (Vault.Entry e : list) bytes += Math.max(0, e.size);
        return list.size() + (list.size() == 1 ? " video · " : " videos · ") + (bytes > 0 ? Fmt.size(bytes) : "0 MB");
    }

    /** Step 1: choose a password (shows what will be backed up and how big it is). */
    @VisibleForTesting
    @Nullable
    AlertDialog startBackup(List<Vault.Entry> list) {
        if (list.isEmpty()) {
            toast("No private videos to back up");
            return null;
        }
        View v = passwordView(true);
        TextInputEditText p1 = v.findViewWithTag("pw1");
        TextInputEditText p2 = v.findViewWithTag("pw2");
        TextInputLayout l2 = v.findViewWithTag("pw2layout");
        AlertDialog d = new MaterialAlertDialogBuilder(activity)
                .setTitle("Encrypted backup")
                .setMessage(backupSummary(list) + ". The backup file is encrypted with this password. "
                        + "Without it the backup can't be opened, by anyone, including you.")
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose where to save", null)
                .show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(x -> {
            char[] a = text(p1), b = text(p2);
            if (a.length < MIN_PASSWORD) {
                l2.setError("Use at least " + MIN_PASSWORD + " characters");
            } else if (!Arrays.equals(a, b)) {
                l2.setError("Passwords don't match");
            } else {
                Arrays.fill(b, '\0');
                pendingPassword = a;
                pendingEntries = list;
                d.dismiss();
                VaultLock.pickerOpening();
                String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(new Date());
                createDoc.launch("Ulfur private backup " + stamp + VaultBackup.EXT);
            }
        });
        return d;
    }

    private void runExport(Uri uri, List<Vault.Entry> list, char[] pw) {
        Progress ui = progressDialog("Backing up private videos", list.size());
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            String err = null;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Can't write there");
                VaultBackup.export(app, list, pw, out, ui);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? "Backup failed" : e.getMessage();
                try {
                    DocumentsContract.deleteDocument(app.getContentResolver(), uri); // no half-written backup
                } catch (Exception ignored) {
                }
            } finally {
                Arrays.fill(pw, '\0');
            }
            final String error = err;
            main.post(() -> {
                ui.dismiss();
                if (error == null) {
                    info("Backup saved", backupSummary(list) + " backed up, encrypted. Keep the password safe: "
                            + "you need it to restore them on this or another phone (Private videos ⋮ → Restore from backup).");
                } else if (!ui.cancelled.get()) {
                    info("Backup failed", error + ". Nothing was changed in Private videos.");
                }
            });
        });
    }

    // ------------------------------------------------------------------ restore

    void startRestore() {
        VaultLock.pickerOpening();
        openDoc.launch(new String[]{"application/octet-stream", "*/*"});
    }

    @VisibleForTesting
    AlertDialog askRestorePassword(Uri uri) {
        View v = passwordView(false);
        TextInputEditText p1 = v.findViewWithTag("pw1");
        TextInputLayout l1 = v.findViewWithTag("pw1layout");
        AlertDialog d = new MaterialAlertDialogBuilder(activity)
                .setTitle("Restore from backup")
                .setMessage("Enter the password you chose when you made this backup.")
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Restore", null)
                .show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(x -> {
            char[] pw = text(p1);
            if (pw.length == 0) {
                l1.setError("Enter the password");
                return;
            }
            d.dismiss();
            runRestore(uri, pw);
        });
        return d;
    }

    private void runRestore(Uri uri, char[] pw) {
        Progress ui = progressDialog("Restoring private videos", 0);
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            VaultBackup.Result r = null;
            String err = null;
            boolean wrongPassword = false;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Can't read that file");
                r = VaultBackup.restore(app, in, pw, ui);
            } catch (VaultBackup.WrongPasswordException e) {
                wrongPassword = true;
                err = e.getMessage();
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? "Restore failed" : e.getMessage();
            } finally {
                Arrays.fill(pw, '\0');
            }
            final VaultBackup.Result res = r;
            final String error = err;
            final boolean retry = wrongPassword;
            main.post(() -> {
                ui.dismiss();
                onChanged.run();
                if (res == null) {
                    AlertDialog d = info(retry ? "Wrong password" : "Couldn't restore", error);
                    if (retry) {
                        d.setButton(DialogInterface.BUTTON_NEUTRAL, "Try again", (dd, w) -> askRestorePassword(uri));
                    }
                    return;
                }
                info(res.failed.isEmpty() ? "Restored" : "Restored with problems", restoreSummary(res));
            });
        });
    }

    static String restoreSummary(VaultBackup.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.restored == 1 ? "1 video restored" : r.restored + " videos restored").append(" to Private videos.");
        if (r.skipped > 0) sb.append("\n").append(r.skipped).append(r.skipped == 1 ? " was" : " were").append(" already here.");
        if (!r.failed.isEmpty()) {
            sb.append("\n\nNot restored:");
            for (String f : r.failed) sb.append("\n• ").append(f);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ plain export

    @VisibleForTesting
    @Nullable
    AlertDialog startPlainExport(List<Vault.Entry> list) {
        if (list.isEmpty()) {
            toast("No private videos to export");
            return null;
        }
        AlertDialog d = new MaterialAlertDialogBuilder(activity)
                .setTitle("Export unencrypted copies?")
                .setMessage(backupSummary(list) + " will be copied, decrypted, into a folder you choose. "
                        + "Those copies are NOT protected: any app or person with access to that folder "
                        + "(or a gallery that scans it) can see them. They also stay in Private videos.\n\n"
                        + "For moving to a new phone, an encrypted backup is safer.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose folder", (dd, w) -> {
                    pendingEntries = list;
                    VaultLock.pickerOpening();
                    openTree.launch(null);
                })
                .show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(activity, R.color.warn));
        return d;
    }

    /** Where plain copies go (a SAF folder in the app, a plain directory in tests). */
    interface Sink {
        OutputStream create(String displayName, String mime) throws IOException;
    }

    /** Decrypts each entry into {@code sink}. Returns how many were written. */
    static int exportPlain(Context ctx, List<Vault.Entry> list, Sink sink, @Nullable VaultBackup.Progress p) throws IOException {
        long total = VaultBackup.totalBytes(ctx, list), done = 0;
        int n = 0;
        byte[] buf = new byte[256 * 1024];
        for (int i = 0; i < list.size(); i++) {
            Vault.Entry e = list.get(i);
            long count = 0;
            try (InputStream in = Vault.openStream(ctx, e); OutputStream out = sink.create(e.name, e.mime)) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (p != null && p.isCancelled()) throw new IOException("Cancelled");
                    out.write(buf, 0, r);
                    count += r;
                    done += r;
                    if (p != null) p.onProgress(done, total, i, list.size());
                }
            }
            if (count != e.size) throw new IOException("\u201c" + e.name + "\u201d wasn't copied completely");
            n++;
        }
        return n;
    }

    private void runPlainExport(Uri tree, List<Vault.Entry> list) {
        Progress ui = progressDialog("Exporting unencrypted copies", list.size());
        final Context app = activity.getApplicationContext();
        io.execute(() -> {
            String err = null;
            int done = 0;
            try {
                ContentResolver cr = app.getContentResolver();
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                done = exportPlain(app, list, (name, mime) -> {
                    Uri doc = DocumentsContract.createDocument(cr, parent, mime, name);
                    if (doc == null) throw new IOException("Can't create \u201c" + name + "\u201d there");
                    OutputStream o = cr.openOutputStream(doc, "w");
                    if (o == null) throw new IOException("Can't write \u201c" + name + "\u201d");
                    return o;
                }, ui);
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() == null ? "Export failed" : e.getMessage();
            }
            final String error = err;
            final int n = done;
            main.post(() -> {
                ui.dismiss();
                if (error == null) toast(n == 1 ? "Exported 1 unencrypted copy" : "Exported " + n + " unencrypted copies");
                else if (!ui.cancelled.get()) info("Export stopped", n + " of " + list.size() + " exported. " + error);
            });
        });
    }

    // ------------------------------------------------------------------ UI helpers

    private View passwordView(boolean confirm) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        box.addView(field("pw1", "Password"));
        if (confirm) box.addView(field("pw2", "Repeat password"));
        return box;
    }

    private TextInputLayout field(String tag, String hint) {
        TextInputLayout l = new TextInputLayout(activity, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        l.setTag(tag + "layout");
        l.setHint(hint);
        l.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        TextInputEditText e = new TextInputEditText(l.getContext());
        e.setTag(tag);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTypeface(android.graphics.Typeface.DEFAULT); // password fields default to monospace
        e.setSingleLine(true);
        l.addView(e);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * activity.getResources().getDisplayMetrics().density);
        l.setLayoutParams(lp);
        return l;
    }

    private static char[] text(EditText e) {
        CharSequence s = e.getText();
        if (s == null) return new char[0];
        char[] out = new char[s.length()];
        for (int i = 0; i < out.length; i++) out[i] = s.charAt(i);
        if (e.getText() != null) e.getText().clear();
        return out;
    }

    private AlertDialog info(String title, String msg) {
        return new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
    }

    /** Progress dialog with size + count, cancellable. */
    final class Progress implements VaultBackup.Progress {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AlertDialog dialog;
        final TextView status;
        final LinearProgressIndicator bar;
        long lastUi;

        Progress(String title, int count) {
            View v = LayoutInflater.from(activity).inflate(R.layout.dialog_export_progress, null);
            status = v.findViewById(R.id.export_status);
            bar = v.findViewById(R.id.export_progress);
            status.setText(count > 0 ? "Preparing " + count + (count == 1 ? " video…" : " videos…") : "Checking the password…");
            dialog = new MaterialAlertDialogBuilder(activity).setTitle(title).setView(v).setCancelable(false)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled.set(true)).show();
        }

        @Override public void onProgress(long done, long total, int index, int count) {
            long now = System.currentTimeMillis();
            if (now - lastUi < 150 && done < total) return;
            lastUi = now;
            main.post(() -> {
                if (bar != null) {
                    bar.setIndeterminate(false);
                    bar.setMax(1000);
                    bar.setProgress(total > 0 ? (int) (1000 * done / total) : 0);
                }
                status.setText("Video " + Math.min(index + 1, count) + " of " + count + " · "
                        + Fmt.size(Math.max(done, 1)) + " of " + Fmt.size(Math.max(total, 1)));
            });
        }

        @Override public boolean isCancelled() {
            return cancelled.get();
        }

        void dismiss() {
            try {
                dialog.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private Progress progressDialog(String title, int count) {
        return new Progress(title, count);
    }
}
