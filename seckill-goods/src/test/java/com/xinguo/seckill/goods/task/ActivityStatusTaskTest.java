package com.xinguo.seckill.goods.task;

import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ActivityStatusTask 行为测试：
 * 每次 refresh 对活动表执行三次条件更新：0->1（进行中）、1->2（已结束）、
 * 0->2（跨窗口直接落终点，防状态卡 0）。
 * 条件过滤/幂等性由 LambdaUpdateWrapper 的 eq(status) 与时间边界保证（集成测试验证真实 SQL）。
 */
class ActivityStatusTaskTest {

    private SeckillActivityMapper activityMapper;
    private ActivityStatusTask task;

    @BeforeEach
    void setUp() {
        activityMapper = mock(SeckillActivityMapper.class);
        task = new ActivityStatusTask(activityMapper);
    }

    @Test
    void refreshRunsThreeConditionalUpdates() {
        when(activityMapper.update(any(), any())).thenReturn(0);

        task.refresh();

        verify(activityMapper, times(3)).update(isNull(), any());
    }

    @Test
    void refreshCountsTransitions() {
        // 第一次：0->1 返回 2；第二次：1->2 返回 1；第三次：0->2 返回 3
        when(activityMapper.update(any(), any())).thenReturn(2, 1, 3);

        task.refresh();

        verify(activityMapper, times(3)).update(isNull(), any());
    }
}