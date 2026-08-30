package rkr.simplekeyboard.inputmethod.latin.clipboard;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class ClipboardSuggestionTest {

    @Test
    public void testRecentClipValidWithinWindow() {
        long now = System.currentTimeMillis();
        long recentTime = now - (5 * 60 * 1000L); // 5 minutes ago
        long oldTime = now - (20 * 60 * 1000L); // 20 minutes ago

        long timeoutMs = ClipboardHistoryManager.CLIPBOARD_SUGGESTION_TIMEOUT_MS;
        assertTrue(now - recentTime <= timeoutMs);
        assertFalse(now - oldTime <= timeoutMs);
    }

    @Test
    public void testClipboardDisplayTextFormatting() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append("a");
        }
        String longText = sb.toString();
        String cleanText = longText.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleanText.length() > 200) {
            cleanText = cleanText.substring(0, 200) + "...";
        }
        String displayText = "📋 \"" + cleanText + "\"";

        assertTrue(displayText.startsWith("📋 \""));
        assertTrue(displayText.endsWith("...\""));
        assertEquals(200, cleanText.length() - 3);
    }

    @Test
    public void testClipboardMultilineTextFormatting() {
        String multiline = "Hello\nWorld\r\nTest";
        String cleanText = multiline.replace('\n', ' ').replace('\r', ' ').trim();
        assertEquals("Hello World  Test", cleanText);
        String displayText = "📋 \"" + cleanText + "\"";
        assertEquals("📋 \"Hello World  Test\"", displayText);
    }

    @Test
    public void testIsSensitiveClipNullHandling() {
        assertFalse(ClipboardHistoryManager.isSensitiveClip(null));
    }

    @Test
    public void testSettingsReadClipboardRetentionMinutesDefaultOnNull() {
        assertEquals(60, rkr.simplekeyboard.inputmethod.latin.settings.Settings.readClipboardRetentionMinutes(null));
    }

    @Test
    public void testSettingsClampsOutOfBoundValues() {
        android.content.SharedPreferences prefs = (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{android.content.SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("getInt".equals(method.getName())) {
                        throw new ClassCastException();
                    }
                    if ("getString".equals(method.getName())) {
                        return "1440"; // legacy 24h
                    }
                    return null;
                }
        );
        assertEquals(720, rkr.simplekeyboard.inputmethod.latin.settings.Settings.readClipboardRetentionMinutes(prefs));
    }
}
