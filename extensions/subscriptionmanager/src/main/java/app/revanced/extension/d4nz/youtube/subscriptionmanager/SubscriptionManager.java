package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import android.content.Context;

import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionManagerSettings;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.ProtobufWireInspector.NumericField;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParseResult.CandidateIdentity;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParser.ProgressProfile;

public final class SubscriptionManager {
    private static final String PREFERENCES_NAME = "revanced_d4nz_subscription_manager_state";
    private static final int MAX_LOGGED_NUMERIC_FIELDS = 16;
    private static final int MAX_LOGGED_IDENTITIES = 16;
    private static final int MAX_DIAGNOSTIC_CHARS = 4096;
    private static final String DIAGNOSTIC_TRUNCATED_SUFFIX = "; truncated";
    private static final Object INITIALIZATION_LOCK = new Object();
    private static final SubscriptionCardParser CARD_PARSER = new SubscriptionCardParser();

    private static volatile SubscriptionManagerState state;
    private static volatile ProgressProfile verifiedProgressProfile;

    private SubscriptionManager() {
    }

    public static void initialize() {
        if (state != null) {
            return;
        }
        synchronized (INITIALIZATION_LOCK) {
            if (state != null) {
                return;
            }
            try {
                Context context = Utils.getContext();
                if (context == null) {
                    return;
                }
                SubscriptionManagerPreferences.Store store =
                        new SubscriptionManagerPreferences.SharedPreferencesStore(
                                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
                SubscriptionManagerState initialized = new SubscriptionManagerState(store);
                SubscriptionManagerAccountHook.applyPendingAccount(initialized);
                state = initialized;
            } catch (RuntimeException ignored) {
                state = null;
            }
        }
    }

    @SuppressWarnings("unused")
    public static void setAccountIdentifier(String accountIdentifier) {
        initialize();
        SubscriptionManagerState current = state;
        if (current == null) {
            return;
        }
        try {
            SubscriptionManagerSwipeHandler.invalidateAllOwnership();
            current.setAccountIdentifier(accountIdentifier);
        } catch (Throwable ignored) {
            disable();
        }
    }

    @SuppressWarnings("unused")
    public static void setIncognito(boolean incognito) {
        initialize();
        SubscriptionManagerState current = state;
        if (current == null) {
            return;
        }
        try {
            SubscriptionManagerSwipeHandler.invalidateAllOwnership();
            current.setIncognito(incognito);
        } catch (Throwable ignored) {
            disable();
        }
    }

    /** Returns the transient namespace token captured when a swipe binding is published. */
    static String currentPersistentAccountNamespaceForSwipe() {
        initialize();
        SubscriptionManagerState current = state;
        if (current == null) return null;
        try {
            return current.currentPersistentAccountNamespace();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Fail-open persistence facade used only after a confirmed swipe. */
    static SubscriptionManagerState.SwipePersistence persistManualHideForSwipe(
            String videoId, String expectedAccountNamespace) {
        initialize();
        SubscriptionManagerState current = state;
        if (current == null) return SubscriptionManagerState.SwipePersistence.FAILED;
        try {
            return current.persistManualHideForSwipe(videoId, expectedAccountNamespace);
        } catch (Throwable ignored) {
            return SubscriptionManagerState.SwipePersistence.FAILED;
        }
    }

    /** Reapplies a persisted manual hide when RecyclerView binds the card again. */
    static boolean isVideoManuallyHiddenForSwipe(
            String videoId, String expectedAccountNamespace) {
        initialize();
        SubscriptionManagerState current = state;
        if (current == null) return false;
        try {
            return current.isVideoManuallyHidden(videoId, expectedAccountNamespace);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setVerifiedProgressProfile(ProgressProfile profile) {
        verifiedProgressProfile = profile;
    }

    public static boolean isCardHidden(byte[] buffer) {
        SubscriptionManagerState current = state;
        if (current == null || buffer == null || buffer.length == 0) {
            return false;
        }

        try {
            ProgressProfile progressProfile = verifiedProgressProfile;
            boolean debug = SubscriptionManagerSettings.SUBSCRIPTION_MANAGER_DEBUG.get();
            String videoId;
            if (!debug && progressProfile == null) {
                videoId = CARD_PARSER.parseUniqueVideoId(buffer);
            } else {
                SubscriptionCardParseResult result = CARD_PARSER.parse(buffer, progressProfile);
                if (debug) {
                    logDiagnostics(result.diagnostics());
                }
                videoId = result.videoId().orElse(null);
            }
            boolean hideWatchedVideos = SubscriptionManagerSettings.SUBSCRIPTION_MANAGER_HIDE_WATCHED.get();
            return videoId != null && current.shouldHideVideo(videoId, hideWatchedVideos);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean shouldEvaluateCard(
            boolean enabled,
            boolean subscriptionsSelected,
            String path,
            int contentIndex,
            byte[] buffer
    ) {
        if (!enabled || !subscriptionsSelected || contentIndex != 0
                || buffer == null || buffer.length == 0) {
            return false;
        }
        return isRootCardPath(path, "video_with_context.e")
                || isRootCardPath(path, "video_lockup_with_attachment.e");
    }

    private static boolean isRootCardPath(String path, String rootComponent) {
        if (path == null || !path.startsWith(rootComponent)) {
            return false;
        }
        if (path.length() == rootComponent.length()) {
            return true;
        }
        char suffix = path.charAt(rootComponent.length());
        return suffix == 'm' || suffix == '-' || suffix == '|';
    }

    static SubscriptionManagerState initializedState() {
        return state;
    }

    static void disable() {
        synchronized (INITIALIZATION_LOCK) {
            state = null;
        }
    }

    private static void logDiagnostics(SubscriptionCardParseResult.Diagnostics diagnostics) {
        Logger.printDebug(() -> formatDiagnostics(diagnostics));
    }

    /** Pure formatter kept package-private so privacy and output bounds can be regression tested. */
    static String formatDiagnostics(SubscriptionCardParseResult.Diagnostics diagnostics) {
        StringBuilder message = new StringBuilder("Subscription card parser: stop=")
                .append(diagnostics.stopReason())
                .append(", fields=").append(diagnostics.fieldsVisited())
                .append(", bytes=").append(diagnostics.bytesVisited());

        List<NumericField> numericFields = diagnostics.numericFields();
        int numericFieldCount = Math.min(numericFields.size(), MAX_LOGGED_NUMERIC_FIELDS);
        for (int index = 0; index < numericFieldCount; index++) {
            NumericField field = numericFields.get(index);
            message.append("; numeric[path=").append(field.path())
                    .append(", wire=").append(field.wireType())
                    .append(", value=").append(field.value()).append(']');
        }
        if (numericFields.size() > numericFieldCount) {
            message.append("; numeric[truncated=")
                    .append(numericFields.size() - numericFieldCount).append(']');
        }
        List<CandidateIdentity> identities = diagnostics.candidateIdentities();
        int identityCount = Math.min(identities.size(), MAX_LOGGED_IDENTITIES);
        for (int index = 0; index < identityCount; index++) {
            CandidateIdentity identity = identities.get(index);
            message.append("; identity[kind=").append(identity.kind())
                    .append(", path=").append(identity.path())
                    .append(", offset=").append(identity.valueOffset()).append(']');
        }
        if (identities.size() > identityCount) {
            message.append("; identity[truncated=")
                    .append(identities.size() - identityCount).append(']');
        }
        if (message.length() > MAX_DIAGNOSTIC_CHARS) {
            message.setLength(MAX_DIAGNOSTIC_CHARS - DIAGNOSTIC_TRUNCATED_SUFFIX.length());
            message.append(DIAGNOSTIC_TRUNCATED_SUFFIX);
        }
        return message.toString();
    }
}
