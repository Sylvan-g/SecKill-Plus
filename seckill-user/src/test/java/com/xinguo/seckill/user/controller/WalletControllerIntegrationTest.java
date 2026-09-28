package com.xinguo.seckill.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.user.entity.User;
import com.xinguo.seckill.user.entity.Wallet;
import com.xinguo.seckill.user.entity.WalletFlow;
import com.xinguo.seckill.user.mapper.UserMapper;
import com.xinguo.seckill.user.mapper.WalletFlowMapper;
import com.xinguo.seckill.user.mapper.WalletMapper;
import com.xinguo.seckill.user.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WalletController + InternalWalletController 集成测试（真实 MySQL/Redis）：
 * register -> recharge(100) -> balance(需登录) -> internal/deduct(40) -> internal/deduct(999) 4004
 * -> internal/refund(40) 幂等两次 -> 未登录访问钱包接口 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletFlowMapper walletFlowMapper;

    @Autowired
    private WalletService walletService;

    private static final String USER = "it_wallet_" + System.currentTimeMillis();
    private String token;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        cleanupRows();
        // 注册用户并自动登录获取 token
        MvcResult reg = mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER + "\",\"password\":\"123456\","
                                + "\"phone\":\"13800000000\",\"nickname\":\"钱包测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode body = objectMapper.readTree(reg.getResponse().getContentAsString());
        userId = body.path("data").path("userId").asLong();
        token = body.path("data").path("token").asText();
    }

    @Test
    void walletRechargeBalanceDeductRefundFlow() throws Exception {
        // ---- 1. 初始余额为 0 ----
        mockMvc.perform(get("/api/user/wallet/balance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.balance").value(0));

        // ---- 2. 充值 100 -> 余额 100，写 type=2 流水 ----
        mockMvc.perform(post("/api/user/wallet/recharge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.balance").value(100.00));

        // ---- 3. 未登录访问余额接口 -> HTTP 401 ----
        mockMvc.perform(get("/api/user/wallet/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // ---- 4. 内部扣款 40（order 两阶段阶段一）-> 成功，余额 60 ----
        mockMvc.perform(post("/internal/wallet/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + userId + ",\"amount\":40.00,"
                                + "\"orderNo\":\"SK_WALLET_TEST_ORDER_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(60.00));

        // ---- 5. 内部扣款 999 余额不足 -> 4004 ----
        mockMvc.perform(post("/internal/wallet/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + userId + ",\"amount\":999.00,"
                                + "\"orderNo\":\"SK_WALLET_TEST_ORDER_2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4004));

        // ---- 6. 补偿退款 40（阶段二失败兜底）-> 余额回到 100 ----
        mockMvc.perform(post("/internal/wallet/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + userId + ",\"amount\":40.00,"
                                + "\"orderNo\":\"SK_WALLET_TEST_ORDER_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(100.00));

        // ---- 7. 同 order_no 重复退款 -> 幂等，余额仍 100 不重复入账 ----
        mockMvc.perform(post("/internal/wallet/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + userId + ",\"amount\":40.00,"
                                + "\"orderNo\":\"SK_WALLET_TEST_ORDER_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(100.00));

        // ---- 8. DB 校验：余额 100、流水条数 type=1 一条 / type=2 一条 / type=3 一条 ----
        Wallet wallet = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId));
        assertNotNull(wallet, "钱包应存在");
        assertEquals(0, BigDecimal.valueOf(100.00).compareTo(wallet.getBalance()), "最终余额应为 100");

        List<WalletFlow> flows = walletFlowMapper.selectList(
                new LambdaQueryWrapper<WalletFlow>().eq(WalletFlow::getUserId, userId));
        assertEquals(1, countType(flows, WalletService.FLOW_TYPE_DEDUCT), "扣款流水应恰一条");
        assertEquals(1, countType(flows, WalletService.FLOW_TYPE_RECHARGE), "充值流水应恰一条");
        assertEquals(1, countType(flows, WalletService.FLOW_TYPE_REFUND), "补偿退款流水应恰一条（幂等）");

        cleanupRows();
    }

    /**
     * 并发退回测试（真实 MySQL）：对同一 order_no、同一钱包发起并发 refund，
     * 断言只入账一次（锁行串行化保证幂等，10.6）。
     */
    @Test
    void concurrentRefundIsIdempotent() throws Exception {
        // 先充值 100
        mockMvc.perform(post("/api/user/wallet/recharge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String orderNo = "SK_CONCURRENT_REFUND_1";
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BigDecimal>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return walletService.refund(userId, BigDecimal.valueOf(100.00), orderNo);
            }));
        }
        ready.await();
        go.countDown();
        List<BigDecimal> results = new ArrayList<>();
        for (Future<BigDecimal> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 只有第一次真正入账：其余应命中幂等直接返回当前余额
        for (BigDecimal r : results) {
            assertEquals(0, BigDecimal.valueOf(200.00).compareTo(r),
                    "并发退款结果应最终收敛到 200.00（仅入账一次）");
        }
        List<WalletFlow> flows = walletFlowMapper.selectList(
                new LambdaQueryWrapper<WalletFlow>()
                        .eq(WalletFlow::getUserId, userId)
                        .eq(WalletFlow::getOrderNo, orderNo));
        assertEquals(1, flows.size(), "并发退款应只有一条 type=3 流水");
        cleanupRows();
    }

    /**
     * 并发扣款测试（真实 MySQL）：4 线程各扣 20（余额 100），
     * 断言余额不为负且流水条数准确（10.4 条件更新防负余额）。
     */
    @Test
    void concurrentDeductNeverNegative() throws Exception {
        // 先充值 100
        mockMvc.perform(post("/api/user/wallet/recharge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final String orderNo = "SK_CONCURRENT_DEDUCT_" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    walletService.deduct(userId, BigDecimal.valueOf(20.00), orderNo);
                    return 1L; // 扣款成功
                } catch (BusinessException e) {
                    return 0L; // 余额不足
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Long> resultCodes = new ArrayList<>();
        for (Future<Long> f : futures) {
            resultCodes.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        int success = (int) resultCodes.stream().filter(r -> r == 1L).count();
        // 余额 100 / 20 = 恰好 5 次成功，8 并发里成功数 <= 5 且余额不为负
        assertTrue(success <= 5, "并发扣款成功次数不应超过余额允许量");
        List<WalletFlow> flows = walletFlowMapper.selectList(
                new LambdaQueryWrapper<WalletFlow>()
                        .eq(WalletFlow::getUserId, userId)
                        .eq(WalletFlow::getType, WalletService.FLOW_TYPE_DEDUCT));
        assertEquals(success, flows.size(), "扣款流水条数应等于成功次数");

        Wallet wallet = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, userId));
        BigDecimal expect = BigDecimal.valueOf(100.00)
                .subtract(BigDecimal.valueOf(20.00).multiply(BigDecimal.valueOf(success)));
        assertEquals(0, expect.compareTo(wallet.getBalance()), "余额 = 100 - 20*成功次数，永不为负");
        cleanupRows();
    }

    private long countType(List<WalletFlow> flows, int type) {
        return flows.stream().filter(f -> f.getType() != null && f.getType() == type).count();
    }

    private void cleanupRows() {
        User u = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, USER));
        if (u != null) {
            if (walletFlowMapper != null) {
                walletFlowMapper.delete(new LambdaQueryWrapper<WalletFlow>().eq(WalletFlow::getUserId, u.getId()));
            }
            if (walletMapper != null) {
                walletMapper.delete(new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, u.getId()));
            }
            userMapper.deleteById(u.getId());
        }
    }
}