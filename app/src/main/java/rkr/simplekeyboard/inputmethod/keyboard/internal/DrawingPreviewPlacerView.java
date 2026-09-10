/*
 * Copyright (C) 2012 The Android Open Source Project
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

package rkr.simplekeyboard.inputmethod.keyboard.internal;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import rkr.simplekeyboard.inputmethod.latin.common.CoordinateUtils;

public final class DrawingPreviewPlacerView extends RelativeLayout {
    private final int[] mKeyboardViewOrigin = CoordinateUtils.newInstance();

    public DrawingPreviewPlacerView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        // Default for ViewGroup is setWillNotDraw(true), which skips the (empty) draw pass.
        // We keep that default: previews/panels are siblings drawn on top of us; we only host them.
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setKeyboardViewGeometry(final int[] originCoords) {
        CoordinateUtils.copy(mKeyboardViewOrigin, originCoords);
    }
}
