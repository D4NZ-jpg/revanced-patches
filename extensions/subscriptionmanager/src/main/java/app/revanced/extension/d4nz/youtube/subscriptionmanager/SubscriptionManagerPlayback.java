package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import app.revanced.extension.youtube.patches.VideoInformation;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionManagerSettings;

public final class SubscriptionManagerPlayback {
    private static final int MINIMUM_THRESHOLD = 1;
    private static final int MAXIMUM_THRESHOLD = 100;

    private SubscriptionManagerPlayback() {
    }

    @SuppressWarnings("unused")
    public static void setVideoTime(long currentTimeMillis) {
        if (!SubscriptionManagerSettings.SUBSCRIPTION_MANAGER.get()
                || !SubscriptionManagerSettings.SUBSCRIPTION_MANAGER_HIDE_WATCHED.get()
                || VideoInformation.lastVideoIdIsShort()) {
            return;
        }

        SubscriptionManager.initialize();
        SubscriptionManagerState state = SubscriptionManager.initializedState();
        if (state == null) {
            return;
        }

        try {
            long videoLengthMillis = VideoInformation.getVideoLength();
            int threshold = clampThreshold(SubscriptionManagerSettings.SUBSCRIPTION_MANAGER_WATCHED_THRESHOLD.get());
            if (!hasReachedThreshold(currentTimeMillis, videoLengthMillis, threshold)) {
                return;
            }

            String videoId = VideoInformation.getVideoId();
            if (!SubscriptionManagerState.isValidId(videoId)) {
                return;
            }
            state.markVideoHiddenAsWatched(videoId);
        } catch (RuntimeException ignored) {
            SubscriptionManager.disable();
        }
    }

    public static int clampThreshold(int threshold) {
        return Math.max(MINIMUM_THRESHOLD, Math.min(MAXIMUM_THRESHOLD, threshold));
    }

    public static boolean hasReachedThreshold(
            long currentTimeMillis,
            long videoLengthMillis,
            int configuredThreshold
    ) {
        if (currentTimeMillis < 0 || videoLengthMillis <= 0) {
            return false;
        }
        int threshold = clampThreshold(configuredThreshold);
        long wholePercentMillis = videoLengthMillis / 100;
        long remainderMillis = videoLengthMillis % 100;
        long requiredTimeMillis = wholePercentMillis * threshold
                + (remainderMillis * threshold + 99) / 100;
        return currentTimeMillis >= requiredTimeMillis;
    }
}
