package com.xinguo.seckill.goods.controller;

import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.goods.dto.StockDTO.DeductRequest;
import com.xinguo.seckill.goods.dto.StockDTO.RollbackRequest;
import com.xinguo.seckill.goods.service.StockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存内部接口（需求文档 8.14）：仅供 order 服务调用，不对公网暴露。
 */
@RestController
@RequestMapping("/internal/stock")
public class InternalStockController {

    private final StockService stockService;

    public InternalStockController(StockService stockService) {
        this.stockService = stockService;
    }

    /** 8.14 乐观锁扣真实库存 {goodsId, count}，失败返回 4001（order 侧重试/置取消） */
    @PostMapping("/deduct")
    public Result<Void> deduct(@RequestBody DeductRequest req) {
        stockService.deduct(req.goodsId, req.count);
        return Result.success();
    }

    /** 8.14 库存回补 {goodsId, count}（D8，取消订单时） */
    @PostMapping("/rollback")
    public Result<Void> rollback(@RequestBody RollbackRequest req) {
        stockService.rollback(req.goodsId, req.count);
        return Result.success();
    }
}