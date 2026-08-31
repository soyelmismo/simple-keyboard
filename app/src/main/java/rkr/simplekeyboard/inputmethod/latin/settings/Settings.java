/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2024 wittmane
 * Copyright (C) 2019 Micha LaQua
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme;
import rkr.simplekeyboard.inputmethod.latin.AudioAndHapticFeedbackManager;
import rkr.simplekeyboard.inputmethod.latin.InputAttributes;
import rkr.simplekeyboard.inputmethod.latin.RichInputMethodManager;

public final class Settings extends BroadcastReceiver implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = Settings.class.getSimpleName();
    public static final String ACTIVE_RESTRICTIONS = "active_restrictions";
    // Settings screens
    public static final String SCREEN_THEME = "screen_theme";
    // In the same order as xml/prefs.xml
    public static final String PREF_AUTO_CAP = "auto_cap";
    public static final String PREF_AUTO_PERIOD = "auto_period";
    public static final String PREF_VIBRATE_ON = "vibrate_on";
    public static final String PREF_SOUND_ON = "sound_on";
    public static final String PREF_POPUP_ON = "popup_on";
    public static final String PREF_SHOW_LANGUAGE_SWITCH_KEY = "pref_show_language_switch_key";
    public static final String PREF_SHOW_LANGUAGE_ON_SPACEBAR = "pref_show_language_on_spacebar";
    public static final String PREF_USE_ON_SCREEN = "pref_use_on_screen";
    public static final String PREF_ENABLE_IME_SWITCH = "pref_enable_ime_switch";
    public static final String PREF_ENABLED_SUBTYPES = "pref_enabled_subtypes";
    public static final String PREF_KEYPRESS_SOUND_VOLUME = "pref_keypress_sound_volume";
    public static final String PREF_KEY_LONGPRESS_TIMEOUT = "pref_key_longpress_timeout";
    public static final String PREF_KEYBOARD_HEIGHT = "pref_keyboard_height";
    public static final String PREF_BOTTOM_OFFSET_PORTRAIT = "pref_bottom_offset_portrait";
    public static final String PREF_BOTTOM_OFFSET_LANDSCAPE = "pref_bottom_offset_landscape";
    public static final String PREF_KEY_REPEAT_START_TIMEOUT = "pref_key_repeat_start_timeout";
    public static final String PREF_KEY_REPEAT_INTERVAL = "pref_key_repeat_interval";
    public static final String PREF_VIBRATION_DURATION = "pref_vibration_duration";
    public static final int DEFAULT_VIBRATION_DURATION = 0;
    public static final String PREF_KEY_PREVIEW_LINGER_TIMEOUT = "pref_key_preview_linger_timeout";
    public static final String PREF_SHOW_SPECIAL_CHARS = "pref_show_special_chars";
    public static final String PREF_SHOW_NUMBER_ROW = "pref_show_number_row";
    public static final String PREF_SPACE_SWIPE = "pref_space_swipe";
    public static final String PREF_DELETE_SWIPE = "pref_delete_swipe";
    public static final String PREF_SWIPE_SENSITIVITY = "pref_swipe_sensitivity";
    public static final String PREF_DISABLE_LANDSCAPE_FULLSCREEN = "pref_disable_landscape_fullscreen";
    public static final String PREF_CLIPBOARD_ENABLED = "pref_clipboard_enabled";
    public static final String PREF_CLIPBOARD_MAX_CLIPS = "pref_clipboard_max_clips";
    public static final int CLIPBOARD_MAX_CLIPS_DEFAULT = 50;
    public static final int CLIPBOARD_MAX_CLIPS_MIN = 10;
    public static final int CLIPBOARD_MAX_CLIPS_MAX = 100;

    public static final String PREF_CLIPBOARD_RETENTION_TIME = "pref_clipboard_retention_time";
    public static final int CLIPBOARD_RETENTION_TIME_MIN_MINUTES = 30;
    public static final int CLIPBOARD_RETENTION_TIME_MAX_MINUTES = 720;
    public static final int CLIPBOARD_RETENTION_TIME_DEFAULT_MINUTES = 60;
    public static final String PREF_CLIPBOARD_SUGGESTIONS = "pref_clipboard_suggestions";
    public static final String PREF_SUGGEST_SCREENSHOTS = "pref_suggest_screenshots";
    public static final String PREF_KEY_SHAPE = "pref_key_shape";

    public static final String KEY_SHAPE_ROUNDED = "rounded";
    public static final String KEY_SHAPE_RECTANGULAR = "rectangular";
    public static final String KEY_SHAPE_BORDERLESS = "borderless";
    public static final String PREF_SHOW_SUGGESTIONS = "pref_show_suggestions";
    public static final String PREF_SUGGESTIONS_IN_URLS = "pref_suggestions_in_urls";
    public static final String PREF_AUTO_LEARN = "pref_auto_learn";
    public static final String PREF_AUTO_CORRECTION_THRESHOLD = "auto_correction_threshold";
    public static final String AUTO_CORRECTION_THRESHOLD_DEFAULT = "1";
    public static final float AUTO_CORRECTION_THRESHOLD_OFF = 0.0f;
    public static final float AUTO_CORRECTION_THRESHOLD_MODEST = 1.0f;
    public static final float AUTO_CORRECTION_THRESHOLD_AGGRESSIVE = 2.0f;
    public static final float AUTO_CORRECTION_THRESHOLD_VERY_AGGRESSIVE = 3.0f;

    private static final float UNDEFINED_PREFERENCE_VALUE_FLOAT = -1.0f;
    private static final int UNDEFINED_PREFERENCE_VALUE_INT = -1;

    private Context mContext;
    private Resources mRes;
    private SharedPreferences mPrefs;
    private SettingsValues mSettingsValues;
    private RestrictionsManager mRestrictionsMgr;
    private final ReentrantLock mSettingsValuesLock = new ReentrantLock();

    private static final Settings sInstance = new Settings();

    public static Settings getInstance() {
        return sInstance;
    }

    public static void init(final Context context) {
        sInstance.onCreate(context);
    }

    private Settings() {
        // Intentional empty constructor for singleton.
    }

    private void onCreate(final Context context) {
        mContext = context;
        mRes = context.getResources();
        mPrefs = PreferenceManagerCompat.getDeviceSharedPreferences(context);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        mRestrictionsMgr = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
        loadRestrictions(mRestrictionsMgr, mPrefs);
        context.registerReceiver(this, new IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED));
    }

    public void onDestroy() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs, final String key) {
        if (PREF_ENABLED_SUBTYPES.equals(key) || key == null) {
            RichInputMethodManager.getInstance().reloadSubtypes(mContext);
        }
        rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet.clearKeyboardCache();
        mSettingsValuesLock.lock();
        try {
            if (mSettingsValues == null) {
                // TODO: Introduce a static function to register this class and ensure that
                // loadSettings must be called before "onSharedPreferenceChanged" is called.
                Log.w(TAG, "onSharedPreferenceChanged called before loadSettings.");
                return;
            }
            loadSettings(mSettingsValues.mInputAttributes);
        } finally {
            mSettingsValuesLock.unlock();
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        loadRestrictions(mRestrictionsMgr, mPrefs);
        onSharedPreferenceChanged(mPrefs, null);
        RichInputMethodManager.getInstance().reloadSubtypes(context);
    }

    public static Set<String> loadRestrictions(final RestrictionsManager restrictionsMgr, final SharedPreferences prefs) {
        final Bundle appRestrictions = restrictionsMgr.getApplicationRestrictions();
        final Set<String> restrictionKeys = appRestrictions.keySet();
        if (restrictionKeys.isEmpty()) {
            if (prefs.contains(ACTIVE_RESTRICTIONS)) {
                prefs.edit().remove(ACTIVE_RESTRICTIONS).apply();
            }
        } else {
            final SharedPreferences.Editor prefsEditor = prefs.edit();
            for (final String key : restrictionKeys) {
                applySingleRestriction(key, appRestrictions, prefsEditor);
            }
            prefsEditor.putStringSet(ACTIVE_RESTRICTIONS, restrictionKeys);
            prefsEditor.apply();
        }
        return restrictionKeys;
    }

    @FunctionalInterface
    private interface RestrictionApplier {
        void apply(String key, Bundle appRestrictions, SharedPreferences.Editor prefsEditor);
    }

    private static final Map<String, RestrictionApplier> RESTRICTION_APPLIERS = createRestrictionAppliers();

    private static Map<String, RestrictionApplier> createRestrictionAppliers() {
        final Map<String, RestrictionApplier> map = new HashMap<>();
        final RestrictionApplier stringApplier = (key, bundle, editor) -> {
            final String val = bundle.getString(key);
            Log.i(TAG, "Loading restriction: " + key + "=" + val);
            editor.putString(key, val);
        };
        final RestrictionApplier boolApplier = (key, bundle, editor) -> {
            final boolean val = bundle.getBoolean(key);
            Log.i(TAG, "Loading restriction: " + key + "=" + val);
            editor.putBoolean(key, val);
        };
        final RestrictionApplier percentFloatApplier = (key, bundle, editor) -> {
            final int val = bundle.getInt(key);
            Log.i(TAG, "Loading restriction: " + key + "=" + val);
            editor.putFloat(key, val / 100f);
        };
        final RestrictionApplier intApplier = (key, bundle, editor) -> {
            final int val = bundle.getInt(key);
            Log.i(TAG, "Loading restriction: " + key + "=" + val);
            editor.putInt(key, val);
        };

        map.put(PREF_ENABLED_SUBTYPES, stringApplier);
        map.put(PREF_AUTO_CORRECTION_THRESHOLD, stringApplier);
        map.put(SCREEN_THEME, (key, bundle, editor) -> {
            final String val = bundle.getString(key);
            Log.i(TAG, "Loading restriction: " + key + "=" + val);
            editor.putString(KeyboardTheme.KEYBOARD_THEME_KEY, val);
        });

        map.put(PREF_AUTO_CAP, boolApplier);
        map.put(PREF_AUTO_PERIOD, boolApplier);
        map.put(PREF_SHOW_NUMBER_ROW, boolApplier);
        map.put(PREF_SHOW_SPECIAL_CHARS, boolApplier);
        map.put(PREF_SHOW_LANGUAGE_SWITCH_KEY, boolApplier);
        map.put(PREF_SHOW_LANGUAGE_ON_SPACEBAR, boolApplier);
        map.put(PREF_USE_ON_SCREEN, boolApplier);
        map.put(PREF_ENABLE_IME_SWITCH, boolApplier);
        map.put(PREF_DELETE_SWIPE, boolApplier);
        map.put(PREF_SPACE_SWIPE, boolApplier);
        map.put(PREF_DISABLE_LANDSCAPE_FULLSCREEN, boolApplier);
        map.put(PREF_SWIPE_SENSITIVITY, stringApplier);
        map.put(PREF_SHOW_SUGGESTIONS, boolApplier);
        map.put(PREF_SUGGESTIONS_IN_URLS, boolApplier);
        map.put(PREF_VIBRATE_ON, boolApplier);
        map.put(PREF_SOUND_ON, boolApplier);
        map.put(PREF_POPUP_ON, boolApplier);

        map.put(PREF_KEYPRESS_SOUND_VOLUME, percentFloatApplier);
        map.put(PREF_KEYBOARD_HEIGHT, percentFloatApplier);

        map.put(PREF_KEY_LONGPRESS_TIMEOUT, intApplier);
        map.put(PREF_BOTTOM_OFFSET_PORTRAIT, intApplier);
        map.put(PREF_BOTTOM_OFFSET_LANDSCAPE, intApplier);
        map.put(PREF_KEY_REPEAT_START_TIMEOUT, intApplier);
        map.put(PREF_KEY_REPEAT_INTERVAL, intApplier);
        map.put(PREF_VIBRATION_DURATION, intApplier);
        map.put(PREF_KEY_PREVIEW_LINGER_TIMEOUT, intApplier);
        map.put(PREF_CLIPBOARD_MAX_CLIPS, intApplier);

        return Collections.unmodifiableMap(map);
    }

    private static void applySingleRestriction(final String key, final Bundle appRestrictions, final SharedPreferences.Editor prefsEditor) {
        final RestrictionApplier applier = RESTRICTION_APPLIERS.get(key);
        if (applier != null) {
            applier.apply(key, appRestrictions, prefsEditor);
        } else {
            Log.e(TAG, "Unhandled restriction: " + key);
        }
    }

    public void loadSettings(final InputAttributes inputAttributes) {
        mSettingsValues = new SettingsValues(mPrefs, mRes, inputAttributes);
    }

    // TODO: Remove this method and add proxy method to SettingsValues.
    public SettingsValues getCurrent() {
        return mSettingsValues;
    }


    // Accessed from the settings interface, hence public
    public static boolean readVibrationEnabled(final SharedPreferences prefs,
            final Resources res) {
        final boolean hasVibrator = AudioAndHapticFeedbackManager.getInstance().hasVibrator();
        return hasVibrator && prefs.getBoolean(PREF_VIBRATE_ON,
                res.getBoolean(R.bool.config_default_vibration_enabled));
    }

    public static int readClipboardRetentionMinutes(final SharedPreferences prefs) {
        if (prefs == null) {
            return CLIPBOARD_RETENTION_TIME_DEFAULT_MINUTES;
        }
        int minutes = CLIPBOARD_RETENTION_TIME_DEFAULT_MINUTES;
        try {
            minutes = prefs.getInt(PREF_CLIPBOARD_RETENTION_TIME, CLIPBOARD_RETENTION_TIME_DEFAULT_MINUTES);
        } catch (ClassCastException e) {
            String val = prefs.getString(PREF_CLIPBOARD_RETENTION_TIME, String.valueOf(CLIPBOARD_RETENTION_TIME_DEFAULT_MINUTES));
            try {
                minutes = Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
                minutes = CLIPBOARD_RETENTION_TIME_DEFAULT_MINUTES;
            }
        }
        return Math.min(CLIPBOARD_RETENTION_TIME_MAX_MINUTES, Math.max(CLIPBOARD_RETENTION_TIME_MIN_MINUTES, minutes));
    }

    public static String readKeyShape(final SharedPreferences prefs) {
        return prefs.getString(PREF_KEY_SHAPE, KEY_SHAPE_ROUNDED);
    }

    public static float readAutoCorrectionThreshold(final SharedPreferences prefs) {
        final String str = prefs.getString(PREF_AUTO_CORRECTION_THRESHOLD, AUTO_CORRECTION_THRESHOLD_DEFAULT);
        try {
            return Float.parseFloat(str);
        } catch (NumberFormatException | NullPointerException e) {
            return AUTO_CORRECTION_THRESHOLD_MODEST;
        }
    }

    public static float readSwipeSensitivity(final SharedPreferences prefs) {
        if (prefs == null) {
            return 1.0f;
        }
        final String str = prefs.getString(PREF_SWIPE_SENSITIVITY, "1.0");
        try {
            return Float.parseFloat(str);
        } catch (NumberFormatException | NullPointerException e) {
            return 1.0f;
        }
    }

    public static String readPrefSubtypes(final SharedPreferences prefs) {
        return prefs.getString(PREF_ENABLED_SUBTYPES, "");
    }

    public static void writePrefSubtypes(final SharedPreferences prefs, final String prefSubtypes) {
        prefs.edit().putString(PREF_ENABLED_SUBTYPES, prefSubtypes).apply();
    }

    public static float readKeypressSoundVolume(final SharedPreferences prefs) {
        final float volume = prefs.getFloat(
                PREF_KEYPRESS_SOUND_VOLUME, UNDEFINED_PREFERENCE_VALUE_FLOAT);
        return (volume != UNDEFINED_PREFERENCE_VALUE_FLOAT) ? volume
                : readDefaultKeypressSoundVolume();
    }

    private static final float DEFAULT_KEYPRESS_SOUND_VOLUME = 0.5f;

    public static float readDefaultKeypressSoundVolume() {
        return DEFAULT_KEYPRESS_SOUND_VOLUME;
    }

    public static int readKeyLongpressTimeout(final SharedPreferences prefs,
            final Resources res) {
        final int milliseconds = prefs.getInt(
                PREF_KEY_LONGPRESS_TIMEOUT, UNDEFINED_PREFERENCE_VALUE_INT);
        return (milliseconds != UNDEFINED_PREFERENCE_VALUE_INT) ? milliseconds
                : readDefaultKeyLongpressTimeout(res);
    }

    public static int readDefaultKeyLongpressTimeout(final Resources res) {
        return res.getInteger(R.integer.config_default_longpress_key_timeout);
    }

    public static float readKeyboardHeight(final SharedPreferences prefs,
            final float defaultValue) {
        return prefs.getFloat(PREF_KEYBOARD_HEIGHT, defaultValue);
    }

    public static int readBottomOffsetPortrait(final SharedPreferences prefs) {
        return prefs.getInt(PREF_BOTTOM_OFFSET_PORTRAIT, DEFAULT_BOTTOM_OFFSET);
    }

    public static int readBottomOffsetLandscape(final SharedPreferences prefs) {
        return prefs.getInt(PREF_BOTTOM_OFFSET_LANDSCAPE, DEFAULT_BOTTOM_OFFSET);
    }

    public static int readKeyRepeatStartTimeout(final SharedPreferences prefs, final Resources res) {
        final int milliseconds = prefs.getInt(PREF_KEY_REPEAT_START_TIMEOUT, UNDEFINED_PREFERENCE_VALUE_INT);
        return (milliseconds != UNDEFINED_PREFERENCE_VALUE_INT) ? milliseconds
                : readDefaultKeyRepeatStartTimeout(res);
    }

    public static int readDefaultKeyRepeatStartTimeout(final Resources res) {
        return res.getInteger(R.integer.config_key_repeat_start_timeout);
    }

    public static int readKeyRepeatInterval(final SharedPreferences prefs, final Resources res) {
        final int milliseconds = prefs.getInt(PREF_KEY_REPEAT_INTERVAL, UNDEFINED_PREFERENCE_VALUE_INT);
        return (milliseconds != UNDEFINED_PREFERENCE_VALUE_INT) ? milliseconds
                : readDefaultKeyRepeatInterval(res);
    }

    public static int readDefaultKeyRepeatInterval(final Resources res) {
        return res.getInteger(R.integer.config_key_repeat_interval);
    }

    public static int readVibrationDuration(final SharedPreferences prefs) {
        return prefs.getInt(PREF_VIBRATION_DURATION, DEFAULT_VIBRATION_DURATION);
    }

    public static int readDefaultVibrationDuration() {
        return DEFAULT_VIBRATION_DURATION;
    }

    public static int readKeyPreviewLingerTimeout(final SharedPreferences prefs, final Resources res) {
        final int milliseconds = prefs.getInt(PREF_KEY_PREVIEW_LINGER_TIMEOUT, UNDEFINED_PREFERENCE_VALUE_INT);
        return (milliseconds != UNDEFINED_PREFERENCE_VALUE_INT) ? milliseconds
                : readDefaultKeyPreviewLingerTimeout(res);
    }

    public static int readDefaultKeyPreviewLingerTimeout(final Resources res) {
        return res.getInteger(R.integer.config_key_preview_linger_timeout);
    }

    public static int readClipboardMaxClips(final SharedPreferences prefs) {
        if (prefs == null) {
            return CLIPBOARD_MAX_CLIPS_DEFAULT;
        }
        int maxClips = CLIPBOARD_MAX_CLIPS_DEFAULT;
        try {
            maxClips = prefs.getInt(PREF_CLIPBOARD_MAX_CLIPS, CLIPBOARD_MAX_CLIPS_DEFAULT);
        } catch (ClassCastException e) {
            String val = prefs.getString(PREF_CLIPBOARD_MAX_CLIPS, String.valueOf(CLIPBOARD_MAX_CLIPS_DEFAULT));
            try {
                maxClips = Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
                maxClips = CLIPBOARD_MAX_CLIPS_DEFAULT;
            }
        }
        return Math.min(CLIPBOARD_MAX_CLIPS_MAX, Math.max(CLIPBOARD_MAX_CLIPS_MIN, maxClips));
    }

    public static int readDefaultClipboardMaxClips() {
        return CLIPBOARD_MAX_CLIPS_DEFAULT;
    }

    public static final int DEFAULT_BOTTOM_OFFSET = 0;

    public static int readKeyboardDefaultColor(final Context context) {
        final int[] keyboardThemeColors = context.getResources().getIntArray(R.array.keyboard_theme_colors);
        final int[] keyboardThemeIds = context.getResources().getIntArray(R.array.keyboard_theme_ids);
        final int themeId = KeyboardTheme.getKeyboardTheme(context).mThemeId;
        for (int index = 0; index < keyboardThemeIds.length; index++) {
            if (themeId == keyboardThemeIds[index]) {
                return keyboardThemeColors[index];
            }
        }

        return Color.TRANSPARENT;
    }

    public static boolean readUseFullscreenMode(final Resources res) {
        return res.getBoolean(R.bool.config_use_fullscreen_mode);
    }

    public static boolean readHasHardwareKeyboard(final Configuration conf) {
        // The standard way of finding out whether we have a hardware keyboard. This code is taken
        // from InputMethodService#onEvaluateInputShown, which canonically determines this.
        // In a nutshell, we have a keyboard if the configuration says the type of hardware keyboard
        // is NOKEYS and if it's not hidden (e.g. folded inside the device).
        return conf.keyboard != Configuration.KEYBOARD_NOKEYS
                && conf.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
    }
}
