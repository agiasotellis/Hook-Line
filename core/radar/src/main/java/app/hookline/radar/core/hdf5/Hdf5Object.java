package app.hookline.radar.core.hdf5;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A group or dataset. Header messages are parsed eagerly (they're small);
 * dataset values are read on demand with {@link #readRaw()}.
 */
public final class Hdf5Object {
    private static final int MSG_DATASPACE = 0x0001;
    private static final int MSG_LINK_INFO = 0x0002;
    private static final int MSG_DATATYPE = 0x0003;
    private static final int MSG_LINK = 0x0006;
    private static final int MSG_LAYOUT = 0x0008;
    private static final int MSG_FILTERS = 0x000B;
    private static final int MSG_ATTRIBUTE = 0x000C;
    private static final int MSG_CONTINUATION = 0x0010;
    private static final int MSG_SYMBOL_TABLE = 0x0011;
    private static final int MSG_ATTRIBUTE_INFO = 0x0015;

    private final Hdf5File f;
    public final String name;
    private final Map<String, Long> children = Hdf5File.ordered();
    private final Map<String, Attribute> attributes = Hdf5File.ordered();
    private final Map<String, Hdf5Object> childCache = Hdf5File.ordered();

    // dataset parts
    private long[] dims;
    private Datatype type;
    private int layoutVersion = -1;
    private int layoutClass = -1;
    private long layoutAddress;
    private long layoutSize;
    private byte[] compactData;
    private long[] chunkDims; // without the trailing element-size dimension
    private int chunkIndexType;
    private long singleChunkFilteredSize;
    private long singleChunkFilterMask;
    private final List<int[]> filters = new ArrayList<>(); // {id, flags, clientData...}
    private long denseLinkHeap = -1, denseLinkBtree = -1;
    private long denseAttrHeap = -1, denseAttrBtree = -1;

    Hdf5Object(Hdf5File f, String name, long headerAddress) {
        this.f = f;
        this.name = name;
        long p = f.abs(headerAddress);
        if (f.u8(p) == 'O' && f.u8(p + 1) == 'H' && f.u8(p + 2) == 'D' && f.u8(p + 3) == 'R') {
            readV2Header(p);
        } else if (f.u8(p) == 1) {
            readV1Header(p);
        } else {
            throw new Hdf5Exception("Unsupported object header version " + f.u8(p) + " for " + name);
        }
    }

    // ---- public API ----------------------------------------------------------

    public boolean isDataset() {
        return layoutClass >= 0;
    }

    public Set<String> childNames() {
        return Collections.unmodifiableSet(children.keySet());
    }

    public Hdf5Object child(String childName) {
        Hdf5Object cached = childCache.get(childName);
        if (cached != null) return cached;
        Long address = children.get(childName);
        if (address == null) return null;
        Hdf5Object o = new Hdf5Object(f, childName, address);
        childCache.put(childName, o);
        return o;
    }

    public Map<String, Attribute> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Attribute attribute(String attrName) {
        return attributes.get(attrName);
    }

    public long[] dims() {
        return dims == null ? new long[0] : dims.clone();
    }

    public Datatype datatype() {
        return type;
    }

    /** All elements in row-major order, as stored (element size = datatype().size). */
    public byte[] readRaw() {
        if (!isDataset()) throw new Hdf5Exception(name + " is not a dataset");
        long count = 1;
        for (long d : dims) count *= d;
        long total = count * type.size;
        if (total > Integer.MAX_VALUE) throw new Hdf5Exception("Dataset too large: " + total + " bytes");
        switch (layoutClass) {
            case 0:
                return compactData;
            case 1: {
                if (f.isUndefined(layoutAddress)) return new byte[(int) total]; // never written
                return f.bytes(f.abs(layoutAddress), (int) total);
            }
            case 2:
                return readChunked((int) total);
            default:
                throw new Hdf5Exception("Unsupported layout class " + layoutClass);
        }
    }

    // ---- object headers ----------------------------------------------------------

    private void readV1Header(long p) {
        int messages = f.u16(p + 2);
        long size = f.u32(p + 8);
        List<long[]> blocks = new ArrayList<>();
        blocks.add(new long[]{p + 16, size});
        int seen = 0;
        for (int b = 0; b < blocks.size() && seen < messages; b++) {
            long q = blocks.get(b)[0];
            long end = q + blocks.get(b)[1];
            while (q + 8 <= end && seen < messages) {
                int msgType = f.u16(q);
                int msgSize = f.u16(q + 2);
                long data = q + 8;
                handleMessage(msgType, data, msgSize, blocks, 1);
                q = data + msgSize;
                seen++;
            }
        }
        finishGroup();
    }

    private void readV2Header(long p) {
        int flags = f.u8(p + 5);
        long q = p + 6;
        if ((flags & 0x20) != 0) q += 16; // times
        if ((flags & 0x10) != 0) q += 4; // attribute phase change values
        int sizeBytes = 1 << (flags & 0x03);
        long chunkSize = f.uN(q, sizeBytes);
        q += sizeBytes;
        boolean creationOrder = (flags & 0x04) != 0;
        List<long[]> blocks = new ArrayList<>();
        blocks.add(new long[]{q, chunkSize});
        for (int b = 0; b < blocks.size(); b++) {
            long start = blocks.get(b)[0];
            long end = start + blocks.get(b)[1];
            if (b > 0) {
                // continuation chunk: "OCHK" signature, messages, checksum (length includes both)
                if (f.u8(start) != 'O' || f.u8(start + 1) != 'C') throw new Hdf5Exception("Bad OCHK signature");
                start += 4;
                end -= 4;
            }
            long r = start;
            int headerLen = 4 + (creationOrder ? 2 : 0);
            while (r + headerLen <= end) {
                int msgType = f.u8(r);
                int msgSize = f.u16(r + 1);
                long data = r + headerLen;
                if (data + msgSize > end) break;
                handleMessage(msgType, data, msgSize, blocks, 2);
                r = data + msgSize;
            }
        }
        finishGroup();
    }

    private long pendingBtree = -1;
    private long pendingHeap = -1;

    private void handleMessage(int msgType, long p, int size, List<long[]> blocks, int headerVersion) {
        switch (msgType) {
            case MSG_CONTINUATION: {
                long offset = f.abs(f.readOffset(p));
                long length = f.readLength(p + f.offsetSize);
                blocks.add(new long[]{offset, length});
                break;
            }
            case MSG_SYMBOL_TABLE:
                pendingBtree = f.readOffset(p);
                pendingHeap = f.readOffset(p + f.offsetSize);
                break;
            case MSG_LINK:
                readLink(p);
                break;
            case MSG_LINK_INFO: {
                int flags = f.u8(p + 1);
                long q = p + 2 + ((flags & 1) != 0 ? 8 : 0);
                long heap = f.readOffset(q);
                if (!f.isUndefined(heap)) {
                    denseLinkHeap = heap;
                    denseLinkBtree = f.readOffset(q + f.offsetSize);
                }
                break;
            }
            case MSG_ATTRIBUTE_INFO: {
                int flags = f.u8(p + 1);
                long q = p + 2 + ((flags & 1) != 0 ? 2 : 0);
                long heap = f.readOffset(q);
                if (!f.isUndefined(heap)) {
                    denseAttrHeap = heap;
                    denseAttrBtree = f.readOffset(q + f.offsetSize);
                }
                break;
            }
            case MSG_DATASPACE:
                dims = readDataspace(p);
                break;
            case MSG_DATATYPE:
                type = Datatype.parse(f, p);
                break;
            case MSG_LAYOUT:
                readLayout(p);
                break;
            case MSG_FILTERS:
                readFilters(p);
                break;
            case MSG_ATTRIBUTE:
                Attribute a = readAttribute(p);
                if (a != null) attributes.put(a.name, a);
                break;
            default:
                break; // fill value, modification time, group info, NIL, etc.
        }
    }

    private void finishGroup() {
        if (pendingBtree >= 0 && !f.isUndefined(pendingBtree)) {
            f.readSymbolTable(pendingBtree, pendingHeap, children);
        }
        if (denseLinkHeap >= 0) {
            for (long pos : new DenseStorage(f).objectPositions(denseLinkHeap, denseLinkBtree)) readLink(pos);
        }
        if (denseAttrHeap >= 0) {
            for (long pos : new DenseStorage(f).objectPositions(denseAttrHeap, denseAttrBtree)) {
                Attribute a = readAttribute(pos);
                if (a != null) attributes.put(a.name, a);
            }
        }
    }

    private void readLink(long p) {
        int flags = f.u8(p + 1);
        long q = p + 2;
        int linkType = 0;
        if ((flags & 0x08) != 0) linkType = f.u8(q++);
        if ((flags & 0x04) != 0) q += 8;
        if ((flags & 0x10) != 0) q += 1;
        int lenSize = 1 << (flags & 0x03);
        int nameLen = (int) f.uN(q, lenSize);
        q += lenSize;
        String linkName = new String(f.bytes(q, nameLen), StandardCharsets.UTF_8);
        q += nameLen;
        if (linkType == 0) children.put(linkName, f.readOffset(q)); // hard links only
    }

    private long[] readDataspace(long p) {
        int version = f.u8(p);
        int rank = f.u8(p + 1);
        long q;
        if (version == 1) q = p + 8;
        else if (version == 2) {
            int dsType = f.u8(p + 3);
            if (dsType == 2) return new long[0]; // null dataspace
            q = p + 4;
        } else throw new Hdf5Exception("Unsupported dataspace version " + version);
        long[] d = new long[rank];
        for (int i = 0; i < rank; i++) d[i] = f.readLength(q + (long) i * f.lengthSize);
        return d;
    }

    private void readLayout(long p) {
        layoutVersion = f.u8(p);
        if (layoutVersion == 3 || layoutVersion == 4) {
            layoutClass = f.u8(p + 1);
            long q = p + 2;
            if (layoutClass == 0) {
                int n = f.u16(q);
                compactData = f.bytes(q + 2, n);
            } else if (layoutClass == 1) {
                layoutAddress = f.readOffset(q);
                layoutSize = f.readLength(q + f.offsetSize);
            } else if (layoutClass == 2) {
                if (layoutVersion == 3) {
                    int dimensionality = f.u8(q);
                    layoutAddress = f.readOffset(q + 1);
                    long r = q + 1 + f.offsetSize;
                    chunkDims = new long[dimensionality - 1];
                    for (int i = 0; i < dimensionality - 1; i++) chunkDims[i] = f.u32(r + 4L * i);
                    chunkIndexType = 0; // v1 B-tree
                } else {
                    int flags = f.u8(q);
                    int dimensionality = f.u8(q + 1);
                    int encLen = f.u8(q + 2);
                    long r = q + 3;
                    chunkDims = new long[dimensionality - 1];
                    for (int i = 0; i < dimensionality; i++) {
                        long v = f.uN(r, encLen);
                        if (i < dimensionality - 1) chunkDims[i] = v;
                        r += encLen;
                    }
                    chunkIndexType = f.u8(r++);
                    if (chunkIndexType == 1 && (flags & 0x02) != 0) { // single chunk, filtered
                        singleChunkFilteredSize = f.readLength(r);
                        singleChunkFilterMask = f.u32(r + f.lengthSize);
                        r += f.lengthSize + 4;
                    } else if (chunkIndexType == 3) {
                        r += 1; // page bits
                    } else if (chunkIndexType == 4) {
                        r += 5;
                    } else if (chunkIndexType == 5) {
                        r += 6;
                    }
                    layoutAddress = f.readOffset(r);
                }
            } else {
                throw new Hdf5Exception("Unsupported layout class " + layoutClass + " (virtual datasets?)");
            }
        } else if (layoutVersion == 1 || layoutVersion == 2) {
            int rank = f.u8(p + 1);
            layoutClass = f.u8(p + 2);
            long q = p + 8;
            long address = 0;
            if (layoutClass != 0) {
                address = f.readOffset(q);
                q += f.offsetSize;
            }
            long[] d = new long[rank];
            for (int i = 0; i < rank; i++) d[i] = f.u32(q + 4L * i);
            layoutAddress = address;
            if (layoutClass == 2) {
                chunkDims = new long[rank - 1];
                System.arraycopy(d, 0, chunkDims, 0, rank - 1);
                chunkIndexType = 0;
            } else if (layoutClass == 0) {
                long r = q + 4L * rank;
                int n = (int) f.u32(r);
                compactData = f.bytes(r + 4, n);
            }
        } else {
            throw new Hdf5Exception("Unsupported data layout version " + layoutVersion);
        }
    }

    private void readFilters(long p) {
        int version = f.u8(p);
        int count = f.u8(p + 1);
        long q = p + (version == 1 ? 8 : 2);
        for (int i = 0; i < count; i++) {
            int id = f.u16(q);
            int nameLen = 0;
            long r;
            if (version == 1 || id >= 256) {
                nameLen = f.u16(q + 2);
                r = q + 4;
            } else {
                r = q + 2;
            }
            int flags = f.u16(r);
            int nValues = f.u16(r + 2);
            r += 4;
            if (version == 1) r += (nameLen + 7) & ~7;
            else r += nameLen;
            int[] filter = new int[2 + nValues];
            filter[0] = id;
            filter[1] = flags;
            for (int k = 0; k < nValues; k++) filter[2 + k] = (int) f.u32(r + 4L * k);
            r += 4L * nValues;
            if (version == 1 && (nValues % 2) == 1) r += 4;
            filters.add(filter);
            q = r;
        }
    }

    private Attribute readAttribute(long p) {
        int version = f.u8(p);
        int nameSize = f.u16(p + 2);
        int typeSize = f.u16(p + 4);
        int spaceSize = f.u16(p + 6);
        long q = p + 8;
        if (version == 3) q += 1; // name character set
        boolean padded = version == 1;
        String attrName = Datatype.fixedString(f.bytes(q, nameSize), 0, nameSize);
        q += padded ? (nameSize + 7) & ~7 : nameSize;
        Datatype dt = Datatype.parse(f, q);
        q += padded ? (typeSize + 7) & ~7 : typeSize;
        long[] shape = readDataspace(q);
        q += padded ? (spaceSize + 7) & ~7 : spaceSize;
        long n = 1;
        for (long d : shape) n *= d;
        return new Attribute(f, attrName, dt, shape, n, q);
    }

    // ---- chunked data --------------------------------------------------------

    private byte[] readChunked(int totalBytes) {
        int rank = dims.length;
        if (chunkDims.length != rank) throw new Hdf5Exception("Chunk rank mismatch in " + name);
        byte[] out = new byte[totalBytes];
        int elementSize = type.size;
        long chunkElements = 1;
        for (long c : chunkDims) chunkElements *= c;
        int chunkBytes = (int) (chunkElements * elementSize);

        List<long[]> chunks = new ArrayList<>(); // {address, size, filterMask, offsets...}
        if (f.isUndefined(layoutAddress)) return out;
        switch (chunkIndexType) {
            case 0:
                collectBtreeChunks(f.abs(layoutAddress), rank, chunks, 0);
                break;
            case 1: {
                long size = filters.isEmpty() ? chunkBytes : singleChunkFilteredSize;
                long[] c = new long[3 + rank];
                c[0] = layoutAddress;
                c[1] = size;
                c[2] = singleChunkFilterMask;
                chunks.add(c);
                break;
            }
            case 2: { // implicit: chunks stored contiguously in row-major chunk order
                long[] grid = chunkGrid(rank);
                long total = 1;
                for (long g : grid) total *= g;
                for (long i = 0; i < total; i++) {
                    long[] c = new long[3 + rank];
                    c[0] = layoutAddress + i * chunkBytes;
                    c[1] = chunkBytes;
                    long rem = i;
                    for (int d = rank - 1; d >= 0; d--) {
                        c[3 + d] = (rem % grid[d]) * chunkDims[d];
                        rem /= grid[d];
                    }
                    chunks.add(c);
                }
                break;
            }
            case 3:
                collectFixedArrayChunks(rank, chunkBytes, chunks);
                break;
            default:
                throw new Hdf5Exception("Unsupported chunk index type " + chunkIndexType + " in " + name);
        }

        for (long[] c : chunks) {
            byte[] data = f.bytes(f.abs(c[0]), (int) c[1]);
            data = applyFilters(data, (int) c[2], chunkBytes);
            copyChunk(data, c, out, elementSize);
        }
        return out;
    }

    private long[] chunkGrid(int rank) {
        long[] grid = new long[rank];
        for (int d = 0; d < rank; d++) grid[d] = (dims[d] + chunkDims[d] - 1) / chunkDims[d];
        return grid;
    }

    private void collectBtreeChunks(long p, int rank, List<long[]> out, int depth) {
        if (depth > 64) throw new Hdf5Exception("Chunk B-tree too deep");
        if (f.u8(p) != 'T' || f.u8(p + 1) != 'R') throw new Hdf5Exception("Bad chunk B-tree signature");
        int nodeType = f.u8(p + 4);
        if (nodeType != 1) throw new Hdf5Exception("Expected chunk B-tree node, got type " + nodeType);
        int level = f.u8(p + 5);
        int entries = f.u16(p + 6);
        long q = p + 8 + 2L * f.offsetSize;
        int keySize = 4 + 4 + 8 * (rank + 1);
        for (int i = 0; i < entries; i++) {
            long size = f.u32(q);
            long mask = f.u32(q + 4);
            long[] c = new long[3 + rank];
            for (int d = 0; d < rank; d++) c[3 + d] = f.u64(q + 8 + 8L * d);
            long child = f.readOffset(q + keySize);
            if (level > 0) {
                collectBtreeChunks(f.abs(child), rank, out, depth + 1);
            } else {
                c[0] = child;
                c[1] = size;
                c[2] = mask;
                out.add(c);
            }
            q += keySize + f.offsetSize;
        }
    }

    private void collectFixedArrayChunks(int rank, int chunkBytes, List<long[]> out) {
        long p = f.abs(layoutAddress);
        if (f.u8(p) != 'F' || f.u8(p + 1) != 'A' || f.u8(p + 2) != 'H' || f.u8(p + 3) != 'D') {
            throw new Hdf5Exception("Bad fixed array header signature");
        }
        int clientId = f.u8(p + 5);
        int entrySize = f.u8(p + 6);
        int pageBits = f.u8(p + 7);
        long maxEntries = f.readLength(p + 8);
        long dataBlock = f.abs(f.readOffset(p + 8 + f.lengthSize));
        if (maxEntries > (1L << pageBits)) throw new Hdf5Exception("Paged fixed array chunk index is not supported");
        if (f.u8(dataBlock) != 'F' || f.u8(dataBlock + 1) != 'A' || f.u8(dataBlock + 2) != 'D' || f.u8(dataBlock + 3) != 'B') {
            throw new Hdf5Exception("Bad fixed array data block signature");
        }
        long q = dataBlock + 6 + f.offsetSize;
        long[] grid = chunkGrid(rank);
        for (long i = 0; i < maxEntries; i++) {
            long address = f.readOffset(q);
            long size = chunkBytes;
            long mask = 0;
            if (clientId == 1) {
                int sizeLen = entrySize - f.offsetSize - 4;
                size = f.uN(q + f.offsetSize, sizeLen);
                mask = f.u32(q + f.offsetSize + sizeLen);
            }
            q += entrySize;
            if (f.isUndefined(address)) continue;
            long[] c = new long[3 + rank];
            c[0] = address;
            c[1] = size;
            c[2] = mask;
            long rem = i;
            for (int d = rank - 1; d >= 0; d--) {
                c[3 + d] = (rem % grid[d]) * chunkDims[d];
                rem /= grid[d];
            }
            out.add(c);
        }
    }

    private byte[] applyFilters(byte[] data, int mask, int chunkBytes) {
        for (int i = filters.size() - 1; i >= 0; i--) {
            if ((mask & (1 << i)) != 0) continue; // filter skipped for this chunk
            int[] flt = filters.get(i);
            switch (flt[0]) {
                case 1:
                    data = Hdf5File.inflate(data, chunkBytes);
                    break;
                case 2: {
                    int elementSize = flt.length > 2 ? flt[2] : type.size;
                    data = Hdf5File.unshuffle(data, elementSize);
                    break;
                }
                case 3: {
                    byte[] stripped = new byte[data.length - 4];
                    System.arraycopy(data, 0, stripped, 0, stripped.length);
                    data = stripped;
                    break;
                }
                default:
                    if ((flt[1] & 1) != 0) break; // optional filter
                    throw new Hdf5Exception("Unsupported HDF5 filter id " + flt[0] + " in " + name);
            }
        }
        return data;
    }

    /** Copy one decoded chunk into the full array, clipping at the dataset edges. */
    private void copyChunk(byte[] chunk, long[] c, byte[] out, int es) {
        int rank = dims.length;
        if (rank == 0) {
            System.arraycopy(chunk, 0, out, 0, Math.min(chunk.length, out.length));
            return;
        }
        int[] cd = new int[rank];
        int[] dd = new int[rank];
        int[] off = new int[rank];
        for (int d = 0; d < rank; d++) {
            cd[d] = (int) chunkDims[d];
            dd[d] = (int) dims[d];
            off[d] = (int) c[3 + d];
        }
        int last = rank - 1;
        int rowLen = Math.min(cd[last], dd[last] - off[last]);
        if (rowLen <= 0) return;
        // iterate over all leading indices within the chunk
        int[] idx = new int[rank];
        while (true) {
            boolean inside = true;
            for (int d = 0; d < last; d++) {
                if (off[d] + idx[d] >= dd[d]) { inside = false; break; }
            }
            if (inside) {
                long src = 0, dst = 0;
                for (int d = 0; d < rank; d++) {
                    src = src * cd[d] + idx[d];
                    dst = dst * dd[d] + off[d] + idx[d];
                }
                int s = (int) (src * es);
                int t = (int) (dst * es);
                int n = rowLen * es;
                if (s + n <= chunk.length) System.arraycopy(chunk, s, out, t, n);
            }
            // advance leading indices (not the last dimension)
            int d = last - 1;
            while (d >= 0) {
                idx[d]++;
                if (idx[d] < cd[d]) break;
                idx[d] = 0;
                d--;
            }
            if (d < 0) break;
        }
    }

    @Override
    public String toString() {
        return (isDataset() ? "Dataset " : "Group ") + name;
    }
}
