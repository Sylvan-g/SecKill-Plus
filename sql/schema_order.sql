-- ============================================================
-- 订单库 seckill_order（Ticket 3，依据需求文档 6.0/6.3）
-- 幂等：CREATE DATABASE IF NOT EXISTS + 先 DROP 再 CREATE
-- ============================================================
CREATE DATABASE IF NOT EXISTS seckill_order DEFAULT CHARSET utf8mb4;
USE seckill_order;

SET NAMES utf8mb4;

DROP TABLE IF EXISTS t_seckill_log;
DROP TABLE IF EXISTS t_seckill_order;

-- 6.3.1 秒杀订单表 t_seckill_order
CREATE TABLE t_seckill_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '订单号：SK+yyyyMMddHHmmss+5位序号',
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  goods_id BIGINT NOT NULL,
  goods_name VARCHAR(128) NOT NULL,
  price DECIMAL(10,2) NOT NULL COMMENT '订单金额 = 秒杀价',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消(超时) 3-已取消(用户主动)，见3.1',
  pay_channel TINYINT DEFAULT NULL COMMENT '支付渠道，1-钱包余额；支付成功后写',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  pay_deadline DATETIME NOT NULL COMMENT '支付截止时间 = created_at + 30分钟',
  paid_at DATETIME,
  -- generated column D13：非取消态(0/1)时等于 activity_id，取消态(2/3)为 NULL
  active_activity_id BIGINT AS (CASE WHEN status IN (0,1) THEN activity_id ELSE NULL END) STORED,
  -- 部分唯一索引：每用户每活动至多一笔非取消态订单；取消态不占位（允许重抢R5，历史保留）
  UNIQUE KEY uk_user_active (user_id, active_activity_id),
  INDEX idx_order_no (order_no),
  INDEX idx_status_deadline (status, pay_deadline) COMMENT '用于定时任务扫描超时订单'
) COMMENT '秒杀订单表';

-- 6.3.2 秒杀流程日志表 t_seckill_log（问题排查与可观测性演示）
CREATE TABLE t_seckill_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT,
  user_id BIGINT,
  order_no VARCHAR(32),
  action VARCHAR(20) COMMENT 'REQUEST/LIMIT_REJECT/STOCK_DEDUCT/MQ_SEND/ORDER_CREATE/ORDER_TIMEOUT/ORDER_CANCEL/WALLET_PAY',
  detail VARCHAR(500),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '秒杀流程日志表，用于问题排查和演示可观测性';