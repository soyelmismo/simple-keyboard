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
import rkr.simplekeyboard.inputmethod.compat.BuildCompatUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewUtils;

public class ClipboardHistoryView extends LinearLayout {

    public interface ClipboardHistoryListener {
        void onPasteText(CharSequence text);
        void onPasteImage(String imageUri);
        void onCloseClipboard();
        void onSearchStateChanged(boolean isSearching);
    }

    private ClipboardDatabase mDatabase;
    private ClipboardHistoryListener mListener;
    private int mTargetHeight = 0;

    private int mTextColor = Color.WHITE;
    private int mFunctionalTextColor = Color.WHITE;
    private int mCardBackgroundColor = 0;
    private int mCardPressedColor = 0;

    private LinearLayout mHeaderLayout;
    private LinearLayout mNormalHeaderLayout;
    private ImageView mCloseButton;
    private TextView mTitleText;
    private ImageView mSearchButton;
    private ImageView mClearButton;

    private LinearLayout mSearchHeaderLayout;
    private ImageView mCloseSearchButton;
    private TextView mSearchQueryView;
    private ImageView mSearchClearButton;

    private boolean mIsSearchActive = false;
    private String mSearchQuery = "";
    private long mCurrentQueryToken = 0L;

    private FrameLayout mContentContainer;
    private ScrollView mScrollView;
    private LinearLayout mCardsContainer;
    private LinearLayout mEmptyView;
    private TextView mEmptyTitle;
    private TextView mEmptyDesc;

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

        ViewUtils.applyKeyboardBackground(this);

