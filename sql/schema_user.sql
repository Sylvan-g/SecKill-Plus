-- ============================================================
-- 用户库 seckill_user（Ticket 3，依据需求文档 6.0/6.1）
-- 幂等：CREATE DATABASE IF NOT EXISTS + 先 DROP 再 CREATE
-- ============================================================
CREATE DATABASE IF NOT EXISTS seckill_user DEFAULT CHARSET utf8mb4;
USE seckill_user;

SET NAMES utf8mb4;

DROP TABLE IF EXISTS t_wallet_flow;
DROP TABLE IF EXISTS t_wallet;
DROP TABLE IF EXISTS t_user;

-- 6.1.1 用户表 t_user
CREATE TABLE t_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password VARCHAR(128) NOT NULL COMMENT 'BCrypt加密存储',
  phone VARCHAR(20),
  nickname VARCHAR(64) COMMENT '昵称，展示用',
  role TINYINT NOT NULL DEFAULT 0 COMMENT '0-普通用户 1-运营 2-管理员',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '用户表';

-- 6.1.2 钱包表 t_wallet（每个用户默认一张，注册时创建）
CREATE TABLE t_wallet (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL UNIQUE COMMENT '一用户一钱包',
  balance DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '余额，不允许为负（靠条件更新保证）',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '用户钱包表';

-- 6.1.3 钱包流水表 t_wallet_flow（支付/充值每一笔留痕，用于对账）
CREATE TABLE t_wallet_flow (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  wallet_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(32) COMMENT '关联订单号，充值可为空',
  type TINYINT NOT NULL COMMENT '1-支付扣款 2-充值入账 3-补偿退款',
  amount DECIMAL(10,2) NOT NULL COMMENT '金额恒为正数；方向由 type 决定',
  balance_after DECIMAL(10,2) NOT NULL COMMENT '交易后余额（用于对账）',
  remark VARCHAR(128),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_wallet (wallet_id),
  INDEX idx_order (order_no)
) COMMENT '钱包流水表';