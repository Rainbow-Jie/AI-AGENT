package com.goocidata.agent.common;

/**
 * 轻量雪花 ID 生成器（单机版）
 *
 * @author nzj
 */
public final class IdGen {

    private static final long EPOCH = 1704067200000L; // 2024-01-01
    private static final long SEQ_BITS = 12L;
    private static final long MAX_SEQ = ~(-1L << SEQ_BITS);

    private static long lastTimestamp = -1L;
    private static long sequence = 0L;

    private IdGen() {
    }

    public static synchronized String next() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            // 时钟回拨，直接沿用上一个时间戳
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                while (now <= lastTimestamp) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        long id = ((now - EPOCH) << SEQ_BITS) | sequence;
        return String.valueOf(id);
    }

    public static String now() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
