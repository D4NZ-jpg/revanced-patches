package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.support.v7.widget.RecyclerView;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionManagerSwipeHandler.GestureClassifier.Result;
import app.revanced.extension.youtube.shared.NavigationBar.NavigationButton;

public final class SubscriptionManagerSwipeHandlerTest {
    private static final String FIRST = "Abc_def-123";
    private static final String SECOND = "Xyz_def-987";

    @Test
    public void earliestFieldOneVideoIdWinsAndLaterCandidatesDoNotReplaceIt() {
        byte[] payload = concat(fieldOne(FIRST), new byte[] { 0x12, 0x01, 0x01 }, fieldOne(SECOND));
        assertEquals(FIRST, SubscriptionManagerSwipeHandler.extractEarliestFieldOneVideoId(payload));
    }

    @Test
    public void malformedOversizeAndMissingSlotsFailOpen() {
        assertNull(SubscriptionManagerSwipeHandler.extractEarliestFieldOneVideoId(null));
        assertNull(SubscriptionManagerSwipeHandler.extractEarliestFieldOneVideoId(new byte[] { 0x0a, 0x0b, 'a' }));
        assertNull(SubscriptionManagerSwipeHandler.extractEarliestFieldOneVideoId(
                fieldOne("bad!def-123")));
        assertNull(SubscriptionManagerSwipeHandler.extractEarliestFieldOneVideoId(
                new byte[SubscriptionManagerSwipeHandler.MAX_PAYLOAD_BYTES + 1]));
        assertNull(SubscriptionManagerSwipeHandler.extractEarliestFieldOneVideoId(
                new byte[] { 0x12, 0x0b, 'A', 'b', 'c', '_', 'd', 'e', 'f', '-', '1', '2', '3' }));
    }

    @Test
    public void invalidEarlierFieldOneDoesNotRejectLaterValidEmbeddedId() {
        assertEquals(FIRST, SubscriptionManagerSwipeHandler.extractEarliestFieldOneVideoId(
                concat(fieldOne("bad!def-123"), fieldOne(FIRST))));
    }

    @Test
    public void sourceItemRequiresAmtjHierarchyAndReadsOnlyAmtjPayloadField() throws Exception {
        assertNull(SubscriptionManagerSwipeHandler.extractSourceItemVideoId(
                new NearbySourceItem(fieldOne(FIRST))));
        assertEquals(FIRST, SubscriptionManagerSwipeHandler.extractSourceItemVideoId(
                newSourceItem("amtj", fieldOne(FIRST))));
        assertEquals(FIRST, SubscriptionManagerSwipeHandler.extractSourceItemVideoId(
                newSourceItem("amtjChild", fieldOne(FIRST))));
    }

    @Test
    public void groupedAmviSourceItemsAreRejectedEvenThroughSubclasses() throws Exception {
        assertNull(SubscriptionManagerSwipeHandler.extractSourceItemVideoId(
                newSourceItem("amvi", fieldOne(FIRST))));
        assertNull(SubscriptionManagerSwipeHandler.extractSourceItemVideoId(
                newSourceItem("amviChild", fieldOne(FIRST))));
    }

    @Test
    public void holderSubclassesResolvePositionMethodOnlyFromNvDeclaration() throws Exception {
        for (String holderClass : new String[]{"gnt", "angw"}) {
            Object holder = newNamedInstance(holderClass);
            Method method = SubscriptionManagerSwipeHandler.holderPositionMethod(holder);
            assertEquals("nv", method.getDeclaringClass().getName());
            assertEquals(17, method.invoke(holder));
        }
    }

    @Test
    public void leftSwipeConsumesOnlyAfterIntentAndCompletesOnUp() {
        SubscriptionManagerSwipeHandler.GestureClassifier classifier = classifier();
        classifier.onDown(100, 100);
        assertEquals(Result.PASS, classifier.onEvent(MotionEvent.ACTION_MOVE, 1, 80, 102));
        assertEquals(Result.CONSUME, classifier.onEvent(MotionEvent.ACTION_MOVE, 1, 45, 103));
        assertEquals(Result.COMPLETE, classifier.onEvent(MotionEvent.ACTION_UP, 1, 45, 103));
    }

    @Test
    public void shortRightVerticalMultitouchAndCancelNeverConsume() {
        assertPassesWithoutCompletion(MotionEvent.ACTION_UP, 1, 80, 100);
        assertCancelled(MotionEvent.ACTION_MOVE, 1, 155, 100);
        assertCancelled(MotionEvent.ACTION_MOVE, 1, 95, 155);
        assertCancelled(MotionEvent.ACTION_POINTER_DOWN, 2, 90, 100);
        assertCancelled(MotionEvent.ACTION_CANCEL, 1, 90, 100);
    }

    @Test
    public void recyclerChildLookupStubMatchesTouchedAbi() throws Exception {
        Method child = RecyclerView.class.getDeclaredMethod("o", float.class, float.class);
        assertTrue(Modifier.isPublic(child.getModifiers()) && Modifier.isFinal(child.getModifiers()));
        assertEquals(View.class, child.getReturnType());
    }

