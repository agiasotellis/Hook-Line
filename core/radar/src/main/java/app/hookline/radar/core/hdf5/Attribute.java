package app.hookline.radar.core.hdf5;

import java.nio.charset.StandardCharsets;

/** An attribute value, decoded lazily as a string, number or numeric array. */
public final class Attribute {
    public final String name;
    public final Datatype type;
    public final long[] shape;
    private final long count;
    private final long dataPos;
    private final Hdf5File f;

    Attribute(Hdf5File f, String name, Datatype type, long[] shape, long count, long dataPos) {
        this.f = f;
        this.name = name;
        this.type = type;
        this.shape = shape;
        this.count = count;
        this.dataPos = dataPos;
    }

    public boolean isString() {
        return type.kind == Datatype.Kind.STRING || type.kind == Datatype.Kind.VLEN_STRING;
    }

    /** First (or only) value as a string; numbers are formatted. */
    public String asString() {
        if (type.kind == Datatype.Kind.STRING) {
            return Datatype.fixedString(f.bytes(dataPos, type.size), 0, type.size);
        }
        if (type.kind == Datatype.Kind.VLEN_STRING) {
            long len = f.u32(dataPos);
            long collection = f.readOffset(dataPos + 4);
            int index = (int) f.u32(dataPos + 4 + f.offsetSize);
            if (len == 0 || f.isUndefined(collection) || collection == 0) return "";
            byte[] b = f.globalHeapObject(collection, index);
            int n = (int) Math.min(len, b.length);
            int end = 0;
            while (end < n && b[end] != 0) end++;
            return new String(b, 0, end, StandardCharsets.UTF_8).trim();
        }
        double v = asDouble();
        return v == Math.rint(v) && !Double.isInfinite(v) ? Long.toString((long) v) : Double.toString(v);
    }

    public double asDouble() {
        if (isString()) {
            try {
                return Double.parseDouble(asString().trim());
            } catch (NumberFormatException e) {
                throw new Hdf5Exception("Attribute " + name + " is not numeric: " + asString());
            }
        }
        if (count < 1) throw new Hdf5Exception("Attribute " + name + " is empty");
        return type.getDouble(f.bytes(dataPos, type.size), 0);
    }

    public double[] asDoubles() {
        if (isString()) return new double[]{asDouble()};
        int n = (int) count;
        byte[] raw = f.bytes(dataPos, n * type.size);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = type.getDouble(raw, i);
        return out;
    }

    @Override
    public String toString() {
        try {
            return name + "=" + (count == 1 || isString() ? asString() : (count + " values"));
        } catch (Hdf5Exception e) {
            return name + "=<" + e.getMessage() + ">";
        }
    }
}
