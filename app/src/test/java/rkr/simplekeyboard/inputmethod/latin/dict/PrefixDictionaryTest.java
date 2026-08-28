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

package rkr.simplekeyboard.inputmethod.latin.dict;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class PrefixDictionaryTest {

    private PrefixDictionary mDict;

    @Before
    public void setUp() {
        mDict = new PrefixDictionary();
    }

    @Test
    public void testBasicPrefixSearch() {
        mDict.insert("keyboard", 100);
        mDict.insert("key", 50);
        mDict.insert("keypad", 80);
        mDict.insert("simple", 90);

        List<CharSequence> results = mDict.getSuggestions("key", 5);
        assertEquals(3, results.size());
        assertEquals("keyboard", results.get(0)); // highest frequency
        assertEquals("keypad", results.get(1));
        assertEquals("key", results.get(2));
    }

    @Test
    public void testCasePreservation() {
        mDict.insert("hello", 100);
        mDict.insert("help", 80);

        // Title case
        List<CharSequence> titleResults = mDict.getSuggestions("He", 5);
        assertEquals(2, titleResults.size());
        assertEquals("Hello", titleResults.get(0));
        assertEquals("Help", titleResults.get(1));

        // Uppercase
        List<CharSequence> upperResults = mDict.getSuggestions("HEL", 5);
        assertEquals(2, upperResults.size());
        assertEquals("HELLO", upperResults.get(0));
        assertEquals("HELP", upperResults.get(1));
    }

    @Test
    public void testStreamLoading() throws IOException {
        String data = "android 200\napple 150\napartment 100\napplication 180\n# comment line\n";
        mDict.loadFromStream(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));

        assertEquals(4, mDict.getWordCount());
        List<CharSequence> results = mDict.getSuggestions("ap", 3);
        assertEquals(3, results.size());
        assertEquals("application", results.get(0)); // 180
        assertEquals("apple", results.get(1));       // 150
        assertEquals("apartment", results.get(2));   // 100
    }

    @Test
    public void testEmptyAndNonMatchingPrefix() {
        mDict.insert("test", 10);
        assertTrue(mDict.getSuggestions("", 5).isEmpty());
        assertTrue(mDict.getSuggestions("xyz", 5).isEmpty());
        assertTrue(mDict.getSuggestions(null, 5).isEmpty());
    }

    @Test
    public void testFuzzyAutocorrection() {
        mDict.insert("teclado", 100);
        mDict.insert("hello", 100);
        mDict.insert("simple", 90);

        // Exact match should return null (no correction needed)
        assertEquals(null, mDict.getBestCorrection("teclado"));

        // 1-character deletion typo (missing 'd') -> should correct to "teclado"
        assertEquals("teclado", mDict.getBestCorrection("teclao"));

        // 1-character substitution typo ('a' instead of 'e') -> "hallo" -> "hello"
        assertEquals("hello", mDict.getBestCorrection("hallo"));

        // 1-character transposition typo -> "helol" -> "hello"
        assertEquals("hello", mDict.getBestCorrection("helol"));

        // Case preservation in autocorrection
        assertEquals("Hello", mDict.getBestCorrection("Helol"));
        assertEquals("HELLO", mDict.getBestCorrection("HELOL"));

        // Too short or non-correctable
        assertEquals(null, mDict.getBestCorrection("hi"));
        assertEquals(null, mDict.getBestCorrection("xyzabc"));
    }
}
