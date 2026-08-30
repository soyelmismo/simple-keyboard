/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
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

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import rkr.simplekeyboard.inputmethod.R;

public final class ClipboardSettingsFragment extends SubScreenFragment {
    private static final int PERMISSION_REQUEST_SCREENSHOTS = 101;

    private SwitchPreference mMasterPref;
    private Preference mSuggestionsPref;
    private Preference mScreenshotsPref;
    private SeekBarDialogPreference mRetentionPref;

    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_clipboard);

        mMasterPref = (SwitchPreference) findPreference(Settings.PREF_CLIPBOARD_HISTORY_ENABLED);
        mSuggestionsPref = findPreference(Settings.PREF_CLIPBOARD_SUGGESTIONS);
        mScreenshotsPref = findPreference(Settings.PREF_SUGGEST_SCREENSHOTS);
        mRetentionPref = (SeekBarDialogPreference) findPreference(Settings.PREF_CLIPBOARD_RETENTION_TIME);

        setupClipboardRetentionTimeSettings(mRetentionPref);

        if (mScreenshotsPref != null) {
            mScreenshotsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (Boolean.TRUE.equals(newValue)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        final String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                ? Manifest.permission.READ_MEDIA_IMAGES
                                : Manifest.permission.READ_EXTERNAL_STORAGE;
                        if (getActivity() != null && getActivity().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{permission}, PERMISSION_REQUEST_SCREENSHOTS);
                            return false;
                        }
                    }
                }
                return true;
            });
        }

        if (mMasterPref != null) {
            final boolean isEnabled = Settings.readClipboardHistoryEnabled(getSharedPreferences());
            mMasterPref.setChecked(isEnabled);
            updateSubPreferencesVisibility(isEnabled);
            mMasterPref.setOnPreferenceChangeListener((preference, newValue) -> {
                final boolean enabled = Boolean.TRUE.equals(newValue);
                updateSubPreferencesVisibility(enabled);
                return true;
            });
        }
    }

    private void updateSubPreferencesVisibility(final boolean isEnabled) {
        final PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        if (isEnabled) {
            if (findPreference(Settings.PREF_CLIPBOARD_SUGGESTIONS) == null && mSuggestionsPref != null) {
                screen.addPreference(mSuggestionsPref);
            }
            if (findPreference(Settings.PREF_SUGGEST_SCREENSHOTS) == null && mScreenshotsPref != null) {
                screen.addPreference(mScreenshotsPref);
            }
            if (findPreference(Settings.PREF_CLIPBOARD_RETENTION_TIME) == null && mRetentionPref != null) {
                screen.addPreference(mRetentionPref);
            }
        } else {
            if (mSuggestionsPref != null) {
                screen.removePreference(mSuggestionsPref);
            }
            if (mScreenshotsPref != null) {
                screen.removePreference(mScreenshotsPref);
            }
            if (mRetentionPref != null) {
                screen.removePreference(mRetentionPref);
            }
        }
    }

    private void setupClipboardRetentionTimeSettings(final SeekBarDialogPreference pref) {
        if (pref == null) {
            return;
        }
        final SharedPreferences prefs = getSharedPreferences();
        final Resources res = getResources();
        pref.setInterface(new SeekBarDialogPreference.ValueProxy() {
            @Override
            public void writeValue(final int value, final String key) {
                prefs.edit().putInt(key, value).apply();
            }

            @Override
            public void writeDefaultValue(final String key) {
                prefs.edit().remove(key).apply();
            }

            @Override
            public int readValue(final String key) {
                return Settings.readClipboardRetentionMinutes(prefs);
            }

            @Override
            public int readDefaultValue(final String key) {
                return Settings.CLIPBOARD_RETENTION_TIME_DEFAULT_MINUTES;
            }

            @Override
            public String getValueText(final int value) {
                return formatRetentionMinutes(res, value);
            }

            @Override
            public void feedbackValue(final int value) {
            }
        });
    }

    private static String formatRetentionMinutes(final Resources res, final int minutes) {
        if (minutes < 60) {
            return res.getString(R.string.retention_time_minutes, minutes);
        }
        final int hours = minutes / 60;
        final int remMinutes = minutes % 60;
        if (remMinutes == 0) {
            return res.getString(R.string.retention_time_hours, hours);
        } else {
            return res.getString(R.string.retention_time_hours_minutes, hours, remMinutes);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_SCREENSHOTS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            final SwitchPreference pref = (SwitchPreference) findPreference(Settings.PREF_SUGGEST_SCREENSHOTS);
            if (pref != null) {
                pref.setChecked(true);
            }
        }
    }
}
