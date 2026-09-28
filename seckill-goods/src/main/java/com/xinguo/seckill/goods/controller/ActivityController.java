package com.xinguo.seckill.goods.controller;

import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityDetail;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityListResult;
import com.xinguo.seckill.goods.service.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动对外接口（需求文档 8.1 / 8.2），游客可浏览，无需登录。
 * 公网经 gateway 路由到本服务（/api/goods/** -> 8200）。
 */
@RestController
@RequestMapping("/api/goods/activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    /** 8.1 获取活动列表（闪购频道页）；status 0未开始/1进行中/2已结束，可空表示全部 */
    @GetMapping("/list")
    public Result<ActivityListResult> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "10") long size) {
        return Result.success(activityService.list(status, page, size));
    }

    /** 8.2 获取活动详情；缓存 -> DB -> 回填，stock 为 Redis 实时剩余库存 */
    @GetMapping("/{activityId}")
    public Result<ActivityDetail> detail(@PathVariable Long activityId) {
        return Result.success(activityService.detail(activityId));
    }
}