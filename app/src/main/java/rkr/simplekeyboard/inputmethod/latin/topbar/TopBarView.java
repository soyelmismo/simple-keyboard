package rkr.simplekeyboard.inputmethod.latin.topbar;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class TopBarView extends FrameLayout {
    public static final int MODE_NORMAL = 0;
    public static final int MODE_TOOL_TRAY = 1;
    public static final int MODE_CLIPBOARD = 2;

    private int mCurrentMode = MODE_NORMAL;

    private LinearLayout mNormalModeContainer;
    private ImageView mExpandButton;
    private HorizontalScrollView mSuggestionsScroll;
    private LinearLayout mSuggestionsContainer;

    private LinearLayout mToolTrayContainer;
    private ImageView mCloseButton;
    private ImageView mClipboardButton;
    private ImageView mSettingsButton;
    private ImageView mLanguageButton;

    private LinearLayout mClipboardStripContainer;
    private ImageView mClipboardBackButton;
    private HorizontalScrollView mClipboardScroll;
    private LinearLayout mClipboardItemsContainer;

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
        android.content.res.TypedArray a = context.obtainStyledAttributes(null, new int[]{android.R.attr.background}, rkr.simplekeyboard.inputmethod.R.attr.keyboardViewStyle, rkr.simplekeyboard.inputmethod.R.style.KeyboardView);
        android.graphics.drawable.Drawable bg = a.getDrawable(0);
        a.recycle();
        if (bg != null) {
            setBackground(bg);
        }

        TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(rkr.simplekeyboard.inputmethod.R.attr.keyTextColor, typedValue, true)) {
            mTextColor = typedValue.data;
        }

        mNormalModeContainer = new LinearLayout(context);
        mNormalModeContainer.setOrientation(LinearLayout.HORIZONTAL);
        mNormalModeContainer.setGravity(Gravity.CENTER_VERTICAL);
        mNormalModeContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        mExpandButton = createIconButton(context, rkr.simplekeyboard.inputmethod.R.drawable.ic_more_horiz);
        mExpandButton.setOnClickListener(v -> setMode(MODE_TOOL_TRAY));
        mNormalModeContainer.addView(mExpandButton);

        mSuggestionsScroll = new HorizontalScrollView(context);
        mSuggestionsScroll.setHorizontalScrollBarEnabled(false);
        mSuggestionsScroll.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f));
        mSuggestionsContainer = new LinearLayout(context);
        mSuggestionsContainer.setOrientation(LinearLayout.HORIZONTAL);
        mSuggestionsContainer.setGravity(Gravity.CENTER_VERTICAL);
        mSuggestionsContainer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSuggestionsScroll.addView(mSuggestionsContainer);
        mNormalModeContainer.addView(mSuggestionsScroll);

        addView(mNormalModeContainer);

        mToolTrayContainer = new LinearLayout(context);
        mToolTrayContainer.setOrientation(LinearLayout.HORIZONTAL);
        mToolTrayContainer.setGravity(Gravity.CENTER_VERTICAL);
        mToolTrayContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        
        mCloseButton = createIconButton(context, rkr.simplekeyboard.inputmethod.R.drawable.ic_close_vector);
        mCloseButton.setOnClickListener(v -> setMode(MODE_NORMAL));
        mToolTrayContainer.addView(mCloseButton);

        mClipboardButton = createIconButton(context, rkr.simplekeyboard.inputmethod.R.drawable.sym_keyboard_paste);
        mClipboardButton.setOnClickListener(v -> setMode(MODE_CLIPBOARD));
        mToolTrayContainer.addView(mClipboardButton);

        mSettingsButton = createIconButton(context, rkr.simplekeyboard.inputmethod.R.drawable.sym_keyboard_settings);
        mSettingsButton.setOnClickListener(v -> {
            if (mListener != null) mListener.onSettingsClicked();
        });
        mToolTrayContainer.addView(mSettingsButton);

        mLanguageButton = createIconButton(context, rkr.simplekeyboard.inputmethod.R.drawable.sym_keyboard_language_switch);
        mLanguageButton.setOnClickListener(v -> {
            if (mListener != null) mListener.onLanguageClicked();
        });
        mToolTrayContainer.addView(mLanguageButton);
        
        addView(mToolTrayContainer);

        mClipboardStripContainer = new LinearLayout(context);
        mClipboardStripContainer.setOrientation(LinearLayout.HORIZONTAL);
        mClipboardStripContainer.setGravity(Gravity.CENTER_VERTICAL);
        mClipboardStripContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mClipboardBackButton = createIconButton(context, rkr.simplekeyboard.inputmethod.R.drawable.ic_close_vector);
        mClipboardBackButton.setOnClickListener(v -> setMode(MODE_NORMAL));
        mClipboardStripContainer.addView(mClipboardBackButton);

        mClipboardScroll = new HorizontalScrollView(context);
        mClipboardScroll.setHorizontalScrollBarEnabled(false);
        mClipboardScroll.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f));
        mClipboardItemsContainer = new LinearLayout(context);
        mClipboardItemsContainer.setOrientation(LinearLayout.HORIZONTAL);
        mClipboardItemsContainer.setGravity(Gravity.CENTER_VERTICAL);
        mClipboardScroll.addView(mClipboardItemsContainer);
        mClipboardStripContainer.addView(mClipboardScroll);

        addView(mClipboardStripContainer);

        setMode(MODE_NORMAL);
    }

    private ImageView createIconButton(Context context, int drawableResId) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(drawableResId);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int paddingH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, context.getResources().getDisplayMetrics());
        iv.setPadding(paddingH, 0, paddingH, 0);
        iv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setClickable(true);
        iv.setFocusable(false);
        TypedValue outValue = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            iv.setBackgroundResource(outValue.resourceId);
        }
        return iv;
    }

    public void setMode(int mode) {
        mCurrentMode = mode;
        mNormalModeContainer.setVisibility(mode == MODE_NORMAL ? View.VISIBLE : View.GONE);
        mToolTrayContainer.setVisibility(mode == MODE_TOOL_TRAY ? View.VISIBLE : View.GONE);
        mClipboardStripContainer.setVisibility(mode == MODE_CLIPBOARD ? View.VISIBLE : View.GONE);

        if (mode == MODE_CLIPBOARD) {
            loadClipboardItems();
        }
    }

    public void setSuggestions(List<CharSequence> suggestions) {
        mSuggestionsContainer.removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) return;
        for (CharSequence suggestion : suggestions) {
            TextView tv = new TextView(getContext());
            tv.setText(suggestion);
            tv.setTextColor(mTextColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setGravity(Gravity.CENTER);
            int paddingH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getContext().getResources().getDisplayMetrics());
            int paddingV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, getContext().getResources().getDisplayMetrics());
            tv.setPadding(paddingH, paddingV, paddingH, paddingV);
            tv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tv.setOnClickListener(v -> {
                if (mListener != null) {
                    mListener.onSuggestionClicked(suggestion);
                }
            });
            mSuggestionsContainer.addView(tv);
        }
    }

    private void loadClipboardItems() {
        mClipboardItemsContainer.removeAllViews();
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    CharSequence text = clipData.getItemAt(i).getText();
                    if (text != null) {
                        addClipboardItem(text);
                    }
                }
            }
        }
    }

    private void addClipboardItem(final CharSequence text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(mTextColor);
        tv.setMaxLines(1);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        int paddingH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getContext().getResources().getDisplayMetrics());
        int paddingV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, getContext().getResources().getDisplayMetrics());
        tv.setPadding(paddingH, paddingV, paddingH, paddingV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(paddingV, 0, paddingV, 0);
        tv.setLayoutParams(lp);
        tv.setBackgroundColor(0x22888888);
        tv.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onClipboardTextClicked(text);
            }
            setMode(MODE_NORMAL);
        });
        mClipboardItemsContainer.addView(tv);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 38, getContext().getResources().getDisplayMetrics());
        int spec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, spec);
    }
}
