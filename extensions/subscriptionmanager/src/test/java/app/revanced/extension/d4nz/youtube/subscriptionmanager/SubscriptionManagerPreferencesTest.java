package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SubscriptionManagerPreferencesTest {
    @Test
    public void sharedPreferencesAdapterUsesApplyForWritesAndRemoves() {
        FakeSharedPreferences preferences = new FakeSharedPreferences();
        SubscriptionManagerPreferences.Store store =
                new SubscriptionManagerPreferences.SharedPreferencesStore(preferences);

        store.putString("key", "value");
        assertEquals("value", store.getString("key"));
        assertEquals(1, preferences.applyCount);
        assertEquals(0, preferences.commitCount);

        store.remove("key");
        assertNull(store.getString("key"));
        assertEquals(2, preferences.applyCount);
        assertEquals(0, preferences.commitCount);
    }

    private static final class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        int applyCount;
        int commitCount;

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            if (value instanceof Set) {
                return new HashSet<>((Set<String>) value);
            }
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pendingValues = new HashMap<>();
            private final Set<String> pendingRemovals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                pendingValues.put(key, value);
                pendingRemovals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                pendingValues.put(key, values == null ? null : new HashSet<>(values));
                pendingRemovals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pendingValues.put(key, value);
                pendingRemovals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pendingValues.put(key, value);
                pendingRemovals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pendingValues.put(key, value);
                pendingRemovals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pendingValues.put(key, value);
                pendingRemovals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                pendingRemovals.add(key);
                pendingValues.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                commitCount++;
                applyChanges();
                return true;
            }

            @Override
            public void apply() {
                applyCount++;
                applyChanges();
            }

            private void applyChanges() {
                if (clear) {
                    values.clear();
                }
                for (String key : pendingRemovals) {
                    values.remove(key);
                }
                for (Map.Entry<String, Object> entry : pendingValues.entrySet()) {
                    if (entry.getValue() == null) {
                        values.remove(entry.getKey());
                    } else {
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }
}
