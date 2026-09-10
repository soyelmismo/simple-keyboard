/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2020 wittmane
 * Copyright (C) 2020 Raimondas Rimkus
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

import android.animation.Animator;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import java.util.ArrayDeque;
import java.util.HashMap;

import rkr.simplekeyboard.inputmethod.keyboard.Key;
import rkr.simplekeyboard.inputmethod.latin.common.CoordinateUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;

/**
 * This class controls pop up key previews. This class decides:
 * - what kind of key previews should be shown.
 * - where key previews should be placed.
 * - how key previews should be shown and dismissed.
 */
public final class KeyPreviewChoreographer {
    private static final String TAG = KeyPreviewChoreographer.class.getSimpleName();

    // Free {@link KeyPreviewView} pool that can be used for key preview.
    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();
    // Map from {@link Key} to {@link KeyPreviewView} that is currently being displayed as key
    // preview.
    private final HashMap<Key,KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();

    private final KeyPreviewDrawParams mParams;

    public KeyPreviewChoreographer(final KeyPreviewDrawParams params) {
        mParams = params;
    }

    public KeyPreviewView getKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView == null) {
            keyPreviewView = mFreeKeyPreviewViews.poll();
        }
        if (keyPreviewView != null) {
            keyPreviewView.setScaleX(1);
            keyPreviewView.setScaleY(1);
            if (keyPreviewView.getParent() == null) {
                addPreviewViewToPlacer(placerView, keyPreviewView);
            }
            return keyPreviewView;
        }
        final Context context = placerView.getContext();
        keyPreviewView = new KeyPreviewView(context, null /* attrs */);
        keyPreviewView.setBackgroundResource(mParams.mPreviewBackgroundResId);
        addPreviewViewToPlacer(placerView, keyPreviewView);
        return keyPreviewView;
    }

    private void addPreviewViewToPlacer(final ViewGroup placerView, final KeyPreviewView keyPreviewView) {
        final LayoutParams lp;
        if (placerView instanceof FrameLayout) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (placerView instanceof RelativeLayout) {
            lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (placerView == null) {
            Log.w(TAG, "addPreviewViewToPlacer: placerView is null");
            return;
        } else {
            Log.w(TAG, "addPreviewViewToPlacer: unsupported placer type "
                    + placerView.getClass().getName());
            return;
        }
        placerView.addView(keyPreviewView, lp);
    }

    public void deallocate() {
        for (final KeyPreviewView view : mShowingKeyPreviewViews.values()) {
            final Object tag = view.getTag();
            if (tag instanceof Animator) {
                ((Animator) tag).cancel();
            }
            view.setTag(null);
            view.setVisibility(View.INVISIBLE);
        }
        mShowingKeyPreviewViews.clear();
        mFreeKeyPreviewViews.clear();
    }

    public void dismissKeyPreview(final Key key, final boolean withAnimation) {
        if (key == null) {
            return;
        }
        final KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.get(key);
        if (keyPreviewView == null) {
            return;
        }
        final Object tag = keyPreviewView.getTag();
        if (withAnimation && dismissWithAnimation(tag)) {
            return;
        }
        dismissImmediately(key, keyPreviewView, tag);
    }

    private boolean dismissWithAnimation(final Object tag) {
        if (tag instanceof Animator) {
            final Animator dismissAnimator = (Animator) tag;
            // Restart cleanly even if a previous dismiss animation is mid-flight
            // (e.g. fast typing between keys).
            if (dismissAnimator.isStarted()) {
                dismissAnimator.cancel();
            }
            dismissAnimator.start();
            return true;
        }
        return false;
    }

    private void dismissImmediately(final Key key, final KeyPreviewView keyPreviewView, final Object tag) {
        mShowingKeyPreviewViews.remove(key);
        if (tag instanceof Animator) {
            ((Animator) tag).cancel();
        }
        keyPreviewView.setTag(null);
        keyPreviewView.setVisibility(View.INVISIBLE);
        mFreeKeyPreviewViews.add(keyPreviewView);
    }

    public void placeAndShowKeyPreview(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int[] keyboardOrigin,
            final ViewGroup placerView, final boolean withAnimation,
            final int backgroundColor, final float cornerRadius) {
        final KeyPreviewView keyPreviewView = getKeyPreviewView(key, placerView);
        placeKeyPreview(key, keyPreviewView, iconsSet, drawParams, keyboardOrigin, backgroundColor, cornerRadius);
        showKeyPreview(key, keyPreviewView, withAnimation);
    }

    private void placeKeyPreview(final Key key, final KeyPreviewView keyPreviewView,
            final KeyboardIconsSet iconsSet, final KeyDrawParams drawParams,
            final int[] originCoords, final int backgroundColor, final float cornerRadius) {
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams, backgroundColor, cornerRadius);
        keyPreviewView.measure(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mParams.setGeometry(keyPreviewView);
        final int previewWidth = Math.max(keyPreviewView.getMeasuredWidth(), mParams.mMinPreviewWidth);
        final int previewHeight = mParams.mPreviewHeight;
        final int keyWidth = key.getWidth();
        // The key preview is horizontally aligned with the center of the visible part of the
        // parent key. If it doesn't fit in this {@link KeyboardView}, it is moved inward to fit and
        // the left/right background is used if such background is specified.
        final int previewX = key.getX() - (previewWidth - keyWidth) / 2
                + CoordinateUtils.x(originCoords);
        // The key preview is placed vertically above the top edge of the parent key with an
        // arbitrary offset.
        final int previewY = key.getY() - previewHeight + mParams.mPreviewOffset
                + CoordinateUtils.y(originCoords);

        ViewLayoutUtils.placeViewAt(
                keyPreviewView, previewX, previewY, previewWidth, previewHeight);
    }

    void showKeyPreview(final Key key, final KeyPreviewView keyPreviewView,
            final boolean withAnimation) {
        if (!withAnimation) {
            keyPreviewView.setVisibility(View.VISIBLE);
            mShowingKeyPreviewViews.put(key, keyPreviewView);
            return;
        }

        // Show preview with animation. Reuse the cached dismiss animator (cloned from the
        // prototype) so we never inflate XML on the press path.
        final Animator dismissAnimator = mParams.createDismissAnimator(keyPreviewView);
        if (dismissAnimator == null) {
            // Fallback: no animator available, just show.
            keyPreviewView.setVisibility(View.VISIBLE);
            mShowingKeyPreviewViews.put(key, keyPreviewView);
            return;
        }
        dismissAnimator.addListener(new DismissListener(key, dismissAnimator));
        keyPreviewView.setTag(dismissAnimator);
        showKeyPreview(key, keyPreviewView, false /* withAnimation */);
    }

    /**
     * Listener that triggers the chained dismissal when the dismiss animator finishes.
     * Each press allocates a tiny inner-class instance; the cost is one vtable dispatch
     * per anim end, far cheaper than re-inflating the animator XML.
     */
    private final class DismissListener implements Animator.AnimatorListener {
        private final Key mKey;
        private final Animator mAnimator;
        DismissListener(final Key key, final Animator animator) {
            mKey = key;
            mAnimator = animator;
        }
        @Override
        public void onAnimationStart(final Animator animation) {}
        @Override
        public void onAnimationEnd(final Animator animation) {
            // Guard against late end() callbacks from an animator that has been replaced
            // by a newer press on the same pooled view (fast typing). If the view's tag has
            // moved on, the newer animator owns the dismissal.
            final KeyPreviewView view = mShowingKeyPreviewViews.get(mKey);
            if (view == null || view.getTag() != mAnimator) {
                return;
            }
            dismissKeyPreview(mKey, false /* withAnimation */);
        }
        @Override
        public void onAnimationCancel(final Animator animation) {}
        @Override
        public void onAnimationRepeat(final Animator animation) {}
    }
}
