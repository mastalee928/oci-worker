package com.ociworker.util;

/**
 * 引导卷 VPUs/GB：与存储页、OCI 档位一致（10～120，步进 10）。
 */
public final class BootVolumeVpusUtil {

    public static final int DEFAULT = 10;
    public static final int MIN = 10;
    public static final int MAX = 120;
    public static final int STEP = 10;

    private BootVolumeVpusUtil() {}

    /** null 或非法值 → {@link #DEFAULT}；否则吸附到合法档位。 */
    public static int normalize(Integer vpusPerGB) {
        if (vpusPerGB == null || vpusPerGB <= 0) {
            return DEFAULT;
        }
        int v = vpusPerGB;
        if (v < MIN) {
            return MIN;
        }
        if (v > MAX) {
            return MAX;
        }
        int rem = v % STEP;
        if (rem == 0) {
            return v;
        }
        int down = v - rem;
        return down < MIN ? MIN : down;
    }

    /** TG / 列表：{@code 50GB(10VPUs)} */
    public static String formatDiskWithVpus(int diskGb, int vpusPerGB) {
        return diskGb + "GB(" + normalize(vpusPerGB) + "VPUs)";
    }
}
