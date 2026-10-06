package com.prisoncore.mine;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal reader for Sponge v3 .schem files (the format WorldEdit writes). */
public final class Schematic {

    public int width, height, length;
    public int[] offset = {0, 0, 0};
    public String[] palette;
    public int[] blocks;   // palette index per cell, index = x + z*W + y*W*L

    public static Schematic read(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            byte type = in.readByte();
            in.readUTF();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) readPayload(in, type);
            @SuppressWarnings("unchecked")
            Map<String, Object> s = root.containsKey("Schematic") ? (Map<String, Object>) root.get("Schematic") : root;
            Schematic sc = new Schematic();
            sc.width = ((Number) s.get("Width")).intValue() & 0xFFFF;
            sc.height = ((Number) s.get("Height")).intValue() & 0xFFFF;
            sc.length = ((Number) s.get("Length")).intValue() & 0xFFFF;
            if (s.get("Offset") instanceof int[] o) sc.offset = o;
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) s.get("Blocks");
            @SuppressWarnings("unchecked")
            Map<String, Object> pal = (Map<String, Object>) b.get("Palette");
            sc.palette = new String[pal.size()];
            for (var e : pal.entrySet()) sc.palette[((Number) e.getValue()).intValue()] = e.getKey();
            byte[] data = (byte[]) b.get("Data");
            int n = sc.width * sc.height * sc.length;
            sc.blocks = new int[n];
            int i = 0, idx = 0;
            while (i < data.length && idx < n) {
                int value = 0, shift = 0, cur;
                do {
                    cur = data[i++];
                    value |= (cur & 0x7F) << shift;
                    shift += 7;
                } while ((cur & 0x80) != 0);
                sc.blocks[idx++] = value;
            }
            return sc;
        }
    }

    private static Object readPayload(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] a = new byte[in.readInt()];
                in.readFully(a);
                return a;
            }
            case 8: return in.readUTF();
            case 9: {
                byte et = in.readByte();
                int len = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, len));
                for (int k = 0; k < len; k++) l.add(readPayload(in, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    byte t = in.readByte();
                    if (t == 0) return m;
                    String name = in.readUTF();
                    m.put(name, readPayload(in, t));
                }
            }
            case 11: {
                int[] a = new int[in.readInt()];
                for (int k = 0; k < a.length; k++) a[k] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[in.readInt()];
                for (int k = 0; k < a.length; k++) a[k] = in.readLong();
                return a;
            }
            default: throw new IOException("Bad NBT tag " + type);
        }
    }
}
