package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ClipboardInputIsolationAndRaceAndroidTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static class RecordingInputConnection extends InputConnectionWrapper {
        final AtomicInteger commitTextCalls = new AtomicInteger(0);
        final AtomicInteger deleteCalls = new AtomicInteger(0);
        final AtomicInteger sendKeyCalls = new AtomicInteger(0);
        final StringBuilder committedContent = new StringBuilder();

        public RecordingInputConnection(InputConnection target) {
            super(target, true);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            commitTextCalls.incrementAndGet();
            if (text != null) {
                committedContent.append(text);
            }
            return super.commitText(text, newCursorPosition);
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            deleteCalls.incrementAndGet();
            return super.deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            sendKeyCalls.incrementAndGet();
            return super.sendKeyEvent(event);
        }
    }

    @Test
    public void testB1_InputIsolationWithRealInputConnectionPipeline() throws Throwable {
        Intent intent = new Intent(mContext, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SettingsActivity activity = (SettingsActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        assertNotNull("SettingsActivity must launch", activity);

        final EditText[] editTextHolder = new EditText[1];
        final RecordingInputConnection[] recConnHolder = new RecordingInputConnection[1];
        final ClipboardHistoryView[] viewHolder = new ClipboardHistoryView[1];
        final String initialBaseline = "BASELINE_RECEIVER_123";

        final ClipboardDatabase db = new ClipboardDatabase(mContext);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText testEditText = new EditText(activity) {
                @Override
                public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                    InputConnection base = super.onCreateInputConnection(outAttrs);
                    RecordingInputConnection rec = new RecordingInputConnection(base);
                    recConnHolder[0] = rec;
                    return rec;
                }
            };
            testEditText.setText(initialBaseline);
            activity.setContentView(testEditText);
            testEditText.requestFocus();
            editTextHolder[0] = testEditText;

            // Initialize real ClipboardHistoryView
            ClipboardHistoryView historyView = new ClipboardHistoryView(activity);
            historyView.setDatabase(db);
            viewHolder[0] = historyView;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        EditText receiverEditText = editTextHolder[0];
        assertNotNull(receiverEditText);

        // Force creation of input connection
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            receiverEditText.onCreateInputConnection(new EditorInfo());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        RecordingInputConnection recConn = recConnHolder[0];
        assertNotNull("RecordingInputConnection must be initialized", recConn);

        ClipboardHistoryView historyView = viewHolder[0];
        assertNotNull(historyView);

        // 1. Enter Search Mode
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.startSearch();
        });
        assertTrue("Search mode must be active", historyView.isSearchActive());

        // 2. Simulate 500 cycles of typing/deleting during search mode
        String[] searchKeystrokes = {"a", "b", "c", "1", "2", "3", " ", "!", "%", "está", "🔥"};
        for (int cycle = 0; cycle < 500; cycle++) {
            final String text = searchKeystrokes[cycle % searchKeystrokes.length];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (historyView.isSearchActive()) {
                    // Simulating the LatinIME dispatch path:
                    historyView.appendSearchText(text);
                } else {
                    recConn.commitText(text, 1);
                }
            });
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        // INVARIANT 1: Zero calls reached the receiver's InputConnection during search
        assertEquals(0, recConn.commitTextCalls.get());
        assertEquals(0, recConn.deleteCalls.get());
        assertEquals(initialBaseline, receiverEditText.getText().toString());

        // 3. Exit Search Mode
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.closeSearch();
        });
        assertFalse("Search mode must be closed", historyView.isSearchActive());

        // 4. Normal input post-search: now keystrokes must reach InputConnection
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            recConn.commitText("_NORMAL_INPUT", 1);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        // INVARIANT 2: Normal input is immediately restored
        assertEquals(1, recConn.commitTextCalls.get());
        assertEquals(initialBaseline + "_NORMAL_INPUT", receiverEditText.getText().toString());

        db.close();
        activity.finish();
    }

    @Test
    public void testB2_PhysicalKeysOnControlledReceiverActivity() throws Throwable {
        Intent intent = new Intent(mContext, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SettingsActivity activity = (SettingsActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        final EditText[] editTextHolder = new EditText[1];
        final String initialBaseline = "BASELINE_RECEIVER_123";

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText testEditText = new EditText(activity);
            testEditText.setText(initialBaseline);
            activity.setContentView(testEditText);
            testEditText.requestFocus();
            editTextHolder[0] = testEditText;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        EditText receiverEditText = editTextHolder[0];
        assertNotNull(receiverEditText);
        assertEquals(initialBaseline, receiverEditText.getText().toString());

        // Simulate physical key events (Escape, Tab, Meta keys)
        int[] keyCodes = {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT
        };

        for (int code : keyCodes) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                receiverEditText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                receiverEditText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            });
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        // Baseline text in receiver must not be corrupted
        assertEquals(initialBaseline, receiverEditText.getText().toString());
        activity.finish();
    }

    @Test
    public void testB3_RealClipboardHistoryViewSearchRaceAndTokens() throws Throwable {
        Intent intent = new Intent(mContext, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SettingsActivity activity = (SettingsActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        final ClipboardDatabase db = new ClipboardDatabase(mContext);
        db.getWritableDatabase().delete("clips", null, null);
        // Prepare database with test clips
        db.insertClip("alpha_clip_target", false);
        db.insertClip("beta_clip_item", false);
        db.insertClip("gamma_clip_note", false);
        db.insertClip("delta_clip_text", false);

        final ClipboardHistoryView[] viewHolder = new ClipboardHistoryView[1];
        final AtomicBoolean searchStateReported = new AtomicBoolean(false);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ClipboardHistoryView historyView = new ClipboardHistoryView(activity);
            historyView.setDatabase(db);
            historyView.setListener(new ClipboardHistoryView.ClipboardHistoryListener() {
                @Override
                public void onPasteText(CharSequence text) {}
                @Override
                public void onPasteImage(String imageUri) {}
                @Override
                public void onCloseClipboard() {}
                @Override
                public void onSearchStateChanged(boolean isSearching) {
                    searchStateReported.set(isSearching);
                }
            });
            // Attach view to activity window so view.post() functions normally
            activity.setContentView(historyView);
            viewHolder[0] = historyView;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        final ClipboardHistoryView historyView = viewHolder[0];
        assertNotNull(historyView);

        // 1. Start Search
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.startSearch();
        });
        assertTrue("isSearchActive must be true after startSearch", historyView.isSearchActive());
        assertTrue("Listener must report search active", searchStateReported.get());

        // 2. Rapid query burst with rapid mutations: "a" -> "alpha"
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.appendSearchText("a");
            historyView.appendSearchText("lpha");
        });

        // Wait for executor and UI handler to process
        Thread.sleep(200);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        // Check child count in real CardsContainer
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup contentContainer = (ViewGroup) historyView.getChildAt(2); // Header, Divider, ContentContainer
            ScrollView scrollView = (ScrollView) contentContainer.getChildAt(0);
            LinearLayout cardsContainer = (LinearLayout) scrollView.getChildAt(0);

            // Exactly 1 card for "alpha_clip_target"
            assertEquals(1, cardsContainer.getChildCount());
        });

        // 3. Rapid mutation: clear and query "gamma"
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int i = 0; i < 5; i++) {
                historyView.deleteSearchChar();
            }
            historyView.appendSearchText("gamma");
        });

        Thread.sleep(200);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup contentContainer = (ViewGroup) historyView.getChildAt(2);
            ScrollView scrollView = (ScrollView) contentContainer.getChildAt(0);
            LinearLayout cardsContainer = (LinearLayout) scrollView.getChildAt(0);

            assertEquals(1, cardsContainer.getChildCount());
        });

        // 4. Close Search
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.closeSearch();
        });
        assertFalse("isSearchActive must be false after closeSearch", historyView.isSearchActive());

        Thread.sleep(200);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        // All 4 clips must be reloaded
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup contentContainer = (ViewGroup) historyView.getChildAt(2);
            ScrollView scrollView = (ScrollView) contentContainer.getChildAt(0);
            LinearLayout cardsContainer = (LinearLayout) scrollView.getChildAt(0);

            assertEquals(4, cardsContainer.getChildCount());
        });

        db.close();
        activity.finish();
    }
}