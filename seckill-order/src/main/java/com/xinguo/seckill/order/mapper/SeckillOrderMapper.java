package com.xinguo.seckill.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinguo.seckill.order.entity.SeckillOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /** 当前读锁行查询（支付/取消并发安全，3.3/3.4）：FOR UPDATE 防止与并发的支付/取消互相覆盖 */
    @Select("SELECT id, order_no, activity_id, user_id, goods_id, goods_name, price, status, pay_channel, "
            + "created_at, pay_deadline, paid_at FROM t_seckill_order WHERE order_no = #{orderNo} FOR UPDATE")
    SeckillOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}