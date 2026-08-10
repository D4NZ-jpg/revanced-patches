package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class SubscriptionCardParseResult {
    private final String videoId;
    private final ChannelIdentity channelIdentity;
    private final OptionalLong watchedProgress;
    private final WatchedStatus watchedStatus;
    private final Diagnostics diagnostics;

    SubscriptionCardParseResult(String videoId, ChannelIdentity channelIdentity,
                                OptionalLong watchedProgress, WatchedStatus watchedStatus,
                                Diagnostics diagnostics) {
        this.videoId = videoId;
        this.channelIdentity = channelIdentity;
        this.watchedProgress = Objects.requireNonNull(watchedProgress);
        this.watchedStatus = Objects.requireNonNull(watchedStatus);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    public Optional<String> videoId() {
        return Optional.ofNullable(videoId);
    }

    public Optional<ChannelIdentity> channelIdentity() {
        return Optional.ofNullable(channelIdentity);
    }

    public OptionalLong watchedProgress() {
        return watchedProgress;
    }

    public WatchedStatus watchedStatus() {
        return watchedStatus;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    public enum WatchedStatus {
        UNKNOWN,
        NOT_WATCHED,
        WATCHED
    }

    public enum IdentityKind {
        VIDEO_ID,
        CHANNEL_ID,
        CHANNEL_HANDLE
    }

    public static final class ChannelIdentity {
        private final IdentityKind kind;
        private final String value;

        ChannelIdentity(IdentityKind kind, String value) {
            if (kind != IdentityKind.CHANNEL_ID && kind != IdentityKind.CHANNEL_HANDLE) {
                throw new IllegalArgumentException("Not a channel identity kind");
            }
            this.kind = kind;
            this.value = Objects.requireNonNull(value);
        }

        public IdentityKind kind() { return kind; }
        public String value() { return value; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ChannelIdentity)) return false;
            ChannelIdentity identity = (ChannelIdentity) other;
            return kind == identity.kind && value.equals(identity.value);
        }

        @Override
        public int hashCode() {
            return 31 * kind.hashCode() + value.hashCode();
        }

        @Override
        public String toString() {
            return kind + ":" + value;
        }
    }

    public static final class Diagnostics {
        private final ProtobufWireInspector.StopReason stopReason;
        private final List<ProtobufWireInspector.NumericField> numericFields;
        private final List<CandidateIdentity> candidateIdentities;
        private final int fieldsVisited;
        private final int bytesVisited;

        Diagnostics(ProtobufWireInspector.Inspection inspection,
                    List<CandidateIdentity> candidateIdentities) {
            this.stopReason = inspection.stopReason();
            this.numericFields = inspection.numericFields();
            this.candidateIdentities = candidateIdentities.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(candidateIdentities));
            this.fieldsVisited = inspection.fieldsVisited();
            this.bytesVisited = inspection.bytesVisited();
        }

        static Diagnostics identityOnly(ProtobufWireInspector.Inspection inspection) {
            return new Diagnostics(inspection, Collections.emptyList());
        }

        public ProtobufWireInspector.StopReason stopReason() { return stopReason; }
        public List<ProtobufWireInspector.NumericField> numericFields() { return numericFields; }
        public List<CandidateIdentity> candidateIdentities() { return candidateIdentities; }
        public int fieldsVisited() { return fieldsVisited; }
        public int bytesVisited() { return bytesVisited; }
    }

    public static final class CandidateIdentity {
        private final IdentityKind kind;
        private final String value;
        private final ProtobufWireInspector.FieldPath path;
        private final int valueOffset;

        CandidateIdentity(IdentityKind kind, String value,
                          ProtobufWireInspector.FieldPath path, int valueOffset) {
            this.kind = Objects.requireNonNull(kind);
            this.value = Objects.requireNonNull(value);
            this.path = Objects.requireNonNull(path);
            this.valueOffset = valueOffset;
        }

        public IdentityKind kind() { return kind; }
        public String value() { return value; }
        public ProtobufWireInspector.FieldPath path() { return path; }
        public int valueOffset() { return valueOffset; }
    }
}
