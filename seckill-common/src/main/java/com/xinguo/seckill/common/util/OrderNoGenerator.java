package com.xinguo.seckill.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单号生成（需求文档 D9 / 8.3）。
 *
 * 格式：SK + yyyyMMddHHmm（分钟，12 位）+ 5 位递增序号 = SK + 17 位数字。
 * 满足验收正则 ^SK\d{17}$；分钟粒度 + 5 位序号（10 万/分钟去重容量）保证并发唯一性。
 * 线程安全：AtomicLong 递增取模。
 *
 * 部署约束（重要）：
 * 1) 本实现仅保证【单进程实例】内唯一（增量计数器为进程静态量）。
 * 2) 进程重启后计数器归零重计，同分钟内可能与旧序号碰撞：
 *    仅当重启后仍处同一自然分钟才可能撞号，故低风险但非严格无碰撞。
 * 3) 当前系统为单实例部署（每服务一个进程），符合此约束。
 *    如需多实例扩容，必须先升级本生成器（如实例ID+时钟），禁止直接多进程共用。
 */
public final class OrderNoGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int SEQ_MODULUS = 100000;
    private static final AtomicLong SEQ = new AtomicLong(0);

    private OrderNoGenerator() {
    }

    public static String generate() {
        long seq = SEQ.incrementAndGet() % SEQ_MODULUS;
        return "SK" + LocalDateTime.now().format(FMT) + String.format("%05d", seq);
    }
}