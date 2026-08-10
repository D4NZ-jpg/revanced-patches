package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SubscriptionManagerStateCodecTest {
    @Test
    public void roundTripPreservesAllPersistentSets() {
        SubscriptionManagerState.Snapshot snapshot = SubscriptionManagerState.Snapshot.create(
                Arrays.asList("manual-b", "manual-a"),
                Arrays.asList("watched-a"),
                Arrays.asList("show-a"),
                Arrays.asList("channel-id-a"),
                Arrays.asList("@handle-a")
        );

        String serialized = SubscriptionManagerStateCodec.serialize(snapshot);
        SubscriptionManagerState.Snapshot restored = SubscriptionManagerStateCodec.deserialize(serialized);

        assertEquals(snapshot, restored);
        assertTrue(restored.getManuallyHiddenVideoIds().contains("manual-a"));
        assertTrue(restored.getWatchedHiddenVideoIds().contains("watched-a"));
        assertTrue(restored.getVideoShowOverrideIds().contains("show-a"));
        assertTrue(restored.getHiddenChannelIds().contains("channel-id-a"));
        assertTrue(restored.getHiddenChannelHandles().contains("@handle-a"));
    }

    @Test
    public void serializationIsDeterministicAndVersioned() {
        SubscriptionManagerState.Snapshot first = SubscriptionManagerState.Snapshot.create(
                Arrays.asList("c", "a", "b"),
                Arrays.asList("w2", "w1"),
                Arrays.asList("o"),
                Arrays.asList("ch"),
                Arrays.asList("@h")
        );
        SubscriptionManagerState.Snapshot second = SubscriptionManagerState.Snapshot.create(
                Arrays.asList("b", "c", "a"),
                Arrays.asList("w1", "w2"),
                Arrays.asList("o"),
                Arrays.asList("ch"),
                Arrays.asList("@h")
        );

        String firstSerialized = SubscriptionManagerStateCodec.serialize(first);
        String secondSerialized = SubscriptionManagerStateCodec.serialize(second);

        assertEquals(firstSerialized, secondSerialized);
        assertTrue(firstSerialized.startsWith("version=1\n"));
    }

    @Test
    public void aggregateSerializationAndDeserializationAreBounded() {
        List<String> largeIds = new ArrayList<>();
        for (int index = 0; index < SubscriptionManagerState.MAX_IDS_PER_COLLECTION; index++) {
            largeIds.add("id-" + index + '-' + repeat("\u754c", 54));
        }
        SubscriptionManagerState.Snapshot largeSnapshot = SubscriptionManagerState.Snapshot.create(
                largeIds, largeIds, largeIds, largeIds, largeIds);

        String serialized = SubscriptionManagerStateCodec.serialize(largeSnapshot);

        assertTrue(serialized.getBytes(StandardCharsets.UTF_8).length
                <= SubscriptionManagerStateCodec.MAX_SERIALIZED_BYTES);
        assertEquals(SubscriptionManagerState.Snapshot.empty(),
                SubscriptionManagerStateCodec.deserialize(serialized));

        String oversized = repeat("x", SubscriptionManagerStateCodec.MAX_SERIALIZED_BYTES + 1);
        assertEquals(SubscriptionManagerState.Snapshot.empty(),
                SubscriptionManagerStateCodec.deserialize(oversized));
    }

    @Test
    public void overCountCollectionFailsOpenInsteadOfPartiallyLoading() {
        StringBuilder manualVideos = new StringBuilder();
        for (int index = 0; index <= SubscriptionManagerState.MAX_IDS_PER_COLLECTION; index++) {
            if (index > 0) manualVideos.append(',');
            manualVideos.append("YQ");
        }
        String serialized = "version=1\nmanualVideos=" + manualVideos
                + "\nwatchedVideos=\nshowOverrides=\nchannelIds=\nchannelHandles=\n";

        assertEquals(SubscriptionManagerState.Snapshot.empty(),
                SubscriptionManagerStateCodec.deserialize(serialized));
    }

    @Test
    public void malformedDataFailsOpenToEmptySnapshot() {
        assertEquals(
                SubscriptionManagerState.Snapshot.empty(),
                SubscriptionManagerStateCodec.deserialize("version=2\nmanualVideos=bad")
        );
        assertEquals(
                SubscriptionManagerState.Snapshot.empty(),
                SubscriptionManagerStateCodec.deserialize("version=1\nmanualVideos=%%%\n")
        );
        assertEquals(
                SubscriptionManagerState.Snapshot.empty(),
                SubscriptionManagerStateCodec.deserialize(
                        "version=1\nmanualVideos=_w\nwatchedVideos=\n"
                                + "showOverrides=\nchannelIds=\nchannelHandles=\n")
        );
        assertFalse(SubscriptionManagerStateCodec.deserialize(null).shouldHideVideo("video-a"));
    }

    private static String repeat(String value, int count) {
        StringBuilder repeated = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) repeated.append(value);
        return repeated.toString();
    }
}
