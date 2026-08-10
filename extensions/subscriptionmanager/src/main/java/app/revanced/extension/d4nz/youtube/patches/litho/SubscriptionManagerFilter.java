package app.revanced.extension.d4nz.youtube.patches.litho;

import app.revanced.extension.shared.patches.litho.Filter;
import app.revanced.extension.shared.patches.litho.FilterGroup.StringFilterGroup;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionManagerSettings;
import app.revanced.extension.youtube.shared.NavigationBar.NavigationButton;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionManager;

@SuppressWarnings("unused")
public final class SubscriptionManagerFilter extends Filter {
    private final StringFilterGroup rootVideoCards = new StringFilterGroup(
            SubscriptionManagerSettings.SUBSCRIPTION_MANAGER,
            "video_with_context.e",
            "video_lockup_with_attachment.e"
    );

    public SubscriptionManagerFilter() {
        addPathCallbacks(rootVideoCards);
        SubscriptionManager.initialize();
    }

    @Override
    public boolean isFiltered(
            String identifier,
            String accessibility,
            String path,
            byte[] buffer,
            StringFilterGroup matchedGroup,
            FilterContentType contentType,
            int contentIndex
    ) {
        try {
            boolean enabled = SubscriptionManagerSettings.SUBSCRIPTION_MANAGER.get();
            if (matchedGroup != rootVideoCards || contentType != FilterContentType.PATH
                    || !enabled || contentIndex != 0 || buffer == null || buffer.length == 0) {
                return false;
            }
            boolean subscriptionsSelected =
                    NavigationButton.getSelectedNavigationButton() == NavigationButton.SUBSCRIPTIONS;
            return SubscriptionManager.shouldEvaluateCard(
                    enabled, subscriptionsSelected, path, contentIndex, buffer)
                    && SubscriptionManager.isCardHidden(buffer);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
