import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Read-only APK inspection; run: java tools/InspectOcrApk.java path/to/base.apk */
class InspectOcrApk {
    static byte[] dex;
    static ByteBuffer buffer;
    static int i32(int offset) { return buffer.getInt(offset); }
    static int u16(int offset) { return Short.toUnsignedInt(buffer.getShort(offset)); }
    static int uleb(int[] cursor) {
        int result = 0, shift = 0, value;
        do { value = dex[cursor[0]++] & 255; result |= (value & 127) << shift; shift += 7; }
        while ((value & 128) != 0);
        return result;
    }
    static String string(int index) {
        int[] cursor = { i32(i32(60) + index * 4) };
        uleb(cursor);
        int start = cursor[0];
        while (dex[cursor[0]] != 0) cursor[0]++;
        return new String(dex, start, cursor[0] - start, StandardCharsets.UTF_8);
    }
    static String type(int index) { return string(i32(i32(68) + index * 4)); }
    static Object value(int[] cursor) {
        int header = dex[cursor[0]++] & 255, kind = header & 31, count = (header >>> 5) + 1;
        if (kind == 31) return header >>> 5 != 0;
        if (kind == 30) return null;
        long number = 0;
        for (int n = 0; n < count; n++) number |= (long)(dex[cursor[0]++] & 255) << (n * 8);
        if (kind == 23) return string((int)number);
        return number;
    }
    static void inspectDex(byte[] bytes) {
        dex = bytes; buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int classes = i32(96), offset = i32(100);
        for (int n = 0; n < classes; n++) {
            int cls = offset + n * 32;
            String name = type(i32(cls));
            if (name.equals("Lcom/example/note2snap/ccl/SideBorderGeometry;"))
                System.out.println("SideBorderGeometry class present=true");
            if (name.equals("Lcom/example/note2snap/ccl/ConnectedComponentLabeler;"))
                inspectCcl(i32(cls + 24));
            if (!name.equals("Lcom/example/note2snap/BuildConfig;")) continue;
            int data = i32(cls + 24), values = i32(cls + 28);
            int[] cursor = { data };
            int staticCount = uleb(cursor);
            uleb(cursor); uleb(cursor); uleb(cursor);
            List<String> names = new ArrayList<>();
            int fieldIndex = 0;
            for (int f = 0; f < staticCount; f++) {
                fieldIndex += uleb(cursor); uleb(cursor);
                names.add(string(i32(i32(84) + fieldIndex * 8 + 4)));
            }
            cursor[0] = values;
            int count = values == 0 ? 0 : uleb(cursor);
            for (int f = 0; f < names.size(); f++) {
                Object val = f < count ? value(cursor) : "(DEX default)";
                if (!names.get(f).equals("DEBUG")) System.out.println("BuildConfig." + names.get(f) + "=" + val);
            }
        }
    }
    static int width(int pos) {
        int unit = u16(pos), op = unit & 255;
        if (op == 0 && unit == 0x100) return 4 + u16(pos + 2) * 2;
        if (op == 0 && unit == 0x200) return 2 + u16(pos + 2) * 4;
        if (op == 0 && unit == 0x300) return 4 + (u16(pos + 2) * i32(pos + 4) + 1) / 2;
        if (op == 0x18) return 5;
        if (op == 0xfa || op == 0xfb) return 4;
        if (op == 3 || op == 6 || op == 9 || op == 0x14 || op == 0x17 ||
            op == 0x1b || (op >= 0x24 && op <= 0x26) || (op >= 0x2a && op <= 0x2c) ||
            (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78) || op == 0xfc || op == 0xfd) return 3;
        if (op == 2 || op == 5 || op == 8 || op == 0x13 || op == 0x15 || op == 0x16 ||
            op == 0x19 || op == 0x1a || op == 0x1c || op == 0x1f || op == 0x20 ||
            op == 0x22 || op == 0x23 || op == 0x29 || (op >= 0x2d && op <= 0x3d) ||
            (op >= 0x44 && op <= 0x6d) || (op >= 0x90 && op <= 0xaf) ||
            (op >= 0xd0 && op <= 0xe2) || op == 0xfe || op == 0xff) return 2;
        return 1;
    }
    static void inspectCcl(int data) {
        int[] cursor = {data};
        int fields = uleb(cursor) + uleb(cursor), direct = uleb(cursor), virtual = uleb(cursor);
        for (int f = 0; f < fields; f++) { uleb(cursor); uleb(cursor); }
        for (int count : new int[]{direct, virtual}) {
            int methodIndex = 0;
            for (int m = 0; m < count; m++) {
                methodIndex += uleb(cursor); uleb(cursor); int code = uleb(cursor);
                if (code == 0) continue;
                String caller = string(i32(i32(92) + methodIndex * 8 + 4));
                for (int pos = code + 16, end = pos + i32(code + 12) * 2; pos < end; pos += width(pos) * 2) {
                    int op = u16(pos) & 255;
                    if (!((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78))) continue;
                    int method = i32(92) + u16(pos + 2) * 8;
                    if (type(u16(method)).equals("Lcom/example/note2snap/ccl/SideBorderGeometry;") &&
                        string(i32(method + 4)).equals("isClearFragment"))
                        System.out.println("CCL." + caller + " invokes SideBorderGeometry.isClearFragment=true");
                }
            }
        }
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Provide an APK path and optional --fixtures");
        try (ZipFile apk = new ZipFile(args[0])) {
            if (args.length == 2 && args[1].equals("--fixtures")) {
                for (ZipEntry entry : Collections.list(apk.entries())) {
                    if (entry.getName().startsWith("assets/paddle_reference/")) {
                        byte[] bytes = apk.getInputStream(entry).readAllBytes();
                        System.out.println(entry.getName() + " bytes=" + bytes.length + " prefix=" +
                            HexFormat.of().formatHex(Arrays.copyOf(bytes, Math.min(4, bytes.length))) + " sha256=" + hash(bytes));
                    }
                }
                return;
            }
            for (String path : List.of("assets/ocr/paddle/en_PP-OCRv5_mobile_rec.onnx",
                                      "assets/ocr/paddle/ppocrv5_en_dict.txt")) {
                ZipEntry entry = Objects.requireNonNull(apk.getEntry(path), path);
                byte[] bytes = apk.getInputStream(entry).readAllBytes();
                System.out.println(path + " bytes=" + bytes.length + " sha256=" + hash(bytes));
            }
            for (ZipEntry entry : Collections.list(apk.entries())) {
                if (entry.getName().matches("classes[0-9]*\\.dex"))
                    inspectDex(apk.getInputStream(entry).readAllBytes());
            }
        }
    }
}
