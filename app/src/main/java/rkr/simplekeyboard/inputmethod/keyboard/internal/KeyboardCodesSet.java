/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2018 Raimondas Rimkus
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

import java.util.HashMap;
import java.util.Map;

import rkr.simplekeyboard.inputmethod.latin.common.Constants;

public final class KeyboardCodesSet {
    public static final String PREFIX_CODE = "!code/";

    private static final Map<String, Integer> NAME_TO_CODE = new HashMap<>();

    static {
        NAME_TO_CODE.put("key_tab", Constants.CODE_TAB);
        NAME_TO_CODE.put("key_enter", Constants.CODE_ENTER);
        NAME_TO_CODE.put("key_space", Constants.CODE_SPACE);
        NAME_TO_CODE.put("key_shift", Constants.CODE_SHIFT);
        NAME_TO_CODE.put("key_capslock", Constants.CODE_CAPSLOCK);
        NAME_TO_CODE.put("key_switch_alpha_symbol", Constants.CODE_SWITCH_ALPHA_SYMBOL);
        NAME_TO_CODE.put("key_output_text", Constants.CODE_OUTPUT_TEXT);
        NAME_TO_CODE.put("key_delete", Constants.CODE_DELETE);
        NAME_TO_CODE.put("key_settings", Constants.CODE_SETTINGS);
        NAME_TO_CODE.put("key_paste", Constants.CODE_PASTE);
        NAME_TO_CODE.put("key_emoji", Constants.CODE_EMOJI);
        NAME_TO_CODE.put("key_action_next", Constants.CODE_ACTION_NEXT);
        NAME_TO_CODE.put("key_action_previous", Constants.CODE_ACTION_PREVIOUS);
        NAME_TO_CODE.put("key_shift_enter", Constants.CODE_SHIFT_ENTER);
        NAME_TO_CODE.put("key_language_switch", Constants.CODE_LANGUAGE_SWITCH);
        NAME_TO_CODE.put("key_unspecified", Constants.CODE_UNSPECIFIED);
    }

    public static int getCode(final String name) {
        final Integer code = NAME_TO_CODE.get(name);
        if (code != null) {
            return code;
        }
        throw new RuntimeException("Unknown key code: " + name);
    }
}
