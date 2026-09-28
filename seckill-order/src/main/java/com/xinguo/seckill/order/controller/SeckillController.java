package com.xinguo.seckill.order.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.order.dto.SeckillDTO.SeckillResult;
import com.xinguo.seckill.order.service.SeckillOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀下单接口（需求文档 8.3）：
 * POST /api/seckill/do/{activityId}，需登录（R7 ①），401 由 Sa-Token 未登录异常处理兜底。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillOrderService seckillOrderService;

    public SeckillController(SeckillOrderService seckillOrderService) {
        this.seckillOrderService = seckillOrderService;
    }

    /** 8.3 秒杀下单；返回 QUEUED（排队中） */
    @PostMapping("/do/{activityId}")
    public Result<SeckillResult> doSeckill(@PathVariable Long activityId) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.success(seckillOrderService.seckill(activityId, userId));
    }
}