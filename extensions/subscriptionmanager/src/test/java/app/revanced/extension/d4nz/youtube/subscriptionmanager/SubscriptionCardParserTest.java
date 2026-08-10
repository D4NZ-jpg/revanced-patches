package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

import app.revanced.extension.d4nz.youtube.subscriptionmanager.ProtobufWireInspector.FieldPath;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParseResult.IdentityKind;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParseResult.WatchedStatus;

public class SubscriptionCardParserTest {
    private static final String VIDEO_ID = "Abc_def-123";
    private static final String OTHER_VIDEO_ID = "Zyx_wvu-987";
    private static final String CHANNEL_ID = "UCabcdefghijklmnopqrstuv";

    private final SubscriptionCardParser parser = new SubscriptionCardParser();

    @Test
    public void extractsStrictKnownThumbnailForms() {
        SubscriptionCardParseResult vi = parser.parse(message(
                stringField(1, "https://i.ytimg.com/vi/" + VIDEO_ID + "/hqdefault.jpg")));
        SubscriptionCardParseResult webp = parser.parse(message(
                stringField(1, "https://i.ytimg.com/vi_webp/" + VIDEO_ID + "/mqdefault.webp")));

        assertEquals(VIDEO_ID, vi.videoId().get());
        assertEquals(VIDEO_ID, webp.videoId().get());
        assertEquals(IdentityKind.VIDEO_ID,
                vi.diagnostics().candidateIdentities().get(0).kind());
    }

