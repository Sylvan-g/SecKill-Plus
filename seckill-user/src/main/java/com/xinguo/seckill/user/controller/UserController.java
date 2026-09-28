package com.xinguo.seckill.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.user.dto.AuthDTO.LoginRequest;
import com.xinguo.seckill.user.dto.AuthDTO.LoginResult;
import com.xinguo.seckill.user.dto.AuthDTO.RegisterRequest;
import com.xinguo.seckill.user.dto.AuthDTO.RegisterResult;
import com.xinguo.seckill.user.entity.User;
import com.xinguo.seckill.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户接口（需求文档 8.12 注册 / 8.13 登录登出；额外提供 me 用于校验受保护接口）
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 8.12 注册（游客可用）；注册成功后自动登录并返回 token */
    @PostMapping("/register")
    public Result<RegisterResult> register(@RequestBody RegisterRequest req) {
        Long userId = userService.register(req);
        StpUtil.login(userId);
        return Result.success(new RegisterResult(userId, StpUtil.getTokenValue()));
    }

    /** 8.13 登录（游客可用）；登录成功签发 token */
    @PostMapping("/login")
    public Result<LoginResult> login(@RequestBody LoginRequest req) {
        LoginResult result = userService.login(req.username, req.password);
        StpUtil.login(result.userId);
        result.token = StpUtil.getTokenValue();
        return Result.success(result);
    }

    /** 8.13 登出（需登录） */
    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.success();
    }

    /** 当前登录用户信息（需登录；供 401 验收与前端展示） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        User user = userService.me();
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", user.getRole());
        return Result.success(data);
    }
}