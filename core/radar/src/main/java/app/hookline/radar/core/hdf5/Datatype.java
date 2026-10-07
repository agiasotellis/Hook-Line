package app.hookline.radar.core.hdf5;

import java.nio.charset.StandardCharsets;

/** The subset of HDF5 datatypes radar files use. */
public final class Datatype {
    public enum Kind { INTEGER, FLOAT, STRING, VLEN_STRING, OTHER }

    public final Kind kind;
    /** Bytes per element as stored. */
    public final int size;
    public final boolean bigEndian;
    public final boolean signed;
    /** Size of this datatype message in bytes (to skip past it). */
    final int encodedLength;

    private Datatype(Kind kind, int size, boolean bigEndian, boolean signed, int encodedLength) {
        this.kind = kind;
        this.size = size;
        this.bigEndian = bigEndian;
        this.signed = signed;
        this.encodedLength = encodedLength;
    }

    static Datatype parse(Hdf5File f, long p) {
        int classAndVersion = f.u8(p);
        int cls = classAndVersion & 0x0F;
        int bits0 = f.u8(p + 1);
        int size = (int) f.u32(p + 4);
        long props = p + 8;
        switch (cls) {
            case 0: // fixed-point
                return new Datatype(Kind.INTEGER, size, (bits0 & 1) != 0, (bits0 & 0x08) != 0, 8 + 4);
            case 1: // floating-point
                return new Datatype(Kind.FLOAT, size, (bits0 & 1) != 0, true, 8 + 12);
            case 3: // fixed-length string
                return new Datatype(Kind.STRING, size, false, false, 8);
            case 8: { // enumeration: base type, then names and values; we read it as its base integer
                Datatype baseType = parse(f, props);
                return new Datatype(baseType.kind, baseType.size, baseType.bigEndian, baseType.signed, -1);
            }
            case 9: { // variable-length
                int vlType = bits0 & 0x0F;
                Datatype baseType = parse(f, props);
                int len = baseType.encodedLength < 0 ? -1 : 8 + baseType.encodedLength;
                return new Datatype(vlType == 1 ? Kind.VLEN_STRING : Kind.OTHER, size, false, false, len);
            }
            default:
                return new Datatype(Kind.OTHER, size, false, false, -1);
        }
    }

    /** Element i of a raw array as a double (integers and floats only). */
    public double getDouble(byte[] raw, int i) {
        int o = i * size;
        if (kind == Kind.FLOAT) {
            if (size == 4) return Float.intBitsToFloat((int) bitsAt(raw, o, 4));
            if (size == 8) return Double.longBitsToDouble(bitsAt(raw, o, 8));
            throw new Hdf5Exception("Unsupported float size " + size);
        }
        if (kind == Kind.INTEGER) {
            long v = bitsAt(raw, o, size);
            if (signed && size < 8) {
                int shift = 64 - size * 8;
                v = (v << shift) >> shift;
            }
            if (!signed && size == 8 && v < 0) return (double) (v >>> 1) * 2.0 + (v & 1);
            return v;
        }
        throw new Hdf5Exception("Not a numeric datatype: " + kind);
    }

    private long bitsAt(byte[] raw, int o, int n) {
        long v = 0;
        if (bigEndian) {
            for (int k = 0; k < n; k++) v = (v << 8) | (raw[o + k] & 0xFF);
        } else {
            for (int k = n - 1; k >= 0; k--) v = (v << 8) | (raw[o + k] & 0xFF);
        }
        return v;
    }

    static String fixedString(byte[] raw, int offset, int len) {
        int end = offset;
        while (end < offset + len && raw[end] != 0) end++;
        return new String(raw, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    @Override
    public String toString() {
        return kind + "(" + size + (bigEndian ? ",BE" : "") + (signed ? ",signed" : "") + ")";
    }
}
