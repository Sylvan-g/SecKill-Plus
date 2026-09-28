package com.xinguo.seckill.order.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.order.dto.OrderDTO.OrderStatusResult;
import com.xinguo.seckill.order.dto.OrderDTO.PayResultDTO;
import com.xinguo.seckill.order.entity.SeckillOrder;
import com.xinguo.seckill.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单对外接口（需求文档 8.4 状态轮询 / 8.5 我的订单 / 8.8 支付 / 8.9 主动取消），需登录（R7 ①）。
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 8.4 查询订单状态（前端轮询）：Lua 已扣但未落库时返回 QUEUED（Redis 有标记且 DB 无行） */
    @GetMapping("/status/{orderNo}")
    public Result<OrderStatusResult> status(@PathVariable String orderNo) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.success(orderService.queryStatus(orderNo, userId));
    }

    /** 8.5 我的订单列表（status 可空，分页） */
    @GetMapping("/list")
    public Result<List<SeckillOrder>> list(@RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.success(orderService.listMy(userId, status, page, size));
    }

    /** 8.8 订单支付（补偿式两阶段：钱包扣款 -> 本地置已支付 -> 失败补偿退款） */
    @PostMapping("/pay/{orderNo}")
    public Result<PayResultDTO> pay(@PathVariable String orderNo) {
        long userId = StpUtil.getLoginIdAsLong();
        OrderService.PayResult r = orderService.pay(orderNo, userId);
        return Result.success(new PayResultDTO(r.orderNo, r.status, r.paidAmount, r.balanceAfter));
    }

    /** 8.9 用户主动取消订单（仅待支付，状态置 3） */
    @PostMapping("/cancel/{orderNo}")
    public Result<Void> cancel(@PathVariable String orderNo) {
        long userId = StpUtil.getLoginIdAsLong();
        orderService.cancel(orderNo, userId, OrderService.ST_USER_CANCELLED);
        return Result.success();
    }
}