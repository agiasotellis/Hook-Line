package app.hookline.radar.core.odim;

import app.hookline.radar.core.hdf5.Attribute;
import app.hookline.radar.core.hdf5.Datatype;
import app.hookline.radar.core.hdf5.Hdf5File;
import app.hookline.radar.core.hdf5.Hdf5Object;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Decodes OPERA Data Information Model (ODIM_H5) files, the European standard
 * for weather radar data: polar volumes and scans (PVOL / SCAN) and
 * Cartesian composites (COMP / IMAGE).
 *
 * Attribute lookup follows ODIM inheritance: dataN/what overrides
 * datasetN/what, which overrides the root /what (same for where and how).
 */
public final class Odim {
    private Odim() {}

    /** Value markers in decoded arrays. Matches the app's Gate sentinels. */
    public static final float UNDETECT = Float.NEGATIVE_INFINITY;
    public static final float NODATA = Float.NaN;

    public static OdimFile read(byte[] bytes) {
        Hdf5File f = Hdf5File.open(bytes);
        Hdf5Object root = f.root();
        String object = str(root, "what", "object", "");
        String source = str(root, "what", "source", "");
        double lat = num(root, "where", "lat", Double.NaN);
        double lon = num(root, "where", "lon", Double.NaN);
        double height = num(root, "where", "height", 0);
        long nominal = time(str(root, "what", "date", null), str(root, "what", "time", null));

        List<Scan> scans = new ArrayList<>();
        Grid grid = null;
        for (String name : sortedDatasets(root)) {
            Hdf5Object ds = root.child(name);
            if (object.equals("COMP") || object.equals("IMAGE") || object.equals("CVOL")) {
                if (grid == null) grid = readGrid(root, ds, nominal);
            } else {
                Scan s = readScan(root, ds, nominal);
                if (s != null) scans.add(s);
            }
        }
        return new OdimFile(object, source, lat, lon, height, nominal, Collections.unmodifiableList(scans), grid);
    }

    // ---- polar ----------------------------------------------------------------

    private static Scan readScan(Hdf5Object root, Hdf5Object ds, long nominal) {
        double elangle = num(ds, "where", "elangle", Double.NaN);
        if (Double.isNaN(elangle)) return null;
        int nbins = (int) num(ds, "where", "nbins", 0);
        int nrays = (int) num(ds, "where", "nrays", 0);
        double rstartKm = num(ds, "where", "rstart", 0);
        double rscale = num(ds, "where", "rscale", 0);
        long start = time(inherit(root, ds, null, "what", "startdate"), inherit(root, ds, null, "what", "starttime"));
        long end = time(inherit(root, ds, null, "what", "enddate"), inherit(root, ds, null, "what", "endtime"));
        if (start == Long.MIN_VALUE) start = nominal;
        if (end == Long.MIN_VALUE) end = start;

        float[] azimuths = azimuths(ds, nrays);
        Map<String, Field> fields = new LinkedHashMap<>();
        for (String dn : sortedChildren(ds, "data")) {
            Hdf5Object d = ds.child(dn);
            Field fd = readField(root, ds, d);
            if (fd == null) continue;
            if (nrays == 0) nrays = fd.rows;
            if (nbins == 0) nbins = fd.cols;
            if (!fields.containsKey(fd.quantity)) fields.put(fd.quantity, fd);
        }
        if (fields.isEmpty() || rscale <= 0) return null;
        if (azimuths == null || azimuths.length != nrays) azimuths = uniformAzimuths(nrays);
        return new Scan(elangle, nrays, nbins, rstartKm * 1000.0, rscale, start, end, azimuths,
                Collections.unmodifiableMap(fields));
    }

    /** Ray centre azimuths, from how/startazA + stopazA when present. */
    private static float[] azimuths(Hdf5Object ds, int nrays) {
        Hdf5Object how = ds.child("how");
        if (how == null) return null;
        Attribute a = how.attribute("startazA");
        Attribute b = how.attribute("stopazA");
        if (a == null || b == null) return null;
        double[] s = a.asDoubles();
        double[] e = b.asDoubles();
        if (s.length != nrays || e.length != nrays) return null;
        float[] out = new float[nrays];
        for (int i = 0; i < nrays; i++) {
            double start = s[i], stop = e[i];
            if (stop < start) stop += 360.0; // ray crosses north
            double mid = (start + stop) / 2.0;
            out[i] = (float) (((mid % 360.0) + 360.0) % 360.0);
        }
        return out;
    }

