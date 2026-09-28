package com.xinguo.seckill.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.user.entity.Wallet;
import com.xinguo.seckill.user.entity.WalletFlow;
import com.xinguo.seckill.user.mapper.WalletFlowMapper;
import com.xinguo.seckill.user.mapper.WalletMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 钱包服务：
 * - getBalance / recharge：对外接口（需求文档 8.6 / 8.7）
 * - deduct / refund：内部扣款与补偿退款（8.14，order 服务两阶段支付调用）
 *
 * 一致性与并发约定：
 * - 所有修改类操作（recharge/deduct/refund）先 SELECT ... FOR UPDATE 锁钱包行，
 *   同一用户的钱包操作完全串行化（行锁持有到事务提交）。
 * - 扣款仍叠加条件更新（balance >= amount）作为防负余额的第二道保险（10.4）。
 * - refund 幂等：锁行后再查询 t_wallet_flow(type=3, order_no)，并发退款在锁后
 *   必能看到先到流水（10.6），杜绝重复入账。
 * - balance_after 一律用锁内读到的 balance 计算（加减 amount），
 *   回避事务快照读导致 balance_after 对账失真（10.4 依赖该字段）。
 */
@Service
public class WalletService {

    /** 流水类型：1-支付扣款 2-充值入账 3-补偿退款（需求文档 6.1.3） */
    public static final int FLOW_TYPE_DEDUCT = 1;
    public static final int FLOW_TYPE_RECHARGE = 2;
    public static final int FLOW_TYPE_REFUND = 3;

    private final WalletMapper walletMapper;
    private final WalletFlowMapper walletFlowMapper;

    public WalletService(WalletMapper walletMapper, WalletFlowMapper walletFlowMapper) {
        this.walletMapper = walletMapper;
        this.walletFlowMapper = walletFlowMapper;
    }

    /**
     * 8.6 查询余额。钱包由注册时创建；若异常缺失则按 0 处理。只读，无需锁。
     */
    public BigDecimal getBalance(Long userId) {
        Wallet wallet = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId));
        return wallet != null ? wallet.getBalance() : BigDecimal.ZERO;
    }

    /**
     * 8.7 钱包充值（模拟）：余额入账 + 写 t_wallet_flow(type=2)。返回充值后余额。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal recharge(Long userId, BigDecimal amount) {
        requirePositive(amount);
        Wallet wallet = walletMapper.selectForUpdate(userId);
        if (wallet == null) {
            throw new BusinessException(5000, "钱包不存在");
        }

        BigDecimal balanceAfter = wallet.getBalance().add(amount);
        walletMapper.addBalance(userId, amount);
        insertFlow(userId, wallet.getId(), null, FLOW_TYPE_RECHARGE, amount, balanceAfter,
                "模拟充值");
        return balanceAfter;
    }

    /**
     * 8.14 条件扣款（order 两阶段支付阶段一）：
     * 锁行 + 条件更新双保险，余额不足 -> 4004；成功写 type=1 流水并返回扣款后余额。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal deduct(Long userId, BigDecimal amount, String orderNo) {
        requirePositive(amount);
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw new BusinessException(5000, "缺少订单号");
        }
        Wallet wallet = walletMapper.selectForUpdate(userId);
        if (wallet == null) {
            throw new BusinessException(5000, "钱包不存在");
        }
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new BusinessException(4004, "钱包余额不足，请先充值");
        }

        int rows = walletMapper.deductByCondition(userId, amount);
        if (rows == 0) {
            // 条件更新兜底：理论在锁行后不会发生
            throw new BusinessException(4004, "钱包余额不足，请先充值");
        }

        BigDecimal balanceAfter = wallet.getBalance().subtract(amount);
        insertFlow(userId, wallet.getId(), orderNo, FLOW_TYPE_DEDUCT, amount, balanceAfter,
                "订单支付扣款");
        return balanceAfter;
    }

    /**
     * 8.14 补偿退款（两阶段支付阶段二失败时 order 调用）：
     * 幂等：锁行后查 type=3 + order_no 流水，已存在则直接返回当前余额（10.6）。
     * 成功写 type=3 流水并返回退款后余额。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal refund(Long userId, BigDecimal amount, String orderNo) {
        requirePositive(amount);
        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw new BusinessException(5000, "缺少订单号");
        }
        Wallet wallet = walletMapper.selectForUpdate(userId);
        if (wallet == null) {
            throw new BusinessException(5000, "钱包不存在");
        }

        // 幂等拦截：锁行后查询，并发退款在 lock 释放后必能看到先到的 type=3 流水
        Long count = walletFlowMapper.selectCount(new LambdaQueryWrapper<WalletFlow>()
                .eq(WalletFlow::getUserId, userId)
                .eq(WalletFlow::getType, FLOW_TYPE_REFUND)
                .eq(WalletFlow::getOrderNo, orderNo));
        if (count != null && count > 0) {
            return wallet.getBalance();
        }

        BigDecimal balanceAfter = wallet.getBalance().add(amount);
        walletMapper.addBalance(userId, amount);
        insertFlow(userId, wallet.getId(), orderNo, FLOW_TYPE_REFUND, amount, balanceAfter,
                "补偿退款");
        return balanceAfter;
    }

    private void insertFlow(Long userId, Long walletId, String orderNo, int type,
                            BigDecimal amount, BigDecimal balanceAfter, String remark) {
        WalletFlow flow = new WalletFlow();
        flow.setWalletId(walletId);
        flow.setUserId(userId);
        flow.setOrderNo(orderNo);
        flow.setType(type);
        flow.setAmount(amount);
        flow.setBalanceAfter(balanceAfter);
        flow.setRemark(remark);
        walletFlowMapper.insert(flow);
    }

    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(5000, "金额必须大于 0");
        }
    }
}