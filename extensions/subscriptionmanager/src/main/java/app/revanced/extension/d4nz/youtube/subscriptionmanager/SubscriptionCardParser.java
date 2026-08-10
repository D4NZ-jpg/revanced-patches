package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParseResult.CandidateIdentity;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParseResult.ChannelIdentity;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParseResult.IdentityKind;
import app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParseResult.WatchedStatus;

/** Extracts only unambiguous card identities and explicitly profiled progress fields. */
public final class SubscriptionCardParser {
    private static final int MAX_CANDIDATE_URL_BYTES = 2048;
    private static final Pattern THUMBNAIL_PATH = Pattern.compile(
            "^/(?:vi|vi_webp)/([A-Za-z0-9_-]{11})/[^/]+$");
    private static final Pattern CHANNEL_PATH = Pattern.compile(
            "^/channel/(UC[A-Za-z0-9_-]{22})/?$");
    private static final Pattern HANDLE_PATH = Pattern.compile(
            "^/(@[A-Za-z0-9][A-Za-z0-9._-]{1,28}[A-Za-z0-9])/?$");

    private final ProtobufWireInspector inspector;

    public SubscriptionCardParser() {
        this(new ProtobufWireInspector());
    }

    public SubscriptionCardParser(ProtobufWireInspector.Limits limits) {
        this(new ProtobufWireInspector(limits));
    }

    SubscriptionCardParser(ProtobufWireInspector inspector) {
        this.inspector = Objects.requireNonNull(inspector);
    }

    public SubscriptionCardParseResult parse(byte[] protobuf) {
        return parse(protobuf, null);
    }

    /** A null profile leaves watched progress unknown rather than inferring a numeric field. */
    public SubscriptionCardParseResult parse(byte[] protobuf, ProgressProfile progressProfile) {
        DiagnosticCandidateCollector candidates = new DiagnosticCandidateCollector();
        ProtobufWireInspector.Inspection inspection = inspector.inspect(protobuf,
                (path, data, offset, length) ->
                        inspectLengthDelimited(path, data, offset, length, candidates));

        String videoId = null;
        ChannelIdentity channelIdentity = null;
        OptionalLong progress = OptionalLong.empty();
        WatchedStatus watchedStatus = WatchedStatus.UNKNOWN;

        if (inspection.isComplete()) {
            videoId = candidates.uniqueVideoId();
            channelIdentity = candidates.uniqueChannelIdentity();
            if (progressProfile != null) {
                progress = configuredProgress(inspection.numericFields(), progressProfile);
                if (progress.isPresent()) {
                    watchedStatus = progress.getAsLong() >= progressProfile.watchedThreshold()
                            ? WatchedStatus.WATCHED
                            : WatchedStatus.NOT_WATCHED;
                }
            }
        }

        SubscriptionCardParseResult.Diagnostics diagnostics =
                new SubscriptionCardParseResult.Diagnostics(inspection, candidates.diagnostics());
        return new SubscriptionCardParseResult(videoId, channelIdentity, progress,
                watchedStatus, diagnostics);
    }

    /** Hot-path parsing used by production filtering when only the video ID is needed. */
    String parseUniqueVideoId(byte[] protobuf) {
        VideoIdCandidateCollector candidates = new VideoIdCandidateCollector();
        ProtobufWireInspector.Inspection inspection = inspector.inspectIdentityOnly(protobuf,
                (data, offset, length) ->
                        inspectLengthDelimited(null, data, offset, length, candidates));
        return inspection.isComplete() ? candidates.uniqueVideoId() : null;
    }

    /** Detailed identity-only parsing retained for parity and limit regression tests. */
    SubscriptionCardParseResult parseIdentityOnly(byte[] protobuf) {
        IdentityCandidateCollector candidates = new IdentityCandidateCollector();
        ProtobufWireInspector.Inspection inspection = inspector.inspectIdentityOnly(protobuf,
                (data, offset, length) ->
                        inspectLengthDelimited(null, data, offset, length, candidates));

        String videoId = null;
        ChannelIdentity channelIdentity = null;
        if (inspection.isComplete()) {
            videoId = candidates.uniqueVideoId();
            channelIdentity = candidates.uniqueChannelIdentity();
        }
        return new SubscriptionCardParseResult(videoId, channelIdentity, OptionalLong.empty(),
                WatchedStatus.UNKNOWN,
                SubscriptionCardParseResult.Diagnostics.identityOnly(inspection));
    }

