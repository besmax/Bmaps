/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.proj;

public final class NativeProjection {
    static { System.loadLibrary("bmaps_proj"); }
    private NativeProjection() {}
    public static native long open();
    public static native int transform(long handle, int source, int target, double x, double y, double[] output);
    public static native String operation(long handle);
    public static native void close(long handle);
}
