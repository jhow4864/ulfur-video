package com.jamh.videoplayer;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

/**
 * Fingerprint / face (class 2 "weak" or better) with the screen-lock PIN, pattern or password as
 * fallback. BIOMETRIC_STRONG | DEVICE_CREDENTIAL isn't supported on Android 9-10, so WEAK is the
 * combination that works on every supported version.
 */
final class VaultAuth {

    static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    interface Callback {
        void onUnlocked();

        /** {@code noScreenLock}: the phone has no PIN/pattern/password/biometric set up. */
        void onFailed(boolean noScreenLock, CharSequence message);
    }

    private VaultAuth() {
    }

    /** True when the phone can authenticate (any secure lock screen or enrolled biometric). */
    static boolean isAvailable(Context ctx) {
        return BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** Create in onCreate (BiometricPrompt must be registered before the activity is started). */
    static BiometricPrompt create(FragmentActivity activity, Callback cb) {
        return new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                        VaultLock.unlock();
                        cb.onUnlocked();
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        boolean noLock = code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL
                                || code == BiometricPrompt.ERROR_NO_BIOMETRICS
                                || code == BiometricPrompt.ERROR_HW_NOT_PRESENT;
                        cb.onFailed(noLock, msg);
                    }
                });
    }

    static void prompt(BiometricPrompt bp) {
        bp.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Private videos")
                .setSubtitle("Use your fingerprint, face or screen lock")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build());
    }
}
