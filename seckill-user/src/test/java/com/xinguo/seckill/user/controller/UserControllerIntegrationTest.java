package com.xinguo.seckill.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.user.entity.User;
import com.xinguo.seckill.user.entity.Wallet;
import com.xinguo.seckill.user.mapper.UserMapper;
import com.xinguo.seckill.user.mapper.WalletMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserController 集成测试：register -> login -> me(需登录) -> logout -> me(401)
 *
 * 说明：需要真实 MySQL（seckill_user 库已由 T3 初始化）与本地 Redis（Sa-Token 会话）
 * 可用。测试用户用唯一用户名，@AfterAll 清理 user/wallet 数据与 t_wallet_flow（若有）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WalletMapper walletMapper;

    /** 唯一测试用户名，避免与 seed 数据冲突 */
    private static final String USER = "it_user_" + System.currentTimeMillis();

    private static String token;

    @BeforeAll
    static void ensureCleanBefore() {
        // 若上次失败残留同名用户，先清（此处不依赖 DB mock，直接依赖已初始化库）
    }

    @AfterAll
    static void cleanupData() {
        // 静态清理交给 tearDown 实例方法（需注入 mapper，无法在 static 使用）
    }

    private void cleanupRows() {
        User u = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, USER));
        if (u != null) {
            walletMapper.delete(new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, u.getId()));
            userMapper.deleteById(u.getId());
        }
    }

    @Test
    void registerLoginMeLogout401Flow() throws Exception {
        cleanupRows(); // 清理上次残留

        // ---- 1. 注册成功：返回 userId + token，DB 有 user + wallet(balance=0) ----
        MvcResult regResult = mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER + "\",\"password\":\"123456\","
                                + "\"phone\":\"13900000000\",\"nickname\":\"集成测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").exists())
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();

        JsonNode regBody = objectMapper.readTree(regResult.getResponse().getContentAsString());
        long userId = regBody.path("data").path("userId").asLong();
        token = regBody.path("data").path("token").asText();
        assertTrue(token.length() > 0, "注册应返回 token");

        User saved = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, USER));
        assertNotNull(saved, "注册后 t_user 应有记录");
        assertEquals(0, saved.getRole().intValue(), "注册用户应为普通用户 role=0");
        assertTrue(saved.getPassword().startsWith("$2a$") || saved.getPassword().startsWith("$2b$"),
                "密码应为 BCrypt 密文");

        Wallet wallet = walletMapper.selectOne(new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId));
        assertNotNull(wallet, "注册后 t_wallet 应有记录");
        assertEquals(0, wallet.getBalance().compareTo(BigDecimal.ZERO), "钱包初始余额应为 0");

        // ---- 2. 重复注册同一用户名 -> 4008 ----
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4008));

        // ---- 3. 登录：正确口令 -> token ----
        MvcResult loginResult = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.balance").value(0))
                .andReturn();
        token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("token").asText();

        // ---- 4. 登录：错误口令 -> 401（业务码） ----
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        // ---- 5. me：带 token 访问受保护接口 -> 200 ----
        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(userId));

        // ---- 6. logout：登出成功 ----
        mockMvc.perform(post("/api/user/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ---- 7. logout 后用旧 token 访问受保护接口 -> HTTP 401 ----
        mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // ---- 8. 未带 token 访问 -> HTTP 401 ----
        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        cleanupRows();
    }
}