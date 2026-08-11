package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public final class SubscriptionManagerStateCodecTest {
    private static String key(String domain, String raw) {
        return SubscriptionManagerHash.identityKey("account-test", domain, raw);
    }

    @Test
    public void roundTripPreservesAllPersistentKeySets() {
        SubscriptionManagerState.Snapshot snapshot = SubscriptionManagerState.Snapshot.create(
                Arrays.asList(key("video", "manual-b"), key("video", "manual-a")),
                Arrays.asList(key("video", "watched-a")),
                Arrays.asList(key("video", "show-a")),
                Arrays.asList(key("channel-id", "channel-id-a")),
                Arrays.asList(key("channel-handle", "@handle-a")));

        String serialized = SubscriptionManagerStateCodec.serialize(snapshot);
        assertEquals(snapshot, SubscriptionManagerStateCodec.deserialize(serialized));
    }

    @Test
    public void serializationIsDeterministicAndVersioned() {
        String a = key("video", "a");
        String b = key("video", "b");
        String c = key("video", "c");
        SubscriptionManagerState.Snapshot first = SubscriptionManagerState.Snapshot.create(
                Arrays.asList(c, a, b), null, null, null, null);
        SubscriptionManagerState.Snapshot second = SubscriptionManagerState.Snapshot.create(
                Arrays.asList(b, c, a), null, null, null, null);

        assertEquals(SubscriptionManagerStateCodec.serialize(first),
                SubscriptionManagerStateCodec.serialize(second));
        assertTrue(SubscriptionManagerStateCodec.serialize(first).startsWith("version=2\n"));
    }

    @Test
    public void serializedStateContainsNeitherRawIdentitiesNorTheirBase64Forms() {
        String video = "Abc_def-123";
        String channel = "UCabcdefghijklmnopqrstuv";
        String handle = "@private.handle";
        SubscriptionManagerState state = new SubscriptionManagerState(
                new SubscriptionManagerStateTest.InMemoryStore(),
                SubscriptionManagerAccount.fromIdentifier("account"));
        state.manuallyHideVideo(video);
        state.hideChannelId(channel);
        state.hideChannelHandle(handle);
        String serialized = SubscriptionManagerStateCodec.serialize(state.snapshot());

        for (String raw : Arrays.asList(video, channel, handle)) {
            assertFalse(serialized.contains(raw));
            assertFalse(serialized.contains(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    public void aggregateSerializationAndDeserializationAreBounded() {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < SubscriptionManagerState.MAX_IDS_PER_COLLECTION; index++) {
            keys.add(key("video", "id-" + index));
        }
        SubscriptionManagerState.Snapshot large = SubscriptionManagerState.Snapshot.create(
                keys, keys, keys, keys, keys);
        String serialized = SubscriptionManagerStateCodec.serialize(large);
        assertTrue(serialized.getBytes(StandardCharsets.UTF_8).length
                <= SubscriptionManagerStateCodec.MAX_SERIALIZED_BYTES);
        assertEquals(large, SubscriptionManagerStateCodec.deserialize(serialized));

        String oversized = repeat("x", SubscriptionManagerStateCodec.MAX_SERIALIZED_BYTES + 1);
        assertEquals(SubscriptionManagerState.Snapshot.empty(),
                SubscriptionManagerStateCodec.deserialize(oversized));
    }

    @Test
    public void legacyRawAndMalformedFormatsAreRejected() {
        String legacy = "version=1\nmanualVideos=QWJjX2RlZi0xMjM\nwatchedVideos=\n"
                + "showOverrides=\nchannelIds=\nchannelHandles=\n";
        assertFalse(SubscriptionManagerStateCodec.decode(legacy).currentFormat);
        assertFalse(SubscriptionManagerStateCodec.decode(
                "version=2\nmanualVideos=raw-video\nwatchedVideos=\nshowOverrides=\n"
                        + "channelIds=\nchannelHandles=\n").currentFormat);
        assertFalse(SubscriptionManagerStateCodec.decode("not-state").currentFormat);
    }

    private static String repeat(String value, int count) {
        StringBuilder repeated = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) repeated.append(value);
        return repeated.toString();
    }
}
