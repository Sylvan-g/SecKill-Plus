package com.xinguo.seckill.common.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OrderNoGenerator 外部行为测试：
 * 1) 格式必须匹配 ^SK\d{17}$
 * 2) 单线程生成 1000 个无重复
 * 3) 并发 100 线程 × 100 个 = 10000 个全部唯一
 */
class OrderNoGeneratorTest {

    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("^SK\\d{17}$");

    @Test
    void generatedOrderNoMatchesFormat() {
        String orderNo = OrderNoGenerator.generate();
        assertTrue(ORDER_NO_PATTERN.matcher(orderNo).matches(),
                "orderNo 格式必须为 SK + 17 位数字，实际: " + orderNo);
    }

    @Test
    void sequentialThousandAreUnique() {
        Set<String> set = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 1000; i++) {
            String no = OrderNoGenerator.generate();
            assertTrue(ORDER_NO_PATTERN.matcher(no).matches(), "非法订单号: " + no);
            set.add(no);
        }
        assertEquals(1000, set.size(), "1000 个订单号必须全部唯一");
    }

    @Test
    void concurrentTenThousandAreUnique() throws InterruptedException {
        int threads = 100;
        int perThread = 100;
        Set<String> set = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            set.add(OrderNoGenerator.generate());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发生成超时");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, set.size(),
                "100 线程 × 100 个订单号必须全部唯一");
    }
}