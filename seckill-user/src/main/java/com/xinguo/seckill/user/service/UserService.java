package com.xinguo.seckill.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.user.dto.AuthDTO.LoginResult;
import com.xinguo.seckill.user.dto.AuthDTO.RegisterRequest;
import com.xinguo.seckill.user.entity.User;
import com.xinguo.seckill.user.entity.Wallet;
import com.xinguo.seckill.user.mapper.UserMapper;
import com.xinguo.seckill.user.mapper.WalletMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 用户服务：注册（自动建钱包）/ 登录 / 登出
 * 契约见需求文档 8.12 / 8.13；错误码 4008（用户名已存在）、401（未登录）。
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final WalletMapper walletMapper;
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper, WalletMapper walletMapper) {
        this.userMapper = userMapper;
        this.walletMapper = walletMapper;
    }

    /**
     * 注册：BCrypt 加密密码 + 建钱包（初始余额 0），返回新用户 id。
     * 说明：token 签发由 controller 在 Web 上下文中完成（否则纯单测无法脱离 Web 容器）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterRequest req) {
        // 参数空值校验：无专用错误码，统一归入 401（用户名/密码缺失即为认证失败语义）
        if (req == null || isBlank(req.username) || isBlank(req.password)) {
            throw new BusinessException(401, "用户名和密码不能为空");
        }
        // 用户名唯一性校验（DB 也有 UNIQUE 兜底，这里提前返回友好错误 4008）
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.username));
        if (exists != null && exists > 0) {
            throw new BusinessException(4008, "用户名已存在");
        }

        User user = new User();
        user.setUsername(req.username);
        user.setPassword(PASSWORD_ENCODER.encode(req.password));
        user.setPhone(req.phone);
        user.setNickname(req.nickname != null ? req.nickname : req.username);
        user.setRole(0);
        try {
            userMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发同名校验竞态：DB UNIQUE 约束兜底，映射回友好错误 4008（需求文档 8.12）
            throw new BusinessException(4008, "用户名已存在");
        }

        // 注册成功自动创建钱包（初始余额 0）
        Wallet wallet = new Wallet();
        wallet.setUserId(user.getId());
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setVersion(0);
        walletMapper.insert(wallet);

        return user.getId();
    }

    /**
     * 登录校验：返回用户身份与钱包余额（不含 token，token 由 controller 在 Web 上下文签发）
     */
    public LoginResult login(String username, String password) {
        if (isBlank(username) || isBlank(password)) {
            throw new BusinessException(401, "用户名和密码不能为空");
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null || !PASSWORD_ENCODER.matches(password, user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        Wallet wallet = walletMapper.selectOne(
                new LambdaQueryWrapper<Wallet>().eq(Wallet::getUserId, user.getId()));
        BigDecimal balance = wallet != null ? wallet.getBalance() : BigDecimal.ZERO;

        LoginResult result = new LoginResult();
        result.userId = user.getId();
        result.nickname = user.getNickname();
        result.balance = balance;
        return result;
    }

    /**
     * 登出：注销当前会话
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 当前登录用户（用于受保护接口校验 token 有效性）
     */
    public User me() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userMapper.selectById(userId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}