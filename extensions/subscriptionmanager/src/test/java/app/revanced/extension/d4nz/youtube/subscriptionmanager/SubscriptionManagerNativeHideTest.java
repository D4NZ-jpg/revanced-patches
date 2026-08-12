package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SubscriptionManagerNativeHideTest {
    @Test
    public void exactFingerprintAcceptsVerifiedHideHandler() {
        assertTrue(SubscriptionManagerNativeHide.isExactHideFingerprint(
                2, 0x249, true, 65153809, "awvn"));
    }

    @Test
    public void exactFingerprintRejectsMenuIndexLookalikes() {
        assertFalse(SubscriptionManagerNativeHide.isExactHideFingerprint(
                2, 0x249, true, 60666189, "bboz"));
        assertFalse(SubscriptionManagerNativeHide.isExactHideFingerprint(
                2, 0x249, true, 73080600, "bazb"));
        assertFalse(SubscriptionManagerNativeHide.isExactHideFingerprint(
                2, 0x249, true, 79289575, "avpb"));
    }

    @Test
    public void exactFingerprintRejectsStructuralDrift() {
        assertFalse(SubscriptionManagerNativeHide.isExactHideFingerprint(
                1, 0x249, true, 65153809, "awvn"));
        assertFalse(SubscriptionManagerNativeHide.isExactHideFingerprint(
                2, 0x248, true, 65153809, "awvn"));
        assertFalse(SubscriptionManagerNativeHide.isExactHideFingerprint(
                2, 0x249, false, 65153809, "awvn"));
        assertFalse(SubscriptionManagerNativeHide.isExactHideFingerprint(
                2, 0x249, true, 65153809, "awvp"));
    }

    @Test
    public void dispatchRequiresExactOneOwnedMenuWithLogger() {
        assertTrue(SubscriptionManagerNativeHide.shouldDispatch(1, true, true));
        assertFalse(SubscriptionManagerNativeHide.shouldDispatch(0, true, true));
        assertFalse(SubscriptionManagerNativeHide.shouldDispatch(2, true, true));
        assertFalse(SubscriptionManagerNativeHide.shouldDispatch(1, false, true));
        assertFalse(SubscriptionManagerNativeHide.shouldDispatch(1, true, false));
    }

    @Test
    public void menuCorrelationRequiresAllObjectIdentities() {
        assertTrue(SubscriptionManagerNativeHide.hasExactMenuCorrelation(true, true, true));
        assertFalse(SubscriptionManagerNativeHide.hasExactMenuCorrelation(false, true, true));
        assertFalse(SubscriptionManagerNativeHide.hasExactMenuCorrelation(true, false, true));
        assertFalse(SubscriptionManagerNativeHide.hasExactMenuCorrelation(true, true, false));
    }
}
