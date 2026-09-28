package com.xinguo.seckill.scheduler.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinguo.seckill.common.response.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * admin 管理接口（需求文档 8.10 / 8.11，公网经 gateway /api/admin/** 转发到本服务）。
 * 本层只做编排转发，鉴权与限流由 gateway（T13/T14）承担。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 8.10 管理员创建活动（转发 goods 内部接口建活动 + 预热库存） */
    @PostMapping("/activity")
    public Result<Long> createActivity(@RequestBody JsonNode body) {
        return Result.success(adminService.createActivity(body));
    }

    /** 8.11 秒杀日志查询（转发 order 内部接口，activityId 可选；size 上限 100 防全量分页滥用） */
    @GetMapping("/log")
    public Result<JsonNode> log(@RequestParam(required = false) Long activityId,
                                @RequestParam(defaultValue = "1") long page,
                                @RequestParam(defaultValue = "50") long size) {
        long safeSize = Math.max(1, Math.min(size, 100));
        return Result.success(adminService.listLogs(activityId, Math.max(1, page), safeSize));
    }
}