/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2020 wittmane
 * Copyright (C) 2017 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.latin.utils;

import android.text.InputType;
import android.text.TextUtils;

import java.util.ArrayList;

import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.settings.SpacingAndPunctuations;

public final class CapsModeUtils {
    private static final int STATE_CAPS = -1;
    private static final int STATE_NO_CAPS = -2;
    private static final int STATE_START = 0;
    private static final int STATE_WORD = 1;
    private static final int STATE_PERIOD = 2;
    private static final int STATE_LETTER = 3;
    private static final int STATE_NUMBER = 4;

    private CapsModeUtils() {
        // This utility class is not publicly instantiable.
    }

    /**
     * Helper method to find out if a code point is starting punctuation.
     *
     * This include the Unicode START_PUNCTUATION category, but also some other symbols that are
     * starting, like the inverted question mark or the double quote.
     *
     * @param codePoint the code point
     * @return true if it's starting punctuation, false otherwise.
     */
    private static boolean isExplicitStartPunctuation(final int codePoint) {
        return codePoint == Constants.CODE_DOUBLE_QUOTE
                || codePoint == Constants.CODE_SINGLE_QUOTE
                || codePoint == Constants.CODE_INVERTED_QUESTION_MARK
                || codePoint == Constants.CODE_INVERTED_EXCLAMATION_MARK;
    }

    private static boolean isStartPunctuation(final int codePoint) {
        return isExplicitStartPunctuation(codePoint)
                || Character.getType(codePoint) == Character.START_PUNCTUATION;
    }

    private static int getCharCaps(final int reqModes) {
        return TextUtils.CAP_MODE_CHARACTERS & reqModes;
    }

    private static int getWordCaps(final int reqModes) {
        return (TextUtils.CAP_MODE_CHARACTERS | TextUtils.CAP_MODE_WORDS) & reqModes;
    }

    private static int getAllCaps(final int reqModes) {
        return (TextUtils.CAP_MODE_CHARACTERS | TextUtils.CAP_MODE_WORDS
                | TextUtils.CAP_MODE_SENTENCES) & reqModes;
    }

