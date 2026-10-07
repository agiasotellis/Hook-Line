package app.hookline.radar.core.hdf5;

import java.util.ArrayList;
import java.util.List;

/**
 * Dense ("new-style", libver=latest) group links and attributes: messages
 * stored in a fractal heap, indexed by a version-2 B-tree. We walk the B-tree
 * to collect heap IDs, then resolve each managed object to its position in
 * the file so the regular link / attribute parsers can read it in place.
 */
final class DenseStorage {
    private final Hdf5File f;

    DenseStorage(Hdf5File f) {
        this.f = f;
    }

    /** File positions of every managed object referenced by the B-tree. */
    List<Long> objectPositions(long heapAddress, long btreeAddress) {
        Heap heap = new Heap(heapAddress);
        List<byte[]> ids = new ArrayList<>();
        new BTree2(btreeAddress, heap.idLength).collectHeapIds(ids);
        List<Long> out = new ArrayList<>(ids.size());
        for (byte[] id : ids) out.add(heap.locate(id));
        return out;
    }

    // ---- fractal heap ---------------------------------------------------------

    private final class Heap {
        final long address;
        final int idLength;
        final int maxHeapSizeBits;
        final int tableWidth;
        final long startBlockSize;
        final long maxDirectBlockSize;
        final long rootBlock;
        final int rootRows;
        final boolean checksummedDirect;
        final int offsetBytes;
        final int lengthBytes;
        final List<long[]> directBlocks = new ArrayList<>(); // {heapOffset, filePos, size}

        Heap(long addr) {
            long p = f.abs(addr);
            if (f.u8(p) != 'F' || f.u8(p + 1) != 'R' || f.u8(p + 2) != 'H' || f.u8(p + 3) != 'P') {
                throw new Hdf5Exception("Bad fractal heap signature");
            }
            address = addr;
            idLength = f.u16(p + 5);
            int filterLen = f.u16(p + 7);
            int flags = f.u8(p + 9);
            long maxManaged = f.u32(p + 10);
            long q = p + 14;
            q += f.lengthSize; // next huge id
            q += f.offsetSize; // huge B-tree
            q += f.lengthSize; // free space
            q += f.offsetSize; // free space manager
            q += f.lengthSize * 8L; // managed space, allocated, iterator, counts...
            tableWidth = f.u16(q);
            startBlockSize = f.readLength(q + 2);
            maxDirectBlockSize = f.readLength(q + 2 + f.lengthSize);
            maxHeapSizeBits = f.u16(q + 2 + 2L * f.lengthSize);
            q += 2 + 2L * f.lengthSize + 2;
            q += 2; // starting rows in root indirect block
            rootBlock = f.readOffset(q);
            rootRows = f.u16(q + f.offsetSize);
            if (filterLen > 0) throw new Hdf5Exception("Filtered fractal heaps are not supported");
            checksummedDirect = (flags & 0x02) != 0;
            offsetBytes = (maxHeapSizeBits + 7) / 8;
            lengthBytes = encSize(Math.min(maxDirectBlockSize, maxManaged));
            if (!f.isUndefined(rootBlock)) {
                if (rootRows == 0) directBlocks.add(new long[]{0, f.abs(rootBlock), startBlockSize});
                else readIndirect(f.abs(rootBlock), rootRows, 0, 0);
            }
        }

        private int maxDirectRows() {
            return log2(maxDirectBlockSize) - log2(startBlockSize) + 2;
        }

        private long rowBlockSize(int row) {
            return row <= 1 ? startBlockSize : startBlockSize << (row - 1);
        }

        private void readIndirect(long p, int rows, long heapOffset, int depth) {
            if (depth > 16) throw new Hdf5Exception("Fractal heap too deep");
            if (f.u8(p) != 'F' || f.u8(p + 1) != 'H' || f.u8(p + 2) != 'I' || f.u8(p + 3) != 'B') {
                throw new Hdf5Exception("Bad fractal heap indirect block signature");
            }
            long q = p + 5 + f.offsetSize + offsetBytes;
            long off = heapOffset;
            int directRows = Math.min(rows, maxDirectRows());
            for (int r = 0; r < rows; r++) {
                long size = rowBlockSize(r);
                for (int c = 0; c < tableWidth; c++) {
                    long child = f.readOffset(q);
                    q += f.offsetSize;
                    if (r < directRows) {
                        if (!f.isUndefined(child)) directBlocks.add(new long[]{off, f.abs(child), size});
                    } else {
                        if (!f.isUndefined(child)) {
                            int childRows = log2(size) - log2(startBlockSize) - log2(tableWidth) + 1;
                            readIndirect(f.abs(child), childRows, off, depth + 1);
                        }
                    }
                    off += size;
                }
            }
        }

