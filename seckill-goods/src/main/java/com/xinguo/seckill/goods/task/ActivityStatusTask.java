package com.xinguo.seckill.goods.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 活动状态维护任务（需求文档 3.6 / 4.2）：
 * - status=0 未开始 且已到 start_time -> 1 进行中（start <= now < end）
 * - status=1 进行中 且已过 end_time   -> 2 已结束
 * - status=0 未开始 且已过 end_time   -> 2 已结束（跨窗口直接落终点，防状态卡 0）
 * - status=3 已下架 一律不动（admin 手动维护）
 *
 * 每 30 秒扫描一次；状态位仅用于列表/运营展示，下单准入门禁以实时时间判断为准（R1）。
 */
@Component
public class ActivityStatusTask {

    private static final Logger log = LoggerFactory.getLogger(ActivityStatusTask.class);

    private final SeckillActivityMapper activityMapper;

    public ActivityStatusTask(SeckillActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    public void refresh() {
        LocalDateTime now = LocalDateTime.now();

        // 0 未开始 -> 1 进行中
        int started = activityMapper.update(null, new LambdaUpdateWrapper<SeckillActivity>()
                .eq(SeckillActivity::getStatus, 0)
                .le(SeckillActivity::getStartTime, now)
                .gt(SeckillActivity::getEndTime, now)
                .set(SeckillActivity::getStatus, 1));

        // 1 进行中 -> 2 已结束
        int ended = activityMapper.update(null, new LambdaUpdateWrapper<SeckillActivity>()
                .eq(SeckillActivity::getStatus, 1)
                .le(SeckillActivity::getEndTime, now)
                .set(SeckillActivity::getStatus, 2));

        // 0 未开始 且 end_time 已过（从未进入进行中窗口）-> 2 已结束
        int skippedToEnd = activityMapper.update(null, new LambdaUpdateWrapper<SeckillActivity>()
                .eq(SeckillActivity::getStatus, 0)
                .le(SeckillActivity::getEndTime, now)
                .set(SeckillActivity::getStatus, 2));

        if (started > 0 || ended > 0 || skippedToEnd > 0) {
            log.info("[activity.status] started={}, ended={}, skippedToEnd={}", started, ended, skippedToEnd);
        }
    }
}