package com.xinguo.seckill.order.dto;

import java.time.LocalDateTime;

/**
 * 秒杀下单 DTO（需求文档 8.3）
 */
public class SeckillDTO {

    /** 8.3 下单响应 data：{orderNo, status}；MQ 未落库时 status=QUEUED */
    public static class SeckillResult {
        public String orderNo;
        public String status;

        public SeckillResult() {
        }

        public SeckillResult(String orderNo, String status) {
            this.orderNo = orderNo;
            this.status = status;
        }
    }

    /**
     * 活动详情（order 从 Redis seckill:activity:{id} 读取，结构与 goods 服务的 ActivityDetail 一致；
     * 减少跨服务依赖，仅读取需要的字段）。
     */
    public static class ActivityInfo {
        public Long activityId;
        public Long goodsId;
        public String goodsName;
        public java.math.BigDecimal seckillPrice;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public Integer limitPerUser;
    }
}