    private static int skipStartPunctuation(final CharSequence cs) {
        int i = cs.length();
        while (i > 0 && isStartPunctuation(cs.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int skipSpacesAndTabs(final CharSequence cs, int i) {
        while (i > 0) {
            final char c = cs.charAt(i - 1);
            if (!Character.isSpaceChar(c) && c != Constants.CODE_TAB) {
                break;
            }
            i--;
        }
        return i;
    }

    private static boolean isParagraphStart(final int nonSpaceIndex, final CharSequence cs) {
        return nonSpaceIndex <= 0 || Character.isWhitespace(cs.charAt(nonSpaceIndex - 1));
    }

    private static boolean checkGermanCommaNewlineLoop(final CharSequence cs, int i, char prevChar) {
        boolean hasNewLine = false;
        while (--i >= 0 && Character.isWhitespace(prevChar)) {
            if (Constants.CODE_ENTER == prevChar) {
                hasNewLine = true;
            }
            prevChar = cs.charAt(i);
        }
        return Constants.CODE_COMMA == prevChar && hasNewLine;
    }

    private static boolean isGermanCommaBeforeNewline(final CharSequence cs, final int i) {
        if (i <= 0) {
            return false;
        }
        return checkGermanCommaNewlineLoop(cs, i, cs.charAt(i - 1));
    }

    private static int getParagraphStartCaps(final CharSequence cs, final int i,
            final SpacingAndPunctuations sp, final int reqModes) {
        if (sp.mUsesGermanRules && isGermanCommaBeforeNewline(cs, i)) {
            return getWordCaps(reqModes);
        }
        return getAllCaps(reqModes);
    }

    private static int getWordSeparatorCaps(final CharSequence cs,
            final SpacingAndPunctuations sp, final int reqModes) {
        if (sp.isWordSeparator(cs.charAt(cs.length() - 1))) {
            return getWordCaps(reqModes);
        }
        return getCharCaps(reqModes);
    }

    private static int skipAmericanClosingPunctuation(final CharSequence cs, int i) {
        while (i > 0) {
            final char c = cs.charAt(i - 1);
            if (c != Constants.CODE_DOUBLE_QUOTE && c != Constants.CODE_SINGLE_QUOTE
                    && Character.getType(c) != Character.END_PUNCTUATION) {
                break;
            }
            i--;
        }
        return i;
    }

    private static boolean isTerminatorNotAbbreviation(final char c, final SpacingAndPunctuations sp) {
        return sp.isSentenceTerminator(c) && !sp.isAbbreviationMarker(c);
    }

    private static int stepStartState(final char c, final boolean usesGermanRules) {
        if (Character.isLetter(c)) {
            return STATE_WORD;
        }
        if (Character.isWhitespace(c)) {
            return STATE_NO_CAPS;
        }
        if (usesGermanRules && Character.isDigit(c)) {
            return STATE_NUMBER;
        }
        return STATE_CAPS;
    }

    private static int stepWordState(final char c, final boolean isSeparator) {
        if (Character.isLetter(c)) {
            return STATE_WORD;
        }
        if (isSeparator) {
            return STATE_PERIOD;
        }
        return STATE_CAPS;
    }

    private static int stepPeriodState(final char c) {
        return Character.isLetter(c) ? STATE_LETTER : STATE_CAPS;
    }

    private static int stepLetterState(final char c, final boolean isSeparator) {
        if (Character.isLetter(c)) {
            return STATE_LETTER;
        }
        if (isSeparator) {
            return STATE_PERIOD;
        }
        return STATE_NO_CAPS;
    }

    private static int stepNumberState(final char c) {
        if (Character.isLetter(c)) {
            return STATE_WORD;
        }
        if (Character.isDigit(c)) {
            return STATE_NUMBER;
        }
        return STATE_NO_CAPS;
    }

    private static int stepOtherStates(final int state, final char c, final SpacingAndPunctuations sp) {
        if (state == STATE_PERIOD) {
            return stepPeriodState(c);
        }
        if (state == STATE_LETTER) {
            return stepLetterState(c, sp.isSentenceSeparator(c));
        }
        return stepNumberState(c);
    }

    private static int stepState(final int state, final char c, final SpacingAndPunctuations sp) {
        if (state == STATE_START) {
            return stepStartState(c, sp.mUsesGermanRules);
        }
        if (state == STATE_WORD) {
            return stepWordState(c, sp.isSentenceSeparator(c));
        }
        return stepOtherStates(state, c, sp);
    }

    private static boolean isTerminalCapsState(final int state) {
        return state != STATE_START && state != STATE_LETTER;
    }

    private static boolean checkSentenceEndingPeriod(final CharSequence cs, int i,
            final SpacingAndPunctuations sp) {
        int state = STATE_START;
        while (i > 0) {
            final char c = cs.charAt(--i);
            state = stepState(state, c, sp);
            if (state == STATE_CAPS) {
                return true;
            }
            if (state == STATE_NO_CAPS) {
                return false;
            }
        }
        return isTerminalCapsState(state);
    }

    private static int evaluateSentenceEnd(final CharSequence cs, final int i,
            final char c, final SpacingAndPunctuations sp, final int reqModes) {
        if (isTerminatorNotAbbreviation(c, sp)) {
            return getAllCaps(reqModes);
        }
        if (sp.isSentenceSeparator(c) && i > 0 && checkSentenceEndingPeriod(cs, i, sp)) {
            return getAllCaps(reqModes);
        }
        return getWordCaps(reqModes);
    }

    private static int getSentenceCapsMode(final CharSequence cs, int i,
            final SpacingAndPunctuations sp, final int reqModes) {
        if (sp.mUsesAmericanTypography) {
            i = skipAmericanClosingPunctuation(cs, i);
        }
        if (i <= 0) {
            return getCharCaps(reqModes);
        }
        final char c = cs.charAt(--i);
        return evaluateSentenceEnd(cs, i, c, sp, reqModes);
    }

    /**
     * Determine what caps mode should be in effect at the current offset in
     * the text. Only the mode bits set in <var>reqModes</var> will be
     * checked. Note that the caps mode flags here are explicitly defined
     * to match those in {@link InputType}.
     *
     * This code is a straight copy of TextUtils.getCapsMode (modulo namespace and formatting
     * issues). This will change in the future as we simplify the code for our use and fix bugs.
     *
     * @param cs The text that should be checked for caps modes.
     * @param reqModes The modes to be checked: may be any combination of
     * {@link TextUtils#CAP_MODE_CHARACTERS}, {@link TextUtils#CAP_MODE_WORDS}, and
     * {@link TextUtils#CAP_MODE_SENTENCES}.
     * @param spacingAndPunctuations The current spacing and punctuations settings.
     *
     * @return Returns the actual capitalization modes that can be in effect
     * at the current position, which is any combination of
     * {@link TextUtils#CAP_MODE_CHARACTERS}, {@link TextUtils#CAP_MODE_WORDS}, and
     * {@link TextUtils#CAP_MODE_SENTENCES}.
     */
    public static int getCapsMode(final CharSequence cs, final int reqModes,
            final SpacingAndPunctuations spacingAndPunctuations) {
        if ((reqModes & (TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES)) == 0) {
            return getCharCaps(reqModes);
        }
        final int newCapIndex = skipStartPunctuation(cs);
        final int nonSpaceIndex = skipSpacesAndTabs(cs, newCapIndex);
        if (isParagraphStart(nonSpaceIndex, cs)) {
            return getParagraphStartCaps(cs, nonSpaceIndex, spacingAndPunctuations, reqModes);
        }
        if (newCapIndex == nonSpaceIndex) {
            return getWordSeparatorCaps(cs, spacingAndPunctuations, reqModes);
        }
        if ((reqModes & TextUtils.CAP_MODE_SENTENCES) == 0) {
            return getWordCaps(reqModes);
        }
        return getSentenceCapsMode(cs, nonSpaceIndex, spacingAndPunctuations, reqModes);
    }

    private static void appendFlagNames(final int capsFlags, final ArrayList<String> builder) {
        if ((capsFlags & TextUtils.CAP_MODE_CHARACTERS) != 0) {
            builder.add("characters");
        }
        if ((capsFlags & TextUtils.CAP_MODE_WORDS) != 0) {
            builder.add("words");
        }
        if ((capsFlags & TextUtils.CAP_MODE_SENTENCES) != 0) {
            builder.add("sentences");
        }
    }

    /**
     * Convert capitalize mode flags into human readable text.
     *
     * @param capsFlags The modes flags to be converted. It may be any combination of
     * {@link TextUtils#CAP_MODE_CHARACTERS}, {@link TextUtils#CAP_MODE_WORDS}, and
     * {@link TextUtils#CAP_MODE_SENTENCES}.
     * @return the text that describe the <code>capsMode</code>.
     */
    public static String flagsToString(final int capsFlags) {
        final int capsFlagsMask = TextUtils.CAP_MODE_CHARACTERS | TextUtils.CAP_MODE_WORDS
                | TextUtils.CAP_MODE_SENTENCES;
        if ((capsFlags & ~capsFlagsMask) != 0) {
            return "unknown<0x" + Integer.toHexString(capsFlags) + ">";
        }
        final ArrayList<String> builder = new ArrayList<>();
        appendFlagNames(capsFlags, builder);
        if (builder.isEmpty()) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < builder.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(builder.get(i));
        }
        return sb.toString();
    }
}
