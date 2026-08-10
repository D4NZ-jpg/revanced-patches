package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Bounded, schema-independent inspection of non-group protobuf wire fields. */
public final class ProtobufWireInspector {
    private static final long MAX_TAG = ((long) 0x1fffffff << 3) | 7;
    public static final Limits DEFAULT_LIMITS = new Limits(64 * 1024, 256 * 1024, 2048, 8);

    private final Limits limits;

    public ProtobufWireInspector() {
        this(DEFAULT_LIMITS);
    }

    public ProtobufWireInspector(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    public Inspection inspect(byte[] data) {
        return inspect(data, null);
    }

    Inspection inspect(byte[] data, LengthDelimitedVisitor visitor) {
        if (data == null) {
            return Inspection.empty(StopReason.MALFORMED);
        }
        if (data.length > limits.maxInputBytes()) {
            return Inspection.empty(StopReason.INPUT_TOO_LARGE);
        }

        State state = new State(limits);
        List<NumericField> numericFields = new ArrayList<>();
        List<LengthDelimitedField> lengthDelimitedFields = new ArrayList<>();
        ParseOutcome outcome;
        try {
            outcome = parseRegion(data, 0, data.length, 0, FieldPath.root(), state,
                    numericFields, lengthDelimitedFields);
        } catch (RuntimeException ignored) {
            outcome = ParseOutcome.MALFORMED;
        }

        StopReason reason = stopReason(state, outcome);
        if (visitor != null) {
            for (LengthDelimitedField field : lengthDelimitedFields) {
                visitor.visit(field.path, data, field.offset, field.length);
            }
        }
        return new Inspection(reason, numericFields, state.fieldsVisited, state.bytesVisited);
    }

    /** Inspects identities without retaining fields or constructing paths for diagnostics. */
    Inspection inspectIdentityOnly(byte[] data, IdentityLengthDelimitedVisitor visitor) {
        if (data == null) {
            return Inspection.empty(StopReason.MALFORMED);
        }
        if (data.length > limits.maxInputBytes()) {
            return Inspection.empty(StopReason.INPUT_TOO_LARGE);
        }

        State state = new State(limits);
        ParseOutcome outcome;
        try {
            outcome = parseIdentityRegion(data, 0, data.length, 0, state, visitor);
        } catch (RuntimeException ignored) {
            outcome = ParseOutcome.MALFORMED;
        }
        return Inspection.identityOnly(stopReason(state, outcome),
                state.fieldsVisited, state.bytesVisited);
    }

    private static StopReason stopReason(State state, ParseOutcome outcome) {
        if (state.byteBudgetExceeded) return StopReason.BYTE_BUDGET_EXCEEDED;
        if (state.fieldBudgetExceeded) return StopReason.FIELD_BUDGET_EXCEEDED;
        if (state.depthLimitExceeded) return StopReason.DEPTH_LIMIT_EXCEEDED;
        return outcome == ParseOutcome.VALID ? StopReason.COMPLETE : StopReason.MALFORMED;
    }

    private ParseOutcome parseRegion(byte[] data, int start, int end, int depth, FieldPath parent,
                                     State state, List<NumericField> numericFields,
                                     List<LengthDelimitedField> lengthDelimitedFields) {
        int position = start;
        boolean sawField = false;
        while (position < end) {
            if (!state.visitField()) return ParseOutcome.LIMIT;
            sawField = true;

            Varint tag = readVarint(data, position, end, state);
            if (tag == null || tag.value <= 0 || tag.value > MAX_TAG) {
                return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
            }
            position = tag.nextOffset;
            int wireType = (int) (tag.value & 7);
            int fieldNumber = (int) (tag.value >>> 3);
            if (fieldNumber == 0) return ParseOutcome.MALFORMED;
            FieldPath path = parent.child(fieldNumber);

            switch (wireType) {
                case 0: {
                    int valueOffset = position;
                    Varint value = readVarint(data, position, end, state);
                    if (value == null) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    numericFields.add(new NumericField(path, valueOffset, 0, value.value));
                    position = value.nextOffset;
                    break;
                }
                case 1: {
                    if (end - position < 8 || !state.visitBytes(8)) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    numericFields.add(new NumericField(path, position, 1,
                            readLittleEndian(data, position, 8)));
                    position += 8;
                    break;
                }
                case 2: {
                    Varint length = readVarint(data, position, end, state);
                    if (length == null || length.value < 0 || length.value > Integer.MAX_VALUE) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    position = length.nextOffset;
                    int byteLength = (int) length.value;
                    if (byteLength > end - position) return ParseOutcome.MALFORMED;
                    if (!state.visitBytes(byteLength)) return ParseOutcome.LIMIT;

                    lengthDelimitedFields.add(new LengthDelimitedField(path, position, byteLength));
                    if (byteLength > 0) {
                        ParseOutcome validation = validateWireMessage(
                                data, position, position + byteLength, state);
                        if (validation == ParseOutcome.LIMIT) return ParseOutcome.LIMIT;
                        if (validation == ParseOutcome.VALID) {
                            if (depth >= limits.maxDepth()) {
                                state.depthLimitExceeded = true;
                            } else {
                                List<NumericField> nestedNumeric = new ArrayList<>();
                                List<LengthDelimitedField> nestedLengths = new ArrayList<>();
                                ParseOutcome nested = parseRegion(data, position,
                                        position + byteLength, depth + 1, path, state,
                                        nestedNumeric, nestedLengths);
                                if (nested == ParseOutcome.LIMIT) return ParseOutcome.LIMIT;
                                if (nested == ParseOutcome.VALID) {
                                    numericFields.addAll(nestedNumeric);
                                    lengthDelimitedFields.addAll(nestedLengths);
                                }
                            }
                        }
                    }
                    position += byteLength;
                    break;
                }
                case 5: {
                    if (end - position < 4 || !state.visitBytes(4)) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    numericFields.add(new NumericField(path, position, 5,
                            readLittleEndian(data, position, 4)));
                    position += 4;
                    break;
                }
                default:
                    return ParseOutcome.MALFORMED;
            }
        }
        return sawField || start == end ? ParseOutcome.VALID : ParseOutcome.MALFORMED;
    }

    private ParseOutcome parseIdentityRegion(byte[] data, int start, int end, int depth,
                                             State state,
                                             IdentityLengthDelimitedVisitor visitor) {
        int position = start;
        boolean sawField = false;
        while (position < end) {
            if (!state.visitField()) return ParseOutcome.LIMIT;
            sawField = true;

            Varint tag = readVarint(data, position, end, state);
            if (tag == null || tag.value <= 0 || tag.value > MAX_TAG) {
                return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
            }
            position = tag.nextOffset;
            int wireType = (int) (tag.value & 7);
            int fieldNumber = (int) (tag.value >>> 3);
            if (fieldNumber == 0) return ParseOutcome.MALFORMED;

            switch (wireType) {
                case 0: {
                    Varint value = readVarint(data, position, end, state);
                    if (value == null) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    position = value.nextOffset;
                    break;
                }
                case 1:
                    if (end - position < 8 || !state.visitBytes(8)) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    position += 8;
                    break;
                case 2: {
                    Varint length = readVarint(data, position, end, state);
                    if (length == null || length.value < 0 || length.value > Integer.MAX_VALUE) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    position = length.nextOffset;
                    int byteLength = (int) length.value;
                    if (byteLength > end - position) return ParseOutcome.MALFORMED;
                    if (!state.visitBytes(byteLength)) return ParseOutcome.LIMIT;

                    if (visitor != null) visitor.visit(data, position, byteLength);
                    if (byteLength > 0) {
                        ParseOutcome validation = validateWireMessage(
                                data, position, position + byteLength, state);
                        if (validation == ParseOutcome.LIMIT) return ParseOutcome.LIMIT;
                        if (validation == ParseOutcome.VALID) {
                            if (depth >= limits.maxDepth()) {
                                state.depthLimitExceeded = true;
                            } else {
                                ParseOutcome nested = parseIdentityRegion(data, position,
                                        position + byteLength, depth + 1, state, visitor);
                                if (nested == ParseOutcome.LIMIT) return ParseOutcome.LIMIT;
                            }
                        }
                    }
                    position += byteLength;
                    break;
                }
                case 5:
                    if (end - position < 4 || !state.visitBytes(4)) {
                        return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
                    }
                    position += 4;
                    break;
                default:
                    return ParseOutcome.MALFORMED;
            }
        }
        return sawField || start == end ? ParseOutcome.VALID : ParseOutcome.MALFORMED;
    }

    /** Non-allocating validation cursor charged to the same budgets as full parsing. */
    private static ParseOutcome validateWireMessage(
            byte[] data, int start, int end, State state) {
        int position = start;
        boolean sawField = false;
        while (position < end) {
            if (!state.visitField()) return ParseOutcome.LIMIT;
            sawField = true;

            Varint tag = readVarint(data, position, end, state);
            if (tag == null || tag.value <= 0 || tag.value > MAX_TAG) {
                return state.limitExceeded() ? ParseOutcome.LIMIT : ParseOutcome.MALFORMED;
            }
            position = tag.nextOffset;
            int fieldNumber = (int) (tag.value >>> 3);
            int wireType = (int) (tag.value & 7);
            if (fieldNumber == 0) return ParseOutcome.MALFORMED;
            switch (wireType) {
                case 0: {
                    Varint value = readVarint(data, position, end, state);
                    if (value == null) {
                        return state.limitExceeded()
                                ? ParseOutcome.LIMIT
                                : ParseOutcome.MALFORMED;
                    }
                    position = value.nextOffset;
                    break;
                }
                case 1:
                    if (end - position < 8 || !state.visitBytes(8)) {
                        return state.limitExceeded()
                                ? ParseOutcome.LIMIT
                                : ParseOutcome.MALFORMED;
                    }
                    position += 8;
                    break;
                case 2: {
                    Varint length = readVarint(data, position, end, state);
                    if (length == null || length.value < 0
                            || length.value > end - length.nextOffset) {
                        return state.limitExceeded()
                                ? ParseOutcome.LIMIT
                                : ParseOutcome.MALFORMED;
                    }
                    int byteLength = (int) length.value;
                    if (!state.visitBytes(byteLength)) return ParseOutcome.LIMIT;
                    position = length.nextOffset + byteLength;
                    break;
                }
                case 5:
                    if (end - position < 4 || !state.visitBytes(4)) {
                        return state.limitExceeded()
                                ? ParseOutcome.LIMIT
                                : ParseOutcome.MALFORMED;
                    }
                    position += 4;
                    break;
                default:
                    return ParseOutcome.MALFORMED;
            }
        }
        return sawField ? ParseOutcome.VALID : ParseOutcome.MALFORMED;
    }

    private static Varint readVarint(byte[] data, int start, int end, State state) {
        long value = 0;
        int position = start;
        for (int index = 0; index < 10 && position < end; index++) {
            if (!state.visitBytes(1)) return null;
            int current = data[position++] & 0xff;
            if (index == 9 && current > 1) return null;
            value |= (long) (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) return new Varint(value, position);
        }
        return null;
    }

    private static long readLittleEndian(byte[] data, int offset, int byteCount) {
        long value = 0;
        for (int index = 0; index < byteCount; index++) {
            value |= (long) (data[offset + index] & 0xff) << (index * 8);
        }
        return value;
    }

    interface LengthDelimitedVisitor {
        void visit(FieldPath path, byte[] data, int offset, int length);
    }

    interface IdentityLengthDelimitedVisitor {
        void visit(byte[] data, int offset, int length);
    }

    private enum ParseOutcome { VALID, MALFORMED, LIMIT }

    public enum StopReason {
        COMPLETE,
        MALFORMED,
        INPUT_TOO_LARGE,
        BYTE_BUDGET_EXCEEDED,
        FIELD_BUDGET_EXCEEDED,
        DEPTH_LIMIT_EXCEEDED
    }

    public static final class Limits {
        private final int maxInputBytes;
        private final int maxBytesVisited;
        private final int maxFields;
        private final int maxDepth;

        public Limits(int maxInputBytes, int maxBytesVisited, int maxFields, int maxDepth) {
            if (maxInputBytes <= 0 || maxBytesVisited <= 0 || maxFields <= 0 || maxDepth < 0) {
                throw new IllegalArgumentException("Limits must be positive (depth may be zero)");
            }
            this.maxInputBytes = maxInputBytes;
            this.maxBytesVisited = maxBytesVisited;
            this.maxFields = maxFields;
            this.maxDepth = maxDepth;
        }

        public int maxInputBytes() { return maxInputBytes; }
        public int maxBytesVisited() { return maxBytesVisited; }
        public int maxFields() { return maxFields; }
        public int maxDepth() { return maxDepth; }
    }

    public static final class FieldPath {
        private static final FieldPath ROOT = new FieldPath(new int[0]);
        private final int[] fields;

        private FieldPath(int[] fields) {
            this.fields = fields;
        }

        public static FieldPath root() {
            return ROOT;
        }

        public static FieldPath of(int... fields) {
            Objects.requireNonNull(fields);
            int[] copy = fields.clone();
            for (int field : copy) {
                if (field <= 0) throw new IllegalArgumentException("Field numbers must be positive");
            }
            return copy.length == 0 ? ROOT : new FieldPath(copy);
        }

        FieldPath child(int field) {
            int[] child = Arrays.copyOf(fields, fields.length + 1);
            child[fields.length] = field;
            return new FieldPath(child);
        }

        public int size() { return fields.length; }
        public int fieldAt(int index) { return fields[index]; }
        public int[] toArray() { return fields.clone(); }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof FieldPath
                    && Arrays.equals(fields, ((FieldPath) other).fields);
        }

        @Override
        public int hashCode() { return Arrays.hashCode(fields); }

        @Override
        public String toString() {
            if (fields.length == 0) return "<root>";
            StringBuilder result = new StringBuilder();
            for (int field : fields) {
                if (result.length() > 0) result.append('.');
                result.append(field);
            }
            return result.toString();
        }
    }

