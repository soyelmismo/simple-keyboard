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

package rkr.simplekeyboard.inputmethod.latin.common;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class StringUtilsTest {

    @Test
    public void testNewSingleCodePointString() {
        assertEquals("a", StringUtils.newSingleCodePointString('a'));
        assertEquals("A", StringUtils.newSingleCodePointString('A'));
        // Unicode surrogate pair: musical symbol G clef (U+1D11E)
        final int gClef = 0x1D11E;
        final String gClefStr = StringUtils.newSingleCodePointString(gClef);
        assertEquals(2, gClefStr.length());
        assertEquals(gClef, gClefStr.codePointAt(0));
    }

    @Test
    public void testContainsInArray() {
        final String[] items = {"alpha", "beta", "gamma"};
        assertTrue(StringUtils.containsInArray("alpha", items));
        assertTrue(StringUtils.containsInArray("beta", items));
        assertFalse(StringUtils.containsInArray("delta", items));
    }

    @Test
    public void testToCodePointArray() {
        final String testStr = "abc";
        final int[] expected = {'a', 'b', 'c'};
        assertArrayEquals(expected, StringUtils.toCodePointArray(testStr));
    }

    @Test
    public void testToSortedCodePointArray() {
        final String testStr = "cba";
        final int[] expected = {'a', 'b', 'c'};
        assertArrayEquals(expected, StringUtils.toSortedCodePointArray(testStr));
    }

    @Test
    public void testIsIdenticalAfterUpcaseAndDowncase() {
        assertTrue(StringUtils.isIdenticalAfterUpcase("HELLO WORLD 123"));
        assertFalse(StringUtils.isIdenticalAfterUpcase("Hello World"));

        assertTrue(StringUtils.isIdenticalAfterDowncase("hello world 123"));
        assertFalse(StringUtils.isIdenticalAfterDowncase("Hello World"));
    }

    @Test
    public void testCapitalizeEachWord() {
        assertEquals("Hello World", StringUtils.capitalizeEachWord("hello world", Locale.ENGLISH));
        assertEquals("Simple Keyboard", StringUtils.capitalizeEachWord("simple keyboard", Locale.ENGLISH));
    }

    @Test
    public void testGermanSharpS() {
        // ß (U+00DF) -> uppercase ẞ (U+1E9E)
        assertEquals("\u1E9E", StringUtils.toTitleCaseOfKeyLabel("\u00DF", Locale.GERMAN));
        // ẞ (U+1E9E) -> lowercase ß (U+00DF)
        assertEquals("\u00DF", StringUtils.toLowerCaseOfKeyLabel("\u1E9E", Locale.GERMAN));
    }
}
