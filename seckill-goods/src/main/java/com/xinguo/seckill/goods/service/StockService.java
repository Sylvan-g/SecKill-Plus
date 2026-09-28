package com.xinguo.seckill.goods.service;

import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 真实库存服务（DB 兜底层，需求文档 8.14 internal stock / 10.3）：
 * - deduct：乐观锁扣减（version 冲突重试，最多 3 次），供 order 下单落库时调用
 * - rollback：库存回补（D8，取消订单时），幂等语义由 order 侧订单状态条件更新保证
 *
 * 注意：Redis 的实时库存扣减在 order 侧 Lua（10.1），本服务只负责 DB 层最终一致。
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /** 乐观锁冲突重试上限（需求文档 10.3） */
    private static final int MAX_RETRY = 3;

    private final GoodsStockMapper goodsStockMapper;

    public StockService(GoodsStockMapper goodsStockMapper) {
        this.goodsStockMapper = goodsStockMapper;
    }

    /**
     * 8.14 乐观锁扣减真实库存：version 冲突则重读重试，最多 3 次。
     * 查询 version（当前读 FOR UPDATE）-> 条件更新 -> 影响行数 0 则重试；仍失败抛 4001。
     *
     * 关键：绝不能用普通 selectById —— REPEATABLE READ 下事务内快照读 version 恒定，
     * 重试永远失败；FOR UPDATE 读到已提交的最新 version，冲突后才能收敛到成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deduct(Long goodsId, int count) {
        validate(count);
        for (int i = 1; i <= MAX_RETRY; i++) {
            GoodsStock stock = goodsStockMapper.selectByIdForUpdate(goodsId);
            if (stock == null) {
                throw new BusinessException(5000, "库存记录不存在");
            }
            int rows = goodsStockMapper.deductByVersion(goodsId, count, stock.getVersion());
            if (rows == 1) {
                return;
            }
            log.warn("[stock.deduct] optimistic-lock conflict goodsId={} attempt={}", goodsId, i);
        }
        // 依然被并发改版 / 库存不足 -> 由调用方（order）按失败处理（订单置 3，10.3）
        throw new BusinessException(4001, "库存不足或并发冲突，请稍后重试");
    }

    /**
     * 8.14 库存回补（D8，取消订单时）：无条件累加。幂等由 order 侧订单状态条件更新保证。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long goodsId, int count) {
        validate(count);
        int rows = goodsStockMapper.rollbackStock(goodsId, count);
        if (rows == 0) {
            throw new BusinessException(5000, "库存记录不存在");
        }
    }

    private void validate(int count) {
        if (count <= 0) {
            throw new BusinessException(5000, "扣减数量必须大于 0");
        }
    }
}