        loadThemeColors(context);
        buildHeader(context);
        buildDivider(context);
        buildContentArea(context);
    }

    private void loadThemeColors(Context context) {
        mTextColor = ViewUtils.getKeyTextColor(context);
        mFunctionalTextColor = ViewUtils.getFunctionalTextColor(context, mTextColor);
        mCardBackgroundColor = ViewUtils.getThemeColor(context, R.attr.keyNormalBackgroundColor, 0);
        mCardPressedColor = ViewUtils.getThemeColor(context, R.attr.keyPressedBackgroundColor, 0);
    }

    private void buildHeader(Context context) {
        mHeaderLayout = new LinearLayout(context);
        mHeaderLayout.setOrientation(HORIZONTAL);
        mHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        int headerHeight = dpToPx(44);
        mHeaderLayout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));

        // Normal header layout
        mNormalHeaderLayout = new LinearLayout(context);
        mNormalHeaderLayout.setOrientation(HORIZONTAL);
        mNormalHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        mNormalHeaderLayout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mCloseButton = ViewUtils.createSquareIconButton(context, R.drawable.ic_close_vector, dpToPx(44), dpToPx(10), mFunctionalTextColor, true);
        mCloseButton.setContentDescription(context.getString(android.R.string.cancel));
        mCloseButton.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onCloseClipboard();
            }
        });
        mNormalHeaderLayout.addView(mCloseButton);

        mTitleText = new TextView(context);
        mTitleText.setText(R.string.clipboard);
        mTitleText.setTextColor(mTextColor);
        mTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        mTitleText.setGravity(Gravity.CENTER_VERTICAL);
        mTitleText.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f);
        mTitleText.setLayoutParams(titleLp);
        mNormalHeaderLayout.addView(mTitleText);

        mSearchButton = ViewUtils.createSquareIconButton(context, R.drawable.sym_keyboard_search, dpToPx(44), dpToPx(10), mFunctionalTextColor, true);
        mSearchButton.setContentDescription(context.getString(R.string.clipboard_search));
        mSearchButton.setOnClickListener(v -> startSearch());
        mNormalHeaderLayout.addView(mSearchButton);

        mClearButton = ViewUtils.createSquareIconButton(context, R.drawable.ic_delete, dpToPx(44), dpToPx(10), mFunctionalTextColor, true);
        mClearButton.setContentDescription(context.getString(R.string.clipboard_clear_all));
        mClearButton.setOnClickListener(v -> clearUnpinned());
        mNormalHeaderLayout.addView(mClearButton);

        mHeaderLayout.addView(mNormalHeaderLayout);

        // Search header layout
        mSearchHeaderLayout = new LinearLayout(context);
        mSearchHeaderLayout.setOrientation(HORIZONTAL);
        mSearchHeaderLayout.setGravity(Gravity.CENTER_VERTICAL);
        mSearchHeaderLayout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mSearchHeaderLayout.setVisibility(GONE);

        mCloseSearchButton = ViewUtils.createSquareIconButton(context, R.drawable.ic_close_vector, dpToPx(44), dpToPx(10), mFunctionalTextColor, true);
        mCloseSearchButton.setContentDescription(context.getString(android.R.string.cancel));
        mCloseSearchButton.setOnClickListener(v -> closeSearch());
        mSearchHeaderLayout.addView(mCloseSearchButton);

        mSearchQueryView = new TextView(context);
        mSearchQueryView.setHint(R.string.clipboard_search_hint);
        mSearchQueryView.setHintTextColor(ResourceUtils.isBrightColor(mTextColor) ? 0x88FFFFFF : 0x88000000);
        mSearchQueryView.setTextColor(mTextColor);
        mSearchQueryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mSearchQueryView.setSingleLine(true);
        mSearchQueryView.setEllipsize(TextUtils.TruncateAt.END);
        mSearchQueryView.setGravity(Gravity.CENTER_VERTICAL);
        mSearchQueryView.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        LinearLayout.LayoutParams queryLp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f);
        mSearchQueryView.setLayoutParams(queryLp);
        mSearchHeaderLayout.addView(mSearchQueryView);

        mSearchClearButton = ViewUtils.createSquareIconButton(context, R.drawable.sym_keyboard_delete, dpToPx(44), dpToPx(10), mFunctionalTextColor, true);
        mSearchClearButton.setContentDescription(context.getString(R.string.clipboard_delete));
        mSearchClearButton.setVisibility(GONE);
        mSearchClearButton.setOnClickListener(v -> {
            mSearchQuery = "";
            updateSearchTextDisplay();
            reloadSearchClips();
        });
        mSearchHeaderLayout.addView(mSearchClearButton);

        mHeaderLayout.addView(mSearchHeaderLayout);

        addView(mHeaderLayout);
    }

    private void buildDivider(Context context) {
        int dividerColor = ResourceUtils.isBrightColor(mTextColor) ? 0x22FFFFFF : 0x18000000;
        addView(ViewUtils.createHorizontalDivider(context, dividerColor, 1.0f));
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

        mEmptyTitle = new TextView(context);
        mEmptyTitle.setText(R.string.clipboard_empty);
        mEmptyTitle.setTextColor(mTextColor);
        mEmptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mEmptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mEmptyTitle.setAlpha(0.7f);
        mEmptyTitle.setGravity(Gravity.CENTER);
        mEmptyTitle.setPadding(0, dpToPx(8), 0, 0);
        mEmptyView.addView(mEmptyTitle);

        mEmptyDesc = new TextView(context);
        mEmptyDesc.setText(R.string.clipboard_empty_description);
        mEmptyDesc.setTextColor(mTextColor);
        mEmptyDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mEmptyDesc.setAlpha(0.45f);
        mEmptyDesc.setGravity(Gravity.CENTER);
        mEmptyDesc.setPadding(0, dpToPx(4), 0, 0);
        mEmptyView.addView(mEmptyDesc);

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

    public boolean isSearchActive() {
        return mIsSearchActive;
    }

    public void startSearch() {
        mIsSearchActive = true;
        mSearchQuery = "";
        if (mNormalHeaderLayout != null) mNormalHeaderLayout.setVisibility(GONE);
        if (mSearchHeaderLayout != null) mSearchHeaderLayout.setVisibility(VISIBLE);
        updateSearchTextDisplay();
        if (mListener != null) {
            mListener.onSearchStateChanged(true);
        }
        reloadSearchClips();
    }

    public void closeSearch() {
        if (!mIsSearchActive) return;
        mIsSearchActive = false;
        mSearchQuery = "";
        if (mSearchHeaderLayout != null) mSearchHeaderLayout.setVisibility(GONE);
        if (mNormalHeaderLayout != null) mNormalHeaderLayout.setVisibility(VISIBLE);
        if (mListener != null) {
            mListener.onSearchStateChanged(false);
        }
        reloadClips();
    }

    public void closeSearchWithoutReload() {
        mIsSearchActive = false;
        mSearchQuery = "";
        if (mSearchHeaderLayout != null) mSearchHeaderLayout.setVisibility(GONE);
        if (mNormalHeaderLayout != null) mNormalHeaderLayout.setVisibility(VISIBLE);
    }

    public void appendSearchText(final String text) {
        if (!mIsSearchActive || text == null || text.isEmpty()) return;
        mSearchQuery += text;
        updateSearchTextDisplay();
        reloadSearchClips();
    }

    public void deleteSearchChar() {
        if (!mIsSearchActive) return;
        if (mSearchQuery.length() > 0) {
            int lastCodePoint = mSearchQuery.codePointBefore(mSearchQuery.length());
            int charCount = Character.charCount(lastCodePoint);
            mSearchQuery = mSearchQuery.substring(0, mSearchQuery.length() - charCount);
            updateSearchTextDisplay();
            reloadSearchClips();
        }
    }

    private void updateSearchTextDisplay() {
        if (mSearchQueryView == null) return;
        if (mSearchQuery.isEmpty()) {
            mSearchQueryView.setText("");
            mSearchQueryView.setHint(R.string.clipboard_search_hint);
            if (mSearchClearButton != null) mSearchClearButton.setVisibility(GONE);
        } else {
            mSearchQueryView.setText(mSearchQuery);
            if (mSearchClearButton != null) mSearchClearButton.setVisibility(VISIBLE);
        }
    }

    private void reloadSearchClips() {
        if (mDatabase == null) return;
        final long token = ++mCurrentQueryToken;
        final String query = mSearchQuery;
        mAsyncExecutor.execute(() -> {
            final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips(query);
            post(() -> {
                if (token == mCurrentQueryToken) {
                    displayClips(updatedClips);
                }
            });
        });
    }

    private void executeDbTaskAndReload(final Runnable dbTask) {
        if (mDatabase == null) return;
        final long token = ++mCurrentQueryToken;
        final String query = mIsSearchActive ? mSearchQuery : null;
        mAsyncExecutor.execute(() -> {
            if (dbTask != null) {
                dbTask.run();
            }
            final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips(query);
            post(() -> {
                if (token == mCurrentQueryToken) {
                    displayClips(updatedClips);
                }
            });
        });
    }

    private void clearUnpinned() {
        executeDbTaskAndReload(() -> {
            mDatabase.clearUnpinned();
            clearSystemClipboardIfMatches(null);
        });
    }

    public void reloadClips() {
        final Context context = getContext();
        android.content.SharedPreferences prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceSharedPreferences(context);
        final long retentionMinutes = rkr.simplekeyboard.inputmethod.latin.settings.Settings.readClipboardRetentionMinutes(prefs);

        executeDbTaskAndReload(() -> mDatabase.deleteExpiredClips(retentionMinutes));
    }

    private void displayClips(List<ClipboardHistoryEntry> clips) {
        mCardsContainer.removeAllViews();

        if (clips == null || clips.isEmpty()) {
            if (mEmptyTitle != null) {
                mEmptyTitle.setText(mIsSearchActive ? R.string.clipboard_search_empty : R.string.clipboard_empty);
            }
            if (mEmptyDesc != null) {
                mEmptyDesc.setText(mIsSearchActive ? "" : getContext().getString(R.string.clipboard_empty_description));
                mEmptyDesc.setVisibility(mIsSearchActive ? GONE : VISIBLE);
            }
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
            final ImageView imageView = new ImageView(context);
            final int imgSize = dpToPx(48);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(imgSize, imgSize);
            imgLp.setMargins(0, 0, dpToPx(10), 0);
            imageView.setLayoutParams(imgLp);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setImageResource(R.drawable.sym_keyboard_paste);

            final String targetTag = entry.id + ":" + entry.uri;
            imageView.setTag(targetTag);

            final java.lang.ref.WeakReference<ImageView> imageViewRef = new java.lang.ref.WeakReference<>(imageView);
            final String imagePath = entry.uri;
            mAsyncExecutor.execute(() -> {
                final android.graphics.Bitmap bm = decodeSampledBitmapFromFile(imagePath, imgSize, imgSize);
                if (bm != null) {
                    post(() -> {
                        final ImageView iv = imageViewRef.get();
                        if (iv != null && targetTag.equals(iv.getTag())) {
                            iv.setImageBitmap(bm);
                        }
                    });
                }
            });
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

    private void togglePinClip(final ClipboardHistoryEntry entry) {
        if (mDatabase == null) return;
        if (!entry.isPinned) {
            mAsyncExecutor.execute(() -> {
                final boolean success = mDatabase.setPinned(entry.id, true);
                if (!success) {
                    post(() -> android.widget.Toast.makeText(getContext(), R.string.clipboard_pin_limit_reached, android.widget.Toast.LENGTH_SHORT).show());
                } else {
                    final long token = ++mCurrentQueryToken;
                    final String query = mIsSearchActive ? mSearchQuery : null;
                    final List<ClipboardHistoryEntry> updatedClips = mDatabase.getClips(query);
                    post(() -> {
                        if (token == mCurrentQueryToken) {
                            displayClips(updatedClips);
                        }
                    });
                }
            });
        } else {
            executeDbTaskAndReload(() -> mDatabase.setPinned(entry.id, false));
        }
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
        executeDbTaskAndReload(() -> {
            mDatabase.deleteClip(entry.id);
            clearSystemClipboardIfMatches(entry.text);
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
        if (BuildCompatUtils.isAtLeastP()) {
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

    private static android.graphics.Bitmap decodeSampledBitmapFromFile(String path, int reqWidth, int reqHeight) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            return null;
        }
        try {
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null;
            }
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            return android.graphics.BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int calculateInSampleSize(android.graphics.BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
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
