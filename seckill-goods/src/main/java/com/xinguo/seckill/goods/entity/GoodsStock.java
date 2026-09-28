package com.xinguo.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 商品真实库存实体（t_goods_stock，需求文档 6.2.2）
 * DB 层最终兜底；Redis 丢失时以 total_stock 为准重新预热（R8）。
 * 下单落库扣减、取消回补（乐观锁 version）。
 */
@TableName("t_goods_stock")
public class GoodsStock {

    @TableId
    private Long goodsId;

    @TableField("total_stock")
    private Integer totalStock;

    /** 乐观锁版本号 */
    private Integer version;

    public Long getGoodsId() {
        return goodsId;
    }

    public void setGoodsId(Long goodsId) {
        this.goodsId = goodsId;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}