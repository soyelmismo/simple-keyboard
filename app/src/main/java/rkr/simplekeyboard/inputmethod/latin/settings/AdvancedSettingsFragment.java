/*
 * Copyright (C) 2026 Antigravity
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

package rkr.simplekeyboard.inputmethod.latin.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;

import androidx.annotation.Nullable;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.AudioAndHapticFeedbackManager;

public final class AdvancedSettingsFragment extends SubScreenFragment {
    @Override
    public void onCreatePreferences(@Nullable final Bundle savedInstanceState, @Nullable final String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.prefs_screen_advanced, rootKey);

        final Context context = requireContext();
        AudioAndHapticFeedbackManager.init(context);

        if (!AudioAndHapticFeedbackManager.getInstance().hasVibrator()) {
            removePreference("category_vibration_advanced");
            removePreference(Settings.PREF_VIBRATION_DURATION);
        }

        setupKeyRepeatStartTimeoutSettings();
        setupKeyRepeatIntervalSettings();
        setupVibrationDurationSettings();
        setupKeyPreviewLingerTimeoutSettings();
        setupBottomOffsetLandscapeSettings();
        setupClipboardMaxClipsSettings();
    }

    private void setupKeyRepeatStartTimeoutSettings() {
        final SeekBarDialogPreference pref = findPreference(Settings.PREF_KEY_REPEAT_START_TIMEOUT);
        if (pref == null) return;
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        pref.setInterface(new SeekBarDialogPreference.SimpleIntProxy(prefs) {
            @Override
            public int readValue(final String key) {
                return Settings.readKeyRepeatStartTimeout(prefs, res);
            }

            @Override
            public int readDefaultValue(final String key) {
                return Settings.readDefaultKeyRepeatStartTimeout(res);
            }

            @Override
            public String getValueText(final int value) {
                return res.getString(R.string.abbreviation_unit_milliseconds, value);
            }
        });
    }

    private void setupKeyRepeatIntervalSettings() {
        final SeekBarDialogPreference pref = findPreference(Settings.PREF_KEY_REPEAT_INTERVAL);
        if (pref == null) return;
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        pref.setInterface(new SeekBarDialogPreference.SimpleIntProxy(prefs) {
            @Override
            public int readValue(final String key) {
                return Settings.readKeyRepeatInterval(prefs, res);
            }

            @Override
            public int readDefaultValue(final String key) {
                return Settings.readDefaultKeyRepeatInterval(res);
            }

            @Override
            public String getValueText(final int value) {
                return res.getString(R.string.abbreviation_unit_milliseconds, value);
            }
        });
    }

    private void setupVibrationDurationSettings() {
        final SeekBarDialogPreference pref = findPreference(Settings.PREF_VIBRATION_DURATION);
        if (pref == null) return;
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        pref.setInterface(new SeekBarDialogPreference.SimpleIntProxy(prefs) {
            @Override
            public int readValue(final String key) {
                return Settings.readVibrationDuration(prefs);
            }

            @Override
            public int readDefaultValue(final String key) {
                return Settings.readDefaultVibrationDuration();
            }

            @Override
            public String getValueText(final int value) {
                if (value <= 0) {
                    return res.getString(R.string.settings_system_default);
                }
                return res.getString(R.string.abbreviation_unit_milliseconds, value);
            }

            @Override
            public void feedbackValue(final int value) {
                AudioAndHapticFeedbackManager.getInstance().performVibrationPreview(value);
            }
        });
    }

    private void setupKeyPreviewLingerTimeoutSettings() {
        final SeekBarDialogPreference pref = findPreference(Settings.PREF_KEY_PREVIEW_LINGER_TIMEOUT);
        if (pref == null) return;
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        pref.setInterface(new SeekBarDialogPreference.SimpleIntProxy(prefs) {
            @Override
            public int readValue(final String key) {
                return Settings.readKeyPreviewLingerTimeout(prefs, res);
            }

            @Override
            public int readDefaultValue(final String key) {
                return Settings.readDefaultKeyPreviewLingerTimeout(res);
            }

            @Override
            public String getValueText(final int value) {
                return res.getString(R.string.abbreviation_unit_milliseconds, value);
            }
        });
    }

    private void setupBottomOffsetLandscapeSettings() {
        final SeekBarDialogPreference pref = findPreference(Settings.PREF_BOTTOM_OFFSET_LANDSCAPE);
        if (pref == null) return;
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        pref.setInterface(new SeekBarDialogPreference.SimpleIntProxy(prefs) {
            @Override
            public int readValue(final String key) {
                return Settings.readBottomOffsetLandscape(prefs);
            }

            @Override
            public int readDefaultValue(final String key) {
                return Settings.DEFAULT_BOTTOM_OFFSET;
            }

            @Override
            public String getValueText(final int value) {
                return res.getString(R.string.abbreviation_unit_dp, value);
            }
        });
    }

    private void setupClipboardMaxClipsSettings() {
        final SeekBarDialogPreference pref = findPreference(Settings.PREF_CLIPBOARD_MAX_CLIPS);
        if (pref == null) return;
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        pref.setInterface(new SeekBarDialogPreference.SimpleIntProxy(prefs) {
            @Override
            public int readValue(final String key) {
                return Settings.readClipboardMaxClips(prefs);
            }

            @Override
            public int readDefaultValue(final String key) {
                return Settings.readDefaultClipboardMaxClips();
            }

            @Override
            public String getValueText(final int value) {
                return res.getString(R.string.unit_clips, value);
            }
        });
    }
}
