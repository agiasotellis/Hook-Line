package app.hookline.radar.core.hdf5;

/** Unsupported feature or corrupt file. Messages name the exact problem. */
public final class Hdf5Exception extends RuntimeException {
    public Hdf5Exception(String message) {
        super(message);
    }
}
