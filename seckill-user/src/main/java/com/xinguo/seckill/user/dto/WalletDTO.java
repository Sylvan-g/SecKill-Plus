package com.xinguo.seckill.user.dto;

import java.math.BigDecimal;

/**
 * 钱包相关 DTO：对外接口（8.6 余额查询 / 8.7 充值）与内部接口（8.14 扣款/退款）
 */
public class WalletDTO {

    /** 8.6 余额查询响应 { userId, balance } */
    public static class BalanceResult {
        public Long userId;
        public BigDecimal balance;

        public BalanceResult() {
        }

        public BalanceResult(Long userId, BigDecimal balance) {
            this.userId = userId;
            this.balance = balance;
        }
    }

    /** 8.7 充值请求体 { amount } */
    public static class RechargeRequest {
        public BigDecimal amount;
    }

    /** 8.14 内部扣款请求体 { userId, amount, orderNo } */
    public static class DeductRequest {
        public Long userId;
        public BigDecimal amount;
        public String orderNo;
    }

    /** 8.14 内部补偿退款请求体 { userId, amount, orderNo } */
    public static class RefundRequest {
        public Long userId;
        public BigDecimal amount;
        public String orderNo;
    }
}