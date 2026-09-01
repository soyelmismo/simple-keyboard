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

package rkr.simplekeyboard.inputmethod.latin.common;

import java.util.Arrays;
import java.util.Locale;

public final class StringUtils {
    private StringUtils() {
        // This utility class is not publicly instantiable.
    }

    public static boolean isBlank(final CharSequence cs) {
        if (cs == null) {
            return true;
        }
        final int strLen = cs.length();
        if (strLen == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }

    public static int codePointCount(final CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        return Character.codePointCount(text, 0, text.length());
    }

    public static int getSingleCodePoint(final CharSequence text, final int defaultCode) {
        return codePointCount(text) == 1 ? Character.codePointAt(text, 0) : defaultCode;
    }

    public static String newSingleCodePointString(final int codePoint) {
        if (Character.charCount(codePoint) == 1) {
            // Optimization: avoid creating a temporary array for characters that are
            // represented by a single char value
            return String.valueOf((char) codePoint);
        }
        // For surrogate pair
        return new String(Character.toChars(codePoint));
    }

    public static boolean containsIgnoreCase(final Iterable<? extends CharSequence> collection, final CharSequence target) {
        if (collection == null || target == null) {
            return false;
        }
        final String targetStr = target.toString();
        for (final CharSequence item : collection) {
            if (item != null && targetStr.equalsIgnoreCase(item.toString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsInArray(final String text,
            final String[] array) {
        for (final String element : array) {
            if (text.equals(element)) {
                return true;
            }
        }
        return false;
    }

    public static int[] toCodePointArray(final CharSequence charSequence) {
        return toCodePointArray(charSequence, 0, charSequence.length());
    }

    private static final int[] EMPTY_CODEPOINTS = {};

    /**
     * Converts a range of a string to an array of code points.
     * @param charSequence the source string.
     * @param startIndex the start index inside the string in java chars, inclusive.
     * @param endIndex the end index inside the string in java chars, exclusive.
     * @return a new array of code points. At most endIndex - startIndex, but possibly less.
     */
    public static int[] toCodePointArray(final CharSequence charSequence,
            final int startIndex, final int endIndex) {
        final int length = charSequence.length();
        if (length <= 0) {
            return EMPTY_CODEPOINTS;
        }
        final int[] codePoints =
                new int[Character.codePointCount(charSequence, startIndex, endIndex)];
        copyCodePointsAndReturnCodePointCount(codePoints, charSequence, startIndex, endIndex,
                false /* downCase */);
        return codePoints;
    }

    /**
     * Copies the codepoints in a CharSequence to an int array.
     *
     * This method assumes there is enough space in the array to store the code points. The size
     * can be measured with Character#codePointCount(CharSequence, int, int) before passing to this
     * method. If the int array is too small, an ArrayIndexOutOfBoundsException will be thrown.
     * Also, this method makes no effort to be thread-safe. Do not modify the CharSequence while
     * this method is running, or the behavior is undefined.
     * This method can optionally downcase code points before copying them, but it pays no attention
     * to locale while doing so.
     *
     * @param destination the int array.
     * @param charSequence the CharSequence.
     * @param startIndex the start index inside the string in java chars, inclusive.
     * @param endIndex the end index inside the string in java chars, exclusive.
     * @param downCase if this is true, code points will be downcased before being copied.
     * @return the number of copied code points.
     */
    public static int copyCodePointsAndReturnCodePointCount(final int[] destination,
            final CharSequence charSequence, final int startIndex, final int endIndex,
            final boolean downCase) {
        int destIndex = 0;
        for (int index = startIndex; index < endIndex;
                index = Character.offsetByCodePoints(charSequence, index, 1)) {
            final int codePoint = Character.codePointAt(charSequence, index);
            // TODO: stop using this, as it's not aware of the locale and does not always do
            // the right thing.
            destination[destIndex] = downCase ? Character.toLowerCase(codePoint) : codePoint;
            destIndex++;
        }
        return destIndex;
    }

    public static int[] toSortedCodePointArray(final String string) {
        final int[] codePoints = toCodePointArray(string);
        Arrays.sort(codePoints);
        return codePoints;
    }

    public static boolean isIdenticalAfterUpcase(final String text) {
        return checkUpperCase(text, false /* requireLetter */);
    }

    private static boolean checkUpperCase(final String text, final boolean requireLetter) {
        if (text == null) {
            return false;
        }
        boolean hasLetter = false;
        final int length = text.length();
        int i = 0;
        while (i < length) {
            final int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                if (!Character.isUpperCase(codePoint)) {
                    return false;
                }
                hasLetter = true;
            }
            i += Character.charCount(codePoint);
        }
        return !requireLetter || hasLetter;
    }

    public static boolean isIdenticalAfterDowncase(final String text) {
        final int length = text.length();
        int i = 0;
        while (i < length) {
            final int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint) && !Character.isLowerCase(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    public static boolean isIdenticalAfterCapitalizeEachWord(final String text) {
        boolean needsCapsNext = true;
        final int len = text.length();
        for (int i = 0; i < len; i = text.offsetByCodePoints(i, 1)) {
            final int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                if ((needsCapsNext && !Character.isUpperCase(codePoint))
                        || (!needsCapsNext && !Character.isLowerCase(codePoint))) {
                    return false;
                }
            }
            // We need a capital letter next if this is a whitespace.
            needsCapsNext = Character.isWhitespace(codePoint);
        }
        return true;
    }
    private static final ThreadLocal<StringBuilder> sScratchStringBuilder = new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
            return new StringBuilder(64);
        }
    };

    // TODO: like capitalizeFirst*, this does not work perfectly for Dutch because of the IJ digraph
    // which should be capitalized together in *some* cases.
    public static String capitalizeEachWord(final String text, final Locale locale) {
        final StringBuilder builder = sScratchStringBuilder.get();
        builder.setLength(0);
        boolean needsCapsNext = true;
        final int len = text.length();
        for (int i = 0; i < len; i = text.offsetByCodePoints(i, 1)) {
            final String nextChar = text.substring(i, text.offsetByCodePoints(i, 1));
            if (needsCapsNext) {
                builder.append(toTitleCaseOfKeyLabel(nextChar, locale));
            } else {
                builder.append(toLowerCaseOfKeyLabel(nextChar, locale));
            }
            // We need a capital letter next if this is a whitespace.
            needsCapsNext = Character.isWhitespace(nextChar.codePointAt(0));
        }
        return builder.toString();
    }

    private static final String LANGUAGE_GREEK = "el";

    private static Locale getLocaleUsedForToTitleCase(final Locale locale) {
        // In Greek locale {@link String#toUpperCase(Locale)} eliminates accents from its result.
        // In order to get accented upper case letter, {@link Locale#ROOT} should be used.
        if (LANGUAGE_GREEK.equals(locale.getLanguage())) {
            return Locale.ROOT;
        }
        return locale;
    }

    public static String toLowerCase(final String text, final Locale locale) {
        final StringBuilder builder = sScratchStringBuilder.get();
        builder.setLength(0);
        final int len = text.length();
        for (int i = 0; i < len; i = text.offsetByCodePoints(i, 1)) {
            final String nextChar = text.substring(i, text.offsetByCodePoints(i, 1));
            builder.append(toLowerCaseOfKeyLabel(nextChar, locale));
        }
        return builder.toString();
    }

    public static String toUpperCase(final String text, final Locale locale) {
        final StringBuilder builder = sScratchStringBuilder.get();
        builder.setLength(0);
        final int len = text.length();
        for (int i = 0; i < len; i = text.offsetByCodePoints(i, 1)) {
            final String nextChar = text.substring(i, text.offsetByCodePoints(i, 1));
            builder.append(toTitleCaseOfKeyLabel(nextChar, locale));
        }
        return builder.toString();
    }

    public static String toLowerCaseOfKeyLabel(final String label, final Locale locale) {
        if (label == null) {
            return null;
        }
        switch (label) {
            case "\u1E9E":
                // sharp S (ß, U+00DF) => ẞ (U+1E9E), not 'SS'.
                return "\u00DF";
            default:
                return label.toLowerCase(getLocaleUsedForToTitleCase(locale));
        }
    }

    public static String toTitleCaseOfKeyLabel(final String label, final Locale locale) {
        if (label == null) {
            return null;
        }
        switch (label) {
            case "\u00DF":
                // sharp S (ß, U+00DF) => ẞ (U+1E9E), not 'SS'.
                return "\u1E9E";
            default:
                return label.toUpperCase(getLocaleUsedForToTitleCase(locale));
        }
    }

    public static int toTitleCaseOfKeyCode(final int code, final Locale locale) {
        if (!Constants.isLetterCode(code)) {
            return code;
        }
        final String label = newSingleCodePointString(code);
        final String titleCaseLabel = toTitleCaseOfKeyLabel(label, locale);
        return getSingleCodePoint(titleCaseLabel, Constants.CODE_UNSPECIFIED);
    }

    private static final char[] ACCENT_MAP = new char[512];

    static {
        for (int i = 0; i < ACCENT_MAP.length; i++) {
            ACCENT_MAP[i] = (char) i;
        }
        // Lowercase vowels and accented characters
        ACCENT_MAP['á'] = 'a';
        ACCENT_MAP['à'] = 'a';
        ACCENT_MAP['ä'] = 'a';
        ACCENT_MAP['â'] = 'a';
        ACCENT_MAP['ã'] = 'a';
        ACCENT_MAP['é'] = 'e';
        ACCENT_MAP['è'] = 'e';
        ACCENT_MAP['ë'] = 'e';
        ACCENT_MAP['ê'] = 'e';
        ACCENT_MAP['í'] = 'i';
        ACCENT_MAP['ì'] = 'i';
        ACCENT_MAP['ï'] = 'i';
        ACCENT_MAP['î'] = 'i';
        ACCENT_MAP['ó'] = 'o';
        ACCENT_MAP['ò'] = 'o';
        ACCENT_MAP['ö'] = 'o';
        ACCENT_MAP['ô'] = 'o';
        ACCENT_MAP['õ'] = 'o';
        ACCENT_MAP['ú'] = 'u';
        ACCENT_MAP['ù'] = 'u';
        ACCENT_MAP['ü'] = 'u';
        ACCENT_MAP['û'] = 'u';
        ACCENT_MAP['ñ'] = 'n';
        ACCENT_MAP['ç'] = 'c';
        // Uppercase vowels and accented characters
        ACCENT_MAP['Á'] = 'A';
        ACCENT_MAP['À'] = 'A';
        ACCENT_MAP['Ä'] = 'A';
        ACCENT_MAP['Â'] = 'A';
        ACCENT_MAP['Ã'] = 'A';
        ACCENT_MAP['É'] = 'E';
        ACCENT_MAP['È'] = 'E';
        ACCENT_MAP['Ë'] = 'E';
        ACCENT_MAP['Ê'] = 'E';
        ACCENT_MAP['Í'] = 'I';
        ACCENT_MAP['Ì'] = 'I';
        ACCENT_MAP['Ï'] = 'I';
        ACCENT_MAP['Î'] = 'I';
        ACCENT_MAP['Ó'] = 'O';
        ACCENT_MAP['Ò'] = 'O';
        ACCENT_MAP['Ö'] = 'O';
        ACCENT_MAP['Ô'] = 'O';
        ACCENT_MAP['Õ'] = 'O';
        ACCENT_MAP['Ú'] = 'U';
        ACCENT_MAP['Ù'] = 'U';
        ACCENT_MAP['Ü'] = 'U';
        ACCENT_MAP['Û'] = 'U';
        ACCENT_MAP['Ñ'] = 'N';
        ACCENT_MAP['Ç'] = 'C';
    }

    public static char foldChar(final char c) {
        final char lower = Character.toLowerCase(c);
        return (lower < ACCENT_MAP.length) ? ACCENT_MAP[lower] : lower;
    }

    public static char removeAccents(final char c) {
        return (c < ACCENT_MAP.length) ? ACCENT_MAP[c] : c;
    }

    public static String stripAccents(final String s) {
        if (s == null) {
            return "";
        }
        final StringBuilder sb = sScratchStringBuilder.get();
        sb.setLength(0);
        sb.ensureCapacity(s.length());
        for (int i = 0; i < s.length(); i++) {
            sb.append(removeAccents(s.charAt(i)));
        }
        return sb.toString();
    }

    public static String stripEnclosingQuotes(final CharSequence text) {
        if (text == null) {
            return "";
        }
        final String clean = text.toString().trim();
        final int len = clean.length();
        if (len >= 2 && clean.charAt(0) == '"' && clean.charAt(len - 1) == '"') {
            return clean.substring(1, len - 1).trim();
        }
        return clean;
    }

    public static String toNormalizedLower(final String s) {
        if (s == null) {
            return "";
        }
        final int len = s.length();
        if (len == 0) {
            return "";
        }
        int i = 0;
        while (i < len && foldChar(s.charAt(i)) == s.charAt(i)) {
            i++;
        }
        if (i == len) {
            return s;
        }
        final StringBuilder sb = sScratchStringBuilder.get();
        sb.setLength(0);
        sb.ensureCapacity(len);
        if (i > 0) {
            sb.append(s, 0, i);
        }
        for (; i < len; i++) {
            sb.append(foldChar(s.charAt(i)));
        }
        return sb.toString();
    }

    public static boolean hasAccents(final CharSequence s) {
        if (s == null) {
            return false;
        }
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            final char c = s.charAt(i);
            if (c != 'ñ' && c != 'Ñ' && foldChar(c) != Character.toLowerCase(c)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllUpperCase(final String s) {
        if (s == null || s.length() <= 1) {
            return false;
        }
        return checkUpperCase(s, true /* requireLetter */);
    }

    public static boolean isPunctuationOrSymbol(final int codePoint) {
        return !Character.isLetterOrDigit(codePoint) && codePoint > 32;
    }

    public static boolean shouldStripPrecedingSpace(final int codePoint) {
        if (codePoint == '.' || codePoint == ',' || codePoint == '?' || codePoint == '!'
                || codePoint == ':' || codePoint == ';' || codePoint == ')' || codePoint == ']'
                || codePoint == '}' || codePoint == '>' || codePoint == '%' || codePoint == '\u2026' /* … */
                || codePoint == '\u201D' /* ” */ || codePoint == '\u2019' /* ’ */ || codePoint == '\u00BB' /* » */) {
            return true;
        }
        return Character.getType(codePoint) == Character.END_PUNCTUATION;
    }

    public static boolean isWordCharacter(final int codePoint) {
        return Character.isLetter(codePoint) || codePoint == '\'' || codePoint == '-';
    }

    public static boolean hasInternalUpperCase(final String s) {
        if (s == null || s.length() <= 1) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static String applyCasing(final String typed, final String suggestion) {
        if (typed == null || typed.isEmpty() || suggestion == null || suggestion.isEmpty()) {
            return suggestion;
        }
        if (isAllUpperCase(typed)) {
            return suggestion.toUpperCase(java.util.Locale.ROOT);
        }
        if (Character.isUpperCase(typed.charAt(0))) {
            return Character.toUpperCase(suggestion.charAt(0)) + suggestion.substring(1);
        }
        if (Character.isUpperCase(suggestion.charAt(0)) && !hasInternalUpperCase(suggestion)) {
            return Character.toLowerCase(suggestion.charAt(0)) + suggestion.substring(1);
        }
        return suggestion;
    }
}
