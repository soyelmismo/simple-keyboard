/*
 * Copyright (C) 2026 Simple Keyboard Contributors
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

package rkr.simplekeyboard.inputmethod.latin.emoji;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewUtils;

public class EmojiPalettesView extends LinearLayout {
    private static final String TAG = EmojiPalettesView.class.getSimpleName();

    public interface EmojiListener {
        void onSelectEmoji(String emoji);
        void onDeleteEmoji();
        void onCloseEmoji();
    }

    private static final String PREF_RECENT_EMOJIS = "pref_recent_emojis";
    private static final int MAX_RECENT_EMOJIS = 35;

    private EmojiListener mListener;
    private int mTargetHeight = 0;

    private int mTextColor = Color.WHITE;
    private int mFunctionalTextColor = Color.WHITE;

    private LinearLayout mHeaderLayout;
    private HorizontalScrollView mCategoryScrollView;
    private LinearLayout mCategoryTabsContainer;
    private ImageView mDeleteButton;
    private ImageView mCloseButton;

    private FrameLayout mContentContainer;
    private GridView mEmojiGridView;
    private TextView mEmptyRecentView;
    private EmojiGridAdapter mGridAdapter;

    private int mCurrentCategoryIndex = 1; // Default to Smileys
    private final List<String> mRecentEmojis = new ArrayList<>();
    private final List<TextView> mCategoryTabViews = new ArrayList<>();
    private boolean mRecentEmojisLoaded = false;
    private boolean mRecentEmojisDirty = false;
    private final StringBuilder mRecentEmojiBuilder = new StringBuilder();

    public EmojiPalettesView(Context context) {
        super(context);
        init(context);
    }

    public EmojiPalettesView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public EmojiPalettesView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void setListener(EmojiListener listener) {
        mListener = listener;
    }

    public void setTargetHeight(int height) {
        mTargetHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mTargetHeight > 0) {
            int exactHeightSpec = MeasureSpec.makeMeasureSpec(mTargetHeight, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, exactHeightSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private void init(Context context) {
        setOrientation(VERTICAL);

        ViewUtils.applyKeyboardBackground(this);

        loadThemeColors(context);
        loadRecentEmojis(context);

        buildHeader(context);
        buildDivider(context);
        buildContentArea(context);

        selectCategory(mRecentEmojis.isEmpty() ? 1 : 0);
    }

    private void loadThemeColors(Context context) {
        mTextColor = ViewUtils.getKeyTextColor(context);
        mFunctionalTextColor = ViewUtils.getFunctionalTextColor(context, mTextColor);
    }

    private void buildHeader(Context context) {
        mHeaderLayout = new LinearLayout(context);
        mHeaderLayout.setOrientation(HORIZONTAL);
        mHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        int headerHeight = ViewUtils.dpToPx(context, 44);
        mHeaderLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));

        // Category scroll view
        mCategoryScrollView = new HorizontalScrollView(context);
        mCategoryScrollView.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f);
        mCategoryScrollView.setLayoutParams(scrollLp);

        mCategoryTabsContainer = new LinearLayout(context);
        mCategoryTabsContainer.setOrientation(HORIZONTAL);
        mCategoryTabsContainer.setGravity(Gravity.CENTER_VERTICAL);
        mCategoryTabsContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int tabWidth = ViewUtils.dpToPx(context, 38);
        for (int i = 0; i < EmojiData.CATEGORY_ICONS.length; i++) {
            final int index = i;
            TextView tabView = new TextView(context);
            tabView.setText(EmojiData.CATEGORY_ICONS[i]);
            tabView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tabView.setGravity(Gravity.CENTER);
            tabView.setLayoutParams(new LinearLayout.LayoutParams(tabWidth, LayoutParams.MATCH_PARENT));
            tabView.setClickable(true);
            tabView.setFocusable(false);
            ViewUtils.applySelectableItemBackground(tabView, true);
            tabView.setOnClickListener(v -> selectCategory(index));

            mCategoryTabViews.add(tabView);
            mCategoryTabsContainer.addView(tabView);
        }
        mCategoryScrollView.addView(mCategoryTabsContainer);
        mHeaderLayout.addView(mCategoryScrollView);

        // Delete button
        int iconWidth = ViewUtils.dpToPx(context, 42);
        mDeleteButton = ViewUtils.createIconButton(context, R.drawable.sym_keyboard_delete, iconWidth, headerHeight, ViewUtils.dpToPx(context, 8), true);
        mDeleteButton.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onDeleteEmoji();
            }
        });
        mHeaderLayout.addView(mDeleteButton);

        // Close / Keyboard return button
        mCloseButton = ViewUtils.createIconButton(context, R.drawable.ic_close_vector, iconWidth, headerHeight, ViewUtils.dpToPx(context, 10), true);
        mCloseButton.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onCloseEmoji();
            }
        });
        mHeaderLayout.addView(mCloseButton);

        addView(mHeaderLayout);
    }

    private void buildDivider(Context context) {
        addView(ViewUtils.createHorizontalDivider(context, mTextColor, 0.12f));
    }

    private void buildContentArea(Context context) {
        mContentContainer = new FrameLayout(context);
        mContentContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.0f));

        mEmojiGridView = new GridView(context);
        mEmojiGridView.setNumColumns(8);
        mEmojiGridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        mEmojiGridView.setGravity(Gravity.CENTER);
        mEmojiGridView.setVerticalScrollBarEnabled(true);
        mEmojiGridView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mGridAdapter = new EmojiGridAdapter(context);
        mEmojiGridView.setAdapter(mGridAdapter);

        mEmojiGridView.setOnItemClickListener((parent, view, position, id) -> {
            String emoji = (String) mGridAdapter.getItem(position);
            if (emoji != null) {
                recordRecentEmoji(emoji);
                if (mListener != null) {
                    mListener.onSelectEmoji(emoji);
                }
            }
        });

        mContentContainer.addView(mEmojiGridView);

        mEmptyRecentView = new TextView(context);
        mEmptyRecentView.setText(R.string.no_recent_emojis);
        mEmptyRecentView.setTextColor(mTextColor);
        mEmptyRecentView.setAlpha(0.5f);
        mEmptyRecentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mEmptyRecentView.setGravity(Gravity.CENTER);
        mEmptyRecentView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mEmptyRecentView.setVisibility(GONE);
        mContentContainer.addView(mEmptyRecentView);

        addView(mContentContainer);
    }

    public void reloadRecentEmojis() {
        saveRecentEmojis();
        mRecentEmojisLoaded = false;
        loadRecentEmojis(getContext());
        selectCategory(mCurrentCategoryIndex);
    }

    public void selectCategory(int index) {
        if (index < 0 || index >= EmojiData.CATEGORY_ICONS.length) {
            index = 1;
        }
        mCurrentCategoryIndex = index;
        for (int i = 0; i < mCategoryTabViews.size(); i++) {
            TextView tab = mCategoryTabViews.get(i);
            tab.setAlpha(i == index ? 1.0f : 0.45f);
        }

        List<String> items;
        if (index == 0) {
            loadRecentEmojis(getContext());
            items = mRecentEmojis;
            mEmptyRecentView.setVisibility(items.isEmpty() ? VISIBLE : GONE);
            mEmojiGridView.setVisibility(items.isEmpty() ? GONE : VISIBLE);
        } else {
            items = Arrays.asList(EmojiData.CATEGORY_EMOJIS[index]);
            mEmptyRecentView.setVisibility(GONE);
            mEmojiGridView.setVisibility(VISIBLE);
        }

        mGridAdapter.setItems(items);
        mEmojiGridView.setSelection(0);
        mEmojiGridView.invalidateViews();
    }

    private void loadRecentEmojis(Context context) {
        if (context == null) {
            Log.w(TAG, "Context is null in loadRecentEmojis");
            return;
        }
        if (mRecentEmojisLoaded) return;
        
        mRecentEmojis.clear();
        try {
            SharedPreferences prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceSharedPreferences(context);
            String raw = prefs.getString(PREF_RECENT_EMOJIS, "");
            if (!TextUtils.isEmpty(raw)) {
                String[] split = raw.split(",");
                for (String s : split) {
                    if (!s.trim().isEmpty()) {
                        mRecentEmojis.add(s.trim());
                    }
                }
            }
            mRecentEmojisLoaded = true;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to load recent emojis", e);
        }
    }

    private void recordRecentEmoji(String emoji) {
        loadRecentEmojis(getContext());
        if (!mRecentEmojis.isEmpty() && emoji.equals(mRecentEmojis.get(0))) {
            return; // No change in order, skip redundant disk I/O
        }
        
        mRecentEmojis.remove(emoji);
        mRecentEmojis.add(0, emoji);
        if (mRecentEmojis.size() > MAX_RECENT_EMOJIS) {
            mRecentEmojis.remove(mRecentEmojis.size() - 1);
        }
        mRecentEmojisDirty = true;

        if (mCurrentCategoryIndex == 0) {
            mGridAdapter.setItems(mRecentEmojis);
            mEmptyRecentView.setVisibility(mRecentEmojis.isEmpty() ? VISIBLE : GONE);
            mEmojiGridView.setVisibility(mRecentEmojis.isEmpty() ? GONE : VISIBLE);
        }
    }

    public void saveRecentEmojis() {
        if (!mRecentEmojisDirty) {
            return;
        }
        mRecentEmojisDirty = false;

        mRecentEmojiBuilder.setLength(0);
        for (int i = 0; i < mRecentEmojis.size(); i++) {
            if (i > 0) mRecentEmojiBuilder.append(",");
            mRecentEmojiBuilder.append(mRecentEmojis.get(i));
        }
        try {
            final SharedPreferences prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceSharedPreferences(getContext());
            final String recentEmojis = mRecentEmojiBuilder.toString();
            if (!recentEmojis.equals(prefs.getString(PREF_RECENT_EMOJIS, null))) {
                prefs.edit()
                        .putString(PREF_RECENT_EMOJIS, recentEmojis)
                        .apply();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to save recent emojis", e);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        saveRecentEmojis();
    }

    public void reloadThemeColors() {
        Context context = getContext();
        loadThemeColors(context);
        ViewUtils.applyKeyboardBackground(this);
        if (mGridAdapter != null) {
            mGridAdapter.notifyDataSetChanged();
        }
    }

    public void deallocateMemory() {
        saveRecentEmojis();
        mRecentEmojis.clear();
    }

    private static class EmojiGridAdapter extends BaseAdapter {
        private final Context mContext;
        private List<String> mItems = new ArrayList<>();
        private final int mItemHeight;

        public EmojiGridAdapter(Context context) {
            mContext = context;
            mItemHeight = ViewUtils.dpToPx(context, 44);
        }

        public void setItems(List<String> items) {
            mItems = items != null ? items : new ArrayList<>();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return (position >= 0 && position < mItems.size()) ? mItems.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(mContext);
                tv.setLayoutParams(new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, mItemHeight));
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                ViewUtils.applySelectableItemBackground(tv, true);
            }

            String emoji = (String) getItem(position);
            tv.setText(emoji != null ? emoji : "");
            return tv;
        }
    }
}