    @Test
    public void identityOnlyMatchesFullUniqueIdentityWithoutDiagnosticsOrProgress() {
        byte[] payload = message(
                stringField(1, "private title that is not a URL"),
                bytesField(2, message(
                        stringField(3, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                        varintField(9, 80))));

        SubscriptionCardParseResult full = parser.parse(payload);
        SubscriptionCardParseResult identityOnly = parser.parseIdentityOnly(payload);

        assertEquals(full.videoId(), identityOnly.videoId());
        assertEquals(VIDEO_ID, identityOnly.videoId().get());
        assertTrue(identityOnly.diagnostics().numericFields().isEmpty());
        assertTrue(identityOnly.diagnostics().candidateIdentities().isEmpty());
        assertFalse(identityOnly.watchedProgress().isPresent());
        assertEquals(WatchedStatus.UNKNOWN, identityOnly.watchedStatus());
        assertEquals(ProtobufWireInspector.StopReason.COMPLETE,
                identityOnly.diagnostics().stopReason());
    }

    @Test
    public void leanVideoIdParsingMatchesDetailedModesWithoutCollectingChannels() {
        byte[] payload = message(
                stringField(1, "https://youtube.com/channel/" + CHANNEL_ID),
                stringField(2, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"));

        SubscriptionCardParseResult full = parser.parse(payload);
        SubscriptionCardParseResult identityOnly = parser.parseIdentityOnly(payload);

        assertEquals(full.videoId().orElse(null), parser.parseUniqueVideoId(payload));
        assertEquals(full.videoId(), identityOnly.videoId());
        assertEquals(full.channelIdentity(), identityOnly.channelIdentity());
    }

    @Test
    public void conflictingVideoIdsAreUnknown() {
        SubscriptionCardParseResult result = parser.parse(message(
                stringField(1, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                stringField(2, "https://i.ytimg.com/vi/" + OTHER_VIDEO_ID + "/default.jpg")));

        assertFalse(result.videoId().isPresent());
        assertEquals(2, result.diagnostics().candidateIdentities().size());
    }

    @Test
    public void channelCandidatesAreConservativeAndAmbiguityIsUnknown() {
        SubscriptionCardParseResult channel = parser.parse(message(
                stringField(1, "https://www.youtube.com/channel/" + CHANNEL_ID)));
        assertEquals(IdentityKind.CHANNEL_ID, channel.channelIdentity().get().kind());
        assertEquals(CHANNEL_ID, channel.channelIdentity().get().value());

        SubscriptionCardParseResult handle = parser.parse(message(stringField(1, "/@creator")));
        assertEquals(IdentityKind.CHANNEL_HANDLE, handle.channelIdentity().get().kind());
        assertEquals("@creator", handle.channelIdentity().get().value());

        SubscriptionCardParseResult ambiguous = parser.parse(message(
                stringField(1, "https://youtube.com/channel/" + CHANNEL_ID),
                stringField(2, "https://youtube.com/@creator")));
        assertFalse(ambiguous.channelIdentity().isPresent());
    }

    @Test
    public void progressIsUnknownWithoutExplicitProfileAndMissingIsNotZero() {
        byte[] payloadWithPlausibleNumbers = message(varintField(9, 100), varintField(10, 80));
        assertFalse(parser.parse(payloadWithPlausibleNumbers).watchedProgress().isPresent());
        assertEquals(WatchedStatus.UNKNOWN,
                parser.parse(payloadWithPlausibleNumbers).watchedStatus());

        SubscriptionCardParser.ProgressProfile profile =
                SubscriptionCardParser.ProgressProfile.percentage(FieldPath.of(9), 80);
        SubscriptionCardParseResult missing = parser.parse(message(varintField(10, 0)), profile);
        assertFalse(missing.watchedProgress().isPresent());
        assertEquals(WatchedStatus.UNKNOWN, missing.watchedStatus());
    }

    @Test
    public void configuredProgressHonors79And80Boundary() {
        SubscriptionCardParser.ProgressProfile profile =
                SubscriptionCardParser.ProgressProfile.percentage(FieldPath.of(9), 80);

        SubscriptionCardParseResult below = parser.parse(message(varintField(9, 79)), profile);
        SubscriptionCardParseResult boundary = parser.parse(message(varintField(9, 80)), profile);

        assertEquals(79, below.watchedProgress().getAsLong());
        assertEquals(WatchedStatus.NOT_WATCHED, below.watchedStatus());
        assertEquals(80, boundary.watchedProgress().getAsLong());
        assertEquals(WatchedStatus.WATCHED, boundary.watchedStatus());
    }

    @Test
    public void configuredProgressUsesExactNestedPathAndRejectsConflicts() {
        SubscriptionCardParser.ProgressProfile profile =
                SubscriptionCardParser.ProgressProfile.percentage(FieldPath.of(3, 7), 80);
        byte[] nested = bytesField(3, message(varintField(7, 80), varintField(8, 12)));
        SubscriptionCardParseResult result = parser.parse(message(varintField(7, 99), nested), profile);
        assertEquals(80, result.watchedProgress().getAsLong());

        SubscriptionCardParseResult conflicting = parser.parse(message(
                bytesField(3, message(varintField(7, 79))),
                bytesField(3, message(varintField(7, 80)))), profile);
        assertFalse(conflicting.watchedProgress().isPresent());
        assertEquals(WatchedStatus.UNKNOWN, conflicting.watchedStatus());
    }

    @Test
    public void decoysDoNotProduceIdentityFalsePositives() {
        List<byte[]> decoys = new ArrayList<>();
        decoys.add(stringField(1, "title https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"));
        decoys.add(stringField(2, "https://example.com/vi/" + VIDEO_ID + "/default.jpg"));
        decoys.add(stringField(3, "https://i.ytimg.com/vi/too_short/default.jpg"));
        decoys.add(stringField(4, "https://i.ytimg.com/vi/" + VIDEO_ID + "x/default.jpg"));
        decoys.add(stringField(5, "https://youtube.example/@creator"));
        decoys.add(stringField(6, "https://youtube.com/watch?v=/channel/" + CHANNEL_ID));
        decoys.add(stringField(7, VIDEO_ID));

        SubscriptionCardParseResult result = parser.parse(message(
                decoys.toArray(new byte[0][])));
        assertFalse(result.videoId().isPresent());
        assertFalse(result.channelIdentity().isPresent());
        assertTrue(result.diagnostics().candidateIdentities().isEmpty());
    }

    @Test
    public void malformedAndTruncatedInputsFailClosed() {
        byte[][] malformed = {
                {(byte) 0x80},
                {0x0a, 0x05, 'a'},
                {0x0b},
                {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, 0x02},
                null
        };
        for (byte[] input : malformed) {
            SubscriptionCardParseResult result = parser.parse(input);
            assertFalse(result.videoId().isPresent());
            assertEquals(WatchedStatus.UNKNOWN, result.watchedStatus());
            assertFalse(result.diagnostics().stopReason()
                    == ProtobufWireInspector.StopReason.COMPLETE);
        }
    }

    @Test
    public void identityOnlyAmbiguousAndMalformedInputsRemainUnknown() {
        SubscriptionCardParseResult ambiguous = parser.parseIdentityOnly(message(
                stringField(1, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                stringField(2, "https://i.ytimg.com/vi/" + OTHER_VIDEO_ID + "/default.jpg")));
        assertFalse(ambiguous.videoId().isPresent());

        byte[] truncatedAfterCandidate = concat(
                stringField(1, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                new byte[] { 0x0a, 0x05, 'a' });
        SubscriptionCardParseResult malformed = parser.parseIdentityOnly(truncatedAfterCandidate);
        assertFalse(malformed.videoId().isPresent());
        assertEquals(ProtobufWireInspector.StopReason.MALFORMED,
                malformed.diagnostics().stopReason());
        assertTrue(malformed.diagnostics().numericFields().isEmpty());
        assertTrue(malformed.diagnostics().candidateIdentities().isEmpty());
    }

    @Test
    public void candidateInsideInvalidNestedBytesIsIgnoredInEveryMode() {
        byte[] invalidNested = concat(
                stringField(2, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                new byte[] { 0x0b });
        byte[] payload = message(bytesField(1, invalidNested));

        SubscriptionCardParseResult full = parser.parse(payload);
        SubscriptionCardParseResult identityOnly = parser.parseIdentityOnly(payload);

        assertEquals(ProtobufWireInspector.StopReason.COMPLETE,
                full.diagnostics().stopReason());
        assertFalse(full.videoId().isPresent());
        assertEquals(full.videoId(), identityOnly.videoId());
        assertEquals(full.diagnostics().stopReason(), identityOnly.diagnostics().stopReason());
        assertNull(parser.parseUniqueVideoId(payload));
    }

    @Test
    public void validationByteBudgetExhaustionFailsOpenWithModeParity() {
        SubscriptionCardParser limitedParser = new SubscriptionCardParser(
                new ProtobufWireInspector.Limits(100, 5, 100, 8));
        byte[] payload = message(bytesField(1, varintField(2, 1)));

        SubscriptionCardParseResult full = limitedParser.parse(payload);
        SubscriptionCardParseResult identityOnly = limitedParser.parseIdentityOnly(payload);

        assertFalse(full.videoId().isPresent());
        assertEquals(ProtobufWireInspector.StopReason.BYTE_BUDGET_EXCEEDED,
                full.diagnostics().stopReason());
        assertEquals(full.diagnostics().stopReason(), identityOnly.diagnostics().stopReason());
        assertNull(limitedParser.parseUniqueVideoId(payload));
    }

    @Test
    public void validationDepthBudgetExhaustionFailsOpenWithModeParity() {
        SubscriptionCardParser limitedParser = new SubscriptionCardParser(
                new ProtobufWireInspector.Limits(4096, 4096, 100, 0));
        byte[] payload = message(bytesField(1, stringField(
                2, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg")));

        SubscriptionCardParseResult full = limitedParser.parse(payload);
        SubscriptionCardParseResult identityOnly = limitedParser.parseIdentityOnly(payload);

        assertFalse(full.videoId().isPresent());
        assertEquals(ProtobufWireInspector.StopReason.DEPTH_LIMIT_EXCEEDED,
                full.diagnostics().stopReason());
        assertEquals(full.diagnostics().stopReason(), identityOnly.diagnostics().stopReason());
        assertNull(limitedParser.parseUniqueVideoId(payload));
    }

    @Test
    public void identityOnlyLimitsSuppressPreviouslyStreamedIdentity() {
        SubscriptionCardParser limitedParser = new SubscriptionCardParser(
                new ProtobufWireInspector.Limits(4096, 4096, 1, 8));
        byte[] payload = message(
                stringField(1, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                varintField(2, 1));

        SubscriptionCardParseResult result = limitedParser.parseIdentityOnly(payload);

        assertFalse(result.videoId().isPresent());
        assertEquals(ProtobufWireInspector.StopReason.FIELD_BUDGET_EXCEEDED,
                result.diagnostics().stopReason());
    }

    @Test
    public void fullProfileModeStillReturnsIdentityProgressAndDiagnostics() {
        byte[] payload = message(
                stringField(1, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                varintField(9, 80));
        SubscriptionCardParser.ProgressProfile profile =
                SubscriptionCardParser.ProgressProfile.percentage(FieldPath.of(9), 80);

        SubscriptionCardParseResult result = parser.parse(payload, profile);

        assertEquals(VIDEO_ID, result.videoId().get());
        assertEquals(80, result.watchedProgress().getAsLong());
        assertEquals(WatchedStatus.WATCHED, result.watchedStatus());
        assertEquals(1, result.diagnostics().numericFields().size());
        assertEquals(1, result.diagnostics().candidateIdentities().size());
    }

    @Test(timeout = 3000)
    public void randomInputNeverThrowsOrFindsDecoyIdentity() {
        Random random = new Random(1234567);
        for (int iteration = 0; iteration < 2000; iteration++) {
            byte[] input = new byte[random.nextInt(256)];
            random.nextBytes(input);
            SubscriptionCardParseResult result = parser.parse(input);
            assertFalse(result.videoId().isPresent());
            assertFalse(result.channelIdentity().isPresent());
            assertEquals(WatchedStatus.UNKNOWN, result.watchedStatus());
        }
    }

    @Test
    public void resultCollectionsAreImmutableAndDiagnosticsAreSanitized() {
        String privateTitle = "private account title";
        SubscriptionCardParseResult result = parser.parse(message(
                stringField(1, privateTitle),
                stringField(2, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                varintField(3, 42)));

        assertThrows(UnsupportedOperationException.class,
                () -> result.diagnostics().candidateIdentities().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> result.diagnostics().numericFields().clear());
        assertEquals(VIDEO_ID, result.diagnostics().candidateIdentities().get(0).value());
        assertFalse(result.diagnostics().candidateIdentities().toString().contains(privateTitle));
    }

    @Test(timeout = 5000)
    public void parserIsSafeForConcurrentUse() throws Exception {
        byte[] payload = message(
                stringField(1, "https://i.ytimg.com/vi/" + VIDEO_ID + "/default.jpg"),
                varintField(9, 80));
        SubscriptionCardParser.ProgressProfile profile =
                SubscriptionCardParser.ProgressProfile.percentage(FieldPath.of(9), 80);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                tasks.add(() -> {
                    SubscriptionCardParseResult result = parser.parse(payload, profile);
                    return VIDEO_ID.equals(result.videoId().orElse(null))
                            && result.watchedStatus() == WatchedStatus.WATCHED;
                });
            }
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                assertTrue(result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    static byte[] message(byte[]... fields) {
        return concat(fields);
    }

    static byte[] stringField(int number, String value) {
        return bytesField(number, value.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] bytesField(int number, byte[] value) {
        return concat(varint((long) number << 3 | 2), varint(value.length), value);
    }

    static byte[] varintField(int number, long value) {
        return concat(varint((long) number << 3), varint(value));
    }

    static byte[] varint(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
        return output.toByteArray();
    }

    static byte[] concat(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }
}
