package com.xinguo.seckill.user.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.user.entity.Wallet;
import com.xinguo.seckill.user.entity.WalletFlow;
import com.xinguo.seckill.user.mapper.WalletFlowMapper;
import com.xinguo.seckill.user.mapper.WalletMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WalletService 外部行为测试（mock mapper）：
 * 1) 充值：余额累加 + 写 type=2 流水
 * 2) 扣款成功：锁行后条件扣款 + 写 type=1 流水，返回扣款后余额（锁内余额计算）
 * 3) 扣款余额不足：4004，不写流水
 * 4) 补偿退款：锁行后查流水决定幂等，写 type=3 流水
 * 5) 退款幂等：同 order_no 已有 type=3 流水 -> 不再入账
 * 6) 参数非法（金额<=0）：抛 BusinessException(5000)
 */
class WalletServiceTest {

    private WalletMapper walletMapper;
    private WalletFlowMapper walletFlowMapper;
    private WalletService walletService;

    /** 模拟 DB 中钱包当前余额，随写操作联动，selectForUpdate 反映最新值 */
    private AtomicReference<BigDecimal> currentBalance;

    @BeforeEach
    void setUp() {
        walletMapper = mock(WalletMapper.class);
        walletFlowMapper = mock(WalletFlowMapper.class);
        walletService = new WalletService(walletMapper, walletFlowMapper);
        currentBalance = new AtomicReference<>(BigDecimal.valueOf(100.00));

        // 锁行读取：返回带当前余额的钱包对象
        when(walletMapper.selectForUpdate(any(Long.class))).thenAnswer(invocation -> {
            Wallet w = new Wallet();
            w.setId(1L);
            w.setUserId(1L);
            w.setBalance(currentBalance.get());
            return w;
        });
        // 入账：余额联动
        when(walletMapper.addBalance(any(Long.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    BigDecimal amount = invocation.getArgument(1);
                    currentBalance.updateAndGet(b -> b.add(amount));
                    return 1;
                });
    }

    @Test
    void rechargeAddsBalanceAndWritesType2Flow() {
        when(walletFlowMapper.insert(any(WalletFlow.class))).thenReturn(1);

        BigDecimal balanceAfter = walletService.recharge(1L, BigDecimal.valueOf(50.00));

        assertEquals(0, BigDecimal.valueOf(150.00).compareTo(balanceAfter));
        verify(walletFlowMapper).insert(any(WalletFlow.class));

        ArgumentCaptor<WalletFlow> captor = ArgumentCaptor.forClass(WalletFlow.class);
        verify(walletFlowMapper).insert(captor.capture());
        WalletFlow flow = captor.getValue();
        assertEquals(WalletService.FLOW_TYPE_RECHARGE, flow.getType());
        assertEquals(0, BigDecimal.valueOf(50.00).compareTo(flow.getAmount()));
        assertEquals(0, BigDecimal.valueOf(150.00).compareTo(flow.getBalanceAfter()));
        assertEquals(1L, flow.getWalletId());
    }

    @Test
    void deductSuccessReturnsBalanceAndWritesType1Flow() {
        when(walletMapper.deductByCondition(any(Long.class), any(BigDecimal.class))).thenReturn(1);
        when(walletFlowMapper.insert(any(WalletFlow.class))).thenReturn(1);

        BigDecimal balanceAfter = walletService.deduct(1L, BigDecimal.valueOf(40.00), "SK20260925120000123");

        assertEquals(0, BigDecimal.valueOf(60.00).compareTo(balanceAfter),
                "扣款后余额应基于锁行读取值计算");
        ArgumentCaptor<WalletFlow> captor = ArgumentCaptor.forClass(WalletFlow.class);
        verify(walletFlowMapper).insert(captor.capture());
        WalletFlow flow = captor.getValue();
        assertEquals(WalletService.FLOW_TYPE_DEDUCT, flow.getType());
        assertEquals(0, BigDecimal.valueOf(40.00).compareTo(flow.getAmount()));
        assertEquals(0, BigDecimal.valueOf(60.00).compareTo(flow.getBalanceAfter()));
        assertEquals("SK20260925120000123", flow.getOrderNo());
    }

    @Test
    void deductInsufficientBalanceThrows4004AndNoFlow() {
        currentBalance.set(BigDecimal.valueOf(30.00));
        when(walletMapper.deductByCondition(any(Long.class), any(BigDecimal.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> walletService.deduct(1L, BigDecimal.valueOf(40.00), "SK20260925120000123"));

        assertEquals(4004, ex.getCode());
        verify(walletMapper, never()).deductByCondition(any(Long.class), any(BigDecimal.class));
        verify(walletFlowMapper, never()).insert(any(WalletFlow.class));
    }

    @Test
    void refundWritesType3Flow() {
        when(walletFlowMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(walletFlowMapper.insert(any(WalletFlow.class))).thenReturn(1);

        BigDecimal balanceAfter = walletService.refund(1L, BigDecimal.valueOf(30.00), "SK20260925120000123");

        assertEquals(0, BigDecimal.valueOf(130.00).compareTo(balanceAfter));
        ArgumentCaptor<WalletFlow> captor = ArgumentCaptor.forClass(WalletFlow.class);
        verify(walletFlowMapper).insert(captor.capture());
        WalletFlow flow = captor.getValue();
        assertEquals(WalletService.FLOW_TYPE_REFUND, flow.getType());
        assertEquals(0, BigDecimal.valueOf(30.00).compareTo(flow.getAmount()));
        assertEquals("SK20260925120000123", flow.getOrderNo());
    }

    @Test
    void refundExistingFlowIsIdempotent() {
        // 已有 type=3 流水 -> 直接返回当前余额，不 addBalance 不 insert
        when(walletFlowMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BigDecimal balanceAfter = walletService.refund(1L, BigDecimal.valueOf(30.00), "SK20260925120000123");

        assertEquals(0, BigDecimal.valueOf(100.00).compareTo(balanceAfter));
        verify(walletMapper, never()).addBalance(any(Long.class), any(BigDecimal.class));
        verify(walletFlowMapper, never()).insert(any(WalletFlow.class));
    }

    @Test
    void nonPositiveAmountRejectedWith5000() {
        assertThrows(BusinessException.class, () -> walletService.recharge(1L, BigDecimal.ZERO));
        assertThrows(BusinessException.class, () -> walletService.deduct(1L, BigDecimal.valueOf(-1), "SK1"));
        assertThrows(BusinessException.class, () -> walletService.refund(1L, null, "SK1"));
        verify(walletFlowMapper, never()).insert(any(WalletFlow.class));
        verify(walletMapper, never()).addBalance(any(Long.class), any(BigDecimal.class));
    }
}