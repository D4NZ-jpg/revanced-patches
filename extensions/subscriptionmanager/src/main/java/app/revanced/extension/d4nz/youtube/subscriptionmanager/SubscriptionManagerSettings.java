package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import app.revanced.extension.shared.settings.BooleanSetting;
import app.revanced.extension.shared.settings.IntegerSetting;

/** Settings owned by the custom patch bundle. */
public final class SubscriptionManagerSettings {
    public static final BooleanSetting SUBSCRIPTION_MANAGER =
            new BooleanSetting("revanced_d4nz_subscription_manager", false);
    /** Runtime rollout gate; intentionally absent from the visible settings screen. */
    public static final BooleanSetting SUBSCRIPTION_MANAGER_SWIPE_TO_HIDE =
            new BooleanSetting("revanced_d4nz_subscription_manager_swipe_to_hide", false);
    public static final BooleanSetting SUBSCRIPTION_MANAGER_HIDE_WATCHED =
            new BooleanSetting("revanced_d4nz_subscription_manager_hide_watched", false);
    public static final IntegerSetting SUBSCRIPTION_MANAGER_WATCHED_THRESHOLD =
            new IntegerSetting("revanced_d4nz_subscription_manager_watched_threshold", 80);
    public static final BooleanSetting SUBSCRIPTION_MANAGER_DEBUG =
            new BooleanSetting("revanced_d4nz_subscription_manager_debug", false);

    private SubscriptionManagerSettings() {
    }
}
