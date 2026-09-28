package com.xinguo.seckill.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单查询/操作 DTO（需求文档 8.4 / 8.5 / 8.8 / 8.9）
 */
public class OrderDTO {

    /** 8.4 订单状态轮询响应：{orderNo, status, price, payDeadline} */
    public static class OrderStatusResult {
        public String orderNo;
        /** QUEUED / WAIT_PAY / PAID / CANCELLED(超时或主动) */
        public String status;
        public BigDecimal price;
        public LocalDateTime payDeadline;

        public OrderStatusResult() {
        }

        public OrderStatusResult(String orderNo, String status, BigDecimal price, LocalDateTime payDeadline) {
            this.orderNo = orderNo;
            this.status = status;
            this.price = price;
            this.payDeadline = payDeadline;
        }
    }

    /** 8.8 支付成功响应 data：{orderNo, status, paidAmount, balanceAfter} */
    public static class PayResultDTO {
        public String orderNo;
        public Integer status;
        public BigDecimal paidAmount;
        public BigDecimal balanceAfter;

        public PayResultDTO() {
        }

        public PayResultDTO(String orderNo, Integer status, BigDecimal paidAmount, BigDecimal balanceAfter) {
            this.orderNo = orderNo;
            this.status = status;
            this.paidAmount = paidAmount;
            this.balanceAfter = balanceAfter;
        }
    }
}