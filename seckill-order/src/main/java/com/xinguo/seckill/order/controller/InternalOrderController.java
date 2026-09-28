package com.xinguo.seckill.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.order.entity.SeckillLog;
import com.xinguo.seckill.order.entity.SeckillOrder;
import com.xinguo.seckill.order.mapper.SeckillLogMapper;
import com.xinguo.seckill.order.mapper.SeckillOrderMapper;
import com.xinguo.seckill.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单内部接口（需求文档 8.14）：仅供 scheduler / 调试调用，不对公网暴露。
 */
@RestController
@RequestMapping("/internal/order")
public class InternalOrderController {

    private final OrderService orderService;
    private final SeckillOrderMapper orderMapper;
    private final SeckillLogMapper logMapper;

    public InternalOrderController(OrderService orderService,
                                   SeckillOrderMapper orderMapper,
                                   SeckillLogMapper logMapper) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.logMapper = logMapper;
    }

    /** 8.14 超时订单列表（待支付且已过支付期限），供 scheduler 扫描兜底（3.4 双机制） */
    @GetMapping("/timeout-list")
    public Result<List<SeckillOrder>> timeoutList(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "50") long size) {
        List<SeckillOrder> rows = orderMapper.selectPage(new Page<>(page, size),
                        new LambdaQueryWrapper<SeckillOrder>()
                                .eq(SeckillOrder::getStatus, OrderService.ST_WAIT_PAY)
                                .lt(SeckillOrder::getPayDeadline, LocalDateTime.now())
                                .orderByAsc(SeckillOrder::getPayDeadline))
                .getRecords();
        return Result.success(rows);
    }

    /** 8.14 超时取消单个订单（status 置 2），供 scheduler 扫描兜底 */
    @PostMapping("/timeout-cancel")
    public Result<Void> timeoutCancel(@RequestBody Map<String, String> body) {
        orderService.cancel(body.get("orderNo"), null, OrderService.ST_TIMEOUT_CANCELLED);
        return Result.success();
    }

    /** T10 待补标记补偿：延迟消息发送失败且仍未过支付期限的待支付订单号列表（scheduler 扫描后重发） */
    @GetMapping("/timeout-send-fail")
    public Result<List<String>> timeoutSendFail() {
        return Result.success(orderService.queryTimeoutSendFailOrders());
    }

    /** T10 待补标记补偿：对指定订单重新发送延迟消息（4005 订单不存在 / 4006 非待支付 / 5000 发送失败） */
    @PostMapping("/timeout-resend")
    public Result<Void> timeoutResend(@RequestBody Map<String, String> body) {
        boolean ok = orderService.resendDelayed(body.get("orderNo"));
        if (!ok) {
            throw new BusinessException(5000, "延迟消息重发失败，请稍后重试");
        }
        return Result.success();
    }

    /**
     * 8.14 秒杀日志分页（按 orderNo 或 activityId 过滤，调试/运营用）。
     * 返回 {list: 当前页记录, total: 满足条件总数}，供前端正确分页（review P1-3）。
     */
    @GetMapping("/logs")
    public Result<Map<String, Object>> logs(@RequestParam(required = false) String orderNo,
                                            @RequestParam(required = false) Long activityId,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<SeckillLog> qw = new LambdaQueryWrapper<SeckillLog>()
                .orderByDesc(SeckillLog::getId);
        if (orderNo != null && !orderNo.isEmpty()) {
            qw.eq(SeckillLog::getOrderNo, orderNo);
        }
        if (activityId != null) {
            qw.eq(SeckillLog::getActivityId, activityId);
        }
        Page<SeckillLog> result = logMapper.selectPage(new Page<>(page, size), qw);
        return Result.success(Map.of(
                "list", result.getRecords(),
                "total", result.getTotal()));
    }
}