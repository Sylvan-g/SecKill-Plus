package com.xinguo.seckill.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单实体（t_seckill_order，需求文档 6.3.1）。
 * active_activity_id 为 generated column（D13）：status∈{0,1} 时=activity_id，否则 NULL；
 * 由 uk_user_active 部分唯一索引保证"每用户每活动至多一笔非取消态订单"。
 */
@TableName("t_seckill_order")
public class SeckillOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long activityId;

    private Long userId;

    private Long goodsId;

    private String goodsName;

    private BigDecimal price;

    /** 0-待支付 1-已支付 2-已取消(超时) 3-已取消(用户主动) */
    private Integer status;

    @TableField("pay_channel")
    private Integer payChannel;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("pay_deadline")
    private LocalDateTime payDeadline;

    @TableField("paid_at")
    private LocalDateTime paidAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getGoodsName() {
        return goodsName;
    }

    public void setGoodsName(String goodsName) {
        this.goodsName = goodsName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(Integer payChannel) {
        this.payChannel = payChannel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getPayDeadline() {
        return payDeadline;
    }

    public void setPayDeadline(LocalDateTime payDeadline) {
        this.payDeadline = payDeadline;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }
}