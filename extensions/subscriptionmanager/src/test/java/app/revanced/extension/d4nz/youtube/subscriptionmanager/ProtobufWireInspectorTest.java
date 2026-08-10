package app.revanced.extension.d4nz.youtube.subscriptionmanager;

import static app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParserTest.bytesField;
import static app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParserTest.concat;
import static app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParserTest.message;
import static app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParserTest.varint;
import static app.revanced.extension.d4nz.youtube.subscriptionmanager.SubscriptionCardParserTest.varintField;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtobufWireInspectorTest {
    @Test
    public void handlesVarintFixed64LengthDelimitedFixed32AndUnknownFields() {
        byte[] nested = message(varintField(7, 80));
        byte[] payload = concat(
                varintField(1, 300),
                fixed64Field(100, 0x0102030405060708L),
                bytesField(50, nested),
                fixed32Field(200, 0x0a0b0c0d));

        ProtobufWireInspector.Inspection inspection = new ProtobufWireInspector().inspect(payload);

        assertTrue(inspection.isComplete());
        assertEquals(4, inspection.numericFields().size());
        assertNumeric(inspection, "1", 0, 300);
        assertNumeric(inspection, "100", 1, 0x0102030405060708L);
        assertNumeric(inspection, "50.7", 0, 80);
        assertNumeric(inspection, "200", 5, 0x0a0b0c0dL);
        for (ProtobufWireInspector.NumericField field : inspection.numericFields()) {
            assertTrue(field.valueOffset() >= 0);
        }
    }

    @Test
    public void malformedAndUnsupportedWireTypesAreReported() {
        assertEquals(ProtobufWireInspector.StopReason.MALFORMED,
                new ProtobufWireInspector().inspect(new byte[]{0x0a, 0x02, 0x08}).stopReason());
        assertEquals(ProtobufWireInspector.StopReason.MALFORMED,
                new ProtobufWireInspector().inspect(new byte[]{0x0b}).stopReason());
        assertEquals(ProtobufWireInspector.StopReason.MALFORMED,
                new ProtobufWireInspector().inspect(new byte[]{0x0d, 0x01}).stopReason());
    }

    @Test
    public void inputByteFieldAndDepthBudgetsStopInspection() {
        ProtobufWireInspector inputLimited = new ProtobufWireInspector(
                new ProtobufWireInspector.Limits(1, 100, 100, 8));
        assertEquals(ProtobufWireInspector.StopReason.INPUT_TOO_LARGE,
                inputLimited.inspect(varintField(1, 1)).stopReason());

        ProtobufWireInspector byteLimited = new ProtobufWireInspector(
                new ProtobufWireInspector.Limits(100, 1, 100, 8));
        assertEquals(ProtobufWireInspector.StopReason.BYTE_BUDGET_EXCEEDED,
                byteLimited.inspect(varintField(1, 1)).stopReason());

        ProtobufWireInspector fieldLimited = new ProtobufWireInspector(
                new ProtobufWireInspector.Limits(100, 100, 1, 8));
        assertEquals(ProtobufWireInspector.StopReason.FIELD_BUDGET_EXCEEDED,
                fieldLimited.inspect(message(varintField(1, 1), varintField(2, 2))).stopReason());

        byte[] nested = bytesField(1, bytesField(1, varintField(1, 1)));
        ProtobufWireInspector depthLimited = new ProtobufWireInspector(
                new ProtobufWireInspector.Limits(100, 1000, 100, 0));
        assertEquals(ProtobufWireInspector.StopReason.DEPTH_LIMIT_EXCEEDED,
                depthLimited.inspect(nested).stopReason());
        assertFalse(depthLimited.inspect(nested).isComplete());
    }

    @Test
    public void nestedValidationUsesGlobalFieldAndByteBudgets() {
        byte[] payload = bytesField(1, varintField(2, 1));

        ProtobufWireInspector fieldLimited = new ProtobufWireInspector(
                new ProtobufWireInspector.Limits(100, 100, 1, 8));
        ProtobufWireInspector byteLimited = new ProtobufWireInspector(
                new ProtobufWireInspector.Limits(100, 5, 100, 8));

        assertEquals(ProtobufWireInspector.StopReason.FIELD_BUDGET_EXCEEDED,
                fieldLimited.inspect(payload).stopReason());
        assertEquals(ProtobufWireInspector.StopReason.BYTE_BUDGET_EXCEEDED,
                byteLimited.inspect(payload).stopReason());
    }

    @Test
    public void fieldPathsAreValueObjects() {
        ProtobufWireInspector.FieldPath first = ProtobufWireInspector.FieldPath.of(1, 2, 3);
        ProtobufWireInspector.FieldPath second = ProtobufWireInspector.FieldPath.of(1, 2, 3);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("1.2.3", first.toString());
        int[] copy = first.toArray();
        copy[0] = 99;
        assertEquals("1.2.3", first.toString());
    }

    private static void assertNumeric(ProtobufWireInspector.Inspection inspection,
                                      String path, int wireType, long value) {
        for (ProtobufWireInspector.NumericField field : inspection.numericFields()) {
            if (path.equals(field.path().toString())) {
                assertEquals(wireType, field.wireType());
                assertEquals(value, field.value());
                return;
            }
        }
        throw new AssertionError("Missing numeric path " + path);
    }

    private static byte[] fixed32Field(int number, int value) {
        byte[] bytes = new byte[4];
        for (int index = 0; index < 4; index++) bytes[index] = (byte) (value >>> (index * 8));
        return concat(varint((long) number << 3 | 5), bytes);
    }

    private static byte[] fixed64Field(int number, long value) {
        byte[] bytes = new byte[8];
        for (int index = 0; index < 8; index++) bytes[index] = (byte) (value >>> (index * 8));
        return concat(varint((long) number << 3 | 1), bytes);
    }
}
