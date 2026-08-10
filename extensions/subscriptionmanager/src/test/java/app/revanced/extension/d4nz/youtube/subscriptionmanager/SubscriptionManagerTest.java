package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SubscriptionManagerTest {
    @Test
    public void diagnosticFormattingFingerprintsIdentitiesWithoutRawLeakage() {
        String videoId = "Abc_def-123";
        String channelId = "UCabcdefghijklmnopqrstuv";
        String handle = "@private.handle";
        byte[] payload = SubscriptionCardParserTest.message(
                SubscriptionCardParserTest.stringField(
                        1, "https://i.ytimg.com/vi/" + videoId + "/default.jpg"),
                SubscriptionCardParserTest.stringField(
                        2, "https://youtube.com/channel/" + channelId),
                SubscriptionCardParserTest.stringField(3, "/" + handle));
        SubscriptionCardParseResult result = new SubscriptionCardParser().parse(payload);

        String diagnostic = SubscriptionManager.formatDiagnostics(result.diagnostics());

        assertFalse(diagnostic.contains(videoId));
        assertFalse(diagnostic.contains(channelId));
        assertFalse(diagnostic.contains(handle));
        assertTrue(diagnostic.contains(
                "fingerprint=" + SubscriptionManagerHash.shortFingerprint(videoId)));
        assertTrue(diagnostic.contains(
                "fingerprint=" + SubscriptionManagerHash.shortFingerprint(channelId)));
        assertTrue(diagnostic.contains(
                "fingerprint=" + SubscriptionManagerHash.shortFingerprint(handle)));
        assertTrue(diagnostic.length() <= 4096);
    }

    @Test
    public void cardEvaluationRequiresEveryFailOpenPrecondition() {
        byte[] buffer = new byte[] { 1 };

        assertTrue(SubscriptionManager.shouldEvaluateCard(
                true, true, "video_with_context.e", 0, buffer));
        assertTrue(SubscriptionManager.shouldEvaluateCard(
                true, true, "video_with_context.eml|child", 0, buffer));
        assertTrue(SubscriptionManager.shouldEvaluateCard(
                true, true, "video_lockup_with_attachment.e|child", 0, buffer));
        assertFalse(SubscriptionManager.shouldEvaluateCard(
                false, true, "video_with_context.e", 0, buffer));
        assertFalse(SubscriptionManager.shouldEvaluateCard(
                true, false, "video_with_context.e", 0, buffer));
        assertFalse(SubscriptionManager.shouldEvaluateCard(
                true, true, "video_with_context.e", 1, buffer));
        assertFalse(SubscriptionManager.shouldEvaluateCard(
                true, true, "home_video_with_context.e", 0, buffer));
        assertFalse(SubscriptionManager.shouldEvaluateCard(
                true, true, "video_with_context.extra", 0, buffer));
        assertFalse(SubscriptionManager.shouldEvaluateCard(
                true, true, "video_with_context.e", 0, null));
        assertFalse(SubscriptionManager.shouldEvaluateCard(
                true, true, "video_with_context.e", 0, new byte[0]));
    }
}
