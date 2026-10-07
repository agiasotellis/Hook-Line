package app.hookline.radar.core.odim;

/**
 * A GPU-ready window of a Cartesian composite: a value texture plus a mesh
 * that places it on the Web Mercator map.
 *
 * The mesh is a regular lat/lon grid over the window; each vertex carries its
 * Mercator position (metres relative to an origin, matching the app's
 * MercatorProjector) and its texture coordinate from the composite's own
 * projection. Vertices are dense enough (about 15 km apart at country zoom)
 * that linear interpolation between them stays well under one composite
 * pixel. Texture coordinates outside 0..1 mean "outside the composite" and
 * are discarded by the shader.
 */
public final class CompositeTile {
    /** Interleaved x, y, u, v; six vertices per quad. */
    public final float[] mesh;
    public final int texWidth, texHeight;
    /** Row-major, texHeight rows of texWidth; below-threshold encoded as {@link #BELOW}. */
    public final float[] values;
    public final long startMillis, endMillis;
    /** Composite pixels per texel (1 = full resolution). */
    public final int step;

    public static final float BELOW = -1.0e30f;
    private static final double R = 6_378_137.0;

    private CompositeTile(float[] mesh, int texWidth, int texHeight, float[] values, long startMillis, long endMillis, int step) {
        this.mesh = mesh;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.values = values;
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.step = step;
    }

    /** Maps lat/lon to fractional composite (col, row) measured from the upper-left pixel corner. */
    public static final class Locator {
        final Projection proj;
        final double xUL, yUL, xscale, yscale;
        public final int cols, rows;

        public Locator(Odim.Grid g) {
            proj = Projection.parse(g.projdef);
            double[] c = g.corners; // UL, UR, LL, LR as lon/lat pairs
            boolean hasLL = !Double.isNaN(c[4]) && !Double.isNaN(c[5]);
            boolean hasUR = !Double.isNaN(c[2]) && !Double.isNaN(c[3]);
            double xu, yu;
            if (hasLL && hasUR) {
                // LL and UR are the corners ODIM always provides; UL is (x of LL, y of UR).
                double[] ll = proj.forward(c[5], c[4]);
                double[] ur = proj.forward(c[3], c[2]);
                xu = ll[0];
                yu = ur[1];
                double xs = g.xscale, ys = g.yscale;
                if (!(xs > 0)) xs = (ur[0] - ll[0]) / g.xsize;
                if (!(ys > 0)) ys = (ur[1] - ll[1]) / g.ysize;
                xscale = xs;
                yscale = ys;
            } else {
                double[] ul = proj.forward(c[1], c[0]);
                double[] lr = proj.forward(c[7], c[6]);
                xu = ul[0];
                yu = ul[1];
                xscale = g.xscale > 0 ? g.xscale : (lr[0] - ul[0]) / g.xsize;
                yscale = g.yscale > 0 ? g.yscale : (ul[1] - lr[1]) / g.ysize;
            }
            xUL = xu;
            yUL = yu;
            cols = g.xsize;
            rows = g.ysize;
        }

        public double[] colRow(double lat, double lon) {
            double[] p = proj.forward(lat, lon);
            return new double[]{(p[0] - xUL) / xscale, (yUL - p[1]) / yscale};
        }
    }