    public static final class NumericField {
        private final FieldPath path;
        private final int valueOffset;
        private final int wireType;
        private final long value;

        NumericField(FieldPath path, int valueOffset, int wireType, long value) {
            this.path = path;
            this.valueOffset = valueOffset;
            this.wireType = wireType;
            this.value = value;
        }

        public FieldPath path() { return path; }
        public int valueOffset() { return valueOffset; }
        public int wireType() { return wireType; }
        public long value() { return value; }
    }

    public static final class Inspection {
        private final StopReason stopReason;
        private final List<NumericField> numericFields;
        private final int fieldsVisited;
        private final int bytesVisited;

        Inspection(StopReason stopReason, List<NumericField> numericFields,
                   int fieldsVisited, int bytesVisited) {
            this.stopReason = stopReason;
            this.numericFields = Collections.unmodifiableList(new ArrayList<>(numericFields));
            this.fieldsVisited = fieldsVisited;
            this.bytesVisited = bytesVisited;
        }

        static Inspection empty(StopReason reason) {
            return identityOnly(reason, 0, 0);
        }

        static Inspection identityOnly(StopReason reason, int fieldsVisited, int bytesVisited) {
            return new Inspection(reason, Collections.emptyList(), fieldsVisited, bytesVisited, false);
        }