    @Test
    public void sourceRemovalCountGuardAcceptsOnlyBoundedUnambiguousPositions() {
        assertTrue(SubscriptionManagerSwipeHandler.attestCounts(8, 8, 3, 6, 1));
        assertFalse(SubscriptionManagerSwipeHandler.attestCounts(8, 7, 3, 6, 1));
        assertFalse(SubscriptionManagerSwipeHandler.attestCounts(8, 8, 3, 8, 1));
        assertFalse(SubscriptionManagerSwipeHandler.attestCounts(8, 8, 9, 6, 1));
        assertFalse(SubscriptionManagerSwipeHandler.attestCounts(8, 8, 3, 6, 3));
        assertFalse(SubscriptionManagerSwipeHandler.attestCounts(0, 0, 0, 0, 0));
    }

    @Test
    public void sourceRemovalPostconditionRequiresAllThreeCountsToDecrementTogether() {
        assertTrue(SubscriptionManagerSwipeHandler.removalPostconditionCounts(
                8, 8, 3, 7, 7, 2));
        assertFalse(SubscriptionManagerSwipeHandler.removalPostconditionCounts(
                8, 8, 3, 8, 7, 2));
        assertFalse(SubscriptionManagerSwipeHandler.removalPostconditionCounts(
                8, 8, 3, 7, 8, 2));
        assertFalse(SubscriptionManagerSwipeHandler.removalPostconditionCounts(
                8, 8, 3, 7, 7, 3));
    }

    @Test
    public void staleRemovalAttemptKeepsHideWhenExactPlanAlreadyReachedPostcondition() {
        assertTrue(SubscriptionManagerSwipeHandler.removalAttemptSucceeded(false, true));
        assertTrue(SubscriptionManagerSwipeHandler.removalAttemptSucceeded(true, false));
        assertFalse(SubscriptionManagerSwipeHandler.removalAttemptSucceeded(false, false));
    }

    @Test
    public void swipeContextRequiresBothSettingsAndSubscriptionsNavigation() {
        assertTrue(SubscriptionManagerSwipeHandler.isSwipeContextEnabled(
                true, true, NavigationButton.SUBSCRIPTIONS));
        assertFalse(SubscriptionManagerSwipeHandler.isSwipeContextEnabled(
                false, true, NavigationButton.SUBSCRIPTIONS));
        assertFalse(SubscriptionManagerSwipeHandler.isSwipeContextEnabled(
                true, false, NavigationButton.SUBSCRIPTIONS));
        assertFalse(SubscriptionManagerSwipeHandler.isSwipeContextEnabled(
                true, true, NavigationButton.HOME));
    }

    @Test
    public void productionVersionRejectsReplacementAndGlobalInvalidation() {
        SubscriptionManagerSwipeVersion versions = new SubscriptionManagerSwipeVersion();
        SubscriptionManagerSwipeVersion.Token first = versions.next();
        assertTrue(versions.matches(first, first));

        SubscriptionManagerSwipeVersion.Token replacement = versions.next();
        assertFalse(versions.matches(replacement, first));
        assertTrue(versions.matches(replacement, replacement));

        versions.invalidateAll();
        assertFalse(versions.isCurrent(first));
        assertFalse(versions.isCurrent(replacement));
        assertFalse(versions.matches(replacement, replacement));
    }

    private static SubscriptionManagerSwipeHandler.GestureClassifier classifier() {
        return new SubscriptionManagerSwipeHandler.GestureClassifier(48, 1.2f);
    }

    private static void assertPassesWithoutCompletion(int action, int pointers, float x, float y) {
        SubscriptionManagerSwipeHandler.GestureClassifier classifier = classifier();
        classifier.onDown(100, 100);
        assertEquals(Result.PASS, classifier.onEvent(action, pointers, x, y));
    }

    private static void assertCancelled(int action, int pointers, float x, float y) {
        SubscriptionManagerSwipeHandler.GestureClassifier classifier = classifier();
        classifier.onDown(100, 100);
        assertEquals(Result.CANCELLED, classifier.onEvent(action, pointers, x, y));
        assertEquals(Result.PASS, classifier.onEvent(MotionEvent.ACTION_UP, 1, x, y));
    }

    private static Object newSourceItem(String className, byte[] payload) throws Exception {
        java.lang.reflect.Constructor<?> constructor =
                Class.forName(className).getDeclaredConstructor(byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance((Object) payload);
    }

    private static Object newNamedInstance(String className) throws Exception {
        java.lang.reflect.Constructor<?> constructor =
                Class.forName(className).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static byte[] fieldOne(String value) {
        byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x0a);
        output.write(ascii.length);
        output.write(ascii, 0, ascii.length);
        return output.toByteArray();
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static final class NearbySourceItem {
        @SuppressWarnings("unused")
        final byte[] c;

        NearbySourceItem(byte[] payload) {
            c = payload;
        }
    }
}
