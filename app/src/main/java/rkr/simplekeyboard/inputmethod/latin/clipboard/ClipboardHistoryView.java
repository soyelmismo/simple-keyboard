package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.Context;
import android.content.res.ColorStateList;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewUtils;

public class ClipboardHistoryView extends LinearLayout {

    public interface ClipboardHistoryListener {
        void onPasteText(CharSequence text);
        void onPasteImage(String imageUri);
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

        Drawable bg = ViewUtils.getThemeDrawable(context, R.attr.keyboardViewStyle, R.style.KeyboardView, android.R.attr.background);
        if (bg != null) {
            setBackground(bg);
        }

        loadThemeColors(context);
        buildHeader(context);
        buildDivider(context);
        buildContentArea(context);
    }

    private void loadThemeColors(Context context) {
        mTextColor = ViewUtils.getThemeColor(context, R.attr.keyTextColor, Color.WHITE);
        mFunctionalTextColor = ViewUtils.getThemeColor(context, R.attr.functionalTextColor, mTextColor);
        mCardBackgroundColor = ViewUtils.getThemeColor(context, R.attr.keyNormalBackgroundColor, 0);
        mCardPressedColor = ViewUtils.getThemeColor(context, R.attr.keyPressedBackgroundColor, 0);
    }

    private void buildHeader(Context context) {
        mHeaderLayout = new LinearLayout(context);
        mHeaderLayout.setOrientation(HORIZONTAL);
        mHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        int headerHeight = dpToPx(44);
        mHeaderLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));

        mCloseButton = ViewUtils.createSquareIconButton(context, R.drawable.ic_close_vector, dpToPx(44), dpToPx(10), mFunctionalTextColor, true);
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

        mClearButton = ViewUtils.createSquareIconButton(context, R.drawable.ic_delete, dpToPx(44), dpToPx(10), mFunctionalTextColor, true);
        mClearButton.setContentDescription(context.getString(R.string.clipboard_clear_all));
        mClearButton.setOnClickListener(v -> {
            if (mDatabase != null) {
                mAsyncExecutor.execute(() -> {
                    mDatabase.clearUnpinned();
                    clearSystemClipboardIfMatches(null);
                    final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips();
                    post(() -> displayClips(updatedClips));
                });
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
        final long retentionMinutes = rkr.simplekeyboard.inputmethod.latin.settings.Settings.readClipboardRetentionMinutes(prefs);

        mAsyncExecutor.execute(() -> {
            mDatabase.deleteExpiredClips(retentionMinutes);
            final List<ClipboardHistoryEntry> clips = mDatabase.getClips();
            post(() -> displayClips(clips));
        });
    }

    private void displayClips(List<ClipboardHistoryEntry> clips) {
        mCardsContainer.removeAllViews();

        if (clips == null || clips.isEmpty()) {
            showEmptyState();
            return;
        }
        populateCards(clips);
    }

    private void showEmptyState() {
        mEmptyView.setVisibility(View.VISIBLE);
        mScrollView.setVisibility(View.GONE);
        mClearButton.setVisibility(View.GONE);
    }

    private boolean hasUnpinnedClips(List<ClipboardHistoryEntry> clips) {
        for (ClipboardHistoryEntry clip : clips) {
            if (!clip.isPinned) {
                return true;
            }
        }
        return false;
    }

    private void populateCards(List<ClipboardHistoryEntry> clips) {
        mEmptyView.setVisibility(View.GONE);
        mScrollView.setVisibility(View.VISIBLE);
        mClearButton.setVisibility(hasUnpinnedClips(clips) ? View.VISIBLE : View.GONE);

        for (ClipboardHistoryEntry clip : clips) {
            mCardsContainer.addView(createCardView(clip));
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

        card.addView(createCardTextContainer(context, entry));
        card.addView(createCardActionsLayout(context, entry));
        return card;
    }

    private View createCardTextContainer(Context context, final ClipboardHistoryEntry entry) {
        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(HORIZONTAL);
        textContainer.setGravity(Gravity.CENTER_VERTICAL);
        textContainer.setClickable(true);
        textContainer.setFocusable(false);
        textContainer.setPadding(dpToPx(12), dpToPx(8), dpToPx(8), dpToPx(8));
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        if (entry.uri != null && !entry.uri.isEmpty()) {
            ImageView imageView = new ImageView(context);
            int imgSize = dpToPx(48);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
            imgLp.setMargins(0, 0, dpToPx(10), 0);
            imageView.setLayoutParams(imgLp);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

            java.io.File imgFile = new java.io.File(entry.uri);
            if (imgFile.exists()) {
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = 4;
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(imgFile.getAbsolutePath(), options);
                if (bm != null) {
                    imageView.setImageBitmap(bm);
                } else {
                    imageView.setImageResource(R.drawable.sym_keyboard_paste);
                }
            } else {
                imageView.setImageResource(R.drawable.sym_keyboard_paste);
            }
            textContainer.addView(imageView);
        }

        TextView textView = new TextView(context);
        textView.setText(entry.text != null ? entry.text : "[Screenshot]");
        textView.setTextColor(mTextColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textView.setMaxLines(4);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textContainer.addView(textView);

        textContainer.setOnClickListener(v -> {
            if (mListener != null) {
                if (entry.uri != null && !entry.uri.isEmpty()) {
                    mListener.onPasteImage(entry.uri);
                } else {
                    mListener.onPasteText(entry.text);
                }
            }
        });
        return textContainer;
    }

    private View createCardActionsLayout(Context context, final ClipboardHistoryEntry entry) {
        LinearLayout actionsLayout = new LinearLayout(context);
        actionsLayout.setOrientation(HORIZONTAL);
        actionsLayout.setGravity(Gravity.CENTER_VERTICAL);
        actionsLayout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        actionsLayout.setPadding(0, 0, dpToPx(4), 0);

        actionsLayout.addView(createPinButton(context, entry));
        actionsLayout.addView(createDeleteButton(context, entry));
        return actionsLayout;
    }

    private ImageView createPinButton(Context context, final ClipboardHistoryEntry entry) {
        int iconRes = entry.isPinned ? R.drawable.ic_pin_filled : R.drawable.ic_pin_outline;
        int descRes = entry.isPinned ? R.string.clipboard_unpin : R.string.clipboard_pin;
        ImageView pinButton = ViewUtils.createSquareIconButton(context, iconRes, dpToPx(36), dpToPx(7), 0, true);
        pinButton.setContentDescription(context.getString(descRes));
        pinButton.setColorFilter(entry.isPinned ? mTextColor : mFunctionalTextColor);
        pinButton.setAlpha(entry.isPinned ? 1.0f : 0.45f);
        pinButton.setOnClickListener(v -> togglePinClip(entry));
        return pinButton;
    }

    private void togglePinClip(ClipboardHistoryEntry entry) {
        if (mDatabase == null) return;
        mAsyncExecutor.execute(() -> {
            mDatabase.setPinned(entry.id, !entry.isPinned);
            final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips();
            post(() -> displayClips(updatedClips));
        });
    }

    private ImageView createDeleteButton(Context context, final ClipboardHistoryEntry entry) {
        ImageView deleteButton = ViewUtils.createSquareIconButton(context, R.drawable.ic_delete, dpToPx(36), dpToPx(7), mFunctionalTextColor, true);
        deleteButton.setContentDescription(context.getString(R.string.clipboard_delete));
        deleteButton.setColorFilter(mFunctionalTextColor);
        deleteButton.setAlpha(0.55f);
        deleteButton.setOnClickListener(v -> deleteClip(entry));
        return deleteButton;
    }

    private void deleteClip(ClipboardHistoryEntry entry) {
        if (mDatabase == null) return;
        mAsyncExecutor.execute(() -> {
            mDatabase.deleteClip(entry.id);
            if (entry.uri != null) {
                try {
                    new java.io.File(entry.uri).delete();
                } catch (Exception ignored) {}
            }
            clearSystemClipboardIfMatches(entry.text);
            final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips();
            post(() -> displayClips(updatedClips));
        });
    }

    private void clearSystemClipboardIfMatches(final String deletedText) {
        try {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            final CharSequence currentSystemText = getPrimaryClipText(cm);
            if (isTextMatching(currentSystemText, deletedText) && cm != null) {
                clearPrimaryClip(cm);
            }
        } catch (Exception ignored) {}
    }

    private CharSequence getPrimaryClipText(final android.content.ClipboardManager cm) {
        if (cm == null || !cm.hasPrimaryClip()) {
            return null;
        }
        final android.content.ClipData primaryClip = cm.getPrimaryClip();
        if (primaryClip == null || primaryClip.getItemCount() == 0) {
            return null;
        }
        return primaryClip.getItemAt(0).getText();
    }

    private boolean isTextMatching(final CharSequence currentText, final String targetText) {
        if (targetText == null) {
            return true;
        }
        return currentText != null && targetText.contentEquals(currentText);
    }

    private void clearPrimaryClip(final android.content.ClipboardManager cm) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            cm.clearPrimaryClip();
        } else {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
        }
    }

    private Drawable getOrCreateCardBackground() {
        if (mCardBgConstantState == null) {
            RippleDrawable rd = buildCardBackgroundDrawable();
            mCardBgConstantState = rd.getConstantState();
            return rd;
        }
        return mCardBgConstantState.newDrawable().mutate();
    }

    private int resolveCardColor() {
        if (mCardBackgroundColor != 0 && mCardBackgroundColor != Color.TRANSPARENT) {
            return mCardBackgroundColor;
        }
        return ResourceUtils.isBrightColor(mTextColor) ? 0x1AFFFFFF : 0x0F000000;
    }

    private int resolveCardPressedColor() {
        if (mCardPressedColor != 0 && mCardPressedColor != Color.TRANSPARENT) {
            return mCardPressedColor;
        }
        return ResourceUtils.isBrightColor(mTextColor) ? 0x33FFFFFF : 0x22000000;
    }

    private RippleDrawable buildCardBackgroundDrawable() {
        GradientDrawable normalDrawable = new GradientDrawable();
        normalDrawable.setShape(GradientDrawable.RECTANGLE);
        normalDrawable.setCornerRadius(dpToPx(8));
        normalDrawable.setColor(resolveCardColor());

        int strokeColor = ResourceUtils.isBrightColor(mTextColor) ? 0x22FFFFFF : 0x18000000;
        normalDrawable.setStroke(dpToPx(1), strokeColor);

        GradientDrawable maskDrawable = new GradientDrawable();
        maskDrawable.setShape(GradientDrawable.RECTANGLE);
        maskDrawable.setCornerRadius(dpToPx(8));
        maskDrawable.setColor(Color.WHITE);

        return new RippleDrawable(ColorStateList.valueOf(resolveCardPressedColor()), normalDrawable, maskDrawable);
    }

    private int dpToPx(int dp) {
        return ViewUtils.dpToPx(getContext(), dp);
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
