-- ============================================================
-- 种子数据（Ticket 3，依据需求文档 2.1 场次表 / 2.2 角色）
-- 场次时间：【演示用相对时间】活动1001 从 NOW()+1min 开始，
--   其余场次按文档 2.1 间隔 +15min 依次错开，每场持续 10 分钟。
--   理由：文档 2.1 固定日（2026-09-25）演示当日不可抢，见 SeedT3-3 反馈；
--   相对时间使本脚本每次重跑都刷新场次，方便反复演示。
--   场次间隔/时长与文档 2.1 完全一致（12:00/12:15/12:30/12:45/13:00，10分钟/场）。
-- 演示账号密码：统一 123456（bcrypt hash 已预置，见下方注释）
--   user_demo  普通用户 role=0
--   ops_demo   运营     role=1
--   admin_demo 管理员   role=2
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE（主键冲突时覆盖更新）
-- ============================================================

-- ---------------- seckill_user ---------------
USE seckill_user;
SET NAMES utf8mb4;

-- 用户（bcrypt hash 对应明文 123456）
INSERT INTO t_user (id, username, password, phone, nickname, role) VALUES
  (1, 'user_demo',  '$2b$10$iyeWRdMDzCjIQpr1DobpCOYt2WxWfq8yuHsmXZ8Iao7C.ZjG0/huq', '13800000001', '张闪购', 0),
  (2, 'ops_demo',   '$2b$10$iyeWRdMDzCjIQpr1DobpCOYt2WxWfq8yuHsmXZ8Iao7C.ZjG0/huq', '13800000002', '李运营', 1),
  (3, 'admin_demo', '$2b$10$iyeWRdMDzCjIQpr1DobpCOYt2WxWfq8yuHsmXZ8Iao7C.ZjG0/huq', '13800000003', '王管理员', 2)
ON DUPLICATE KEY UPDATE
  password = VALUES(password), nickname = VALUES(nickname), role = VALUES(role);

-- 钱包：给每个演示用户预置余额，便于层刷测试直接支付
INSERT INTO t_wallet (user_id, balance, version) VALUES
  (1, 5000.00, 0),
  (2, 10000.00, 0),
  (3, 10000.00, 0)
ON DUPLICATE KEY UPDATE
  balance = VALUES(balance), version = VALUES(version);

-- ---------------- seckill_goods ---------------
USE seckill_goods;
SET NAMES utf8mb4;

-- 5 个场次（活动1001-1005）库存与文档 2.1 表格一致；时间用相对 NOW() 偏移
INSERT INTO t_seckill_activity
  (id, goods_id, goods_name, goods_img, original_price, seckill_price, stock, limit_per_user, start_time, end_time, status) VALUES
  (1001, 2001, '无线蓝牙耳机（降噪款）',     'https://console.enterprise.trae.cn/api/ide/v1/text_to_image?prompt=headphones&image_size=square', 299.00,  99.00,  100, 1, NOW() + INTERVAL 1 MINUTE,  NOW() + INTERVAL 11 MINUTE, 0),
  (1002, 2002, '智能手机（6.7英寸旗舰）',    'https://console.enterprise.trae.cn/api/ide/v1/text_to_image?prompt=smartphone&image_size=square', 4999.00, 2999.00, 50,  1, NOW() + INTERVAL 16 MINUTE, NOW() + INTERVAL 26 MINUTE, 0),
  (1003, 2003, '智能手表（运动版）',         'https://console.enterprise.trae.cn/api/ide/v1/text_to_image?prompt=smartwatch&image_size=square', 1499.00, 699.00,  80,  1, NOW() + INTERVAL 31 MINUTE, NOW() + INTERVAL 41 MINUTE, 0),
  (1004, 2004, '机械键盘（87键青轴）',       'https://console.enterprise.trae.cn/api/ide/v1/text_to_image?prompt=keyboard&image_size=square', 599.00,  299.00,  120, 1, NOW() + INTERVAL 46 MINUTE, NOW() + INTERVAL 56 MINUTE, 0),
  (1005, 2005, '便携充电宝（20000mAh）',     'https://console.enterprise.trae.cn/api/ide/v1/text_to_image?prompt=powerbank&image_size=square', 199.00,  59.00,  200, 1, NOW() + INTERVAL 61 MINUTE, NOW() + INTERVAL 71 MINUTE, 0)
ON DUPLICATE KEY UPDATE
  goods_name = VALUES(goods_name), seckill_price = VALUES(seckill_price),
  stock = VALUES(stock), start_time = VALUES(start_time), end_time = VALUES(end_time);

-- 真实库存表（goods 库兜底层），与活动初始库存一致
INSERT INTO t_goods_stock (goods_id, total_stock, version) VALUES
  (2001, 100, 0),
  (2002, 50,  0),
  (2003, 80,  0),
  (2004, 120, 0),
  (2005, 200, 0)
ON DUPLICATE KEY UPDATE total_stock = VALUES(total_stock), version = VALUES(version);

-- ---------------- seckill_order ---------------
-- 订单库无需种子数据：订单/日志由业务运行时写入