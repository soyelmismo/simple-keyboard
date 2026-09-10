/*
 * Copyright (C) 2011 The Android Open Source Project
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

package rkr.simplekeyboard.inputmethod.latin.utils;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public final class ViewLayoutUtils {
    private ViewLayoutUtils() {
        // This utility class is not publicly instantiable.
    }

    /**
     * Positions a view via {@link View#layout(int, int, int, int)} and translation without
     * touching {@link View#setLayoutParams} on the hot path. LayoutParams (with width/height)
     * must have been assigned once when the view was added to its parent; mutating width/height
     * or re-applying setLayoutParams() triggers {@code requestLayout()} up the tree.
     *
     * <p>For the key-preview path the placer is a full-window container that does not
     * consume margins or layout params from preview children. We enforce the view dimensions
     * at {@code (0, 0, w, h)} and position it via {@link View#setTranslationX(float)} /
     * {@link View#setTranslationY(float)}. Calling {@code layout(x, y, x + w, y + h)} together
     * with {@code setTranslationX(x)} would shift the view by {@code 2 * x} because Android
     * computes visual position as {@code left + translationX}.
     */
    public static void placeViewAt(final View view, final int x, final int y, final int w,
            final int h) {
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            final ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) lp;
            if (marginLayoutParams.leftMargin != x || marginLayoutParams.topMargin != y
                    || marginLayoutParams.width != w || marginLayoutParams.height != h) {
                marginLayoutParams.width = w;
                marginLayoutParams.height = h;
                marginLayoutParams.setMargins(x, y, -50, 0);
                view.setLayoutParams(marginLayoutParams);
            }
        }
        view.layout(x, y, x + w, y + h);
    }

    public static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
        final WindowManager.LayoutParams params = window.getAttributes();
        if (params != null && params.height != layoutHeight) {
            params.height = layoutHeight;
            window.setAttributes(params);
        }
    }

    public static void updateLayoutHeightOf(final View view, final int layoutHeight) {
        final ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != layoutHeight) {
            params.height = layoutHeight;
            view.setLayoutParams(params);
        }
    }

    public static void updateLayoutGravityOf(final View view, final int layoutGravity) {
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)lp;
            if (params.gravity != layoutGravity) {
                params.gravity = layoutGravity;
                view.setLayoutParams(params);
            }
        } else if (lp instanceof FrameLayout.LayoutParams) {
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)lp;
            if (params.gravity != layoutGravity) {
                params.gravity = layoutGravity;
                view.setLayoutParams(params);
            }
        } else {
            throw new IllegalArgumentException("Layout parameter doesn't have gravity: "
                    + lp.getClass().getName());
        }
    }
}
