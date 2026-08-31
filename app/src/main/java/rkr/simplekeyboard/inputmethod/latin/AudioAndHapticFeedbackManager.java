/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.latin;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rkr.simplekeyboard.inputmethod.compat.BuildCompatUtils;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;

/**
 * This class gathers audio feedback and haptic feedback functions.
 *
 * It offers a consistent and simple interface that allows LatinIME to forget about the
 * complexity of settings and the like.
 */
public final class AudioAndHapticFeedbackManager {
    private static final long TICK_FREQUENCY = 100;
    private static final SparseIntArray sAudioFxMap = new SparseIntArray();

    static {
        sAudioFxMap.put(Constants.CODE_DELETE, AudioManager.FX_KEYPRESS_DELETE);
        sAudioFxMap.put(Constants.CODE_ENTER, AudioManager.FX_KEYPRESS_RETURN);
        sAudioFxMap.put(Constants.CODE_SPACE, AudioManager.FX_KEYPRESS_SPACEBAR);
    }

    private ExecutorService mBackgroundThread;
    private AudioManager mAudioManager;
    private Vibrator mVibrator;

    private SettingsValues mSettingsValues;
    private boolean mSoundOn;
    private long mLastTickTime = 0;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        mBackgroundThread = Executors.newSingleThreadExecutor();
        mBackgroundThread.execute(() -> {
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        });
    }

    public boolean hasVibrator() {
        return mVibrator != null && mVibrator.hasVibrator();
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    private static int getSoundEffectForCode(final int code) {
        return sAudioFxMap.get(code, AudioManager.FX_KEYPRESS_STANDARD);
    }

    public void performAudioFeedback(final int code) {
        if (mAudioManager == null || !mSoundOn) {
            return;
        }
        playSoundEffect(getSoundEffectForCode(code), mSettingsValues.mKeypressSoundVolume);
    }

    public void playSoundEffect(final int effectType, final float volume) {
        if (mAudioManager == null) {
            return;
        }

        mBackgroundThread.execute(() -> {
            mAudioManager.playSoundEffect(effectType, volume);
        });
    }

    private boolean canHapticVibrate() {
        return mSettingsValues != null && mSettingsValues.mVibrateOn && mVibrator != null;
    }

    private void triggerCustomVibration(final int durationMs, final View fallbackView) {
        if (mVibrator == null) {
            return;
        }
        if (durationMs > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mVibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                mVibrator.vibrate(durationMs);
            }
        } else {
            triggerVibrationEffect(VibrationEffect.EFFECT_CLICK, fallbackView);
        }
    }

    private void triggerVibrationEffect(final int effectId, final View fallbackView) {
        if (mVibrator == null) {
            return;
        }
        if (BuildCompatUtils.isAtLeastQ()) {
            mVibrator.vibrate(VibrationEffect.createPredefined(effectId));
        } else if (fallbackView != null) {
            fallbackView.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private void vibratePredefined(final int effectId, final View fallbackView) {
        if (!canHapticVibrate()) {
            return;
        }
        mBackgroundThread.execute(() -> triggerVibrationEffect(effectId, fallbackView));
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn) {
        if (!canHapticVibrate()) {
            return;
        }
        final int customDuration = mSettingsValues != null ? mSettingsValues.mVibrationDuration : 0;
        mBackgroundThread.execute(() -> triggerCustomVibration(customDuration, viewToPerformHapticFeedbackOn));
    }

    public void performVibrationPreview(final int durationMs) {
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            return;
        }
        if (mBackgroundThread != null) {
            mBackgroundThread.execute(() -> triggerCustomVibration(durationMs, null));
        }
    }

    private boolean isTickThrottled() {
        return System.currentTimeMillis() - mLastTickTime < TICK_FREQUENCY;
    }

    public void performTickFeedback() {
        if (!canHapticVibrate() || isTickThrottled()) {
            return;
        }

        if (BuildCompatUtils.isAtLeastQ()) {
            mLastTickTime = System.currentTimeMillis();
            vibratePredefined(VibrationEffect.EFFECT_TICK, null);
        }
    }

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged() {
        mSoundOn = reevaluateIfSoundIsOn();
    }
}
