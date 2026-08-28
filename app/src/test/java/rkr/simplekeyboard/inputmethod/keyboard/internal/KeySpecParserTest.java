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

package rkr.simplekeyboard.inputmethod.keyboard.internal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(JUnit4.class)
public class KeySpecParserTest {

    @Test
    public void testGetLabelSimple() {
        assertEquals("a", KeySpecParser.getLabel("a"));
        assertEquals("A", KeySpecParser.getLabel("A"));
        assertEquals("1", KeySpecParser.getLabel("1"));
        assertEquals("hello", KeySpecParser.getLabel("hello"));
    }

    @Test
    public void testGetLabelWithOutputText() {
        assertEquals("label", KeySpecParser.getLabel("label|output"));
        assertEquals("a", KeySpecParser.getLabel("a|output_text"));
    }

    @Test
    public void testGetLabelEscaped() {
        assertEquals("|", KeySpecParser.getLabel("\\|"));
        assertEquals("a|b", KeySpecParser.getLabel("a\\|b"));
        assertEquals("\\", KeySpecParser.getLabel("\\\\"));
    }

    @Test
    public void testGetLabelForIcon() {
        assertNull(KeySpecParser.getLabel("!icon/delete"));
    }
}