    private static OptionalLong configuredProgress(
            List<ProtobufWireInspector.NumericField> numericFields, ProgressProfile profile) {
        Set<Long> matches = new LinkedHashSet<>();
        for (ProtobufWireInspector.NumericField field : numericFields) {
            if (field.wireType() == 0 && field.path().equals(profile.path())
                    && field.value() >= profile.minimumValue()
                    && field.value() <= profile.maximumValue()) {
                matches.add(field.value());
            }
        }
        return matches.size() == 1 ? OptionalLong.of(matches.iterator().next()) : OptionalLong.empty();
    }

    private static void inspectLengthDelimited(ProtobufWireInspector.FieldPath path, byte[] data,
                                               int offset, int length, CandidateSink candidates) {
        if (length == 0 || length > MAX_CANDIDATE_URL_BYTES
                || !hasPlausibleIdentityUrlPrefix(data, offset, length)
                || !isPrintableAscii(data, offset, length)) {
            return;
        }
        String value = new String(data, offset, length, StandardCharsets.US_ASCII);
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ignored) {
            return;
        }
        if (uri.getFragment() != null || uri.getUserInfo() != null) return;

        String pathValue = uri.getPath();
        if (pathValue == null) return;
        String host = uri.getHost();
        boolean relative = uri.getScheme() == null && host == null && value.startsWith("/");

        if (!relative && isHttp(uri) && isThumbnailHost(host)) {
            Matcher thumbnail = THUMBNAIL_PATH.matcher(pathValue);
            if (thumbnail.matches()) {
                candidates.add(IdentityKind.VIDEO_ID, thumbnail.group(1), path, offset);
            }
        }

