package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.Context;
import android.content.Intent;
import android.os.Debug;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ClipboardSoakMemoryAndroidTest {

    private static final String TAG = "SOAK_TEST";

    @Test
    public void testD_Soak1000CyclesRealHistoryView() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = new Intent(context, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SettingsActivity activity = (SettingsActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        final ClipboardDatabase db = new ClipboardDatabase(context);
        db.getWritableDatabase().delete("clips", null, null);

        // Populate realistic test database: 20 pinned, 30 unpinned
        for (int i = 0; i < 20; i++) {
            db.insertClip("alpha_beta_gamma_note_" + i, true);
        }
        for (int i = 0; i < 30; i++) {
            db.insertClip("delta_epsilon_zeta_item_" + i, false);
        }

        final ClipboardHistoryView[] viewHolder = new ClipboardHistoryView[1];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ClipboardHistoryView historyView = new ClipboardHistoryView(activity);
            historyView.setDatabase(db);
            activity.setContentView(historyView);
            viewHolder[0] = historyView;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        final ClipboardHistoryView historyView = viewHolder[0];
        assertNotNull(historyView);

        String[] queryMatrix = {
            "alpha", "beta", "gamma", "delta", "epsilon", "zeta",
            "note", "item", "xyz_nonexistent", "", "alpha", "delta"
        };

        // Warm up GC
        Runtime.getRuntime().gc();
        Thread.sleep(100);

        Log.i(TAG, "=== STARTING SOAK TEST (1000 CYCLES) ===");
        Log.i(TAG, String.format("%-8s | %-10s | %-10s | %-10s | %-8s", "Cycle", "Total PSS", "Java Heap", "Native Heap", "Cards"));

        long initialPss = 0;

        for (int cycle = 1; cycle <= 1000; cycle++) {
            final String query = queryMatrix[cycle % queryMatrix.length];

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                historyView.startSearch();
                historyView.appendSearchText(query);
            });

            // Brief yield for async executor
            Thread.sleep(1);

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                historyView.closeSearch();
            });

            // Every 100 cycles, trigger GC, idle UI and record telemetry sample
            if (cycle == 1 || cycle % 100 == 0) {
                Runtime.getRuntime().gc();
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();

                Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
                Debug.getMemoryInfo(memInfo);

                int pss = memInfo.getTotalPss();
                String javaHeap = memInfo.getMemoryStat("summary.java-heap");
                String nativeHeap = memInfo.getMemoryStat("summary.native-heap");

                final int[] cardCountHolder = new int[1];
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    ViewGroup contentContainer = (ViewGroup) historyView.getChildAt(2);
                    ScrollView scrollView = (ScrollView) contentContainer.getChildAt(0);
                    LinearLayout cardsContainer = (LinearLayout) scrollView.getChildAt(0);
                    cardCountHolder[0] = cardsContainer.getChildCount();
                });

                if (cycle == 1) {
                    initialPss = pss;
                }

                Log.i(TAG, String.format("%-8d | %-8d KB | %-8s KB | %-8s KB | %-8d",
                        cycle, pss, javaHeap != null ? javaHeap : "N/A", nativeHeap != null ? nativeHeap : "N/A", cardCountHolder[0]));
            }
        }

        Runtime.getRuntime().gc();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        Debug.MemoryInfo finalMem = new Debug.MemoryInfo();
        Debug.getMemoryInfo(finalMem);
        int finalPss = finalMem.getTotalPss();

        Log.i(TAG, "=== SOAK TEST COMPLETED (1000 CYCLES) ===");
        Log.i(TAG, "Initial PSS: " + initialPss + " KB, Final PSS: " + finalPss + " KB");

        // Verify final UI state is clean (all 50 clips displayed after closeSearch)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup contentContainer = (ViewGroup) historyView.getChildAt(2);
            ScrollView scrollView = (ScrollView) contentContainer.getChildAt(0);
            LinearLayout cardsContainer = (LinearLayout) scrollView.getChildAt(0);
            assertEquals(50, cardsContainer.getChildCount());
        });

        db.close();
        activity.finish();
    }
}