package com.xinguo.seckill.goods.service;

import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockService 外部行为测试（mock mapper）：
 * 1) deduct 首次成功
 * 2) deduct version 冲突重试后成功（最多 3 次）
 * 3) deduct 重试耗尽 -> 4001
 * 4) deduct count <= 0 -> 5000
 * 5) rollback 成功；不存在 -> 5000
 */
class StockServiceTest {

    private GoodsStockMapper goodsStockMapper;
    private StockService stockService;

    @BeforeEach
    void setUp() {
        goodsStockMapper = mock(GoodsStockMapper.class);
        stockService = new StockService(goodsStockMapper);
    }

    private GoodsStock stock(Long goodsId, int total, int version) {
        GoodsStock s = new GoodsStock();
        s.setGoodsId(goodsId);
        s.setTotalStock(total);
        s.setVersion(version);
        return s;
    }

    @Test
    void deductSuccessFirstTry() {
        when(goodsStockMapper.selectByIdForUpdate(2001L)).thenReturn(stock(2001L, 100, 0));
        when(goodsStockMapper.deductByVersion(anyLong(), anyInt(), anyInt())).thenReturn(1);

        stockService.deduct(2001L, 1);

        ArgumentCaptor<Integer> versionCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(goodsStockMapper, times(1)).deductByVersion(anyLong(), anyInt(), versionCaptor.capture());
        assertEquals(0, versionCaptor.getValue(), "应使用查询到的 version=0");
    }

    @Test
    void deductRetriesOnVersionConflictUntilSuccess() {
        // 每次重试 selectByIdForUpdate 返回最新 version（模拟 FOR UPDATE 读到已提交值）
        when(goodsStockMapper.selectByIdForUpdate(2001L))
                .thenReturn(stock(2001L, 100, 0))
                .thenReturn(stock(2001L, 100, 1))   // 第 1 次冲突后重读：version=1
                .thenReturn(stock(2001L, 100, 1));
        // 前两次 version 冲突（返回 0），第三次成功
        when(goodsStockMapper.deductByVersion(anyLong(), anyInt(), anyInt()))
                .thenReturn(0)
                .thenReturn(0)
                .thenReturn(1);

        stockService.deduct(2001L, 1);

        verify(goodsStockMapper, times(3)).deductByVersion(anyLong(), anyInt(), anyInt());
    }

    @Test
    void deductThrows4001WhenRetriesExhausted() {
        // 重试 3 次都读到同一旧 version（如库存不足场景），最终失败
        when(goodsStockMapper.selectByIdForUpdate(2001L)).thenReturn(stock(2001L, 100, 0));
        when(goodsStockMapper.deductByVersion(anyLong(), anyInt(), anyInt())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> stockService.deduct(2001L, 1));

        assertEquals(4001, ex.getCode());
        verify(goodsStockMapper, times(3)).deductByVersion(anyLong(), anyInt(), anyInt());
        verify(goodsStockMapper, times(3)).selectByIdForUpdate(2001L);
    }

    @Test
    void deductRejectsNonPositiveCount() {
        BusinessException ex = assertThrows(BusinessException.class, () -> stockService.deduct(2001L, 0));
        assertEquals(5000, ex.getCode());
        verify(goodsStockMapper, never()).deductByVersion(anyLong(), anyInt(), anyInt());
    }

    @Test
    void rollbackSuccess() {
        when(goodsStockMapper.rollbackStock(anyLong(), anyInt())).thenReturn(1);
        stockService.rollback(2001L, 1);
        verify(goodsStockMapper, times(1)).rollbackStock(2001L, 1);
    }

    @Test
    void rollbackMissingRowThrows5000() {
        when(goodsStockMapper.rollbackStock(anyLong(), anyInt())).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class, () -> stockService.rollback(2001L, 1));
        assertEquals(5000, ex.getCode());
    }
}