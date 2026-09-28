package com.xinguo.seckill.goods.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinguo.seckill.goods.entity.GoodsStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface GoodsStockMapper extends BaseMapper<GoodsStock> {

    /**
     * 当前读获取库存行（带 FOR UPDATE 行锁）：
     * 用于乐观锁重试——普通快照读在 REPEATABLE READ 下单事务内读到的 version 恒定，
     * 重试将永远拿到旧值导致重试失效；FOR UPDATE 读到的是已提交的最新 version，
     * 使"版本冲突 -> 重读 -> 再更新"真正收敛（需求文档 10.3）。
     */
    @Select("SELECT goods_id, total_stock, version FROM t_goods_stock "
            + "WHERE goods_id = #{goodsId} FOR UPDATE")
    GoodsStock selectByIdForUpdate(@Param("goodsId") Long goodsId);

    /**
     * 乐观锁扣减真实库存（需求文档 10.3）：
     * 仅当 version 匹配且剩余库存足够时扣减；影响行数 0 表示版本冲突或库存不足，由 service 重试。
     */
    @Update("UPDATE t_goods_stock "
            + "SET total_stock = total_stock - #{count}, version = version + 1 "
            + "WHERE goods_id = #{goodsId} AND total_stock >= #{count} AND version = #{version}")
    int deductByVersion(@Param("goodsId") Long goodsId,
                        @Param("count") int count,
                        @Param("version") int version);

    /**
     * 库存回补（D8，取消订单时）：
     * 无条件累加（回补永不会让余额超卖），version 同步 +1 保持乐观锁一致性。
     */
    @Update("UPDATE t_goods_stock "
            + "SET total_stock = total_stock + #{count}, version = version + 1 "
            + "WHERE goods_id = #{goodsId}")
    int rollbackStock(@Param("goodsId") Long goodsId, @Param("count") int count);
}