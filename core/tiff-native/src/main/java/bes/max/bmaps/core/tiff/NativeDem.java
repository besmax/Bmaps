/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps.core.tiff;

public final class NativeDem {
    static { System.loadLibrary("bmaps_dem"); }

    private NativeDem() {}

    public static native int open(byte[] path, long[] handle);
    public static native double[] metadata(long handle);
    public static native int sample(long handle, int column, int row, double[] value);
    public static native void close(long handle);
}
