package com.xinguo.seckill.goods.controller;

import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityDetail;
import com.xinguo.seckill.goods.dto.ActivityDTO.CreateActivityRequest;
import com.xinguo.seckill.goods.service.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动内部接口（需求文档 8.14）：仅供 order / scheduler(admin) 服务调用，不对公网暴露。
 */
@RestController
@RequestMapping("/internal/activity")
public class InternalActivityController {

    private final ActivityService activityService;

    public InternalActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    /** 8.14 活动详情（order 下单时校验时间/回填缓存） */
    @GetMapping("/{id}")
    public Result<ActivityDetail> get(@PathVariable Long id) {
        return Result.success(activityService.detail(id));
    }

    /** 8.14 创建活动（scheduler(admin) 转发）：落库 + 初始化真实库存 + 立即预热 */
    @PostMapping("/create")
    public Result<Long> create(@RequestBody CreateActivityRequest req) {
        return Result.success(activityService.create(req));
    }
}