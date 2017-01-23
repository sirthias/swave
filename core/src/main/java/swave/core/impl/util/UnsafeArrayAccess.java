package swave.core.impl.util;

import static org.jctools.util.UnsafeAccess.UNSAFE;

public final class UnsafeArrayAccess {
    private static final long REF_ARRAY_BASE;
    private static final int REF_ELEMENT_SHIFT;
    private static final long INT_ARRAY_BASE;
    private static final int INT_ELEMENT_SHIFT;

    static {
        int scale = UNSAFE.arrayIndexScale(Object[].class);
        if (4 == scale) REF_ELEMENT_SHIFT = 2;
        else if (8 == scale) REF_ELEMENT_SHIFT = 3;
        else throw new IllegalStateException("Unknown pointer size");
        REF_ARRAY_BASE = UNSAFE.arrayBaseOffset(Object[].class);

        scale = UNSAFE.arrayIndexScale(int[].class);
        if (4 == scale) INT_ELEMENT_SHIFT = 2;
        else if (8 == scale) INT_ELEMENT_SHIFT = 3;
        else throw new IllegalStateException("Unknown integer size");
        INT_ARRAY_BASE = UNSAFE.arrayBaseOffset(Object[].class);
    }
    private UnsafeArrayAccess() {
    }

    public static long calcRefArrayElementOffset(long index) {
        return REF_ARRAY_BASE + (index << REF_ELEMENT_SHIFT);
    }

    public static long calcIntArrayElementOffset(long index) {
        return INT_ARRAY_BASE + (index << INT_ELEMENT_SHIFT);
    }
}

