/*
 * Copyright (C) 2026 Simple Keyboard Authors
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

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rkr.simplekeyboard.inputmethod.keyboard.KeyboardTheme;
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.dict.user.UserDictionaryEntry;
import rkr.simplekeyboard.inputmethod.latin.dict.user.UserDictionaryManager;

public final class BackupHelper {
    private static final String TAG = BackupHelper.class.getSimpleName();

    public static final int CURRENT_VERSION = 1;
    public static final String APP_IDENTIFIER = "rkr.simplekeyboard.inputmethod";

    public static final String KEY_VERSION = "version";
    public static final String KEY_APP = "app";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_PREFERENCES = "preferences";
    public static final String KEY_TYPE = "type";
    public static final String KEY_VALUE = "value";

    public static final String KEY_LEARNED_WORDS = "learned_words";
    public static final String KEY_BLOCKED_WORDS = "blocked_words";
    public static final String KEY_WORD = "word";
    public static final String KEY_FREQUENCY = "frequency";
    public static final String KEY_SHORTCUT = "shortcut";

    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_INT = "int";
    public static final String TYPE_FLOAT = "float";
    public static final String TYPE_STRING = "string";

    public static final int MAX_BACKUP_SIZE_BYTES = 2 * 1024 * 1024; // 2 MB
    public static final int MAX_STRING_LENGTH = 4096;
    public static final int MAX_USER_WORDS_LIMIT = 20000;

    public static final String PREF_RECENT_EMOJIS = "pref_recent_emojis";
    public static final String PREF_BACKUP_INCLUDE_USER_DICTIONARY = "pref_backup_include_user_dictionary";

    public enum PrefType {
        BOOLEAN(TYPE_BOOLEAN),
        INT(TYPE_INT),
        FLOAT(TYPE_FLOAT),
        STRING(TYPE_STRING);

        private final String mName;

        PrefType(final String name) {
            mName = name;
        }

        public String getTypeName() {
            return mName;
        }

        @Nullable
        public static PrefType fromString(@Nullable final String name) {
            if (name == null) {
                return null;
            }
            for (PrefType type : values()) {
                if (type.mName.equalsIgnoreCase(name)) {
                    return type;
                }
            }
            return null;
        }
    }

    private static final Map<String, PrefType> WHITELIST = createWhitelist();

    private static Map<String, PrefType> createWhitelist() {
        final Map<String, PrefType> map = new HashMap<>();

        // Booleans
        map.put(Settings.PREF_AUTO_CAP, PrefType.BOOLEAN);
        map.put(Settings.PREF_AUTO_PERIOD, PrefType.BOOLEAN);
        map.put(Settings.PREF_AUTO_STRIP_PUNCTUATION_SPACE, PrefType.BOOLEAN);
        map.put(Settings.PREF_VIBRATE_ON, PrefType.BOOLEAN);
        map.put(Settings.PREF_SOUND_ON, PrefType.BOOLEAN);
        map.put(Settings.PREF_POPUP_ON, PrefType.BOOLEAN);
        map.put(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, PrefType.BOOLEAN);
        map.put(Settings.PREF_SHOW_LANGUAGE_ON_SPACEBAR, PrefType.BOOLEAN);
        map.put(Settings.PREF_USE_ON_SCREEN, PrefType.BOOLEAN);
        map.put(Settings.PREF_ENABLE_IME_SWITCH, PrefType.BOOLEAN);
        map.put(Settings.PREF_SHOW_SPECIAL_CHARS, PrefType.BOOLEAN);
        map.put(Settings.PREF_SHOW_NUMBER_ROW, PrefType.BOOLEAN);
        map.put(Settings.PREF_SPACE_SWIPE, PrefType.BOOLEAN);
        map.put(Settings.PREF_DELETE_SWIPE, PrefType.BOOLEAN);
        map.put(Settings.PREF_DISABLE_LANDSCAPE_FULLSCREEN, PrefType.BOOLEAN);
        map.put(Settings.PREF_CLIPBOARD_ENABLED, PrefType.BOOLEAN);
        map.put(Settings.PREF_CLIPBOARD_SUGGESTIONS, PrefType.BOOLEAN);
        map.put(Settings.PREF_SUGGEST_SCREENSHOTS, PrefType.BOOLEAN);
        map.put(Settings.PREF_SHOW_SUGGESTIONS, PrefType.BOOLEAN);
        map.put(Settings.PREF_SUGGESTIONS_IN_URLS, PrefType.BOOLEAN);
        map.put(Settings.PREF_AUTO_LEARN, PrefType.BOOLEAN);
        map.put(PREF_BACKUP_INCLUDE_USER_DICTIONARY, PrefType.BOOLEAN);

        // Integers
        map.put(Settings.PREF_KEY_LONGPRESS_TIMEOUT, PrefType.INT);
        map.put(Settings.PREF_BOTTOM_OFFSET_PORTRAIT, PrefType.INT);
        map.put(Settings.PREF_BOTTOM_OFFSET_LANDSCAPE, PrefType.INT);
        map.put(Settings.PREF_KEY_REPEAT_START_TIMEOUT, PrefType.INT);
        map.put(Settings.PREF_KEY_REPEAT_INTERVAL, PrefType.INT);
        map.put(Settings.PREF_VIBRATION_DURATION, PrefType.INT);
        map.put(Settings.PREF_KEY_PREVIEW_LINGER_TIMEOUT, PrefType.INT);
        map.put(Settings.PREF_CLIPBOARD_MAX_CLIPS, PrefType.INT);
        map.put(Settings.PREF_CLIPBOARD_RETENTION_TIME, PrefType.INT);

        // Floats
        map.put(Settings.PREF_KEYPRESS_SOUND_VOLUME, PrefType.FLOAT);
        map.put(Settings.PREF_KEYBOARD_HEIGHT, PrefType.FLOAT);

        // Strings
        map.put(Settings.PREF_ENABLED_SUBTYPES, PrefType.STRING);
        map.put(Settings.PREF_SWIPE_SENSITIVITY, PrefType.STRING);
        map.put(Settings.PREF_KEY_SHAPE, PrefType.STRING);
        map.put(Settings.PREF_AUTO_CORRECTION_THRESHOLD, PrefType.STRING);
        map.put(KeyboardTheme.KEYBOARD_THEME_KEY, PrefType.STRING);
        map.put(PREF_RECENT_EMOJIS, PrefType.STRING);

        return Collections.unmodifiableMap(map);
    }

    public static Map<String, PrefType> getWhitelist() {
        return WHITELIST;
    }

    private BackupHelper() {
        // Utility class
    }

    @NonNull
    public static String exportToJson(@NonNull final SharedPreferences prefs) throws JSONException {
        return exportToJson(prefs, null, false);
    }

    @NonNull
    public static String exportToJson(@NonNull final SharedPreferences prefs,
                                      @Nullable final UserDictionaryManager userDictManager,
                                      final boolean includeUserWords) throws JSONException {
        final JSONObject root = new JSONObject();
        root.put(KEY_VERSION, CURRENT_VERSION);
        root.put(KEY_APP, APP_IDENTIFIER);
        root.put(KEY_TIMESTAMP, System.currentTimeMillis());

        final JSONObject prefsJson = new JSONObject();
        final Map<String, ?> allEntries = prefs.getAll();

        for (Map.Entry<String, PrefType> entry : WHITELIST.entrySet()) {
            final String key = entry.getKey();
            final PrefType expectedType = entry.getValue();

            if (!allEntries.containsKey(key)) {
                continue;
            }

            final Object rawValue = allEntries.get(key);
            if (rawValue == null) {
                continue;
            }

            final JSONObject itemObj = new JSONObject();
            itemObj.put(KEY_TYPE, expectedType.getTypeName());

            switch (expectedType) {
                case BOOLEAN:
                    if (rawValue instanceof Boolean) {
                        itemObj.put(KEY_VALUE, rawValue);
                        prefsJson.put(key, itemObj);
                    }
                    break;
                case INT:
                    if (rawValue instanceof Number) {
                        itemObj.put(KEY_VALUE, ((Number) rawValue).intValue());
                        prefsJson.put(key, itemObj);
                    }
                    break;
                case FLOAT:
                    if (rawValue instanceof Number) {
                        float floatVal = ((Number) rawValue).floatValue();
                        if (Float.isFinite(floatVal)) {
                            itemObj.put(KEY_VALUE, (double) floatVal);
                            prefsJson.put(key, itemObj);
                        }
                    }
                    break;
                case STRING:
                    if (rawValue instanceof String) {
                        itemObj.put(KEY_VALUE, rawValue);
                        prefsJson.put(key, itemObj);
                    }
                    break;
            }
        }

        root.put(KEY_PREFERENCES, prefsJson);

        if (includeUserWords && userDictManager != null) {
            final List<UserDictionaryEntry> learned = userDictManager.getLearnedWords();
            if (learned != null && !learned.isEmpty()) {
                final org.json.JSONArray learnedArray = new org.json.JSONArray();
                for (final UserDictionaryEntry entry : learned) {
                    if (entry == null || entry.word == null || entry.word.isEmpty()) {
                        continue;
                    }
                    final JSONObject wordObj = new JSONObject();
                    wordObj.put(KEY_WORD, entry.word);
                    wordObj.put(KEY_FREQUENCY, entry.frequency);
                    if (entry.shortcut != null && !entry.shortcut.isEmpty()) {
                        wordObj.put(KEY_SHORTCUT, entry.shortcut);
                    }
                    learnedArray.put(wordObj);
                }
                root.put(KEY_LEARNED_WORDS, learnedArray);
            }

            final List<UserDictionaryEntry> blocked = userDictManager.getBlockedWords();
            if (blocked != null && !blocked.isEmpty()) {
                final org.json.JSONArray blockedArray = new org.json.JSONArray();
                for (final UserDictionaryEntry entry : blocked) {
                    if (entry != null && entry.word != null && !entry.word.isEmpty()) {
                        blockedArray.put(entry.word);
                    }
                }
                root.put(KEY_BLOCKED_WORDS, blockedArray);
            }
        }

        return root.toString(2);
    }

    public static void exportToStream(@NonNull final SharedPreferences prefs, @NonNull final OutputStream os) throws IOException, JSONException {
        exportToStream(prefs, os, null, false);
    }

    public static void exportToStream(@NonNull final SharedPreferences prefs,
                                      @NonNull final OutputStream os,
                                      @Nullable final UserDictionaryManager userDictManager,
                                      final boolean includeUserWords) throws IOException, JSONException {
        final String json = exportToJson(prefs, userDictManager, includeUserWords);
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    public static final class ValidationResult {
        public final boolean success;
        public final int version;
        public final int validEntriesCount;
        public final Map<String, Object> validatedEntries;
        public final List<UserDictionaryEntry> learnedWords;
        public final List<String> blockedWords;
        public final Set<String> ignoredKeys;
        @Nullable public final String errorMessage;

        private ValidationResult(final boolean success,
                                 final int version,
                                 final int validEntriesCount,
                                 @NonNull final Map<String, Object> validatedEntries,
                                 @NonNull final List<UserDictionaryEntry> learnedWords,
                                 @NonNull final List<String> blockedWords,
                                 @NonNull final Set<String> ignoredKeys,
                                 @Nullable final String errorMessage) {
            this.success = success;
            this.version = version;
            this.validEntriesCount = validEntriesCount;
            this.validatedEntries = Collections.unmodifiableMap(validatedEntries);
            this.learnedWords = Collections.unmodifiableList(learnedWords);
            this.blockedWords = Collections.unmodifiableList(blockedWords);
            this.ignoredKeys = Collections.unmodifiableSet(ignoredKeys);
            this.errorMessage = errorMessage;
        }

        public boolean hasUserWords() {
            return !learnedWords.isEmpty() || !blockedWords.isEmpty();
        }

        public static ValidationResult ok(final int version,
                                          @NonNull final Map<String, Object> validatedEntries,
                                          @NonNull final List<UserDictionaryEntry> learnedWords,
                                          @NonNull final List<String> blockedWords,
                                          @NonNull final Set<String> ignoredKeys) {
            return new ValidationResult(true, version, validatedEntries.size(), validatedEntries, learnedWords, blockedWords, ignoredKeys, null);
        }

        public static ValidationResult ok(final int version,
                                          @NonNull final Map<String, Object> validatedEntries,
                                          @NonNull final Set<String> ignoredKeys) {
            return ok(version, validatedEntries, Collections.emptyList(), Collections.emptyList(), ignoredKeys);
        }

        public static ValidationResult error(@NonNull final String errorMessage) {
            return new ValidationResult(false, 0, 0, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptySet(), errorMessage);
        }

        public static ValidationResult error(@NonNull final String errorMessage, @NonNull final Set<String> ignoredKeys) {
            return new ValidationResult(false, 0, 0, Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), ignoredKeys, errorMessage);
        }
    }

    @NonNull
    public static ValidationResult validateAndParseStream(@NonNull final InputStream is) {
        try {
            final byte[] buffer = new byte[8192];
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int totalBytesRead = 0;
            int read;
            while ((read = is.read(buffer)) != -1) {
                totalBytesRead += read;
                if (totalBytesRead > MAX_BACKUP_SIZE_BYTES) {
                    return ValidationResult.error("File exceeds maximum allowed size of 2 MB.");
                }
                baos.write(buffer, 0, read);
            }
            if (totalBytesRead == 0) {
                return ValidationResult.error("Backup file is empty.");
            }
            final String jsonStr = baos.toString(StandardCharsets.UTF_8.name());
            return validateAndParseJson(jsonStr);
        } catch (IOException e) {
            Log.e(TAG, "Error reading backup stream", e);
            return ValidationResult.error("Failed to read backup file: " + e.getMessage());
        }
    }

    @NonNull
    public static ValidationResult validateAndParseJson(@NonNull final String jsonStr) {
        if (jsonStr.trim().isEmpty()) {
            return ValidationResult.error("Backup JSON content is empty.");
        }
        if (jsonStr.getBytes(StandardCharsets.UTF_8).length > MAX_BACKUP_SIZE_BYTES) {
            return ValidationResult.error("Backup content exceeds 2 MB limit.");
        }

        final JSONObject root;
        try {
            root = new JSONObject(jsonStr);
        } catch (JSONException e) {
            Log.w(TAG, "Malformed JSON in backup file", e);
            return ValidationResult.error("Malformed JSON structure.");
        }

        if (!root.has(KEY_VERSION)) {
            return ValidationResult.error("Missing backup version identifier.");
        }

        final int version = root.optInt(KEY_VERSION, -1);
        if (version <= 0) {
            return ValidationResult.error("Invalid backup version format.");
        }
        if (version > CURRENT_VERSION) {
            return ValidationResult.error("Unsupported backup version " + version + " (current supported version: " + CURRENT_VERSION + ").");
        }

        if (!root.has(KEY_PREFERENCES)) {
            return ValidationResult.error("Missing preferences object in backup.");
        }

        final JSONObject prefsObj = root.optJSONObject(KEY_PREFERENCES);
        if (prefsObj == null) {
            return ValidationResult.error("Preferences node is not a valid JSON object.");
        }

        final Map<String, Object> validatedEntries = new HashMap<>();
        final Set<String> ignoredKeys = new HashSet<>();

        final Iterator<String> keys = prefsObj.keys();
        while (keys.hasNext()) {
            final String key = keys.next();

            // Ignore blacklisted or unknown keys
            if (Settings.ACTIVE_RESTRICTIONS.equals(key)) {
                ignoredKeys.add(key);
                continue;
            }

            final PrefType expectedType = WHITELIST.get(key);
            if (expectedType == null) {
                ignoredKeys.add(key);
                continue;
            }

            final Object entryRaw = prefsObj.opt(key);
            if (!(entryRaw instanceof JSONObject)) {
                return ValidationResult.error("Malformed entry for key: " + key);
            }

            final JSONObject itemObj = (JSONObject) entryRaw;
            final String typeStr = itemObj.optString(KEY_TYPE, null);
            final PrefType declaredType = PrefType.fromString(typeStr);

            if (declaredType == null || declaredType != expectedType) {
                return ValidationResult.error("Type mismatch for key " + key + ": expected " + expectedType.getTypeName() + ", got " + typeStr);
            }

            if (itemObj.isNull(KEY_VALUE)) {
                return ValidationResult.error("Null value for key: " + key);
            }

            switch (expectedType) {
                case BOOLEAN: {
                    final Object val = itemObj.opt(KEY_VALUE);
                    if (val instanceof Boolean) {
                        validatedEntries.put(key, val);
                    } else {
                        return ValidationResult.error("Invalid boolean value for key: " + key);
                    }
                    break;
                }
                case INT: {
                    final Object val = itemObj.opt(KEY_VALUE);
                    if (val instanceof Number) {
                        final double num = ((Number) val).doubleValue();
                        if (Double.isFinite(num) && Math.floor(num) == num && num >= Integer.MIN_VALUE && num <= Integer.MAX_VALUE) {
                            validatedEntries.put(key, ((Number) val).intValue());
                        } else {
                            return ValidationResult.error("Integer value out of bounds or not an integer for key: " + key);
                        }
                    } else {
                        return ValidationResult.error("Invalid integer value for key: " + key);
                    }
                    break;
                }
                case FLOAT: {
                    final Object val = itemObj.opt(KEY_VALUE);
                    if (val instanceof Number) {
                        final float floatVal = ((Number) val).floatValue();
                        if (Float.isFinite(floatVal)) {
                            validatedEntries.put(key, floatVal);
                        } else {
                            return ValidationResult.error("Non-finite float value (NaN/Infinity) for key: " + key);
                        }
                    } else {
                        return ValidationResult.error("Invalid float value for key: " + key);
                    }
                    break;
                }
                case STRING: {
                    final Object val = itemObj.opt(KEY_VALUE);
                    if (val instanceof String) {
                        final String strVal = (String) val;
                        if (strVal.length() <= MAX_STRING_LENGTH) {
                            validatedEntries.put(key, strVal);
                        } else {
                            return ValidationResult.error("String value exceeds maximum allowed length of " + MAX_STRING_LENGTH + " characters for key: " + key);
                        }
                    } else {
                        return ValidationResult.error("Invalid string value for key: " + key);
                    }
                    break;
                }
            }
        }

        final List<UserDictionaryEntry> learnedWords = new ArrayList<>();
        if (root.has(KEY_LEARNED_WORDS)) {
            final Object rawLearned = root.opt(KEY_LEARNED_WORDS);
            if (rawLearned instanceof org.json.JSONArray) {
                final org.json.JSONArray arr = (org.json.JSONArray) rawLearned;
                final int len = Math.min(arr.length(), MAX_USER_WORDS_LIMIT);
                for (int i = 0; i < len; i++) {
                    final Object item = arr.opt(i);
                    if (item instanceof JSONObject) {
                        final JSONObject obj = (JSONObject) item;
                        final String word = obj.optString(KEY_WORD, null);
                        if (word != null && !word.trim().isEmpty() && word.length() <= 64) {
                            final int freq = obj.optInt(KEY_FREQUENCY, 250);
                            final String shortcut = obj.optString(KEY_SHORTCUT, null);
                            learnedWords.add(new UserDictionaryEntry(-1, word.trim(), StringUtils.toNormalizedLower(word.trim()),
                                    Math.min(Math.max(freq, 1), 255), shortcut, System.currentTimeMillis()));
                        }
                    } else if (item instanceof String) {
                        final String word = (String) item;
                        if (!word.trim().isEmpty() && word.length() <= 64) {
                            learnedWords.add(new UserDictionaryEntry(word.trim(), 250));
                        }
                    }
                }
            }
        }

        final List<String> blockedWords = new ArrayList<>();
        if (root.has(KEY_BLOCKED_WORDS)) {
            final Object rawBlocked = root.opt(KEY_BLOCKED_WORDS);
            if (rawBlocked instanceof org.json.JSONArray) {
                final org.json.JSONArray arr = (org.json.JSONArray) rawBlocked;
                final int len = Math.min(arr.length(), MAX_USER_WORDS_LIMIT);
                for (int i = 0; i < len; i++) {
                    final Object item = arr.opt(i);
                    if (item instanceof String) {
                        final String word = (String) item;
                        if (!word.trim().isEmpty() && word.length() <= 64) {
                            blockedWords.add(word.trim());
                        }
                    } else if (item instanceof JSONObject) {
                        final JSONObject obj = (JSONObject) item;
                        final String word = obj.optString(KEY_WORD, null);
                        if (word != null && !word.trim().isEmpty() && word.length() <= 64) {
                            blockedWords.add(word.trim());
                        }
                    }
                }
            }
        }

        if (validatedEntries.isEmpty() && learnedWords.isEmpty() && blockedWords.isEmpty() && prefsObj.length() > 0 && ignoredKeys.size() == prefsObj.length()) {
            return ValidationResult.error("No valid or compatible preferences found in backup.", ignoredKeys);
        }

        return ValidationResult.ok(version, validatedEntries, learnedWords, blockedWords, ignoredKeys);
    }

    public static boolean applyValidatedBackup(@NonNull final SharedPreferences prefs, @NonNull final ValidationResult result) {
        if (!result.success || result.validatedEntries.isEmpty()) {
            Log.w(TAG, "Cannot apply failed or empty validation result");
            return false;
        }

        final Map<String, ?> existing = prefs.getAll();
        final SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;

        for (Map.Entry<String, Object> entry : result.validatedEntries.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            final Object oldVal = existing.get(key);

            if (value != null && value.equals(oldVal)) {
                continue;
            }

            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
                changed = true;
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
                changed = true;
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
                changed = true;
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
                changed = true;
            }
        }

        if (changed) {
            editor.apply();
        }
        return true;
    }

    public static int applyValidatedUserWords(@NonNull final UserDictionaryManager userDictManager, @NonNull final ValidationResult result) {
        if (!result.success) {
            return 0;
        }
        int count = 0;
        for (final UserDictionaryEntry entry : result.learnedWords) {
            if (userDictManager.addWord(entry.word, entry.frequency, entry.shortcut)) {
                count++;
            }
        }
        for (final String blocked : result.blockedWords) {
            if (userDictManager.blockWord(blocked)) {
                count++;
            }
        }
        return count;
    }
}
