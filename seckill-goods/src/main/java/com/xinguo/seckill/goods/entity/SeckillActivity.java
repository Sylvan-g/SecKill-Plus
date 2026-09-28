package com.xinguo.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动实体（t_seckill_activity，需求文档 6.2.1）
 * 一场活动 = 一个商品的一次秒杀场次；status 由 goods 定时任务维护（3.2/4.2）
 */
@TableName("t_seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("goods_id")
    private Long goodsId;

    @TableField("goods_name")
    private String goodsName;

    @TableField("goods_img")
    private String goodsImg;

    @TableField("original_price")
    private BigDecimal originalPrice;

    @TableField("seckill_price")
    private BigDecimal seckillPrice;

    /** 秒杀库存（预售总库存，仅预热用，R8） */
    private Integer stock;

    @TableField("limit_per_user")
    private Integer limitPerUser;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    /** 0-未开始 1-进行中 2-已结束 3-已下架 */
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getGoodsImg() {
        return goodsImg;
    }

    public void setGoodsImg(String goodsImg) {
        this.goodsImg = goodsImg;
    }

    public BigDecimal getOriginalPrice() {
        return originalPrice;
    }

    public void setOriginalPrice(BigDecimal originalPrice) {
        this.originalPrice = originalPrice;
    }

    public BigDecimal getSeckillPrice() {
        return seckillPrice;
    }

    public void setSeckillPrice(BigDecimal seckillPrice) {
        this.seckillPrice = seckillPrice;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public Integer getLimitPerUser() {
        return limitPerUser;
    }

    public void setLimitPerUser(Integer limitPerUser) {
        this.limitPerUser = limitPerUser;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}