package com.xinguo.seckill.goods.dto;

/**
 * 库存内部接口 DTO（需求文档 8.14）
 */
public class StockDTO {

    /** POST /internal/stock/deduct 请求体 {goodsId, count} */
    public static class DeductRequest {
        public Long goodsId;
        public Integer count;
    }

    /** POST /internal/stock/rollback 请求体 {goodsId, count} */
    public static class RollbackRequest {
        public Long goodsId;
        public Integer count;
    }
}