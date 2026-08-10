package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SubscriptionManagerPlaybackTest {
    @Test
    public void thresholdIsClampedToInclusivePercentageRange() {
        assertEquals(1, SubscriptionManagerPlayback.clampThreshold(Integer.MIN_VALUE));
        assertEquals(1, SubscriptionManagerPlayback.clampThreshold(1));
        assertEquals(80, SubscriptionManagerPlayback.clampThreshold(80));
        assertEquals(100, SubscriptionManagerPlayback.clampThreshold(100));
        assertEquals(100, SubscriptionManagerPlayback.clampThreshold(Integer.MAX_VALUE));
    }

    @Test
    public void thresholdBoundaryIsInclusive() {
        assertFalse(SubscriptionManagerPlayback.hasReachedThreshold(7_999, 10_000, 80));
        assertTrue(SubscriptionManagerPlayback.hasReachedThreshold(8_000, 10_000, 80));
        assertTrue(SubscriptionManagerPlayback.hasReachedThreshold(8_001, 10_000, 80));
    }

    @Test
    public void thresholdRoundsUpAndRejectsInvalidPlaybackValues() {
        assertFalse(SubscriptionManagerPlayback.hasReachedThreshold(0, 3, 1));
        assertTrue(SubscriptionManagerPlayback.hasReachedThreshold(1, 3, 1));
        assertFalse(SubscriptionManagerPlayback.hasReachedThreshold(2, 3, 100));
        assertTrue(SubscriptionManagerPlayback.hasReachedThreshold(3, 3, 100));
        assertFalse(SubscriptionManagerPlayback.hasReachedThreshold(-1, 10_000, 80));
        assertFalse(SubscriptionManagerPlayback.hasReachedThreshold(0, 0, 80));
    }

    @Test
    public void thresholdComparisonDoesNotOverflowForLargeLengths() {
        assertFalse(SubscriptionManagerPlayback.hasReachedThreshold(
                Long.MAX_VALUE - 1, Long.MAX_VALUE, 100));
        assertTrue(SubscriptionManagerPlayback.hasReachedThreshold(
                Long.MAX_VALUE, Long.MAX_VALUE, 100));
    }
}
