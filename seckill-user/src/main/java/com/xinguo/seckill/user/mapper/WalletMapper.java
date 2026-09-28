package com.xinguo.seckill.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xinguo.seckill.user.entity.Wallet;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface WalletMapper extends BaseMapper<Wallet> {

    /**
     * 锁行读取：对用户钱包行加排他锁（SELECT ... FOR UPDATE），
     * 保证同一用户的钱包增/扣/退款操作完全串行化。
     * 调用必须在 @Transactional 事务内，锁一直持有到事务提交/回滚。
     */
    @Select("SELECT * FROM t_wallet WHERE user_id = #{userId} FOR UPDATE")
    Wallet selectForUpdate(@Param("userId") Long userId);

    /**
     * 条件扣款：仅当 balance >= amount 时扣减，防止出现负数余额（需求文档 10.4）。
     * 返回影响行数：1=扣款成功；0=余额不足。行锁保证并发安全。
     * 注意：balance_after 需扣款成功后重新 SELECT 获取（文档 10.4），确保对账数据准确。
     */
    @Update("UPDATE t_wallet SET balance = balance - #{amount}, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND balance >= #{amount}")
    int deductByCondition(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 入账（充值/补偿退款）：余额正向累加。amount 恒为正数，调用方保证校验过。
     */
    @Update("UPDATE t_wallet SET balance = balance + #{amount}, updated_at = NOW() "
            + "WHERE user_id = #{userId}")
    int addBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}