    /**
     * Build a tile covering a lat/lon window (typically the visible map plus
     * a margin). Returns null when the window doesn't touch the composite.
     *
     * @param maxTexels   largest texture edge to produce (e.g. 2048)
     * @param originLat   Mercator origin shared with the other map layers
     * @param maxPool     take the block maximum when downsampling (right for reflectivity)
     */
    public static CompositeTile build(Odim.Grid g, Locator loc, double south, double west, double north, double east,
                                      int maxTexels, double originLat, double originLon, boolean maxPool) {
        // Pixel window: project points along the edges and through the middle of the box.
        double cMin = Double.MAX_VALUE, cMax = -Double.MAX_VALUE, rMin = Double.MAX_VALUE, rMax = -Double.MAX_VALUE;
        int n = 24;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                if (i != 0 && i != n && j != 0 && j != n && i != n / 2 && j != n / 2) continue;
                double lat = south + (north - south) * i / n;
                double lon = west + (east - west) * j / n;
                double[] cr = loc.colRow(lat, lon);
                cMin = Math.min(cMin, cr[0]);
                cMax = Math.max(cMax, cr[0]);
                rMin = Math.min(rMin, cr[1]);
                rMax = Math.max(rMax, cr[1]);
            }
        }
        int c0 = (int) Math.max(0, Math.floor(cMin));
        int c1 = (int) Math.min(loc.cols, Math.ceil(cMax));
        int r0 = (int) Math.max(0, Math.floor(rMin));
        int r1 = (int) Math.min(loc.rows, Math.ceil(rMax));
        if (c1 - c0 < 1 || r1 - r0 < 1) return null;

        int step = Math.max(1, (int) Math.ceil(Math.max(c1 - c0, r1 - r0) / (double) maxTexels));
        int w = (c1 - c0 + step - 1) / step;
        int h = (r1 - r0 + step - 1) / step;
        float[] values = sample(g.field, r0, c0, r1, c1, step, w, h, maxPool);

        // The texture spans exactly cols c0 .. c0 + w*step, rows r0 .. r0 + h*step.
        double spanC = (double) w * step, spanR = (double) h * step;
        int nx = 64, ny = 64;
        float[] mesh = new float[nx * ny * 6 * 4];
        double[] vx = new double[(nx + 1) * (ny + 1)], vy = new double[vx.length], vu = new double[vx.length], vv = new double[vx.length];
        double y0 = mercY(originLat);
        for (int j = 0; j <= ny; j++) {
            double lat = south + (north - south) * j / ny;
            double my = mercY(lat) - y0;
            for (int i = 0; i <= nx; i++) {
                double lon = west + (east - west) * i / nx;
                int k = j * (nx + 1) + i;
                vx[k] = Math.toRadians(lonDiffDeg(lon, originLon)) * R;
                vy[k] = my;
                double[] cr = loc.colRow(lat, lon);
                vu[k] = (cr[0] - c0) / spanC;
                vv[k] = (cr[1] - r0) / spanR;
            }
        }
        int m = 0;
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int a = j * (nx + 1) + i, b = a + 1, c = a + nx + 1, d = c + 1;
                int[] tri = {a, c, d, a, d, b};
                for (int t : tri) {
                    mesh[m++] = (float) vx[t];
                    mesh[m++] = (float) vy[t];
                    mesh[m++] = (float) vu[t];
                    mesh[m++] = (float) vv[t];
                }
            }
        }
        return new CompositeTile(mesh, w, h, values, g.startMillis, g.endMillis, step);
    }

    /** Value at a lat/lon, or NaN outside the composite. */
    public static float valueAt(Odim.Grid g, Locator loc, double lat, double lon) {
        double[] cr = loc.colRow(lat, lon);
        int c = (int) Math.floor(cr[0]), r = (int) Math.floor(cr[1]);
        if (c < 0 || r < 0 || c >= loc.cols || r >= loc.rows) return Float.NaN;
        return g.field.value(r, c);
    }

    private static float[] sample(Odim.Field f, int r0, int c0, int r1, int c1, int step, int w, int h, boolean maxPool) {
        float[] out = new float[w * h];
        float[] row = new float[f.cols];
        if (step == 1) {
            for (int j = 0; j < h; j++) {
                f.row(r0 + j, row);
                for (int i = 0; i < w; i++) out[j * w + i] = encode(row[c0 + i]);
            }
            return out;
        }
        java.util.Arrays.fill(out, BELOW);
        for (int r = r0; r < r1; r++) {
            if (!maxPool && (r - r0) % step != 0) continue;
            f.row(r, row);
            int j = (r - r0) / step;
            for (int c = c0; c < c1; c++) {
                if (!maxPool && (c - c0) % step != 0) continue;
                float v = encode(row[c]);
                int k = j * w + (c - c0) / step;
                if (v > out[k]) out[k] = v;
            }
        }
        return out;
    }

    private static float encode(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? BELOW : v;
    }

    private static double mercY(double latDeg) {
        return R * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(latDeg) / 2));
    }

    private static double lonDiffDeg(double lon, double lon0) {
        double d = lon - lon0;
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return d;
    }
}
