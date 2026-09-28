package com.xinguo.seckill.user.controller;

import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.user.dto.WalletDTO.DeductRequest;
import com.xinguo.seckill.user.dto.WalletDTO.RefundRequest;
import com.xinguo.seckill.user.service.WalletService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 钱包内部接口（需求文档 8.14）：仅供 order 服务两阶段支付调用，不对公网暴露。
 * - POST /internal/wallet/deduct 条件扣款（阶段一）
 * - POST /internal/wallet/refund  补偿退款（阶段二失败兜底，幂等）
 */
@RestController
@RequestMapping("/internal/wallet")
public class InternalWalletController {

    private final WalletService walletService;

    public InternalWalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /** 8.14 条件扣款，返回扣款后余额（调用方据此更新/对账） */
    @PostMapping("/deduct")
    public Result<BigDecimal> deduct(@RequestBody DeductRequest req) {
        BigDecimal balanceAfter = walletService.deduct(req.userId, req.amount, req.orderNo);
        return Result.success(balanceAfter);
    }

    /** 8.14 补偿退款（幂等），返回退款后余额 */
    @PostMapping("/refund")
    public Result<BigDecimal> refund(@RequestBody RefundRequest req) {
        BigDecimal balanceAfter = walletService.refund(req.userId, req.amount, req.orderNo);
        return Result.success(balanceAfter);
    }
}