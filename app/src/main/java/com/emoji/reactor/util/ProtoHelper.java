package com.emoji.reactor.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量级纯 Java Protobuf 二进制流解析与重构工具 (ProtoHelper)
 * 零第三方依赖、零 Native 动态库，专门用于拦截与改写 QQ NT 网络推送数据包
 */
public class ProtoHelper {

    public static class ProtoVarInt {
        public final long value;
        public final int nextIndex;

        public ProtoVarInt(long value, int nextIndex) {
            this.value = value;
            this.nextIndex = nextIndex;
        }
    }

    public static class ProtoField {
        public final int fieldNumber;
        public final int wireType;
        public final byte[] payload;

        public ProtoField(int fieldNumber, int wireType, byte[] payload) {
            this.fieldNumber = fieldNumber;
            this.wireType = wireType;
            this.payload = payload != null ? payload : new byte[0];
        }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int tag = (fieldNumber << 3) | wireType;
            writeVarInt(out, tag);
            if (wireType == 0 || wireType == 1 || wireType == 5) {
                out.write(payload);
            } else if (wireType == 2) {
                writeVarInt(out, payload.length);
                out.write(payload);
            }
            return out.toByteArray();
        }
    }

    public static ProtoVarInt readVarInt(byte[] bytes, int startIndex) {
        if (bytes == null) return null;
        long result = 0;
        int shift = 0;
        int index = startIndex;
        while (index < bytes.length && shift < 64) {
            int b = bytes[index] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            index++;
            if ((b & 0x80) == 0) {
                return new ProtoVarInt(result, index);
            }
            shift += 7;
        }
        return null;
    }

    public static void writeVarInt(ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            } else {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    public static byte[] encodeVarInt(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, value);
        return out.toByteArray();
    }

    public static List<ProtoField> parseFields(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        List<ProtoField> fields = new ArrayList<>();
        int index = 0;
        while (index < bytes.length) {
            ProtoVarInt tagVar = readVarInt(bytes, index);
            if (tagVar == null) return null;
            index = tagVar.nextIndex;
            int fieldNumber = (int) (tagVar.value >>> 3);
            int wireType = (int) (tagVar.value & 0x07);

            if (wireType == 0) { // Varint
                int valStart = index;
                ProtoVarInt val = readVarInt(bytes, index);
                if (val == null) return null;
                int valEnd = val.nextIndex;
                byte[] payload = new byte[valEnd - valStart];
                System.arraycopy(bytes, valStart, payload, 0, payload.length);
                fields.add(new ProtoField(fieldNumber, wireType, payload));
                index = valEnd;
            } else if (wireType == 1) { // 64-bit
                if (index + 8 > bytes.length) return null;
                byte[] payload = new byte[8];
                System.arraycopy(bytes, index, payload, 0, 8);
                fields.add(new ProtoField(fieldNumber, wireType, payload));
                index += 8;
            } else if (wireType == 2) { // Length-delimited
                ProtoVarInt lenVar = readVarInt(bytes, index);
                if (lenVar == null) return null;
                int start = lenVar.nextIndex;
                if (lenVar.value < 0 || lenVar.value > (bytes.length - start)) return null;
                int len = (int) lenVar.value;
                byte[] payload = new byte[len];
                System.arraycopy(bytes, start, payload, 0, len);
                fields.add(new ProtoField(fieldNumber, wireType, payload));
                index = start + len;
            } else if (wireType == 5) { // 32-bit
                if (index + 4 > bytes.length) return null;
                byte[] payload = new byte[4];
                System.arraycopy(bytes, index, payload, 0, 4);
                fields.add(new ProtoField(fieldNumber, wireType, payload));
                index += 4;
            } else {
                return null;
            }
        }
        return fields;
    }

    public static byte[] serializeFields(List<ProtoField> fields) throws IOException {
        if (fields == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ProtoField f : fields) {
            out.write(f.encode());
        }
        return out.toByteArray();
    }
}
