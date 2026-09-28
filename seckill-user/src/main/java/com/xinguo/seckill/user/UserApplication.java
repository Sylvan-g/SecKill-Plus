package com.xinguo.seckill.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户服务启动类：seckill_user 库，端口 8100
 */
@SpringBootApplication(scanBasePackages = "com.xinguo.seckill")
@MapperScan("com.xinguo.seckill.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}