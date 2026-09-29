package bes.max.bmaps.core.proj;

public final class NativeProjection {
    static { System.loadLibrary("bmaps_proj"); }
    private NativeProjection() {}
    public static native long open();
    public static native int transform(long handle, int source, int target, double x, double y, double[] output);
    public static native String operation(long handle);
    public static native void close(long handle);
}
