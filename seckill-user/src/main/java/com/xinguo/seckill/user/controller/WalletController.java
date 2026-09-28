package com.xinguo.seckill.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.user.dto.WalletDTO.BalanceResult;
import com.xinguo.seckill.user.dto.WalletDTO.RechargeRequest;
import com.xinguo.seckill.user.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 钱包对外接口（需求文档 8.6 余额查询 / 8.7 模拟充值），均需登录。
 */
@RestController
@RequestMapping("/api/user/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /** 8.6 钱包余额查询：{ userId, balance } */
    @GetMapping("/balance")
    public Result<BalanceResult> balance() {
        Long userId = StpUtil.getLoginIdAsLong();
        BigDecimal balance = walletService.getBalance(userId);
        return Result.success(new BalanceResult(userId, balance));
    }

    /** 8.7 钱包充值（模拟，演示用）：入账 + 流水，返回充值后余额 */
    @PostMapping("/recharge")
    public Result<BalanceResult> recharge(@RequestBody RechargeRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();
        BigDecimal balance = walletService.recharge(userId, req.amount);
        return Result.success(new BalanceResult(userId, balance));
    }
}