    private static float[] uniformAzimuths(int nrays) {
        float[] out = new float[nrays];
        double step = 360.0 / nrays;
        for (int i = 0; i < nrays; i++) out[i] = (float) ((i + 0.5) * step);
        return out;
    }

    // ---- composites ------------------------------------------------------------

    private static Grid readGrid(Hdf5Object root, Hdf5Object ds, long nominal) {
        Hdf5Object dataGroup = null;
        Field field = null;
        for (String dn : sortedChildren(ds, "data")) {
            dataGroup = ds.child(dn);
            field = readField(root, ds, dataGroup);
            if (field != null) break;
        }
        if (field == null) return null;
        String projdef = inherit(root, ds, dataGroup, "where", "projdef");
        double xscale = numInherit(root, ds, dataGroup, "where", "xscale");
        double yscale = numInherit(root, ds, dataGroup, "where", "yscale");
        double[] corners = new double[8];
        String[] names = {"UL_lon", "UL_lat", "UR_lon", "UR_lat", "LL_lon", "LL_lat", "LR_lon", "LR_lat"};
        for (int i = 0; i < 8; i++) corners[i] = numInherit(root, ds, dataGroup, "where", names[i]);
        long start = time(inherit(root, ds, dataGroup, "what", "startdate"), inherit(root, ds, dataGroup, "what", "starttime"));
        long end = time(inherit(root, ds, dataGroup, "what", "enddate"), inherit(root, ds, dataGroup, "what", "endtime"));
        if (start == Long.MIN_VALUE) start = nominal;
        if (end == Long.MIN_VALUE) end = start;
        return new Grid(projdef == null ? "" : projdef, field.cols, field.rows, xscale, yscale, corners, start, end, field);
    }

    // ---- fields --------------------------------------------------------------------

    private static Field readField(Hdf5Object root, Hdf5Object ds, Hdf5Object d) {
        Hdf5Object data = d.child("data");
        if (data == null || !data.isDataset()) return null;
        long[] dims = data.dims();
        if (dims.length != 2) return null;
        String quantity = inherit(root, ds, d, "what", "quantity");
        if (quantity == null) return null;
        double gain = numOr(inherit(root, ds, d, "what", "gain"), 1.0);
        double offset = numOr(inherit(root, ds, d, "what", "offset"), 0.0);
        double nodata = numOr(inherit(root, ds, d, "what", "nodata"), Double.NaN);
        double undetect = numOr(inherit(root, ds, d, "what", "undetect"), Double.NaN);
        long bytes = dims[0] * dims[1] * data.datatype().size;
        if (bytes <= compactThresholdBytes) {
            byte[] raw = data.readRaw();
            return new Field(quantity.trim(), (int) dims[0], (int) dims[1], gain, offset, nodata, undetect, raw, data.datatype());
        }
        return compactField(quantity.trim(), data, (int) dims[0], (int) dims[1], gain, offset, nodata, undetect);
    }

    /**
     * Fields bigger than this are stored as 16-bit codes instead of raw bytes. The
     * OPERA composite is a 3800 x 4400 float64 grid: 134 MB raw, 33 MB compact.
     * Package-visible so tests can force the compact path.
     */
    static long compactThresholdBytes = 24L << 20;

    /** Two streaming passes (range, then codes): the raw dataset is never held in memory. */
    private static Field compactField(String quantity, Hdf5Object data, int rows, int cols,
                                      double gain, double offset, double nodata, double undetect) {
        final double[] range = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        data.forEachElement((i, r) -> {
            if (r == undetect || r == nodata || Double.isNaN(r)) return;
            if (r < range[0]) range[0] = r;
            if (r > range[1]) range[1] = r;
        });
        final double min = Double.isInfinite(range[0]) ? 0.0 : range[0];
        double span = Double.isInfinite(range[1]) ? 0.0 : range[1] - min;
        final double step = span > 0 ? span / (Field.MAX_CODE - Field.FIRST_VALUE) : 1.0;
        final short[] codes = new short[rows * cols]; // 0 = no data until written
        data.forEachElement((i, r) -> {
            if (i < 0 || i >= codes.length) return;
            int code;
            if (r == undetect) code = Field.CODE_UNDETECT;
            else if (r == nodata || Double.isNaN(r)) code = Field.CODE_NODATA;
            else code = Field.FIRST_VALUE + (int) Math.round((r - min) / step);
            codes[i] = (short) Math.min(Field.MAX_CODE, Math.max(0, code));
        });
        return new Field(quantity, rows, cols, gain, offset, nodata, undetect, codes, min, step);
    }

