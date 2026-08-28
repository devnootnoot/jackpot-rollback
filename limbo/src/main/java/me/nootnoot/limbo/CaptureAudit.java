package me.nootnoot.limbo;

import java.util.List;

public final class CaptureAudit {

    private CaptureAudit() {
    }

    public static Boolean detectSectionFluidCount(List<byte[]> play, int chunkId, int sections) {
        boolean withFluid = false;
        boolean withoutFluid = false;
        int examined = 0;
        for (byte[] body : play) {
            int[] pos = {0};
            if (readVarInt(body, pos) != chunkId) {
                continue;
            }
            byte[] data = sectionData(body, pos);
            if (data == null) {
                continue;
            }
            examined++;
            if (sectionsDecode(data, sections, false)) {
                withoutFluid = true;
            }
            if (sectionsDecode(data, sections, true)) {
                withFluid = true;
            }
            if (examined >= 16) {
                break;
            }
        }
        if (examined == 0 || withFluid == withoutFluid) {
            return null;
        }
        return withFluid;
    }

    public static int countChunks(List<byte[]> play, int chunkId) {
        int count = 0;
        for (byte[] body : play) {
            int[] pos = {0};
            if (readVarInt(body, pos) == chunkId) {
                count++;
            }
        }
        return count;
    }

    private static byte[] sectionData(byte[] body, int[] pos) {
        if (pos[0] + 8 > body.length) {
            return null;
        }
        pos[0] += 8;
        int heightmaps = readVarInt(body, pos);
        if (heightmaps < 0 || heightmaps > 64) {
            return null;
        }
        for (int i = 0; i < heightmaps; i++) {
            if (readVarInt(body, pos) < 0) {
                return null;
            }
            int longs = readVarInt(body, pos);
            if (longs < 0 || longs > 4096) {
                return null;
            }
            pos[0] += longs * 8;
            if (pos[0] < 0 || pos[0] > body.length) {
                return null;
            }
        }
        int size = readVarInt(body, pos);
        if (size <= 0 || pos[0] + size > body.length) {
            return null;
        }
        byte[] data = new byte[size];
        System.arraycopy(body, pos[0], data, 0, size);
        return data;
    }

    private static boolean sectionsDecode(byte[] data, int sections, boolean fluidCount) {
        int[] pos = {0};
        for (int s = 0; s < sections; s++) {
            pos[0] += fluidCount ? 4 : 2;
            if (!skipPalettedContainer(data, pos, 8, 4096)
                    || !skipPalettedContainer(data, pos, 3, 64)) {
                return false;
            }
        }
        return pos[0] == data.length;
    }

    private static boolean skipPalettedContainer(byte[] data, int[] pos, int maxDirectBits,
                                                 int entries) {
        if (pos[0] >= data.length) {
            return false;
        }
        int bitsPerEntry = data[pos[0]++] & 0xFF;
        if (bitsPerEntry == 0) {
            return readVarInt(data, pos) >= 0 && pos[0] <= data.length;
        }
        if (bitsPerEntry > 64) {
            return false;
        }
        if (bitsPerEntry <= maxDirectBits) {
            int paletteLength = readVarInt(data, pos);
            if (paletteLength < 0 || paletteLength > entries) {
                return false;
            }
            for (int i = 0; i < paletteLength; i++) {
                if (readVarInt(data, pos) < 0) {
                    return false;
                }
            }
        }
        int perLong = 64 / bitsPerEntry;
        pos[0] += ((entries + perLong - 1) / perLong) * 8;
        return pos[0] <= data.length;
    }

    private static int readVarInt(byte[] data, int[] pos) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            if (pos[0] >= data.length) {
                return -1;
            }
            int b = data[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }
}
