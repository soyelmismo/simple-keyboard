package rkr.simplekeyboard.inputmethod.latin.settings;

import android.content.SharedPreferences;
import android.content.res.Resources;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class AdvancedSettingsTest {

    private SharedPreferences createFakePreferences(final Map<String, Object> values) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getInt".equals(method.getName())) {
                String key = (String) args[0];
                int defValue = (int) args[1];
                if (values.containsKey(key)) {
                    Object val = values.get(key);
                    if (val instanceof Integer) {
                        return val;
                    }
                    if (val instanceof String) {
                        throw new ClassCastException("String cannot be cast to Integer");
                    }
                }
                return defValue;
            }
            if ("getString".equals(method.getName())) {
                String key = (String) args[0];
                String defValue = (String) args[1];
                if (values.containsKey(key)) {
                    return String.valueOf(values.get(key));
                }
                return defValue;
            }
            if ("getBoolean".equals(method.getName())) {
                String key = (String) args[0];
                boolean defValue = (boolean) args[1];
                if (values.containsKey(key)) {
                    return values.get(key);
                }
                return defValue;
            }
            return null;
        };
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                handler
        );
    }

    private Resources createFakeResources(final Map<Integer, Integer> intMap) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getInteger".equals(method.getName())) {
                int id = (int) args[0];
                if (intMap.containsKey(id)) {
                    return intMap.get(id);
                }
                return 0;
            }
            return null;
        };
        return (Resources) Proxy.newProxyInstance(
                Resources.class.getClassLoader(),
                new Class<?>[]{Resources.class},
                handler
        );
    }

    @Test
    public void testReadBottomOffsetLandscape() {
        Map<String, Object> map = new HashMap<>();
        SharedPreferences prefs = createFakePreferences(map);
        assertEquals(0, Settings.readBottomOffsetLandscape(prefs));

        map.put(Settings.PREF_BOTTOM_OFFSET_LANDSCAPE, 24);
        assertEquals(24, Settings.readBottomOffsetLandscape(createFakePreferences(map)));
    }

    @Test
    public void testReadVibrationDuration() {
        Map<String, Object> map = new HashMap<>();
        SharedPreferences prefs = createFakePreferences(map);
        assertEquals(0, Settings.readVibrationDuration(prefs));

        map.put(Settings.PREF_VIBRATION_DURATION, 45);
        assertEquals(45, Settings.readVibrationDuration(createFakePreferences(map)));
    }

    @Test
    public void testReadClipboardMaxClips() {
        assertEquals(Settings.CLIPBOARD_MAX_CLIPS_DEFAULT, Settings.readClipboardMaxClips(null));

        Map<String, Object> map = new HashMap<>();
        SharedPreferences prefs = createFakePreferences(map);
        assertEquals(50, Settings.readClipboardMaxClips(prefs));

        map.put(Settings.PREF_CLIPBOARD_MAX_CLIPS, 25);
        assertEquals(25, Settings.readClipboardMaxClips(createFakePreferences(map)));

        // Test clamping to minimum 10
        map.put(Settings.PREF_CLIPBOARD_MAX_CLIPS, 2);
        assertEquals(10, Settings.readClipboardMaxClips(createFakePreferences(map)));

        // Test clamping to maximum 100
        map.put(Settings.PREF_CLIPBOARD_MAX_CLIPS, 500);
        assertEquals(100, Settings.readClipboardMaxClips(createFakePreferences(map)));

        // Test String fallback
        map.put(Settings.PREF_CLIPBOARD_MAX_CLIPS, "75");
        assertEquals(75, Settings.readClipboardMaxClips(createFakePreferences(map)));

        map.put(Settings.PREF_CLIPBOARD_MAX_CLIPS, "invalid");
        assertEquals(50, Settings.readClipboardMaxClips(createFakePreferences(map)));
    }

    @Test
    public void testSettingsValuesSnapshot() {
        SettingsValues values = new SettingsValues(15, 30);
        assertEquals(15, values.mBottomOffsetPortrait);
        assertEquals(30, values.mBottomOffsetLandscape);
        assertEquals(400, values.mKeyRepeatStartTimeout);
        assertEquals(50, values.mKeyRepeatInterval);
        assertEquals(0, values.mVibrationDuration);
        assertEquals(53, values.mKeyPreviewPopupDismissDelay);
        assertEquals(50, values.mClipboardMaxClips);
    }
}