        if ((relative || isHttp(uri) && isYouTubeHost(host)) && uri.getQuery() == null) {
            Matcher channel = CHANNEL_PATH.matcher(pathValue);
            if (channel.matches()) {
                candidates.add(IdentityKind.CHANNEL_ID, channel.group(1), path, offset);
                return;
            }
            Matcher handle = HANDLE_PATH.matcher(pathValue);
            if (handle.matches()) {
                candidates.add(IdentityKind.CHANNEL_HANDLE, handle.group(1), path, offset);
            }
        }
    }

    private static boolean hasPlausibleIdentityUrlPrefix(byte[] data, int offset, int length) {
        return asciiPrefixIgnoreCase(data, offset, length, "http://")
                || asciiPrefixIgnoreCase(data, offset, length, "https://")
                || asciiPrefix(data, offset, length, "/channel/")
                || asciiPrefix(data, offset, length, "/@");
    }

    private static boolean asciiPrefixIgnoreCase(byte[] data, int offset, int length, String prefix) {
        if (length < prefix.length()) return false;
        for (int index = 0; index < prefix.length(); index++) {
            int actual = data[offset + index] & 0xff;
            char expected = prefix.charAt(index);
            if (actual >= 'A' && actual <= 'Z') actual += 'a' - 'A';
            if (actual != expected) return false;
        }
        return true;
    }

    private static boolean asciiPrefix(byte[] data, int offset, int length, String prefix) {
        if (length < prefix.length()) return false;
        for (int index = 0; index < prefix.length(); index++) {
            if ((data[offset + index] & 0xff) != prefix.charAt(index)) return false;
        }
        return true;
    }

    private static boolean isPrintableAscii(byte[] data, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            int value = data[index] & 0xff;
            if (value < 0x21 || value > 0x7e) return false;
        }
        return true;
    }

    private static boolean isHttp(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
    }

    private static boolean isThumbnailHost(String host) {
        if (host == null) return false;
        String lower = asciiLowercase(host);
        return lower.equals("ytimg.com") || lower.endsWith(".ytimg.com");
    }

    private static boolean isYouTubeHost(String host) {
        if (host == null) return false;
        String lower = asciiLowercase(host);
        return lower.equals("youtube.com") || lower.endsWith(".youtube.com");
    }

    private static String asciiLowercase(String value) {
        StringBuilder lower = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            lower.append(current >= 'A' && current <= 'Z' ? (char) (current + ('a' - 'A')) : current);
        }
        return lower.toString();
    }

    public static final class ProgressProfile {
        private final ProtobufWireInspector.FieldPath path;
        private final long minimumValue;
        private final long maximumValue;
        private final long watchedThreshold;

        public ProgressProfile(ProtobufWireInspector.FieldPath path, long minimumValue,
                               long maximumValue, long watchedThreshold) {
            this.path = Objects.requireNonNull(path);
            if (path.size() == 0) throw new IllegalArgumentException("Progress path cannot be empty");
            if (minimumValue < 0 || maximumValue < minimumValue
                    || watchedThreshold < minimumValue || watchedThreshold > maximumValue) {
                throw new IllegalArgumentException("Invalid progress value range or threshold");
            }
            this.minimumValue = minimumValue;
            this.maximumValue = maximumValue;
            this.watchedThreshold = watchedThreshold;
        }

        public static ProgressProfile percentage(ProtobufWireInspector.FieldPath path,
                                                 long watchedThreshold) {
            return new ProgressProfile(path, 0, 100, watchedThreshold);
        }

        public ProtobufWireInspector.FieldPath path() { return path; }
        public long minimumValue() { return minimumValue; }
        public long maximumValue() { return maximumValue; }
        public long watchedThreshold() { return watchedThreshold; }
    }

    private interface CandidateSink {
        void add(IdentityKind kind, String value, ProtobufWireInspector.FieldPath path, int offset);
    }

    private static final class DiagnosticCandidateCollector implements CandidateSink {
        private final Map<String, CandidateIdentity> candidates = new LinkedHashMap<>();

        @Override
        public void add(IdentityKind kind, String value,
                        ProtobufWireInspector.FieldPath path, int offset) {
            String key = kind.name() + ':' + value;
            candidates.putIfAbsent(key, new CandidateIdentity(kind, value, path, offset));
        }

        String uniqueVideoId() {
            String value = null;
            for (CandidateIdentity candidate : candidates.values()) {
                if (candidate.kind() != IdentityKind.VIDEO_ID) continue;
                if (value != null && !value.equals(candidate.value())) return null;
                value = candidate.value();
            }
            return value;
        }

        ChannelIdentity uniqueChannelIdentity() {
            CandidateIdentity match = null;
            for (CandidateIdentity candidate : candidates.values()) {
                if (candidate.kind() == IdentityKind.VIDEO_ID) continue;
                if (match != null && (match.kind() != candidate.kind()
                        || !match.value().equals(candidate.value()))) {
                    return null;
                }
                match = candidate;
            }
            return match == null ? null : new ChannelIdentity(match.kind(), match.value());
        }

        List<CandidateIdentity> diagnostics() {
            return new ArrayList<>(candidates.values());
        }
    }

    private static final class VideoIdCandidateCollector implements CandidateSink {
        private String videoId;
        private boolean conflictingVideoIds;

        @Override
        public void add(IdentityKind kind, String value,
                        ProtobufWireInspector.FieldPath unusedPath, int unusedOffset) {
            if (kind != IdentityKind.VIDEO_ID) return;
            if (videoId == null) {
                videoId = value;
            } else if (!videoId.equals(value)) {
                conflictingVideoIds = true;
            }
        }

        String uniqueVideoId() {
            return conflictingVideoIds ? null : videoId;
        }
    }

    private static final class IdentityCandidateCollector implements CandidateSink {
        private String videoId;
        private boolean conflictingVideoIds;
        private IdentityKind channelKind;
        private String channelValue;
        private boolean conflictingChannelIdentities;

        @Override
        public void add(IdentityKind kind, String value,
                        ProtobufWireInspector.FieldPath unusedPath, int unusedOffset) {
            if (kind == IdentityKind.VIDEO_ID) {
                if (videoId == null) {
                    videoId = value;
                } else if (!videoId.equals(value)) {
                    conflictingVideoIds = true;
                }
                return;
            }
            if (channelValue == null) {
                channelKind = kind;
                channelValue = value;
            } else if (channelKind != kind || !channelValue.equals(value)) {
                conflictingChannelIdentities = true;
            }
        }

        String uniqueVideoId() {
            return conflictingVideoIds ? null : videoId;
        }

        ChannelIdentity uniqueChannelIdentity() {
            return channelValue == null || conflictingChannelIdentities
                    ? null
                    : new ChannelIdentity(channelKind, channelValue);
        }
    }
}