    // ---- attribute helpers ----------------------------------------------------------

    /** Look up group/attr on d, then ds, then root. */
    private static String inherit(Hdf5Object root, Hdf5Object ds, Hdf5Object d, String group, String attr) {
        Hdf5Object[] chain = d == null ? new Hdf5Object[]{ds, root} : new Hdf5Object[]{d, ds, root};
        for (Hdf5Object o : chain) {
            if (o == null) continue;
            Hdf5Object g = o.child(group);
            if (g == null) continue;
            Attribute a = g.attribute(attr);
            if (a != null) return a.asString();
        }
        return null;
    }

    private static double numInherit(Hdf5Object root, Hdf5Object ds, Hdf5Object d, String group, String attr) {
        return numOr(inherit(root, ds, d, group, attr), Double.NaN);
    }

    private static double numOr(String s, double dflt) {
        if (s == null) return dflt;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String str(Hdf5Object o, String group, String attr, String dflt) {
        Hdf5Object g = o.child(group);
        if (g == null) return dflt;
        Attribute a = g.attribute(attr);
        return a == null ? dflt : a.asString();
    }

    private static double num(Hdf5Object o, String group, String attr, double dflt) {
        Hdf5Object g = o.child(group);
        if (g == null) return dflt;
        Attribute a = g.attribute(attr);
        if (a == null) return dflt;
        try {
            return a.asDouble();
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    /** "YYYYMMDD" + "HHmmss" (UTC) to epoch millis, or Long.MIN_VALUE. */
    static long time(String date, String time) {
        if (date == null || date.trim().length() < 8) return Long.MIN_VALUE;
        String d = date.trim();
        String t = time == null ? "000000" : (time.trim() + "000000").substring(0, 6);
        try {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(4, 6)) - 1, Integer.parseInt(d.substring(6, 8)),
                    Integer.parseInt(t.substring(0, 2)), Integer.parseInt(t.substring(2, 4)), Integer.parseInt(t.substring(4, 6)));
            return c.getTimeInMillis();
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    private static List<String> sortedDatasets(Hdf5Object root) {
        return sortedChildren(root, "dataset");
    }

    /** Children named prefix1, prefix2, ... in numeric order. */
    private static List<String> sortedChildren(Hdf5Object o, String prefix) {
        List<String> names = new ArrayList<>();
        for (String n : o.childNames()) {
            if (n.startsWith(prefix) && n.length() > prefix.length() && Character.isDigit(n.charAt(prefix.length()))) names.add(n);
        }
        Collections.sort(names, (a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a.substring(prefix.length())), Integer.parseInt(b.substring(prefix.length())));
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });
        return names;
    }

    // ---- results ----------------------------------------------------------------

    public static final class OdimFile {
        public final String object;
        public final String source;
        public final double lat, lon, heightM;
        public final long nominalMillis;
        public final List<Scan> scans;
        public final Grid grid;

        OdimFile(String object, String source, double lat, double lon, double heightM, long nominalMillis, List<Scan> scans, Grid grid) {
            this.object = object;
            this.source = source;
            this.lat = lat;
            this.lon = lon;
            this.heightM = heightM;
            this.nominalMillis = nominalMillis;
            this.scans = scans;
            this.grid = grid;
        }

        /** The NOD: (ODIM node) code from what/source, e.g. "deess", or null. */
        public String node() {
            for (String part : source.split(",")) {
                String p = part.trim();
                if (p.startsWith("NOD:")) return p.substring(4).trim();
            }
            return null;
        }
    }

    /** One elevation sweep. Ranges in metres; azimuths are ray centres in degrees. */
    public static final class Scan {
        public final double elevationDeg;
        public final int nrays, nbins;
        public final double firstBinStartM, binSpacingM;
        public final long startMillis, endMillis;
        public final float[] azimuths;
        public final Map<String, Field> fields;

        Scan(double elevationDeg, int nrays, int nbins, double firstBinStartM, double binSpacingM, long startMillis, long endMillis,
             float[] azimuths, Map<String, Field> fields) {
            this.elevationDeg = elevationDeg;
            this.nrays = nrays;
            this.nbins = nbins;
            this.firstBinStartM = firstBinStartM;
            this.binSpacingM = binSpacingM;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.azimuths = azimuths;
            this.fields = fields;
        }

        /** Range to the centre of the first bin. */
        public double firstBinCenterM() {
            return firstBinStartM + binSpacingM / 2.0;
        }
    }

    /** A Cartesian composite. Row 0 is the northern (upper) edge. */
    public static final class Grid {
        public final String projdef;
        public final int xsize, ysize;
        public final double xscale, yscale;
        /** UL_lon, UL_lat, UR_lon, UR_lat, LL_lon, LL_lat, LR_lon, LR_lat. */
        public final double[] corners;
        public final long startMillis, endMillis;
        public final Field field;

        Grid(String projdef, int xsize, int ysize, double xscale, double yscale, double[] corners, long startMillis, long endMillis, Field field) {
            this.projdef = projdef;
            this.xsize = xsize;
            this.ysize = ysize;
            this.xscale = xscale;
            this.yscale = yscale;
            this.corners = corners;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.field = field;
        }
    }

    /** A 2-D array of one quantity, kept raw; values are scaled on access. */
    public static final class Field {
        public final String quantity;
        public final int rows, cols;
        public final double gain, offset, nodata, undetect;
        private final byte[] raw;
        private final Datatype type;
        // Compact form for large fields: 16-bit codes, raw = codeMin + (code - FIRST_VALUE) * codeStep.
        private final short[] codes;
        private final double codeMin, codeStep;
        static final int CODE_NODATA = 0, CODE_UNDETECT = 1, FIRST_VALUE = 2, MAX_CODE = 65535;

        Field(String quantity, int rows, int cols, double gain, double offset, double nodata, double undetect, byte[] raw, Datatype type) {
            this.quantity = quantity;
            this.rows = rows;
            this.cols = cols;
            this.gain = gain;
            this.offset = offset;
            this.nodata = nodata;
            this.undetect = undetect;
            this.raw = raw;
            this.type = type;
            this.codes = null;
            this.codeMin = 0;
            this.codeStep = 1;
        }

        Field(String quantity, int rows, int cols, double gain, double offset, double nodata, double undetect,
              short[] codes, double codeMin, double codeStep) {
            this.quantity = quantity;
            this.rows = rows;
            this.cols = cols;
            this.gain = gain;
            this.offset = offset;
            this.nodata = nodata;
            this.undetect = undetect;
            this.raw = null;
            this.type = null;
            this.codes = codes;
            this.codeMin = codeMin;
            this.codeStep = codeStep;
        }

        /** Physical value, {@link #UNDETECT} (no echo) or {@link #NODATA} (not scanned). */
        public float value(int row, int col) {
            if (row < 0 || col < 0 || row >= rows || col >= cols) return NODATA;
            if (codes != null) {
                int code = codes[row * cols + col] & 0xFFFF;
                if (code == CODE_NODATA) return NODATA;
                if (code == CODE_UNDETECT) return UNDETECT;
                return (float) ((codeMin + (code - FIRST_VALUE) * codeStep) * gain + offset);
            }
            double r = type.getDouble(raw, row * cols + col);
            if (r == undetect) return UNDETECT;
            if (r == nodata || Double.isNaN(r)) return NODATA;
            return (float) (r * gain + offset);
        }

        /** Whole row of physical values, reusing [out] when it is big enough. */
        public float[] row(int row, float[] out) {
            float[] o = out != null && out.length >= cols ? out : new float[cols];
            for (int c = 0; c < cols; c++) o[c] = value(row, c);
            return o;
        }

        public int sizeBytes() {
            return codes != null ? codes.length * 2 : raw.length;
        }
    }
}
