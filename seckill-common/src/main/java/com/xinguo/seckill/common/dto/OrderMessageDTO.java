package com.xinguo.seckill.common.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀下单 MQ 消息体（需求文档 3.2：{orderNo, activityId, userId, goodsId, price}）。
 * 由 order 服务生产者发送、order 服务消费者接收；字段不可擅自增删。
 */
public class OrderMessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderNo;
    private Long activityId;
    private Long userId;
    private Long goodsId;
    private BigDecimal price;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getGoodsId() {
        return goodsId;
    }

    public void setGoodsId(Long goodsId) {
        this.goodsId = goodsId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    @Override
    public String toString() {
        return "OrderMessageDTO{" +
                "orderNo='" + orderNo + '\'' +
                ", activityId=" + activityId +
                ", userId=" + userId +
                ", goodsId=" + goodsId +
                ", price=" + price +
                '}';
    }
}