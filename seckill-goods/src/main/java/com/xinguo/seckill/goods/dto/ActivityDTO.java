package com.xinguo.seckill.goods.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动相关 DTO（需求文档 8.1 列表 / 8.2 详情）
 */
public class ActivityDTO {

    /** 8.1 列表项：stock 为 Redis 实时剩余库存，未预热返回 DB 值 */
    public static class ActivityListItem {
        public Long activityId;
        public String goodsName;
        public BigDecimal originalPrice;
        public BigDecimal seckillPrice;
        public Integer stock;
        public Integer limitPerUser;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public Integer status;
    }

    /** 8.1 列表响应：{ list, total, serverTime } */
    public static class ActivityListResult {
        public List<ActivityListItem> list;
        public long total;
        public LocalDateTime serverTime;

        public ActivityListResult() {
        }

        public ActivityListResult(List<ActivityListItem> list, long total, LocalDateTime serverTime) {
            this.list = list;
            this.total = total;
            this.serverTime = serverTime;
        }
    }

    /** 8.2 详情：含 goodsId/goodsImg，字段更多 */
    public static class ActivityDetail {
        public Long activityId;
        public Long goodsId;
        public String goodsName;
        public String goodsImg;
        public BigDecimal originalPrice;
        public BigDecimal seckillPrice;
        public Integer stock;
        public Integer limitPerUser;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public LocalDateTime serverTime;
        public Integer status;
    }

    /** 8.14 内部创建活动请求体（POST /internal/activity/create，字段对应 6.2.1 DDL） */
    public static class CreateActivityRequest {
        public Long goodsId;
        public String goodsName;
        public String goodsImg;
        public BigDecimal originalPrice;
        public BigDecimal seckillPrice;
        public Integer stock;
        public Integer limitPerUser;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
    }
}