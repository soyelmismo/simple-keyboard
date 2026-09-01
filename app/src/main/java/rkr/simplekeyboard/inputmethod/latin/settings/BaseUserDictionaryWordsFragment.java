/*
 * Copyright (C) 2026 Simple Keyboard Authors
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

package rkr.simplekeyboard.inputmethod.latin.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.dict.user.UserDictionaryEntry;

public abstract class BaseUserDictionaryWordsFragment extends Fragment implements MenuProvider {
    private static final String TAG = BaseUserDictionaryWordsFragment.class.getSimpleName();

    private EditText mSearchEditText;
    private ImageButton mClearSearchButton;
    private RecyclerView mRecyclerView;
    private TextView mEmptyView;
    private ExtendedFloatingActionButton mAddFab;
    private WordsAdapter mAdapter;
    private String mCurrentQuery = "";
    private OnBackPressedCallback mBackCallback;

    @StringRes protected abstract int getEmptyTextResId();
    @StringRes protected abstract int getFabTextResId();
    @StringRes protected abstract int getFabDialogTitleResId();
    @StringRes protected abstract int getFabDialogHintResId();
    @StringRes protected abstract int getDeleteSingleDialogTitleResId();
    @StringRes protected abstract int getDeleteSingleDialogMessageResId();
    @StringRes protected abstract int getDeleteSingleButtonResId();
    @StringRes protected abstract int getDeleteBatchDialogTitleResId();
    @StringRes protected abstract int getDeleteBatchDialogMessageResId();
    @StringRes protected abstract int getDeleteBatchButtonResId();
    @StringRes protected abstract int getSingleDeletedToastResId();
    @StringRes protected abstract int getBatchDeletedToastResId();

    protected abstract List<UserDictionaryEntry> queryEntries(@NonNull Context context, @Nullable String query);
    protected abstract boolean onAddWord(@NonNull Context context, @NonNull String word);
    protected abstract void onRemoveSingleEntry(@NonNull Context context, @NonNull UserDictionaryEntry entry);
    protected abstract void onRemoveBatchEntries(@NonNull Context context, @NonNull List<UserDictionaryEntry> entries);

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_user_dictionary_words, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        mSearchEditText = view.findViewById(R.id.search_edit_text);
        mClearSearchButton = view.findViewById(R.id.clear_search_button);
        mRecyclerView = view.findViewById(R.id.words_recycler_view);
        mEmptyView = view.findViewById(R.id.empty_view);
        mAddFab = view.findViewById(R.id.add_word_fab);

        mAddFab.setText(getFabTextResId());
        mAddFab.setOnClickListener(v -> showAddWordDialog());

        mEmptyView.setText(getEmptyTextResId());

        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new WordsAdapter(
                this::onWordSingleClicked,
                this::onWordLongClicked,
                this::onSelectionChanged
        );
        mRecyclerView.setAdapter(mAdapter);

        mSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {}

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
                if (mAdapter != null && mAdapter.isSelectionMode()) {
                    exitSelectionMode();
                }
                mCurrentQuery = s != null ? s.toString().trim() : "";
                if (mClearSearchButton != null) {
                    mClearSearchButton.setVisibility(mCurrentQuery.isEmpty() ? View.GONE : View.VISIBLE);
                }
                loadWords();
            }

            @Override
            public void afterTextChanged(final Editable s) {}
        });

        mClearSearchButton.setOnClickListener(v -> {
            if (mSearchEditText != null) {
                mSearchEditText.setText("");
            }
        });

        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (mAdapter != null && mAdapter.isSelectionMode()) {
                    exitSelectionMode();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mBackCallback);

        loadWords();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadWords();
    }

    protected void loadWords() {
        final Context context = getContext();
        if (context == null) {
            Log.w(TAG, "loadWords: context is null");
            return;
        }
        final List<UserDictionaryEntry> words = queryEntries(context, mCurrentQuery);
        if (mAdapter != null) {
            mAdapter.setWords(words);
        }
        updateEmptyView(words.isEmpty());
    }

    private void updateEmptyView(final boolean isEmpty) {
        if (mEmptyView != null) {
            mEmptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }
    }

    private void onWordSingleClicked(final UserDictionaryEntry entry) {
        final Context context = getContext();
        if (context == null) {
            Log.w(TAG, "onWordSingleClicked: context is null");
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(getDeleteSingleDialogTitleResId())
                .setMessage(getString(getDeleteSingleDialogMessageResId(), entry.word))
                .setPositiveButton(getDeleteSingleButtonResId(), (dialog, which) -> {
                    onRemoveSingleEntry(context, entry);
                    Toast.makeText(context, getSingleDeletedToastResId(), Toast.LENGTH_SHORT).show();
                    loadWords();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onWordLongClicked(final UserDictionaryEntry entry) {
        if (mAdapter != null) {
            mAdapter.setSelectionMode(true);
            mAdapter.toggleSelection(entry.id);
        }
    }

    private void onSelectionChanged() {
        if (mAdapter == null) {
            Log.w(TAG, "onSelectionChanged: mAdapter is null");
            return;
        }
        final boolean inSelection = mAdapter.isSelectionMode();
        if (mBackCallback != null) {
            mBackCallback.setEnabled(inSelection);
        }
        if (mAddFab != null) {
            if (inSelection) {
                mAddFab.hide();
            } else {
                mAddFab.show();
            }
        }
        requireActivity().invalidateOptionsMenu();
    }

    protected void exitSelectionMode() {
        if (mAdapter != null) {
            mAdapter.clearSelection();
            mAdapter.setSelectionMode(false);
        }
        if (mBackCallback != null) {
            mBackCallback.setEnabled(false);
        }
        if (mAddFab != null) {
            mAddFab.show();
        }
        requireActivity().invalidateOptionsMenu();
    }

    private void showAddWordDialog() {
        final Context context = requireContext();
        final FrameLayout container = new FrameLayout(context);
        final EditText input = new EditText(context);
        input.setHint(getFabDialogHintResId());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        final int paddingPx = (int) (16 * getResources().getDisplayMetrics().density);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.leftMargin = paddingPx;
        lp.rightMargin = paddingPx;
        input.setLayoutParams(lp);
        container.addView(input);

        new MaterialAlertDialogBuilder(context)
                .setTitle(getFabDialogTitleResId())
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String word = input.getText().toString().trim();
                    if (!word.isEmpty()) {
                        final boolean added = onAddWord(context, word);
                        if (added) {
                            Toast.makeText(context, R.string.word_added, Toast.LENGTH_SHORT).show();
                            loadWords();
                        } else {
                            Toast.makeText(context, R.string.word_already_exists, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeleteSelectedDialog() {
        final Context context = getContext();
        if (context == null) {
            Log.w(TAG, "showDeleteSelectedDialog: context is null");
            return;
        }
        if (mAdapter == null) {
            Log.w(TAG, "showDeleteSelectedDialog: mAdapter is null");
            return;
        }
        final int count = mAdapter.getSelectedCount();
        if (count == 0) {
            Log.w(TAG, "showDeleteSelectedDialog: selected count is 0");
            return;
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(getDeleteBatchDialogTitleResId())
                .setMessage(getString(getDeleteBatchDialogMessageResId(), count))
                .setPositiveButton(getDeleteBatchButtonResId(), (dialog, which) -> {
                    final List<UserDictionaryEntry> selected = mAdapter.getSelectedEntries();
                    onRemoveBatchEntries(context, selected);
                    Toast.makeText(context, getString(getBatchDeletedToastResId(), selected.size()), Toast.LENGTH_SHORT).show();
                    exitSelectionMode();
                    loadWords();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onCreateMenu(@NonNull final Menu menu, @NonNull final MenuInflater menuInflater) {
        if (mAdapter != null && mAdapter.isSelectionMode()) {
            final boolean allSelected = mAdapter.isAllSelected();
            final MenuItem selectAllItem = menu.add(Menu.NONE, R.id.action_select_all, Menu.NONE,
                    allSelected ? R.string.deselect_all : R.string.select_all);
            selectAllItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

            final MenuItem deleteSelectedItem = menu.add(Menu.NONE, R.id.action_delete_selected, Menu.NONE, getDeleteBatchButtonResId());
            deleteSelectedItem.setIcon(R.drawable.ic_delete);
            deleteSelectedItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull final MenuItem menuItem) {
        final int id = menuItem.getItemId();
        if (id == R.id.action_select_all) {
            if (mAdapter != null) {
                mAdapter.toggleSelectAll();
            }
            return true;
        } else if (id == R.id.action_delete_selected) {
            showDeleteSelectedDialog();
            return true;
        } else if (id == android.R.id.home && mAdapter != null && mAdapter.isSelectionMode()) {
            exitSelectionMode();
            return true;
        }
        return false;
    }

    protected static class WordsAdapter extends RecyclerView.Adapter<WordsAdapter.WordViewHolder> {
        interface OnWordClickListener {
            void onWordClick(UserDictionaryEntry entry);
        }

        interface OnWordLongClickListener {
            void onWordLongClick(UserDictionaryEntry entry);
        }

        interface OnSelectionChangedListener {
            void onSelectionChanged();
        }

        private final List<UserDictionaryEntry> mWords = new ArrayList<>();
        private final Set<Long> mSelectedIds = new HashSet<>();
        private boolean mSelectionMode = false;
        private final OnWordClickListener mClickListener;
        private final OnWordLongClickListener mLongClickListener;
        private final OnSelectionChangedListener mSelectionChangedListener;

        WordsAdapter(final OnWordClickListener clickListener,
                     final OnWordLongClickListener longClickListener,
                     final OnSelectionChangedListener selectionChangedListener) {
            mClickListener = clickListener;
            mLongClickListener = longClickListener;
            mSelectionChangedListener = selectionChangedListener;
        }

        void setWords(final List<UserDictionaryEntry> words) {
            mWords.clear();
            if (words != null) {
                mWords.addAll(words);
            }
            if (mSelectionMode) {
                final Set<Long> currentIds = new HashSet<>();
                for (final UserDictionaryEntry entry : mWords) {
                    currentIds.add(entry.id);
                }
                mSelectedIds.retainAll(currentIds);
                if (mSelectedIds.isEmpty() && !mWords.isEmpty()) {
                    mSelectionMode = false;
                    if (mSelectionChangedListener != null) {
                        mSelectionChangedListener.onSelectionChanged();
                    }
                }
            }
            notifyDataSetChanged();
        }

        boolean isSelectionMode() {
            return mSelectionMode;
        }

        void setSelectionMode(final boolean selectionMode) {
            if (mSelectionMode != selectionMode) {
                mSelectionMode = selectionMode;
                if (!selectionMode) {
                    mSelectedIds.clear();
                }
                notifyDataSetChanged();
                if (mSelectionChangedListener != null) {
                    mSelectionChangedListener.onSelectionChanged();
                }
            }
        }

        void toggleSelection(final long id) {
            if (mSelectedIds.contains(id)) {
                mSelectedIds.remove(id);
                if (mSelectedIds.isEmpty()) {
                    setSelectionMode(false);
                    return;
                }
            } else {
                mSelectedIds.add(id);
            }
            notifyDataSetChanged();
            if (mSelectionChangedListener != null) {
                mSelectionChangedListener.onSelectionChanged();
            }
        }

        void toggleSelectAll() {
            if (isAllSelected()) {
                mSelectedIds.clear();
                setSelectionMode(false);
            } else {
                for (final UserDictionaryEntry entry : mWords) {
                    mSelectedIds.add(entry.id);
                }
                notifyDataSetChanged();
                if (mSelectionChangedListener != null) {
                    mSelectionChangedListener.onSelectionChanged();
                }
            }
        }

        boolean isAllSelected() {
            return !mWords.isEmpty() && mSelectedIds.size() == mWords.size();
        }

        int getSelectedCount() {
            return mSelectedIds.size();
        }

        List<UserDictionaryEntry> getSelectedEntries() {
            final List<UserDictionaryEntry> result = new ArrayList<>(mSelectedIds.size());
            for (final UserDictionaryEntry entry : mWords) {
                if (mSelectedIds.contains(entry.id)) {
                    result.add(entry);
                }
            }
            return result;
        }

        void clearSelection() {
            mSelectedIds.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public WordViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            final View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user_dictionary_word, parent, false);
            return new WordViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull final WordViewHolder holder, final int position) {
            final UserDictionaryEntry entry = mWords.get(position);
            holder.wordTextView.setText(entry.word);

            if (mSelectionMode) {
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setChecked(mSelectedIds.contains(entry.id));
                holder.deleteButton.setVisibility(View.GONE);

                holder.itemView.setOnClickListener(v -> toggleSelection(entry.id));
                holder.checkBox.setOnClickListener(v -> toggleSelection(entry.id));
                holder.itemView.setOnLongClickListener(null);
            } else {
                holder.checkBox.setVisibility(View.GONE);
                holder.deleteButton.setVisibility(View.VISIBLE);

                holder.itemView.setOnClickListener(v -> {
                    if (mClickListener != null) {
                        mClickListener.onWordClick(entry);
                    }
                });

                holder.itemView.setOnLongClickListener(v -> {
                    if (mLongClickListener != null) {
                        mLongClickListener.onWordLongClick(entry);
                        return true;
                    }
                    return false;
                });

                holder.deleteButton.setOnClickListener(v -> {
                    if (mClickListener != null) {
                        mClickListener.onWordClick(entry);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return mWords.size();
        }

        static class WordViewHolder extends RecyclerView.ViewHolder {
            final TextView wordTextView;
            final MaterialCheckBox checkBox;
            final View deleteButton;

            WordViewHolder(@NonNull final View itemView) {
                super(itemView);
                wordTextView = itemView.findViewById(R.id.word_text);
                checkBox = itemView.findViewById(R.id.selection_checkbox);
                deleteButton = itemView.findViewById(R.id.action_button);
            }
        }
    }
}
