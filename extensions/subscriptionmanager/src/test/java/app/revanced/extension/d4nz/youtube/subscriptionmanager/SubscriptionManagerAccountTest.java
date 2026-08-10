package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SubscriptionManagerAccountTest {
    @Test
    public void unresolvedNamespaceIsDefaultAndNonPersistent() {
        SubscriptionManagerAccount account = SubscriptionManagerAccount.fromIdentifier(null);

        assertEquals(SubscriptionManagerAccount.UNRESOLVED_NAMESPACE, account.getNamespace());
        assertFalse(account.isPersistent());
    }

    @Test
    public void accountIdentifierIsHashedAndStable() {
        SubscriptionManagerAccount first = SubscriptionManagerAccount.fromIdentifier("user@example.com");
        SubscriptionManagerAccount second = SubscriptionManagerAccount.fromIdentifier(" user@example.com ");

        assertEquals(first, second);
        assertTrue(first.getNamespace().startsWith("account-"));
        assertFalse(first.getNamespace().contains("user@example.com"));
    }

    @Test
    public void incognitoNamespaceIsNonPersistent() {
        SubscriptionManagerAccount account = SubscriptionManagerAccount.incognito();

        assertEquals(SubscriptionManagerAccount.INCOGNITO_NAMESPACE, account.getNamespace());
        assertFalse(account.isPersistent());
    }
}
