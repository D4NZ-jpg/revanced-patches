package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import android.content.SharedPreferences;

public final class SubscriptionManagerPreferences {
    static final String STATE_KEY_PREFIX = "revanced_d4nz_subscription_manager_state:";

    private SubscriptionManagerPreferences() {
    }

    /** Storage is accessed only when state changes or the account namespace changes. */
    public interface Store {
        String getString(String key);

        void putString(String key, String value);

        void remove(String key);
    }

    public static final class SharedPreferencesStore implements Store {
        private final SharedPreferences preferences;

        public SharedPreferencesStore(SharedPreferences preferences) {
            if (preferences == null) {
                throw new IllegalArgumentException("preferences must not be null");
            }
            this.preferences = preferences;
        }

        @Override
        public String getString(String key) {
            return preferences.getString(key, null);
        }

        @Override
        public void putString(String key, String value) {
            preferences.edit().putString(key, value).apply();
        }

        @Override
        public void remove(String key) {
            preferences.edit().remove(key).apply();
        }
    }
}
