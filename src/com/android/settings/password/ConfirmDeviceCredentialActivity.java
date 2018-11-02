
/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.password;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricPrompt.AuthenticationCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.android.internal.widget.LockPatternUtils;
import com.android.settings.R;
import com.android.settings.Utils;

import java.util.concurrent.Executor;

/**
 * Launch this when you want to confirm the user is present by asking them to enter their
 * PIN/password/pattern.
 */
public class ConfirmDeviceCredentialActivity extends FragmentActivity {
    public static final String TAG = ConfirmDeviceCredentialActivity.class.getSimpleName();

    // The normal flow that apps go through
    private static final int CREDENTIAL_NORMAL = 1;
    // Unlocks the managed profile when the primary profile is unlocked
    private static final int CREDENTIAL_MANAGED = 2;

    private static final String TAG_BIOMETRIC_FRAGMENT = "fragment";

    public static class InternalActivity extends ConfirmDeviceCredentialActivity {
    }

    public static Intent createIntent(CharSequence title, CharSequence details) {
        Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                ConfirmDeviceCredentialActivity.class.getName());
        intent.putExtra(KeyguardManager.EXTRA_TITLE, title);
        intent.putExtra(KeyguardManager.EXTRA_DESCRIPTION, details);
        return intent;
    }

    public static Intent createIntent(CharSequence title, CharSequence details, long challenge) {
        Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                ConfirmDeviceCredentialActivity.class.getName());
        intent.putExtra(KeyguardManager.EXTRA_TITLE, title);
        intent.putExtra(KeyguardManager.EXTRA_DESCRIPTION, details);
        intent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_CHALLENGE, challenge);
        intent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_HAS_CHALLENGE, true);
        return intent;
    }

    private BiometricManager mBiometricManager;
    private BiometricFragment mBiometricFragment;
    private DevicePolicyManager mDevicePolicyManager;
    private LockPatternUtils mLockPatternUtils;
    private UserManager mUserManager;
    private ChooseLockSettingsHelper mChooseLockSettingsHelper;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private String mTitle;
    private String mDetails;
    private int mUserId;
    private int mEffectiveUserId;
    private int mCredentialMode;
    private boolean mGoingToBackground;

    private Executor mExecutor = (runnable -> {
        mHandler.post(runnable);
    });

    private AuthenticationCallback mAuthenticationCallback = new AuthenticationCallback() {
        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
            if (!mGoingToBackground) {
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                    finish();
                } else {
                    // All other errors go to some version of CC
                    showConfirmCredentials();
                }
            }

        }

        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
            setResult(Activity.RESULT_OK);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBiometricManager = getSystemService(BiometricManager.class);
        mDevicePolicyManager = getSystemService(DevicePolicyManager.class);
        mUserManager = UserManager.get(this);
        mLockPatternUtils = new LockPatternUtils(this);

        Intent intent = getIntent();
        mTitle = intent.getStringExtra(KeyguardManager.EXTRA_TITLE);
        mDetails = intent.getStringExtra(KeyguardManager.EXTRA_DESCRIPTION);
        String alternateButton = intent.getStringExtra(
                KeyguardManager.EXTRA_ALTERNATE_BUTTON_LABEL);
        boolean frp = KeyguardManager.ACTION_CONFIRM_FRP_CREDENTIAL.equals(intent.getAction());

        mUserId = UserHandle.myUserId();
        mEffectiveUserId = mUserManager.getCredentialOwnerProfile(mUserId);
        if (isInternalActivity()) {
            try {
                mUserId = Utils.getUserIdFromBundle(this, intent.getExtras());
            } catch (SecurityException se) {
                Log.e(TAG, "Invalid intent extra", se);
            }
        }
        final boolean isManagedProfile = UserManager.get(this).isManagedProfile(mUserId);
        // if the client app did not hand in a title and we are about to show the work challenge,
        // check whether there is a policy setting the organization name and use that as title
        if ((mTitle == null) && isManagedProfile) {
            mTitle = getTitleFromOrganizationName(mUserId);
        }
        mChooseLockSettingsHelper = new ChooseLockSettingsHelper(this);
        final LockPatternUtils lockPatternUtils = new LockPatternUtils(this);

        boolean launchedBiometric = false;
        boolean launchedCDC = false;
        // If the target is a managed user and user key not unlocked yet, we will force unlock
        // tied profile so it will enable work mode and unlock managed profile, when personal
        // challenge is unlocked.
        if (frp) {
            launchedCDC = mChooseLockSettingsHelper.launchFrpConfirmationActivity(
                    0, mTitle, mDetails, alternateButton);
        } else if (isManagedProfile && isInternalActivity()
                && !lockPatternUtils.isSeparateProfileChallengeEnabled(mUserId)) {
            mCredentialMode = CREDENTIAL_MANAGED;
            if (isBiometricAllowed()) {
                showBiometricPrompt();
                launchedBiometric = true;
            } else {
                showConfirmCredentials();
            }
        } else {
            mCredentialMode = CREDENTIAL_NORMAL;
            if (isBiometricAllowed()) {
                // Don't need to check if biometrics / pin/pattern/pass are enrolled. It will go to
                // onAuthenticationError and do the right thing automatically.
                showBiometricPrompt();
                launchedBiometric = true;
            } else {
                showConfirmCredentials();
            }
        }

        if (launchedCDC) {
            finish();
        } else if (launchedBiometric) {
            // Keep this activity alive until BiometricPrompt goes away
        } else {
            Log.d(TAG, "No pattern, password or PIN set.");
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Translucent activity that is "visible", so it doesn't complain about finish()
        // not being called before onResume().
        setVisible(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!isChangingConfigurations()) {
            mGoingToBackground = true;
            if (mBiometricFragment != null) {
                mBiometricFragment.cancel();
            }
            finish();
        } else {
            mGoingToBackground = false;
        }
    }

    // User could be locked while Effective user is unlocked even though the effective owns the
    // credential. Otherwise, biometric can't unlock fbe/keystore through
    // verifyTiedProfileChallenge. In such case, we also wanna show the user message that
    // biometric is disabled due to device restart.
    private boolean isStrongAuthRequired() {
        return !mLockPatternUtils.isBiometricAllowedForUser(mEffectiveUserId)
                || !mUserManager.isUserUnlocked(mUserId);
    }

    private boolean isBiometricDisabledByAdmin() {
        final int disabledFeatures =
                mDevicePolicyManager.getKeyguardDisabledFeatures(null, mEffectiveUserId);
        return (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS) != 0;
    }

    private boolean isBiometricAllowed() {
        return !isStrongAuthRequired() && !isBiometricDisabledByAdmin();
    }

    private void showBiometricPrompt() {
        mBiometricManager.setActiveUser(mUserId);

        mBiometricFragment = (BiometricFragment) getSupportFragmentManager()
                .findFragmentByTag(TAG_BIOMETRIC_FRAGMENT);
        boolean newFragment = false;

        if (mBiometricFragment == null) {
            final BiometricFragment.PromptInfo info = new BiometricFragment.PromptInfo.Builder()
                    .setTitle(mTitle)
                    .setSubtitle(mDetails)
                    .setNegativeButtonText(getResources()
                            .getString(R.string.confirm_device_credential_use_alternate_method))
                    .build();
            mBiometricFragment = BiometricFragment.newInstance(info);
            newFragment = true;
        }
        mBiometricFragment.setCallbacks(mExecutor, mAuthenticationCallback);

        if (newFragment) {
            getSupportFragmentManager().beginTransaction()
                    .add(mBiometricFragment, TAG_BIOMETRIC_FRAGMENT).commit();
        }
    }

    /**
     * Shows ConfirmDeviceCredentials for normal apps.
     */
    private void showConfirmCredentials() {
        boolean launched = false;
        if (mCredentialMode == CREDENTIAL_MANAGED) {
            // We set the challenge as 0L, so it will force to unlock managed profile when it
            // unlocks primary profile screen lock, by calling verifyTiedProfileChallenge()
            launched = mChooseLockSettingsHelper
                    .launchConfirmationActivityWithExternalAndChallenge(
                            0 /* request code */, null /* title */, mTitle, mDetails,
                            true /* isExternal */, 0L /* challenge */, mUserId);
        } else if (mCredentialMode == CREDENTIAL_NORMAL){
            launched = mChooseLockSettingsHelper.launchConfirmationActivity(
                    0 /* request code */, null /* title */,
                    mTitle, mDetails, false /* returnCredentials */, true /* isExternal */,
                    mUserId);
        }
        if (!launched) {
            Log.d(TAG, "No pin/pattern/pass set");
            setResult(Activity.RESULT_OK);
        }
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        // Finish without animation since the activity is just there so we can launch
        // BiometricPrompt.
        overridePendingTransition(R.anim.confirm_credential_biometric_transition_enter, 0);
    }

    private boolean isInternalActivity() {
        return this instanceof ConfirmDeviceCredentialActivity.InternalActivity;
    }

    private String getTitleFromOrganizationName(int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        CharSequence organizationNameForUser = (dpm != null)
                ? dpm.getOrganizationNameForUser(userId) : null;
        return organizationNameForUser != null ? organizationNameForUser.toString() : null;
    }
}
