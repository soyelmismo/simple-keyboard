package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;

public class ClipboardHistoryView extends LinearLayout {

    public interface ClipboardHistoryListener {
        void onPasteText(CharSequence text);
        void onCloseClipboard();
    }

    private ClipboardDatabase mDatabase;
    private ClipboardHistoryListener mListener;
    private int mTargetHeight = 0;

    private int mTextColor = Color.WHITE;
    private int mFunctionalTextColor = Color.WHITE;
    private int mCardBackgroundColor = 0;
    private int mCardPressedColor = 0;

    private LinearLayout mHeaderLayout;
    private ImageView mCloseButton;
    private TextView mTitleText;
    private ImageView mClearButton;

    private FrameLayout mContentContainer;
    private ScrollView mScrollView;
    private LinearLayout mCardsContainer;
    private LinearLayout mEmptyView;

    public ClipboardHistoryView(Context context) {
        super(context);
        init(context);
    }

    public ClipboardHistoryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ClipboardHistoryView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void setDatabase(ClipboardDatabase database) {
        mDatabase = database;
    }

    public void setListener(ClipboardHistoryListener listener) {
        mListener = listener;
    }

    public void setTargetHeight(int height) {
        mTargetHeight = height;
        requestLayout();
    }

    private void init(Context context) {
        setOrientation(VERTICAL);

        TypedArray a = context.obtainStyledAttributes(null, new int[]{android.R.attr.background}, R.attr.keyboardViewStyle, R.style.KeyboardView);
        Drawable bg = a.getDrawable(0);
        a.recycle();
        if (bg != null) {
            setBackground(bg);
        }

        TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.keyTextColor, typedValue, true)) {
            mTextColor = typedValue.data;
        }
        if (context.getTheme().resolveAttribute(R.attr.functionalTextColor, typedValue, true)) {
            mFunctionalTextColor = typedValue.data;
        } else {
            mFunctionalTextColor = mTextColor;
        }
        if (context.getTheme().resolveAttribute(R.attr.keyNormalBackgroundColor, typedValue, true)) {
            mCardBackgroundColor = typedValue.data;
        }
        if (context.getTheme().resolveAttribute(R.attr.keyPressedBackgroundColor, typedValue, true)) {
            mCardPressedColor = typedValue.data;
        }

        buildHeader(context);
        buildDivider(context);
        buildContentArea(context);
    }

    private void buildHeader(Context context) {
        mHeaderLayout = new LinearLayout(context);
        mHeaderLayout.setOrientation(HORIZONTAL);
        mHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        int headerHeight = dpToPx(44);
        mHeaderLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));

        mCloseButton = createIconButton(context, R.drawable.ic_close_vector, dpToPx(44), dpToPx(10));
        mCloseButton.setContentDescription(context.getString(android.R.string.cancel));
        mCloseButton.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onCloseClipboard();
            }
        });
        mHeaderLayout.addView(mCloseButton);

        mTitleText = new TextView(context);
        mTitleText.setText(R.string.clipboard);
        mTitleText.setTextColor(mTextColor);
        mTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        mTitleText.setGravity(Gravity.CENTER_VERTICAL);
        mTitleText.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f);
        mTitleText.setLayoutParams(titleLp);
        mHeaderLayout.addView(mTitleText);

        mClearButton = createIconButton(context, R.drawable.ic_delete, dpToPx(44), dpToPx(10));
        mClearButton.setContentDescription(context.getString(R.string.clipboard_clear_all));
        mClearButton.setOnClickListener(v -> {
            if (mDatabase != null) {
                mDatabase.clearUnpinned();
                reloadClips();
            }
        });
        mHeaderLayout.addView(mClearButton);

        addView(mHeaderLayout);
    }

    private void buildDivider(Context context) {
        View divider = new View(context);
        int dividerColor = ResourceUtils.isBrightColor(mTextColor) ? 0x22FFFFFF : 0x18000000;
        divider.setBackgroundColor(dividerColor);
        divider.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(1)));
        addView(divider);
    }

    private void buildContentArea(Context context) {
        mContentContainer = new FrameLayout(context);
        mContentContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Scrollable list
        mScrollView = new ScrollView(context);
        mScrollView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mScrollView.setFillViewport(true);
        mScrollView.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);

        mCardsContainer = new LinearLayout(context);
        mCardsContainer.setOrientation(VERTICAL);
        mCardsContainer.setLayoutParams(new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        mCardsContainer.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        mScrollView.addView(mCardsContainer);
        mContentContainer.addView(mScrollView);

        // Empty view
        mEmptyView = new LinearLayout(context);
        mEmptyView.setOrientation(VERTICAL);
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView emptyIcon = new ImageView(context);
        emptyIcon.setImageResource(R.drawable.sym_keyboard_paste);
        emptyIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        emptyIcon.setColorFilter(mFunctionalTextColor);
        emptyIcon.setAlpha(0.35f);
        emptyIcon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)));
        mEmptyView.addView(emptyIcon);

        TextView emptyTitle = new TextView(context);
        emptyTitle.setText(R.string.clipboard_empty);
        emptyTitle.setTextColor(mTextColor);
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        emptyTitle.setAlpha(0.7f);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dpToPx(8), 0, 0);
        mEmptyView.addView(emptyTitle);

        TextView emptyDesc = new TextView(context);
        emptyDesc.setText(R.string.clipboard_empty_description);
        emptyDesc.setTextColor(mTextColor);
        emptyDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        emptyDesc.setAlpha(0.45f);
        emptyDesc.setGravity(Gravity.CENTER);
        emptyDesc.setPadding(0, dpToPx(4), 0, 0);
        mEmptyView.addView(emptyDesc);

        mContentContainer.addView(mEmptyView);
        addView(mContentContainer);
    }

    private java.util.concurrent.ExecutorService mAsyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Drawable.ConstantState mCardBgConstantState;

    public void deallocateMemory() {
        if (mCardsContainer != null) {
            mCardsContainer.removeAllViews();
        }
    }

    public void reloadClips() {
        if (mDatabase == null) return;
        final Context context = getContext();
        android.content.SharedPreferences prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceSharedPreferences(context);
        String val = prefs.getString(rkr.simplekeyboard.inputmethod.latin.settings.Settings.PREF_CLIPBOARD_RETENTION_TIME, "1440");
        long tempRetention = 1440L;
        try {
            tempRetention = Long.parseLong(val);
        } catch (NumberFormatException ignored) {}
        final long retentionMinutes = tempRetention;

        mAsyncExecutor.execute(() -> {
            mDatabase.deleteExpiredClips(retentionMinutes);
            final List<ClipboardHistoryEntry> clips = mDatabase.getClips();
            post(() -> displayClips(clips));
        });
    }

    private void displayClips(List<ClipboardHistoryEntry> clips) {
        mCardsContainer.removeAllViews();

        if (clips == null || clips.isEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            mScrollView.setVisibility(View.GONE);
            mClearButton.setVisibility(View.GONE);
        } else {
            mEmptyView.setVisibility(View.GONE);
            mScrollView.setVisibility(View.VISIBLE);

            boolean hasUnpinned = false;
            for (ClipboardHistoryEntry clip : clips) {
                if (!clip.isPinned) {
                    hasUnpinned = true;
                    break;
                }
            }
            mClearButton.setVisibility(hasUnpinned ? View.VISIBLE : View.GONE);

            for (ClipboardHistoryEntry clip : clips) {
                mCardsContainer.addView(createCardView(clip));
            }
        }
    }

    private View createCardView(final ClipboardHistoryEntry entry) {
        final Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dpToPx(3), 0, dpToPx(3));
        card.setLayoutParams(cardLp);
        card.setBackground(getOrCreateCardBackground());

        // Left text area
        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(VERTICAL);
        textContainer.setGravity(Gravity.CENTER_VERTICAL);
        textContainer.setClickable(true);
        textContainer.setFocusable(false);
        textContainer.setPadding(dpToPx(12), dpToPx(10), dpToPx(8), dpToPx(10));
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        TextView textView = new TextView(context);
        textView.setText(entry.text);
        textView.setTextColor(mTextColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textView.setMaxLines(4);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textContainer.addView(textView);

        textContainer.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onPasteText(entry.text);
            }
        });
        card.addView(textContainer);

        // Right actions (Pin + Delete)
        LinearLayout actionsLayout = new LinearLayout(context);
        actionsLayout.setOrientation(HORIZONTAL);
        actionsLayout.setGravity(Gravity.CENTER_VERTICAL);
        actionsLayout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        actionsLayout.setPadding(0, 0, dpToPx(4), 0);

        // Pin Button
        ImageView pinButton = createIconButton(context,
                entry.isPinned ? R.drawable.ic_pin_filled : R.drawable.ic_pin_outline,
                dpToPx(36), dpToPx(7));
        pinButton.setContentDescription(context.getString(entry.isPinned ? R.string.clipboard_unpin : R.string.clipboard_pin));
        if (entry.isPinned) {
            pinButton.setColorFilter(mTextColor);
            pinButton.setAlpha(1.0f);
        } else {
            pinButton.setColorFilter(mFunctionalTextColor);
            pinButton.setAlpha(0.45f);
        }
        pinButton.setOnClickListener(v -> {
            if (mDatabase != null) {
                mAsyncExecutor.execute(() -> {
                    mDatabase.setPinned(entry.id, !entry.isPinned);
                    final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips();
                    post(() -> displayClips(updatedClips));
                });
            }
        });
        actionsLayout.addView(pinButton);

        // Delete Button
        ImageView deleteButton = createIconButton(context, R.drawable.ic_delete, dpToPx(36), dpToPx(7));
        deleteButton.setContentDescription(context.getString(R.string.clipboard_delete));
        deleteButton.setColorFilter(mFunctionalTextColor);
        deleteButton.setAlpha(0.55f);
        deleteButton.setOnClickListener(v -> {
            if (mDatabase != null) {
                mAsyncExecutor.execute(() -> {
                    mDatabase.deleteClip(entry.id);
                    final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips();
                    post(() -> displayClips(updatedClips));
                });
            }
        });
        actionsLayout.addView(deleteButton);

        card.addView(actionsLayout);
        return card;
    }

    private Drawable getOrCreateCardBackground() {
        if (mCardBgConstantState == null) {
            GradientDrawable normalDrawable = new GradientDrawable();
            normalDrawable.setShape(GradientDrawable.RECTANGLE);
            normalDrawable.setCornerRadius(dpToPx(8));

            int cardColor = mCardBackgroundColor;
            if (cardColor == 0 || cardColor == Color.TRANSPARENT) {
                if (ResourceUtils.isBrightColor(mTextColor)) {
                    cardColor = 0x1AFFFFFF;
                } else {
                    cardColor = 0x0F000000;
                }
            }
            normalDrawable.setColor(cardColor);

            int strokeColor = ResourceUtils.isBrightColor(mTextColor) ? 0x22FFFFFF : 0x18000000;
            normalDrawable.setStroke(dpToPx(1), strokeColor);

            GradientDrawable maskDrawable = new GradientDrawable();
            maskDrawable.setShape(GradientDrawable.RECTANGLE);
            maskDrawable.setCornerRadius(dpToPx(8));
            maskDrawable.setColor(Color.WHITE);

            int rippleColor = (mCardPressedColor != 0 && mCardPressedColor != Color.TRANSPARENT)
                    ? mCardPressedColor
                    : (ResourceUtils.isBrightColor(mTextColor) ? 0x33FFFFFF : 0x22000000);

            RippleDrawable rd = new RippleDrawable(ColorStateList.valueOf(rippleColor), normalDrawable, maskDrawable);
            mCardBgConstantState = rd.getConstantState();
            return rd;
        }
        return mCardBgConstantState.newDrawable().mutate();
    }

    private ImageView createIconButton(Context context, int drawableResId, int size, int padding) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(drawableResId);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(padding, padding, padding, padding);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setClickable(true);
        iv.setFocusable(false);
        iv.setColorFilter(mFunctionalTextColor);

        TypedValue outValue = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            iv.setBackgroundResource(outValue.resourceId);
        } else if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            iv.setBackgroundResource(outValue.resourceId);
        }
        return iv;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getContext().getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = mTargetHeight;
        if (height <= 0) {
            height = dpToPx(250);
        }
        int exactHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, exactHeightSpec);
    }
}
