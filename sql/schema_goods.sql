-- ============================================================
-- 商品库 seckill_goods（Ticket 3，依据需求文档 6.0/6.2）
-- 幂等：CREATE DATABASE IF NOT EXISTS + 先 DROP 再 CREATE
-- ============================================================
CREATE DATABASE IF NOT EXISTS seckill_goods DEFAULT CHARSET utf8mb4;
USE seckill_goods;

SET NAMES utf8mb4;

DROP TABLE IF EXISTS t_goods_stock;
DROP TABLE IF EXISTS t_seckill_activity;

-- 6.2.1 秒杀活动表 t_seckill_activity
CREATE TABLE t_seckill_activity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  goods_id BIGINT NOT NULL COMMENT '商品ID',
  goods_name VARCHAR(128) NOT NULL,
  goods_img VARCHAR(255),
  original_price DECIMAL(10,2) NOT NULL COMMENT '原价',
  seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
  stock INT NOT NULL COMMENT '秒杀库存（预售总库存，仅预热用）',
  limit_per_user INT NOT NULL DEFAULT 1 COMMENT '每人限购数量',
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0-未开始 1-进行中 2-已结束 3-已下架（goods定时维护）',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_start_time (start_time),
  INDEX idx_status (status)
) COMMENT '秒杀活动表（一场活动 = 一个商品的一次秒杀场次）';

-- 6.2.2 商品真实库存表 t_goods_stock（DB层最终兜底，Redis丢失时以此为准）
CREATE TABLE t_goods_stock (
  goods_id BIGINT PRIMARY KEY,
  total_stock INT NOT NULL COMMENT '真实剩余库存（下单落库时扣减，取消时回补）',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'
) COMMENT '商品真实库存表（DB层最终兜底）';