package app.hookline.radar.core.hdf5;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A small, dependency-free, read-only HDF5 reader covering what weather radar
 * files (ODIM_H5 and friends) use in practice:
 *
 * <ul>
 *   <li>superblock versions 0–3</li>
 *   <li>object header versions 1 and 2, with continuation blocks</li>
 *   <li>old-style groups (symbol table, v1 B-tree, local heap) and new-style
 *       compact groups (link messages)</li>
 *   <li>contiguous, compact and chunked datasets; chunk indexes: v1 B-tree,
 *       single chunk, implicit, and fixed array (non-paged)</li>
 *   <li>filters: deflate, shuffle, Fletcher-32 (checksum stripped)</li>
 *   <li>attributes with fixed-point, floating-point, fixed-length string,
 *       variable-length string and enum types</li>
 * </ul>
 *
 * Written for Android: no JNI, no java.nio.file, works on a byte array.
 * Anything outside this set throws {@link Hdf5Exception} with a clear message
 * rather than returning wrong data.
 */
public final class Hdf5File {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1a, '\n'};

    final ByteBuffer buf;
    final int offsetSize;
    final int lengthSize;
    final long base;
    private final Hdf5Object root;

    private Hdf5File(byte[] bytes) {
        buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long sbPos = findSuperblock(bytes);
        int version = u8(sbPos + 8);
        long rootAddress;
        if (version == 0 || version == 1) {
            offsetSize = u8(sbPos + 13);
            lengthSize = u8(sbPos + 14);
            long p = sbPos + 24 + (version == 1 ? 4 : 0);
            base = readOffset(p);
            p += 4L * offsetSize; // base, free-space, EOF, driver info
            // root group symbol table entry: link name offset, object header address
            rootAddress = readOffset(p + offsetSize);
        } else if (version == 2 || version == 3) {
            offsetSize = u8(sbPos + 9);
            lengthSize = u8(sbPos + 10);
            long p = sbPos + 12;
            base = readOffset(p);
            p += offsetSize * 3L; // base, superblock extension, EOF
            rootAddress = readOffset(p);
        } else {
            throw new Hdf5Exception("Unsupported HDF5 superblock version " + version);
        }
        root = new Hdf5Object(this, "/", rootAddress);
    }

    public static Hdf5File open(byte[] bytes) {
        return new Hdf5File(bytes);
    }

    public static boolean isHdf5(byte[] bytes) {
        try {
            findSuperblock(bytes);
            return true;
        } catch (Hdf5Exception e) {
            return false;
        }
    }

    public Hdf5Object root() {
        return root;
    }

    /** Resolve a slash-separated path from the root, e.g. "dataset1/data1/what". Null if absent. */
    public Hdf5Object get(String path) {
        Hdf5Object cur = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            cur = cur.child(part);
            if (cur == null) return null;
        }
        return cur;
    }

    // ---- low-level reading -------------------------------------------------

    private static long findSuperblock(byte[] b) {
        long[] candidates = {0, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};
        for (long c : candidates) {
            if (c + 8 > b.length) break;
            boolean ok = true;
            for (int i = 0; i < 8; i++) {
                if (b[(int) c + i] != SIGNATURE[i]) { ok = false; break; }
            }
            if (ok) return c;
        }
        throw new Hdf5Exception("Not an HDF5 file");
    }

    int u8(long pos) {
        check(pos, 1);
        return buf.get((int) pos) & 0xFF;
    }

    int u16(long pos) {
        check(pos, 2);
        return buf.getShort((int) pos) & 0xFFFF;
    }

    long u32(long pos) {
        check(pos, 4);
        return buf.getInt((int) pos) & 0xFFFFFFFFL;
    }

    long u64(long pos) {
        check(pos, 8);
        return buf.getLong((int) pos);
    }

    /** Unsigned little-endian integer of 1..8 bytes. */
    long uN(long pos, int n) {
        check(pos, n);
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (buf.get((int) pos + i) & 0xFF);
        return v;
    }

    long readOffset(long pos) {
        return uN(pos, offsetSize);
    }

    long readLength(long pos) {
        return uN(pos, lengthSize);
    }

    boolean isUndefined(long address) {
        return address == -1L || (offsetSize < 8 && address == (1L << (offsetSize * 8)) - 1);
    }

    /** File position for an address stored in the file. */
    long abs(long address) {
        return base + address;
    }

    byte[] bytes(long pos, int len) {
        check(pos, len);
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = buf.get((int) pos + i);
        return out;
    }

    String cString(long pos) {
        int end = (int) pos;
        while (end < buf.limit() && buf.get(end) != 0) end++;
        return new String(bytes(pos, end - (int) pos), StandardCharsets.UTF_8);
    }

    private void check(long pos, long len) {
        if (pos < 0 || pos + len > buf.limit()) {
            throw new Hdf5Exception("Read past end of file at " + pos + " (+" + len + "), size " + buf.limit());
        }
    }

    // ---- local heap, symbol tables, global heap ------------------------------

    /** Data segment position of a local heap. */
    long localHeapData(long heapAddress) {
        long p = abs(heapAddress);
        if (u8(p) != 'H' || u8(p + 1) != 'E' || u8(p + 2) != 'A' || u8(p + 3) != 'P') {
            throw new Hdf5Exception("Bad local heap signature");
        }
        long q = p + 8 + 2L * lengthSize; // signature, version, reserved, data size, free list offset
        return abs(readOffset(q));
    }

    /** Walk a group v1 B-tree, collecting name → object header address. */
    void readSymbolTable(long btreeAddress, long heapAddress, Map<String, Long> out) {
        long heapData = localHeapData(heapAddress);
        walkGroupBtree(abs(btreeAddress), heapData, out, 0);
    }

    private void walkGroupBtree(long p, long heapData, Map<String, Long> out, int depth) {
        if (depth > 64) throw new Hdf5Exception("Group B-tree too deep");
        String sig = new String(bytes(p, 4), StandardCharsets.US_ASCII);
        if (sig.equals("SNOD")) {
            readSymbolNode(p, heapData, out);
            return;
        }
        if (!sig.equals("TREE")) throw new Hdf5Exception("Bad group B-tree signature " + sig);
        int type = u8(p + 4);
        if (type != 0) throw new Hdf5Exception("Expected group B-tree node, got type " + type);
        int level = u8(p + 5);
        int entries = u16(p + 6);
        long q = p + 8 + 2L * offsetSize; // skip siblings
        q += lengthSize; // key 0
        for (int i = 0; i < entries; i++) {
            long child = abs(readOffset(q));
            q += offsetSize + lengthSize; // child + next key
            if (level > 0) walkGroupBtree(child, heapData, out, depth + 1);
            else readSymbolNode(child, heapData, out);
        }
    }

    private void readSymbolNode(long p, long heapData, Map<String, Long> out) {
        String sig = new String(bytes(p, 4), StandardCharsets.US_ASCII);
        if (!sig.equals("SNOD")) throw new Hdf5Exception("Bad symbol node signature " + sig);
        int count = u16(p + 6);
        long q = p + 8;
        int entrySize = 2 * offsetSize + 4 + 4 + 16;
        for (int i = 0; i < count; i++) {
            long nameOffset = readOffset(q);
            long header = readOffset(q + offsetSize);
            out.put(cString(heapData + nameOffset), header);
            q += entrySize;
        }
    }

    /** Read one object from a global heap collection (used by variable-length strings). */
    byte[] globalHeapObject(long collectionAddress, int index) {
        long p = abs(collectionAddress);
        if (u8(p) != 'G' || u8(p + 1) != 'C' || u8(p + 2) != 'O' || u8(p + 3) != 'L') {
            throw new Hdf5Exception("Bad global heap signature");
        }
        long size = readLength(p + 8);
        long end = p + size;
        long q = p + 8 + lengthSize;
        while (q + 8 + lengthSize <= end) {
            int idx = u16(q);
            if (idx == 0) break; // free space
            long objSize = readLength(q + 8);
            long data = q + 8 + lengthSize;
            if (idx == index) return bytes(data, (int) objSize);
            q = data + ((objSize + 7) & ~7L);
        }
        throw new Hdf5Exception("Global heap object " + index + " not found");
    }

    // ---- filters ---------------------------------------------------------------

    static byte[] inflate(byte[] in, int expectedSize) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(in);
            byte[] out = new byte[Math.max(expectedSize, 64)];
            int n = 0;
            while (!inf.finished()) {
                if (n == out.length) {
                    byte[] bigger = new byte[out.length * 2];
                    System.arraycopy(out, 0, bigger, 0, n);
                    out = bigger;
                }
                int got = inf.inflate(out, n, out.length - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
            if (n == out.length) return out;
            byte[] exact = new byte[n];
            System.arraycopy(out, 0, exact, 0, n);
            return exact;
        } catch (DataFormatException e) {
            throw new Hdf5Exception("Corrupt deflate data: " + e.getMessage());
        } finally {
            inf.end();
        }
    }

    static byte[] unshuffle(byte[] in, int elementSize) {
        if (elementSize <= 1) return in;
        int n = in.length / elementSize;
        byte[] out = new byte[in.length];
        for (int b = 0; b < elementSize; b++) {
            int src = b * n;
            for (int i = 0; i < n; i++) out[i * elementSize + b] = in[src + i];
        }
        // trailing bytes that don't form a whole element are copied as-is
        System.arraycopy(in, n * elementSize, out, n * elementSize, in.length - n * elementSize);
        return out;
    }

    static List<String> unmodifiable(List<String> l) {
        return Collections.unmodifiableList(new ArrayList<>(l));
    }

    static <K, V> Map<K, V> ordered() {
        return new LinkedHashMap<>();
    }
}
