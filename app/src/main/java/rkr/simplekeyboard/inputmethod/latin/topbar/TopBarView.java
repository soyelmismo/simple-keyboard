package rkr.simplekeyboard.inputmethod.latin.topbar;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewUtils;

public class TopBarView extends FrameLayout {
    private static final String TAG = TopBarView.class.getSimpleName();
    public static final int MODE_NORMAL = 0;
    public static final int MODE_TOOL_TRAY = 1;

    private int mCurrentMode = MODE_NORMAL;

    private LinearLayout mNormalModeContainer;
    private ImageView mExpandButton;
    private LinearLayout mSuggestionsContainer;
    private boolean mIsExternalActive;
    private boolean mIsSinglePillMode;
    private TextView mLeftSlot;
    private View mDivider1;
    private TextView mCenterSlot;
    private View mDivider2;
    private TextView mRightSlot;

    private LinearLayout mToolTrayContainer;
    private ImageView mCloseButton;
    private ImageView mEmojiButton;
    private ImageView mClipboardButton;
    private ImageView mSettingsButton;
    private ImageView mLanguageButton;

    private TopBarListener mListener;

    private final OnClickListener mSlotClickListener = v -> {
        if (v instanceof TextView) {
            final TextView tv = (TextView) v;
            final CharSequence text = tv.getText();
            if (!TextUtils.isEmpty(text)) {
                handleSuggestionClick(text);
            }
        }
    };

    private final OnLongClickListener mSlotLongClickListener = v -> {
        if (v instanceof TextView) {
            final TextView tv = (TextView) v;
            final CharSequence text = tv.getText();
            if (!TextUtils.isEmpty(text) && mListener != null) {
                mListener.onSuggestionLongClicked(StringUtils.stripEnclosingQuotes(text));
                return true;
            }
        }
        return false;
    };

    private int mTextColor = 0xFFCCCCCC;

    public TopBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public void setListener(TopBarListener listener) {
        mListener = listener;
    }

