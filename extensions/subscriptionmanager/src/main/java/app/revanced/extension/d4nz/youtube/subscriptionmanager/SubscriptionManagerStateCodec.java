package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Codec for account-scoped, one-way identity keys. Raw identities are never accepted here. */
public final class SubscriptionManagerStateCodec {
    public static final int VERSION = 2;
    public static final int MAX_SERIALIZED_BYTES = 512 * 1024;

    private static final String VERSION_LINE = "version=" + VERSION;
    private static final String MANUAL_VIDEOS_KEY = "manualVideos";
    private static final String WATCHED_VIDEOS_KEY = "watchedVideos";
    private static final String SHOW_OVERRIDES_KEY = "showOverrides";
    private static final String CHANNEL_IDS_KEY = "channelIds";
    private static final String CHANNEL_HANDLES_KEY = "channelHandles";
    private static final String[] ORDERED_KEYS = new String[]{
            MANUAL_VIDEOS_KEY, WATCHED_VIDEOS_KEY, SHOW_OVERRIDES_KEY,
            CHANNEL_IDS_KEY, CHANNEL_HANDLES_KEY
    };

    private SubscriptionManagerStateCodec() {
    }

    public static String serialize(SubscriptionManagerState.Snapshot snapshot) {
        if (snapshot == null) snapshot = SubscriptionManagerState.Snapshot.empty();

        StringBuilder builder = new StringBuilder();
        builder.append(VERSION_LINE).append('\n');
        List<Set<String>> collections = snapshot.collectionsInSerializationOrder();
        for (int i = 0; i < ORDERED_KEYS.length; i++) {
            builder.append(ORDERED_KEYS[i]).append('=').append(serializeSet(collections.get(i))).append('\n');
        }
        String serialized = builder.toString();
        return utf8Length(serialized) <= MAX_SERIALIZED_BYTES
                ? serialized : serialize(SubscriptionManagerState.Snapshot.empty());
    }

    public static SubscriptionManagerState.Snapshot deserialize(String serialized) {
        return decode(serialized).snapshot;
    }

    static DecodeResult decode(String serialized) {
        if (serialized == null || serialized.isEmpty()
                || serialized.length() > MAX_SERIALIZED_BYTES
                || utf8Length(serialized) > MAX_SERIALIZED_BYTES) {
            return new DecodeResult(SubscriptionManagerState.Snapshot.empty(), false);
        }
        try {
            Map<String, String> valuesByKey = parseLines(serialized);
            if (!String.valueOf(VERSION).equals(valuesByKey.remove("version"))) {
                return new DecodeResult(SubscriptionManagerState.Snapshot.empty(), false);
            }
            String manualVideos = valuesByKey.remove(MANUAL_VIDEOS_KEY);
            String watchedVideos = valuesByKey.remove(WATCHED_VIDEOS_KEY);
            String showOverrides = valuesByKey.remove(SHOW_OVERRIDES_KEY);
            String channelIds = valuesByKey.remove(CHANNEL_IDS_KEY);
            String channelHandles = valuesByKey.remove(CHANNEL_HANDLES_KEY);
            if (manualVideos == null || watchedVideos == null || showOverrides == null
                    || channelIds == null || channelHandles == null || !valuesByKey.isEmpty()) {
                return new DecodeResult(SubscriptionManagerState.Snapshot.empty(), false);
            }
            SubscriptionManagerState.Snapshot snapshot = SubscriptionManagerState.Snapshot.create(
                    deserializeSet(manualVideos), deserializeSet(watchedVideos),
                    deserializeSet(showOverrides), deserializeSet(channelIds),
                    deserializeSet(channelHandles));
            return new DecodeResult(snapshot, true);
        } catch (IllegalArgumentException ex) {
            return new DecodeResult(SubscriptionManagerState.Snapshot.empty(), false);
        }
    }

    private static Map<String, String> parseLines(String serialized) {
        Map<String, String> valuesByKey = new LinkedHashMap<>();
        String[] lines = serialized.split("\\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) continue;
            int separatorIndex = line.indexOf('=');
            if (separatorIndex <= 0) throw new IllegalArgumentException("Malformed line");
            String key = line.substring(0, separatorIndex);
            if (valuesByKey.put(key, line.substring(separatorIndex + 1)) != null) {
                throw new IllegalArgumentException("Duplicate key");
            }
        }
        return valuesByKey;
    }

    private static String serializeSet(Set<String> keys) {
        if (keys == null || keys.isEmpty()) return "";
        List<String> sortedKeys = new ArrayList<>(keys);
        Collections.sort(sortedKeys);
        StringBuilder builder = new StringBuilder();
        int count = Math.min(sortedKeys.size(), SubscriptionManagerState.MAX_IDS_PER_COLLECTION);
        for (int i = 0; i < count; i++) {
            if (i > 0) builder.append(',');
            builder.append(sortedKeys.get(i));
        }
        return builder.toString();
    }

    private static List<String> deserializeSet(String serializedSet) {
        if (serializedSet == null || serializedSet.isEmpty()) return Collections.emptyList();
        String[] keys = serializedSet.split(",", -1);
        if (keys.length > SubscriptionManagerState.MAX_IDS_PER_COLLECTION) {
            throw new IllegalArgumentException("Too many keys");
        }
        ArrayList<String> result = new ArrayList<>(keys.length);
        for (String key : keys) {
            if (!SubscriptionManagerState.isValidStoredKey(key)) {
                throw new IllegalArgumentException("Invalid stored key");
            }
            result.add(key);
        }
        return result;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static final class DecodeResult {
        final SubscriptionManagerState.Snapshot snapshot;
        final boolean currentFormat;

        DecodeResult(SubscriptionManagerState.Snapshot snapshot, boolean currentFormat) {
            this.snapshot = snapshot;
            this.currentFormat = currentFormat;
        }
    }
}
