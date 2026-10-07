package app.hookline.radar.core.odim;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Forward map projections (lat/lon degrees to x/y metres) for the PROJ
 * strings radar composites declare in where/projdef. Ellipsoidal formulas
 * from Snyder, "Map Projections: A Working Manual" (USGS PP 1395).
 *
 * Supported: laea (oblique and polar), lcc (1SP and 2SP), stere (polar),
 * merc / webmerc. Anything else throws, so a wrongly placed composite can
 * never be drawn silently.
 */
public abstract class Projection {
    final double a, e, e2, x0, y0, toMetres;

    Projection(Params p) {
        a = p.a;
        e2 = p.e2;
        e = Math.sqrt(e2);
        x0 = p.d("x_0", 0) * p.unit;
        y0 = p.d("y_0", 0) * p.unit;
        toMetres = p.unit;
    }

    /** Projected x/y in metres for latitude/longitude in degrees. */
    public abstract double[] forward(double latDeg, double lonDeg);

    public static Projection parse(String projdef) {
        Params p = new Params(projdef);
        String proj = p.s("proj", "");
        switch (proj) {
            case "laea": return new Laea(p);
            case "lcc": return new Lcc(p);
            case "stere": return new PolarStere(p);
            case "merc":
            case "webmerc": return new Merc(p);
            default: throw new IllegalArgumentException("Unsupported projection '" + proj + "' in: " + projdef);
        }
    }

    // ---- shared ellipsoid helpers ---------------------------------------------------

    /** Authalic q (Snyder 3-12). */
    double q(double phi) {
        double s = Math.sin(phi);
        if (e < 1e-12) return 2 * s;
        double es = e * s;
        return (1 - e2) * (s / (1 - es * es) - (1 / (2 * e)) * Math.log((1 - es) / (1 + es)));
    }

    /** m (Snyder 14-15). */
    double m(double phi) {
        double s = Math.sin(phi);
        return Math.cos(phi) / Math.sqrt(1 - e2 * s * s);
    }

