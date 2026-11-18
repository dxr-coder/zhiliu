package com.smartflow.server.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public final class NumberGen {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private NumberGen() {
    }

    /** 启动时注入当日已有最大序号（含跨表 max）。 */
    public static void seed(int maxToday) {
        SEQ.updateAndGet(cur -> Math.max(cur, maxToday));
    }

    /** @param prefix L|E|T|M|P */
    public static String genNo(String prefix) {
        int n = SEQ.incrementAndGet() % 100;
        if (n == 0) {
            n = SEQ.incrementAndGet() % 100;
            if (n == 0) {
                n = 1;
            }
        }
        return prefix + "-" + LocalDate.now().format(D) + "-" + String.format("%02d", n);
    }
}