        private Inspection(StopReason stopReason, List<NumericField> numericFields,
                           int fieldsVisited, int bytesVisited, boolean copyFields) {
            this.stopReason = stopReason;
            this.numericFields = copyFields
                    ? Collections.unmodifiableList(new ArrayList<>(numericFields))
                    : numericFields;
            this.fieldsVisited = fieldsVisited;
            this.bytesVisited = bytesVisited;
        }

        public StopReason stopReason() { return stopReason; }
        public boolean isComplete() { return stopReason == StopReason.COMPLETE; }
        public List<NumericField> numericFields() { return numericFields; }
        public int fieldsVisited() { return fieldsVisited; }
        public int bytesVisited() { return bytesVisited; }
    }

    private static final class State {
        final Limits limits;
        int fieldsVisited;
        int bytesVisited;
        boolean fieldBudgetExceeded;
        boolean byteBudgetExceeded;
        boolean depthLimitExceeded;

        State(Limits limits) { this.limits = limits; }

        boolean visitField() {
            if (fieldsVisited >= limits.maxFields()) {
                fieldBudgetExceeded = true;
                return false;
            }
            fieldsVisited++;
            return true;
        }

        boolean visitBytes(int count) {
            if (count < 0 || count > limits.maxBytesVisited() - bytesVisited) {
                byteBudgetExceeded = true;
                return false;
            }
            bytesVisited += count;
            return true;
        }

        boolean limitExceeded() { return fieldBudgetExceeded || byteBudgetExceeded; }
    }

    private static final class Varint {
        final long value;
        final int nextOffset;
        Varint(long value, int nextOffset) { this.value = value; this.nextOffset = nextOffset; }
    }

    private static final class LengthDelimitedField {
        final FieldPath path;
        final int offset;
        final int length;

        LengthDelimitedField(FieldPath path, int offset, int length) {
            this.path = path;
            this.offset = offset;
            this.length = length;
        }
    }
}
