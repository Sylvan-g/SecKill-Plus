package com.xinguo.seckill.user.dto;

import java.math.BigDecimal;

/**
 * 注册/登录相关响应 DTO
 */
public class AuthDTO {

    /** 注册响应：{ userId, token }（需求文档 8.12） */
    public static class RegisterResult {
        public Long userId;
        public String token;

        public RegisterResult() {
        }

        public RegisterResult(Long userId, String token) {
            this.userId = userId;
            this.token = token;
        }
    }

    /** 登录响应：{ token, userId, nickname, balance }（需求文档 8.13） */
    public static class LoginResult {
        public String token;
        public Long userId;
        public String nickname;
        public BigDecimal balance;

        public LoginResult() {
        }

        public LoginResult(String token, Long userId, String nickname, BigDecimal balance) {
            this.token = token;
            this.userId = userId;
            this.nickname = nickname;
            this.balance = balance;
        }
    }

    /** 注册请求体（需求文档 8.12） */
    public static class RegisterRequest {
        public String username;
        public String password;
        public String phone;
        public String nickname;
    }

    /** 登录请求体（需求文档 8.13） */
    public static class LoginRequest {
        public String username;
        public String password;
    }
}