        long locate(byte[] id) {
            int type = (id[0] >> 4) & 0x03;
            if (type != 0) throw new Hdf5Exception("Only managed fractal heap objects are supported (type " + type + ")");
            long offset = le(id, 1, offsetBytes);
            for (long[] b : directBlocks) {
                if (offset >= b[0] && offset < b[0] + b[2]) return b[1] + (offset - b[0]);
            }
            throw new Hdf5Exception("Fractal heap offset " + offset + " not found");
        }
    }

    // ---- version 2 B-tree ---------------------------------------------------------

    private final class BTree2 {
        final int type;
        final int nodeSize;
        final int recordSize;
        final int depth;
        final long root;
        final int rootRecords;
        final int heapIdLength;
        final int[] maxRecords;
        final int[] cumMaxRecordsSize;
        final int maxRecordsSize;

        BTree2(long addr, int heapIdLength) {
            long p = f.abs(addr);
            if (f.u8(p) != 'B' || f.u8(p + 1) != 'T' || f.u8(p + 2) != 'H' || f.u8(p + 3) != 'D') {
                throw new Hdf5Exception("Bad v2 B-tree header signature");
            }
            this.heapIdLength = heapIdLength;
            type = f.u8(p + 5);
            nodeSize = (int) f.u32(p + 6);
            recordSize = f.u16(p + 10);
            depth = f.u16(p + 12);
            root = f.readOffset(p + 16);
            rootRecords = f.u16(p + 16 + f.offsetSize);

            // Field sizes follow H5B2hdr.c: derived from the maximum records per node.
            maxRecords = new int[depth + 1];
            cumMaxRecordsSize = new int[depth + 1];
            long[] cumMax = new long[depth + 1];
            final int prefix = 10;
            maxRecords[0] = (nodeSize - prefix) / recordSize;
            cumMax[0] = maxRecords[0];
            cumMaxRecordsSize[0] = encSize(cumMax[0]);
            maxRecordsSize = encSize(maxRecords[0]);
            for (int u = 1; u <= depth; u++) {
                int ptr = f.offsetSize + maxRecordsSize + (u > 1 ? cumMaxRecordsSize[u - 1] : 0);
                maxRecords[u] = (nodeSize - (prefix + ptr)) / (recordSize + ptr);
                cumMax[u] = ((long) maxRecords[u] + 1) * cumMax[u - 1] + maxRecords[u];
                cumMaxRecordsSize[u] = encSize(cumMax[u]);
            }
        }

        void collectHeapIds(List<byte[]> out) {
            if (f.isUndefined(root) || rootRecords == 0) return;
            walk(f.abs(root), rootRecords, depth, out);
        }

        private void walk(long p, int records, int level, List<byte[]> out) {
            boolean leaf = level == 0;
            char k = (char) f.u8(p + 2);
            if (f.u8(p) != 'B' || f.u8(p + 1) != 'T' || k != (leaf ? 'L' : 'I')) {
                throw new Hdf5Exception("Bad v2 B-tree node signature");
            }
            long q = p + 6;
            for (int i = 0; i < records; i++) {
                out.add(heapIdFromRecord(q));
                q += recordSize;
            }
            if (leaf) return;
            for (int i = 0; i <= records; i++) {
                long child = f.readOffset(q);
                q += f.offsetSize;
                int childRecords = (int) f.uN(q, maxRecordsSize);
                q += maxRecordsSize;
                if (level > 1) q += cumMaxRecordsSize[level - 1];
                walk(f.abs(child), childRecords, level - 1, out);
            }
        }

        private byte[] heapIdFromRecord(long q) {
            switch (type) {
                case 5: return f.bytes(q + 4, heapIdLength); // link name: hash, heap id
                case 6: return f.bytes(q + 8, heapIdLength); // link creation order: order, heap id
                case 8: return f.bytes(q, 8); // attribute name: heap id(8), flags, order, hash
                case 9: return f.bytes(q, 8); // attribute creation order: heap id(8), flags, order
                default: throw new Hdf5Exception("Unexpected v2 B-tree record type " + type);
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------

    static int log2(long v) {
        return 63 - Long.numberOfLeadingZeros(v);
    }

    /** Bytes needed to encode values up to v (H5VM_limit_enc_size). */
    static int encSize(long v) {
        return v <= 0 ? 1 : (log2(v) / 8) + 1;
    }

    static long le(byte[] b, int off, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }
}
