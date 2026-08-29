package rkr.simplekeyboard.inputmethod.latin.topbar;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
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
import rkr.simplekeyboard.inputmethod.latin.utils.ViewUtils;

public class TopBarView extends FrameLayout {
    public static final int MODE_NORMAL = 0;
    public static final int MODE_TOOL_TRAY = 1;

    private int mCurrentMode = MODE_NORMAL;

    private LinearLayout mNormalModeContainer;
    private ImageView mExpandButton;
    private LinearLayout mSuggestionsContainer;
    private boolean mIsExternalActive;
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

    private int mTextColor = 0xFFCCCCCC;

    public TopBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public void setListener(TopBarListener listener) {
        mListener = listener;
    }

    private void init(Context context) {
        Drawable bg = ViewUtils.getThemeDrawable(context, R.attr.keyboardViewStyle, R.style.KeyboardView, android.R.attr.background);
        if (bg != null) {
            setBackground(bg);
        }

        mTextColor = ViewUtils.getThemeColor(context, R.attr.keyTextColor, 0xFFCCCCCC);

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
        mSuggestionsContainer.setGravity(Gravity.CENTER_VERTICAL);
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
        int spacerWidth = ViewUtils.dpToPx(context, 18);
        rightSpacer.setLayoutParams(new LinearLayout.LayoutParams(spacerWidth, LayoutParams.MATCH_PARENT));
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
        View divider = new View(context);
        int dividerWidth = ViewUtils.dpToPx(context, 1);
        int dividerHeight = ViewUtils.dpToPx(context, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dividerWidth, dividerHeight);
        lp.gravity = Gravity.CENTER_VERTICAL;
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(mTextColor);
        divider.setAlpha(0.18f);
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
        tv.setFocusable(false);
        tv.setVisibility(View.INVISIBLE);
        ViewUtils.applySelectableItemBackground(tv, false);
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
        if (suggestions == null || suggestions.isEmpty()) {
            clearSuggestions();
            return;
        }
        if (isExternalViewActive()) {
            setExternalView(null);
        }
        dispatchSuggestions(suggestions, boldIndex);
    }

    private void clearSuggestions() {
        resetSlot(mLeftSlot);
        mDivider1.setVisibility(View.INVISIBLE);
        resetSlot(mCenterSlot);
        mDivider2.setVisibility(View.INVISIBLE);
        resetSlot(mRightSlot);
    }

    private void dispatchSuggestions(List<CharSequence> suggestions, int boldIndex) {
        final int count = suggestions.size();
        if (count >= 3) {
            renderThreeSuggestions(suggestions);
        } else if (count == 2) {
            renderTwoSuggestions(suggestions);
        } else {
            renderSingleSuggestion(suggestions, boldIndex == 0);
        }
    }

    private void renderThreeSuggestions(List<CharSequence> suggestions) {
        bindSlot(mLeftSlot, suggestions.get(0), false);
        mDivider1.setVisibility(View.VISIBLE);
        bindSlot(mCenterSlot, suggestions.get(1), true);
        mDivider2.setVisibility(View.VISIBLE);
        bindSlot(mRightSlot, suggestions.get(2), false);
    }

    private void renderTwoSuggestions(List<CharSequence> suggestions) {
        bindSlot(mLeftSlot, suggestions.get(0), false);
        mDivider1.setVisibility(View.VISIBLE);
        bindSlot(mCenterSlot, suggestions.get(1), true);
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

    public void setClipboardSuggestion(final String fullClipText) {
        if (isExternalViewActive()) {
            setExternalView(null);
        }
        if (fullClipText == null || fullClipText.trim().isEmpty()) {
            setSuggestions(null, -1);
            return;
        }

        mLeftSlot.setVisibility(View.GONE);
        mDivider1.setVisibility(View.GONE);
        mDivider2.setVisibility(View.GONE);
        mRightSlot.setVisibility(View.GONE);

        String cleanText = fullClipText.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleanText.length() > 200) {
            cleanText = cleanText.substring(0, 200) + "...";
        }
        final String displayText = "📋 \"" + cleanText + "\"";

        mCenterSlot.setText(displayText);
        mCenterSlot.setCompoundDrawablesRelative(null, null, null, null);
        mCenterSlot.setVisibility(View.VISIBLE);
        mCenterSlot.setTypeface(Typeface.DEFAULT_BOLD);
        mCenterSlot.setAlpha(1.0f);
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
        if (imageUri == null || imageUri.trim().isEmpty()) {
            setSuggestions(null, -1);
            return;
        }

        mLeftSlot.setVisibility(View.GONE);
        mDivider1.setVisibility(View.GONE);
        mDivider2.setVisibility(View.GONE);
        mRightSlot.setVisibility(View.GONE);

        mCenterSlot.setText("🖼️ Screenshot");
        if (thumbnail != null) {
            android.graphics.drawable.BitmapDrawable thumbDrawable = new android.graphics.drawable.BitmapDrawable(getResources(), thumbnail);
            int size = ViewUtils.dpToPx(getContext(), 24);
            thumbDrawable.setBounds(0, 0, size, size);
            mCenterSlot.setCompoundDrawablesRelative(thumbDrawable, null, null, null);
            mCenterSlot.setCompoundDrawablePadding(ViewUtils.dpToPx(getContext(), 6));
        } else {
            mCenterSlot.setCompoundDrawablesRelative(null, null, null, null);
        }
        mCenterSlot.setVisibility(View.VISIBLE);
        mCenterSlot.setTypeface(Typeface.DEFAULT_BOLD);
        mCenterSlot.setAlpha(1.0f);
        mCenterSlot.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onScreenshotSuggestionClicked(imageUri);
            }
        });
    }

    private void resetSlot(TextView slot) {
        slot.setText("");
        slot.setCompoundDrawablesRelative(null, null, null, null);
        slot.setVisibility(View.INVISIBLE);
        slot.setOnClickListener(null);
    }

    private void applySlotStyle(TextView slot, boolean isHighlighted) {
        slot.setTypeface(isHighlighted ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        slot.setAlpha(isHighlighted ? 1.0f : 0.85f);
    }

    private String stripEnclosingQuotes(CharSequence text) {
        String clean = text.toString();
        if (clean.length() > 2 && clean.startsWith("\"") && clean.endsWith("\"")) {
            return clean.substring(1, clean.length() - 1);
        }
        return clean;
    }

    private void handleSuggestionClick(CharSequence text) {
        if (mListener != null) {
            mListener.onSuggestionClicked(stripEnclosingQuotes(text));
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
        slot.setOnClickListener(v -> handleSuggestionClick(text));
    }
    
    public void setExternalView(View view) {
        if (view == null) {
            if (mIsExternalActive) {
                android.util.Log.i("LatinIME", "setExternalView(null) called, clearing external view.");
                mIsExternalActive = false;
                mSuggestionsContainer.removeAllViews();
                // Match LeanType: do NOT add the standard slots back immediately,
                // to avoid changing the layout geometry and breaking the autofill session.
            }
        } else {
            android.util.Log.i("LatinIME", "setExternalView(View) called, setting external view.");
            mIsExternalActive = true;
            mSuggestionsContainer.removeAllViews();
            mSuggestionsContainer.addView(view, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
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