    /** t (Snyder 15-9). */
    double t(double phi) {
        double es = e * Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2) / Math.pow((1 - es) / (1 + es), e / 2);
    }

    static double lonDiff(double lon, double lon0) {
        double d = lon - lon0;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    // ---- Lambert azimuthal equal-area ---------------------------------------------

    static final class Laea extends Projection {
        final double lat0, lon0, qp, rq, beta1, d, sinB1, cosB1;
        final int pole; // 1 north polar, -1 south polar, 0 oblique

        Laea(Params p) {
            super(p);
            lat0 = Math.toRadians(p.d("lat_0", 0));
            lon0 = Math.toRadians(p.d("lon_0", 0));
            qp = q(Math.PI / 2);
            rq = a * Math.sqrt(qp / 2);
            if (Math.abs(Math.abs(lat0) - Math.PI / 2) < 1e-10) {
                pole = lat0 > 0 ? 1 : -1;
                beta1 = d = sinB1 = cosB1 = 0;
            } else {
                pole = 0;
                beta1 = Math.asin(q(lat0) / qp);
                sinB1 = Math.sin(beta1);
                cosB1 = Math.cos(beta1);
                d = a * m(lat0) / (rq * cosB1);
            }
        }

        @Override
        public double[] forward(double latDeg, double lonDeg) {
            double phi = Math.toRadians(latDeg);
            double dl = lonDiff(Math.toRadians(lonDeg), lon0);
            if (pole != 0) {
                double rho = a * Math.sqrt(Math.max(0, qp - pole * q(phi)));
                return new double[]{x0 + rho * Math.sin(dl), y0 - pole * rho * Math.cos(dl)};
            }
            double beta = Math.asin(Math.max(-1, Math.min(1, q(phi) / qp)));
            double sb = Math.sin(beta), cb = Math.cos(beta);
            double b = rq * Math.sqrt(2 / (1 + sinB1 * sb + cosB1 * cb * Math.cos(dl)));
            double x = b * d * cb * Math.sin(dl);
            double y = (b / d) * (cosB1 * sb - sinB1 * cb * Math.cos(dl));
            return new double[]{x0 + x, y0 + y};
        }
    }

    // ---- Lambert conformal conic -------------------------------------------------------

    static final class Lcc extends Projection {
        final double lon0, n, bigF, rho0, k0;

        Lcc(Params p) {
            super(p);
            double lat1 = Math.toRadians(p.d("lat_1", p.d("lat_0", 0)));
            double lat2 = Math.toRadians(p.d("lat_2", Math.toDegrees(lat1)));
            double lat0 = Math.toRadians(p.d("lat_0", Math.toDegrees(lat1)));
            lon0 = Math.toRadians(p.d("lon_0", 0));
            k0 = p.d("k_0", p.d("k", 1));
            double m1 = m(lat1), m2 = m(lat2), t1 = t(lat1), t2 = t(lat2);
            n = Math.abs(lat1 - lat2) < 1e-10 ? Math.sin(lat1) : (Math.log(m1) - Math.log(m2)) / (Math.log(t1) - Math.log(t2));
            bigF = m1 / (n * Math.pow(t1, n));
            rho0 = a * k0 * bigF * Math.pow(t(lat0), n);
        }

        @Override
        public double[] forward(double latDeg, double lonDeg) {
            double phi = Math.toRadians(latDeg);
            double rho = a * k0 * bigF * Math.pow(t(phi), n);
            double theta = n * lonDiff(Math.toRadians(lonDeg), lon0);
            return new double[]{x0 + rho * Math.sin(theta), y0 + rho0 - rho * Math.cos(theta)};
        }
    }

    // ---- polar stereographic ---------------------------------------------------------------

    static final class PolarStere extends Projection {
        final double lon0, scale;
        final int pole;

        PolarStere(Params p) {
            super(p);
            double lat0 = p.d("lat_0", 90);
            if (Math.abs(Math.abs(lat0) - 90) > 1e-9) {
                throw new IllegalArgumentException("Only polar stereographic is supported (lat_0=" + lat0 + ")");
            }
            pole = lat0 > 0 ? 1 : -1;
            lon0 = Math.toRadians(p.d("lon_0", 0));
            double latTs = Math.toRadians(pole * p.d("lat_ts", 90 * pole));
            double k0 = p.d("k_0", p.d("k", 1));
            if (Math.abs(latTs - Math.PI / 2) > 1e-10) {
                scale = a * m(latTs) / t(latTs);
            } else {
                scale = 2 * a * k0 / Math.sqrt(Math.pow(1 + e, 1 + e) * Math.pow(1 - e, 1 - e));
            }
        }

        @Override
        public double[] forward(double latDeg, double lonDeg) {
            double phi = Math.toRadians(pole * latDeg);
            double dl = lonDiff(Math.toRadians(lonDeg), lon0) * pole;
            double rho = scale * t(phi);
            return new double[]{x0 + pole * rho * Math.sin(dl), y0 - pole * rho * Math.cos(dl)};
        }
    }

    // ---- Mercator ---------------------------------------------------------------------------

    static final class Merc extends Projection {
        final double lon0, k0;

        Merc(Params p) {
            super(p);
            lon0 = Math.toRadians(p.d("lon_0", 0));
            double latTs = Math.toRadians(p.d("lat_ts", 0));
            k0 = p.d("k_0", p.d("k", 1)) * m(latTs);
        }

        @Override
        public double[] forward(double latDeg, double lonDeg) {
            double phi = Math.toRadians(latDeg);
            double x = a * k0 * lonDiff(Math.toRadians(lonDeg), lon0);
            double y = -a * k0 * Math.log(t(phi));
            return new double[]{x0 + x, y0 + y};
        }
    }

    // ---- PROJ string parsing -------------------------------------------------------------------

    static final class Params {
        final Map<String, String> v = new HashMap<>();
        final double a, e2, unit;

        Params(String projdef) {
            for (String tok : projdef.trim().split("\\s+")) {
                if (tok.isEmpty()) continue;
                String t = tok.startsWith("+") ? tok.substring(1) : tok;
                int eq = t.indexOf('=');
                if (eq < 0) v.put(t.toLowerCase(Locale.ROOT), "");
                else v.put(t.substring(0, eq).toLowerCase(Locale.ROOT), t.substring(eq + 1));
            }
            double[] ell = ellipsoid();
            a = ell[0];
            e2 = ell[1];
            String u = s("units", "m");
            unit = u.equals("km") ? 1000.0 : 1.0;
        }

        private double[] ellipsoid() {
            if (v.containsKey("r")) return new double[]{d("r", 6371000), 0};
            double[] named = null;
            String ellps = s("ellps", s("datum", "WGS84")).toUpperCase(Locale.ROOT);
            switch (ellps) {
                case "WGS84": case "GRS80": case "ETRS89": case "NAD83": named = new double[]{6378137.0, 1 / 298.257223563}; break;
                case "INTL": named = new double[]{6378388.0, 1 / 297.0}; break;
                case "BESSEL": named = new double[]{6377397.155, 1 / 299.1528128}; break;
                case "SPHERE": named = new double[]{6370997.0, 0}; break;
                default: named = new double[]{6378137.0, 1 / 298.257223563};
            }
            if (ellps.equals("GRS80")) named[1] = 1 / 298.257222101;
            double aa = v.containsKey("a") ? d("a", named[0]) : named[0];
            double f;
            if (v.containsKey("b")) f = 1 - d("b", aa) / aa;
            else if (v.containsKey("rf")) f = 1 / d("rf", 298.257223563);
            else if (v.containsKey("f")) f = d("f", named[1]);
            else if (v.containsKey("a") && !v.containsKey("ellps") && !v.containsKey("datum")) f = 0; // only a given: sphere
            else f = named[1];
            return new double[]{aa, 2 * f - f * f};
        }

        String s(String k, String dflt) {
            String r = v.get(k);
            return r == null ? dflt : r;
        }

        double d(String k, double dflt) {
            String r = v.get(k);
            if (r == null || r.isEmpty()) return dflt;
            try {
                return Double.parseDouble(r);
            } catch (NumberFormatException ex) {
                return dflt;
            }
        }
    }
}
