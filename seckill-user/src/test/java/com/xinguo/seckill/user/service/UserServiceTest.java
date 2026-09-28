package com.xinguo.seckill.user.service;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.user.dto.AuthDTO.RegisterRequest;
import com.xinguo.seckill.user.entity.User;
import com.xinguo.seckill.user.entity.Wallet;
import com.xinguo.seckill.user.mapper.UserMapper;
import com.xinguo.seckill.user.mapper.WalletMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService 外部行为测试（mock mapper）：
 * 1) 注册：密码 BCrypt 可逆 + 自动建钱包（初始余额 0）
 * 2) 注册：username 已存在 -> BusinessException(4008)，且不建钱包
 * 3) 登录：口令错误 -> BusinessException(401)
 */
class UserServiceTest {

    private UserMapper userMapper;
    private WalletMapper walletMapper;
    private UserService userService;

    @BeforeAll
    static void initSaToken() {
        // 离线环境初始化 Sa-Token（默认内存 Dao），避免依赖 Spring 容器
        SaManager.setConfig(new SaTokenConfig());
    }

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        walletMapper = mock(WalletMapper.class);
        userService = new UserService(userMapper, walletMapper);
    }

    private RegisterRequest req(String username, String password) {
        RegisterRequest r = new RegisterRequest();
        r.username = username;
        r.password = password;
        r.phone = "13800000000";
        r.nickname = "阿离";
        return r;
    }

    @Test
    void registerEncodesPasswordWithBcryptAndCreatesWallet() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(10001L);   // 模拟 DB 回填自增 id
            return 1;
        });
        when(walletMapper.insert(any(Wallet.class))).thenReturn(1);

        Long userId = userService.register(req("alice", "123456"));

        // 响应：返回新用户 id
        assertEquals(10001L, userId);

        // 密码加密：保存到库的必须是 BCrypt 密文，明文能校验通过
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertTrue(encoder.matches("123456", userCaptor.getValue().getPassword()),
                "库中密码必须是 BCrypt 密文且明文 123456 可校验");

        // 自动建钱包，初始余额 0
        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletMapper).insert(walletCaptor.capture());
        assertEquals(10001L, walletCaptor.getValue().getUserId());
        assertEquals(BigDecimal.ZERO, walletCaptor.getValue().getBalance());
    }

    @Test
    void registerDuplicateUsernameThrows4008AndNoWallet() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(req("duplicated", "123456")));

        assertEquals(4008, ex.getCode());
        verify(userMapper, never()).insert(any(User.class));
        verify(walletMapper, never()).insert(any(Wallet.class));
    }

    @Test
    void loginWithWrongPasswordThrows401() {
        User stored = new User();
        stored.setId(2L);
        stored.setUsername("alice");
        stored.setPassword(new BCryptPasswordEncoder().encode("correct"));
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(stored);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login("alice", "wrong"));

        assertEquals(401, ex.getCode());
    }
}