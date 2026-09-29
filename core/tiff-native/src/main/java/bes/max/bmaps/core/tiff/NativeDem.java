package bes.max.bmaps.core.tiff;

public final class NativeDem {
    static { System.loadLibrary("bmaps_dem"); }

    private NativeDem() {}

    public static native int open(byte[] path, long[] handle);
    public static native double[] metadata(long handle);
    public static native int sample(long handle, int column, int row, double[] value);
    public static native void close(long handle);
}