    private void init(Context context) {
        ViewUtils.applyKeyboardBackground(this);

        mTextColor = ViewUtils.getKeyTextColor(context);

        int iconWidthPx = ViewUtils.dpToPx(context, 34);

        mNormalModeContainer = new LinearLayout(context);
        mNormalModeContainer.setOrientation(LinearLayout.HORIZONTAL);
        mNormalModeContainer.setGravity(Gravity.CENTER_VERTICAL);
        mNormalModeContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        mExpandButton = ViewUtils.createBarIconButton(context, R.drawable.ic_more_horiz, iconWidthPx);
        mExpandButton.setOnClickListener(v -> setMode(MODE_TOOL_TRAY));
        mNormalModeContainer.addView(mExpandButton);

        // 3-slot centered suggestions container with subtle dividers
        mSuggestionsContainer = new LinearLayout(context);
        mSuggestionsContainer.setOrientation(LinearLayout.HORIZONTAL);
        mSuggestionsContainer.setGravity(Gravity.CENTER);
        mSuggestionsContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f));

        mLeftSlot = createSuggestionSlot(context, 16.0f, false);
        mDivider1 = createDivider(context);
        mCenterSlot = createSuggestionSlot(context, 17.5f, true);
        mDivider2 = createDivider(context);
        mRightSlot = createSuggestionSlot(context, 16.0f, false);

        mSuggestionsContainer.addView(mLeftSlot);
        mSuggestionsContainer.addView(mDivider1);
        mSuggestionsContainer.addView(mCenterSlot);
        mSuggestionsContainer.addView(mDivider2);
        mSuggestionsContainer.addView(mRightSlot);
        mNormalModeContainer.addView(mSuggestionsContainer);

        View rightSpacer = new View(context);
        rightSpacer.setLayoutParams(new LinearLayout.LayoutParams(iconWidthPx, LayoutParams.MATCH_PARENT));
        mNormalModeContainer.addView(rightSpacer);

        addView(mNormalModeContainer);

        mToolTrayContainer = new LinearLayout(context);
        mToolTrayContainer.setOrientation(LinearLayout.HORIZONTAL);
        mToolTrayContainer.setGravity(Gravity.CENTER_VERTICAL);
        mToolTrayContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        mCloseButton = ViewUtils.createBarIconButton(context, R.drawable.ic_close_vector, iconWidthPx);
        mCloseButton.setOnClickListener(v -> setMode(MODE_NORMAL));
        mToolTrayContainer.addView(mCloseButton);

        mEmojiButton = ViewUtils.createBarIconButton(context, R.drawable.ic_emoji_vector, iconWidthPx);
        mEmojiButton.setOnClickListener(v -> {
            setMode(MODE_NORMAL);
            if (mListener != null) {
                mListener.onEmojiClicked();
            }
        });
        mToolTrayContainer.addView(mEmojiButton);

        mClipboardButton = ViewUtils.createBarIconButton(context, R.drawable.sym_keyboard_paste, iconWidthPx);
        mClipboardButton.setOnClickListener(v -> {
            setMode(MODE_NORMAL);
            if (mListener != null) {
                mListener.onClipboardClicked();
            }
        });
        mToolTrayContainer.addView(mClipboardButton);

        mSettingsButton = ViewUtils.createBarIconButton(context, R.drawable.sym_keyboard_settings, iconWidthPx);
        mSettingsButton.setOnClickListener(v -> {
            if (mListener != null) mListener.onSettingsClicked();
        });
        mToolTrayContainer.addView(mSettingsButton);

        mLanguageButton = ViewUtils.createBarIconButton(context, R.drawable.sym_keyboard_language_switch, iconWidthPx);
        mLanguageButton.setOnClickListener(v -> {
            if (mListener != null) mListener.onLanguageClicked();
        });
        mToolTrayContainer.addView(mLanguageButton);
        
        addView(mToolTrayContainer);

        setMode(MODE_NORMAL);
    }

    private View createDivider(Context context) {
        View divider = ViewUtils.createVerticalDivider(context, 18, mTextColor, 0.18f);
        divider.setVisibility(View.INVISIBLE);
        return divider;
    }

    private TextView createSuggestionSlot(Context context, float textSizeSp, boolean isBold) {
        TextView tv = new TextView(context);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f));
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(mTextColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        tv.setTypeface(isBold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        int paddingH = ViewUtils.dpToPx(context, 4);
        tv.setPadding(paddingH, 0, paddingH, 0);
        tv.setClickable(true);
        tv.setLongClickable(true);
        tv.setFocusable(false);
        tv.setVisibility(View.INVISIBLE);
        ViewUtils.applySelectableItemBackground(tv, false);
        tv.setOnClickListener(mSlotClickListener);
        tv.setOnLongClickListener(mSlotLongClickListener);
        return tv;
    }

    public void setMode(int mode) {
        mCurrentMode = mode;
        mNormalModeContainer.setVisibility(mode == MODE_NORMAL ? View.VISIBLE : View.GONE);
        mToolTrayContainer.setVisibility(mode == MODE_TOOL_TRAY ? View.VISIBLE : View.GONE);
    }

    public boolean isToolTrayOpen() {
        return mCurrentMode == MODE_TOOL_TRAY;
    }

    public void closeToolTray() {
        if (mCurrentMode != MODE_NORMAL) {
            setMode(MODE_NORMAL);
        }
    }

    public void setSuggestions(List<CharSequence> suggestions, int boldIndex) {
        if (isExternalViewActive()) {
            setExternalView(null);
        }
        clearSuggestions();
        if (suggestions == null || suggestions.isEmpty()) {
            return;
        }
        dispatchSuggestions(suggestions, boldIndex);
    }

    private void clearSuggestions() {
        resetSlot(mLeftSlot);
        mDivider1.setVisibility(View.INVISIBLE);
        resetSlot(mCenterSlot);
        mDivider2.setVisibility(View.INVISIBLE);
        resetSlot(mRightSlot);

        if (mIsSinglePillMode) {
            mIsSinglePillMode = false;
            restoreSlotLayoutParams(mLeftSlot);
            restoreSlotLayoutParams(mCenterSlot);
            restoreSlotLayoutParams(mRightSlot);
        }
    }

    private void restoreSlotLayoutParams(TextView slot) {
        ViewGroup.LayoutParams lp = slot.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            llp.width = 0;
            llp.weight = 1.0f;
            slot.setLayoutParams(llp);
        } else {
            slot.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f));
        }
    }

    private void dispatchSuggestions(List<CharSequence> suggestions, int boldIndex) {
        final int count = suggestions.size();
        if (count >= 3) {
            renderThreeSuggestions(suggestions, boldIndex);
        } else if (count == 2) {
            renderTwoSuggestions(suggestions, boldIndex);
        } else {
            renderSingleSuggestion(suggestions, boldIndex == 0);
        }
    }

    private void renderThreeSuggestions(List<CharSequence> suggestions, int boldIndex) {
        bindSlot(mLeftSlot, suggestions.get(0), boldIndex == 0);
        mDivider1.setVisibility(View.VISIBLE);
        bindSlot(mCenterSlot, suggestions.get(1), boldIndex == 1);
        mDivider2.setVisibility(View.VISIBLE);
        bindSlot(mRightSlot, suggestions.get(2), boldIndex == 2);
    }

    private void renderTwoSuggestions(List<CharSequence> suggestions, int boldIndex) {
        bindSlot(mLeftSlot, suggestions.get(0), boldIndex == 0);
        mDivider1.setVisibility(View.VISIBLE);
        bindSlot(mCenterSlot, suggestions.get(1), boldIndex == 1);
        mDivider2.setVisibility(View.INVISIBLE);
        bindSlot(mRightSlot, null, false);
    }

    private void renderSingleSuggestion(List<CharSequence> suggestions, boolean isBold) {
        bindSlot(mLeftSlot, null, false);
        mDivider1.setVisibility(View.INVISIBLE);
        bindSlot(mCenterSlot, suggestions.get(0), isBold);
        mDivider2.setVisibility(View.INVISIBLE);
        bindSlot(mRightSlot, null, false);
    }

    private void setupSingleCenterPillMode() {
        mIsSinglePillMode = true;
        mLeftSlot.setVisibility(View.GONE);
        mDivider1.setVisibility(View.GONE);
        mDivider2.setVisibility(View.GONE);
        mRightSlot.setVisibility(View.GONE);

        ViewGroup.LayoutParams lp = mCenterSlot.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            llp.width = LayoutParams.WRAP_CONTENT;
            llp.weight = 0f;
            mCenterSlot.setLayoutParams(llp);
        } else {
            mCenterSlot.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        }
        int paddingH = ViewUtils.dpToPx(getContext(), 12);
        mCenterSlot.setPadding(paddingH, 0, paddingH, 0);
        mCenterSlot.setVisibility(View.VISIBLE);
        mCenterSlot.setTypeface(Typeface.DEFAULT_BOLD);
        mCenterSlot.setAlpha(1.0f);
    }

    public void setClipboardSuggestion(final String fullClipText) {
        if (isExternalViewActive()) {
            setExternalView(null);
        }
        clearSuggestions();
        if (fullClipText == null || fullClipText.trim().isEmpty()) {
            return;
        }

        setupSingleCenterPillMode();

        String cleanText = fullClipText.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleanText.length() > 200) {
            cleanText = cleanText.substring(0, 200) + "...";
        }
        final String displayText = "📋 \"" + cleanText + "\"";

        mCenterSlot.setText(displayText);
        mCenterSlot.setCompoundDrawablesRelative(null, null, null, null);
        mCenterSlot.setCompoundDrawablePadding(0);
        mCenterSlot.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onClipboardSuggestionClicked(fullClipText);
            }
        });
    }

    public void setScreenshotSuggestion(final String imageUri, final android.graphics.Bitmap thumbnail) {
        if (isExternalViewActive()) {
            setExternalView(null);
        }
        clearSuggestions();
        if (imageUri == null || imageUri.trim().isEmpty()) {
            return;
        }

        setupSingleCenterPillMode();

        mCenterSlot.setText(R.string.screenshot);
        if (thumbnail != null) {
            final androidx.core.graphics.drawable.RoundedBitmapDrawable roundedThumb =
                    androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getResources(), thumbnail);
            final int size = ViewUtils.dpToPx(getContext(), 24);
            roundedThumb.setCornerRadius(ViewUtils.dpToPx(getContext(), 4));
            roundedThumb.setBounds(0, 0, size, size);
            mCenterSlot.setCompoundDrawablesRelative(roundedThumb, null, null, null);
            mCenterSlot.setCompoundDrawablePadding(ViewUtils.dpToPx(getContext(), 8));
        } else {
            mCenterSlot.setCompoundDrawablesRelative(null, null, null, null);
            mCenterSlot.setCompoundDrawablePadding(0);
        }
        mCenterSlot.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onScreenshotSuggestionClicked(imageUri);
            }
        });
    }

    private void resetSlot(TextView slot) {
        slot.setText("");
        slot.setCompoundDrawablesRelative(null, null, null, null);
        slot.setCompoundDrawablePadding(0);
        int paddingH = ViewUtils.dpToPx(getContext(), 4);
        slot.setPadding(paddingH, 0, paddingH, 0);
        slot.setTypeface(Typeface.DEFAULT);
        slot.setAlpha(0.85f);
        slot.setVisibility(View.INVISIBLE);
        slot.setOnClickListener(mSlotClickListener);
        slot.setOnLongClickListener(mSlotLongClickListener);
    }

    private void applySlotStyle(TextView slot, boolean isHighlighted) {
        slot.setTypeface(isHighlighted ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        slot.setAlpha(isHighlighted ? 1.0f : 0.85f);
    }

    private void handleSuggestionClick(CharSequence text) {
        if (mListener != null) {
            mListener.onSuggestionClicked(StringUtils.stripEnclosingQuotes(text));
        }
    }

    private void bindSlot(TextView slot, final CharSequence text, boolean isHighlighted) {
        if (TextUtils.isEmpty(text)) {
            resetSlot(slot);
            return;
        }

        slot.setText(text);
        slot.setVisibility(View.VISIBLE);
        applySlotStyle(slot, isHighlighted);
    }
    
    public void setExternalView(View view) {
        if (view == null) {
            if (mIsExternalActive) {
                Log.i(TAG, "setExternalView(null) called, clearing external view.");
                mIsExternalActive = false;
                mSuggestionsContainer.removeAllViews();
                restoreStandardSlots();
            }
        } else {
            Log.i(TAG, "setExternalView(View) called, setting external view.");
            mIsExternalActive = true;
            mSuggestionsContainer.removeAllViews();
            mSuggestionsContainer.addView(view, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    private void restoreStandardSlots() {
        if (mSuggestionsContainer == null) {
            return;
        }
        if (mLeftSlot != null && mLeftSlot.getParent() == null) {
            mSuggestionsContainer.addView(mLeftSlot);
        }
        if (mDivider1 != null && mDivider1.getParent() == null) {
            mSuggestionsContainer.addView(mDivider1);
        }
        if (mCenterSlot != null && mCenterSlot.getParent() == null) {
            mSuggestionsContainer.addView(mCenterSlot);
        }
        if (mDivider2 != null && mDivider2.getParent() == null) {
            mSuggestionsContainer.addView(mDivider2);
        }
        if (mRightSlot != null && mRightSlot.getParent() == null) {
            mSuggestionsContainer.addView(mRightSlot);
        }
    }

    public boolean isExternalViewActive() {
        return mIsExternalActive;
    }

    public void setLanguageButtonVisible(boolean visible) {
        if (mLanguageButton != null) {
            mLanguageButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = ViewUtils.dpToPx(getContext(), 38);
        int spec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, spec);
    }
}

