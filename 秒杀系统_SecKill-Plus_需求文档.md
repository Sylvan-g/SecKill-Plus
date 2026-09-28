# 闪购秒杀系统（SecKill-Plus）需求文档

## 0. 使用说明

本文档面向AI编程工具，用于生成完整可运行代码。实现完成后请自己通读一遍代码、理解每处设计的原因——文档末尾的"验收清单"和"面试可能追问的问题"是用来自查的，务必对着过一遍再放进简历。

> **编程红线**：第 3 节"业务决策清单"是业务层的唯一依据，第 4 节"订单状态机"是订单模块的唯一依据，第 5 节"服务划分"是整个微服务工程的唯一依据。凡文档内出现枚举、状态码、错误码，一律以对应章节的表格为准，**不许自行发明新值**。若发现文档与源码冲突，停下上报，不要擅自改文档。

***

## 1. 项目概述

**新果商城（XinGuo Mall，包名 `com.xinguo`）** 是一家专注数码 3C 品类的电商平台。本文档描述其招牌活动 **「超级闪购节·数码3C专场」**：限时、限量、限价，整点开抢。典型一场是 **100 件库存、上万人同时抢**，核心解决三类工程问题：

1. **超卖**：高并发下库存扣减必须原子、准确，多卖一件都是事故
2. **系统被打垮**：瞬时流量需要限流 + 削峰 + 排队，不能让数据库直接扛住峰值
3. **恶意刷单**：同一用户不能重复下单、接口不能被脚本刷、未登录用户不能下单

平台以**微服务架构**落地（5 个可运行服务 + 独立前端工程，见第 5 节），引入**平台自有钱包**作为支付手段，把"抢到 → 支付 → 超时取消 → 库存回补"做成一条完整闭环。

## 2. 业务场景

### 2.1 平台与活动设定

- **平台**：新果商城（XinGuo）。秒杀是 App/网页端的固定频道"限时闪购"。
- **活动**：超级闪购节·数码3C专场，于 2026-09-25 举行，全天共 **5 个场次**，每场一个爆款单品，**场次开始即开抢，持续 10 分钟**。
- **商品清单与场次安排**（下文所有接口示例、测试数据均取自这张表）：

| 场次 | 开始时间 | 结束时间 | 商品 | 原价 | 秒杀价 | 库存 | 每人限购 |
| ---- | ---- | ---- | ---- | ---- | ---- | ---- | ---- |
| 1（活动1001） | 12:00:00 | 12:10:00 | 无线蓝牙耳机（降噪款） | 299.00 | 99.00 | 100 | 1 |
| 2（活动1002） | 12:15:00 | 12:25:00 | 智能手机（6.7英寸旗舰） | 4999.00 | 2999.00 | 50 | 1 |
| 3（活动1003） | 12:30:00 | 12:40:00 | 智能手表（运动版） | 1499.00 | 699.00 | 80 | 1 |
| 4（活动1004） | 12:45:00 | 12:55:00 | 机械键盘（87键青轴） | 599.00 | 299.00 | 120 | 1 |
| 5（活动1005） | 13:00:00 | 13:10:00 | 便携充电宝（20000mAh） | 199.00 | 59.00 | 200 | 1 |

> 业务上：一个"活动" = 一个商品的一次秒杀场次（1:1）。"闪购节"只是营销页面的合集概念，代码里无需单独的"节日"实体。

### 2.2 角色与权限

| 角色 | 说明 | 能做什么 |
| ---- | ---- | ---- |
| 游客 | 未登录 | 浏览闪购频道、活动列表、活动详情；**不能下单** |
| 注册用户 | 登录用户 | 抢购、查看订单、钱包充值、钱包支付 |
| 运营 | 平台运营人员（role=1） | 创建活动、查看秒杀日志 |
| 管理员 | 平台管理员（role=2） | 运营全部权限 + 任意查询对账 |

鉴权统一走 **Sa-Token 微服务共享会话**（`sa-token-dao-redis`，个服务通过同一 Redis 校验 token；角色在 [t_user](#611-用户表t_user) 的 `role` 字段，接口用注解校验角色）。

### 2.3 用户旅程（主流程）

1. 用户在 11:50 打开闪购频道，看到场次 1 的倒计时（活动 1001，状态=0 未开始）。
2. 12:00:00 整，用户点"立即秒杀"：
   - 网关分布式限流放行 → order 服务校验活动时间（从Redis活动缓存读取）→ Lua 原子扣减 Redis 库存并打"用户已购"标记 → 返回"排队中"（QUEUED，含订单号）。
   - order 服务发 MQ 消息，异步落库生成订单（status=0 待支付），并调 goods 服务扣真实库存。
3. 用户在订单页点"去支付"，余额不足则先充值，再支付（补偿式两阶段跨服务扣款，见 4.3）。
4. 用户在"我的订单"看到该订单为已支付，抢购完成。

**未支付被取消路径：** 订单 30 分钟未支付 → **延迟消息消费**或 **scheduler 扫描兜底**触发取消（status=2），回滚 Redis 库存、调 goods 服务回补 DB 库存、删除用户购买标记——**该用户可重新抢购该场次**（决策 R5）。

### 2.4 关键业务规则（R1–R10，编程直接照做）

| 编号 | 规则 | 规定 |
| ---- | ---- | ---- |
| R1 | 场次开关 | 下单前校验 `now` 在 `[start_time, end_time)` 内，否则返回 4003；order 服务从 **Redis 活动缓存**读取时间（未命中调 goods 内部接口回填），`status` 由 goods 服务定时维护（见 4.6），下单以实时时间判断为准 |
| R2 | 限购 | 每用户每场限购 `limit_per_user`（本场景全部为 1）。**任一时刻**同一用户最多持有该活动 1 笔非取消态订单，由 DB 部分唯一索引（见 6.6） + Redis 用户标记双重保证；已取消（status=2/3）不占额度，允许重抢（R5） |
| R3 | 排队语义 | 用户点抢购命中 Lua 后立即返回"排队中"，**不阻塞等待落库**；落库由 MQ 异步完成 |
| R4 | 支付期限 | 订单落库后 30 分钟（`pay-timeout-minutes: 30`）内必须支付，逾期自动取消 |
| R5 | 超时可重抢 | **超时/主动取消后允许该用户重新抢购**（取消时删除用户购买标记、回滚库存）。由部分唯一索引（取消态不占位）物理支持 |
| R6 | 支付方式 | 只支持**平台钱包余额**支付（pay_channel=1）；不支持第三方支付。秒杀特价商品**已支付即终态**，不提供退款/售后 |
| R7 | 风控 | ① 下单必须登录（Sa-Token 业务层校验，401 拦截）；② 每人限购（R2）；③ **分布式限流**（Redis+Lua 令牌桶，网关层，见 10.2）；④ 同一账号并发点击只生成 1 个订单（Redis 标记 + DB 部分索引） |
| R8 | 库存一致性 | Redis 库存是第一道闸（快），DB `t_goods_stock`（goods 库）是最终兜底（准）。Redis 丢失时**以 DB `t_goods_stock.total_stock` 为准**重新预热——绝不能用活动原始 `stock`，否则活动进行中 Redis 重启会把已扣库存重置、直接超卖 |
| R9 | 钱包扣款 | 钱包扣款用**条件更新**（`UPDATE ... WHERE balance >= amount`）保证不产生负数余额；同一订单并发支付只能成功一次（支付前订单状态条件更新，见 4.3） |
| R10 | 预约提醒（可选） | 场次详情页提供"提醒我"，到期由定时任务推送。**列为可选增强，不实现则验收清单相应项保持未勾选** |

### 2.5 业务决策清单（编程唯一依据）

| # | 决策项 | 决策值 | 理由 |
| ---- | ---- | ---- | ---- |
| D1 | 活动/商品关系 | 活动 = 商品的一次秒杀场次（1:1） | 简化建模，闪购节仅为营销概念 |
| D2 | 超时取消后是否可重抢 | **允许** | 真实电商场景释放库存给其他用户，也方便压测反复验证 |
| D3 | 已支付订单可否退款 | **不可**，已支付即终态 | 秒杀特价商品行规不退不换；避免实现退款分支 |
| D4 | 支付方式 | 仅平台钱包（内置余额），充值接口为模拟充值 | 聚焦秒杀核心链路，避免第三方对接复杂度 |
| D5 | 风控强度 | 登录 + 限购 + 分布式限流，不做验证码/短信 | 聚焦防超卖与防重复，验证码等作为后续扩展 |
| D6 | 用户主动取消 | 仅待支付（status=0）订单可主动取消（status=3） | 已支付订单不可取消，见 D3 |
| D7 | Redis 库存回补时机 | 订单取消时 INCR 回补 + 删除用户标记 | 与 R5 配套 |
| D8 | DB 库存回补 | 取消时调 goods 服务 `total_stock + 1`（低频，不做乐观锁重试） | 取消是低频操作，简单回补足够 |
| D9 | 订单号格式 | `SK` + yyyyMMddHHmmss + 5 位随机序号，如 SK20260925120000123 | 唯一、可读、可追踪 |
| D10 | 部署形态 | **微服务化**：5 个可运行服务，静态端口直连（不做 Nacos/注册中心） | 用户决策；本机无 Docker，注册中心成本高 |
| D11 | 数据库 | **每服务独立分库**：seckill_user / seckill_goods / seckill_order 三库 | 用户决策 |
| D12 | 支付一致性 | **补偿式两阶段**（跨服务调用，本地事务+失败补偿） | 微服务下无分布式事务框架（Seata），按决策 Q10 |
| D13 | 重抢实现 | `uk_activity_user` 改为**部分唯一索引**（非取消态唯一，取消态不占位） | 同时满足 R5 重抢与 R2 防重，见 6.6 |
| D14 | 限流 | **分布式限流**：Redis+Lua 令牌桶（替代单机 Guava） | 用户决策"核心+分布式限流" |

> 编程时若发现需要新决策，先停下来问用户，**不要自己拍板改 D1–D14**。

## 3. 订单状态机与支付流程（order 服务域）

### 3.1 订单状态机（唯一依据）

| 状态码 | 含义 | 可流转到 | 说明 |
| ---- | ---- | ---- | ---- |
| QUEUED | 排队中 | WAIT_PAY | **接口层临时状态**：Lua 扣减成功、MQ 尚未落库，接口先返回此值；订单表内不存在 QUEUED 行 |
| 0（WAIT_PAY） | 待支付 | 1 / 2 / 3 | MQ 落库后的初始态，30 分钟支付期限开始计时 |
| 1（PAID） | 已支付 | （终态） | 支付成功，不可退款不可取消 |
| 2（TIMEOUT_CANCELLED） | 已取消（超时） | （终态） | 延迟消息/扫描触发置位，回滚库存 |
| 3（USER_CANCELLED） | 已取消（用户主动） | （终态） | 用户对待支付订单调取消接口 |

状态流转图：

```
用户点秒杀 → QUEUED（排队中，Redis 已扣减）
   └─ MQ 异步落库 → 0 待支付
                      ├─ 用户支付成功 → 1 已支付（终态）
                      ├─ 30 分钟未支付（延迟消息/扫描）→ 2 已取消(超时)（终态，回滚库存）
                      └─ 用户主动取消 → 3 已取消(用户主动)（终态，回滚库存）
```

**并发安全约定**：所有状态变更使用条件更新（`UPDATE ... WHERE id=? AND status=?`），影响行数为 0 表示状态已被他人抢先变更，直接返回对应错误码。

### 3.2 秒杀主流程时序（order 服务 + 跨服务依赖）

```
用户点击"立即秒杀"（经 gateway 路由到 order 服务 /api/seckill/do/{activityId}）
  → [gateway] Redis+Lua 分布式令牌桶校验 → 不通过：直接返回 4290
  → [order] Sa-Token 登录校验 → 未登录：返回 401
  → [order] 从 Redis 活动缓存(seckill:activity:{id})读取活动起止时间校验（R1）
       → 缓存未命中：调 goods 内部接口 GET /internal/activity/{id} 并回填缓存
       → 未开始/已结束：返回 4003
  → [order] 执行 Lua 脚本（原子扣 Redis 库存 + 用户去重标记）
       → 返回 -1（库存不足）：返回 4001
       → 返回 -2（重复购买）：返回 4002
       → 返回 1（成功）：
             → 生成订单号（D9），写秒杀日志（t_seckill_log：REQUEST/STOCK_DEDUCT，order 库）
             → 发送 MQ 消息 {orderNo, activityId, userId, goodsId, price}
             → 立即返回 {orderNo, status: QUEUED}
[MQ Consumer（order 服务，异步）]
  → 消费消息 → 插入 t_seckill_order(status=0, pay_deadline=now+30min)（order 库）
       → 插入失败（order_no 唯一冲突=重复消息；部分唯一索引冲突=该用户已有非取消态订单）：记日志，忽略
       → 插入成功：
             → 调 goods 内部接口 POST /internal/stock/deduct {goodsId, count:1} 扣真实库存（乐观锁）
                  → goods 返回失败（乐观锁 version 冲突）：order 重试 3 次
                  → 仍失败：订单置 status=3（取消），记录告警日志，人工介入
  → 落库同时发送 30 分钟延迟消息（order 服务 OrderTimeoutConsumer 消费，见 4.4）
```

### 3.3 支付流程时序（补偿式两阶段，order → wallet 跨服务）

```
用户点击"去支付"（POST /api/order/pay/{orderNo}，order 服务）
  → 开启本地事务（order 库）
  → [order] 校验订单：存在、属主=当前用户（否则 4005）、status=0（否则 4006）、
    pay_deadline > now（否则 4007，交由取消任务处理）
  → [order] SELECT ... FOR UPDATE 锁定订单行（防与取消/另一支付并发）
  → [order] 调 user 服务内部接口 POST /internal/wallet/deduct {userId, amount, orderNo}（第一阶段：扣款）
       → user 服务（独立本地事务）：条件扣款 UPDATE t_wallet SET balance=balance-? WHERE user_id=? AND balance>=?
            → 影响行数 0：返回 4004（钱包余额不足，前端引导去充值）
            → 扣款成功：写 t_wallet_flow(type=1)，返回扣款后余额
  → [order] 本地更新订单：UPDATE t_seckill_order SET status=1, paid_at=NOW(), pay_channel=1 WHERE id=? AND status=0
       → 影响行数 0：订单已被他事务置为非 0（如超时取消刚发生）→ 调 user 服务 POST /internal/wallet/refund 补偿退钱
         （第二阶段补偿），返回 4006
  → 提交本地事务，返回 {orderNo, status: 1, paidAmount: 99.00}
```

**一致性说明**：跨服务无分布式事务，采用"先扣款、后置状态、失败补偿退款"两阶段。极端情况（order 置状态成功但 user 扣款后补偿退款也失败）由**钱包流水 + 对账任务**（10.5）兜底发现。

### 3.4 超时/取消流程（延迟消息 + 扫描兜底双机制）

```
超时取消采用【延迟消息 + 扫描兜底】双机制，保证不依赖单点：
  → [order 服务，落库时] 发送 30 分钟延迟消息 → [OrderTimeoutConsumer] 消费触发取消（时效性优先）
  → [scheduler 服务，OrderTimeoutScanTask] 每分钟调 order 内部接口，兜底扫描（防消息丢失）

取消逻辑（order 服务 OrderCancelService，超时与主动取消共用，参数化最终状态码 2/3；
order 本地事务 + 条件更新保证幂等）：
  → UPDATE t_seckill_order SET status=? WHERE id=? AND status=0（防重复取消）
  → Redis：INCR seckill:stock:{activityId}（回补）；DEL seckill:user:{activityId}:{userId}（允许重抢，R5）
  → 调 goods 内部接口 POST /internal/stock/rollback {goodsId, count:1} 回补 DB 库存（D8）
    （goods 调用失败：记告警日志，由启动预热/对账兜底修正）
  → 写秒杀日志 ORDER_TIMEOUT/ORDER_CANCEL（按最终状态码区分）

[用户主动取消] POST /api/order/cancel/{orderNo}（order 服务）
  → 校验订单属主、status=0，执行上述取消逻辑（状态置 3），立即返回，不等定时任务
```

### 3.5 scheduler 服务：超时扫描兜底

```
[OrderTimeoutScanTask]每分钟：
  → 调 order 内部接口 GET /internal/order/timeout-list?deadline=now获取到期待支付订单
  → 逐笔调 order 内部接口 POST /internal/order/timeout-cancel {orderNo} 执行取消（同 4.4 逻辑）
```

### 3.6 goods 服务：活动状态与库存维护

| 条件 | status | 维护者 |
| ---- | ---- | ---- |
| `now < start_time` | 0 未开始（创建时默认） | 创建时 |
| `start_time <= now < end_time` | 1 进行中 | goods 内置 `ActivityStatusTask` 定时维护 |
| `now >= end_time` | 2 已结束 | goods 内置 `ActivityStatusTask` |
| 运营手动下架 | 3 已下架 | admin 接口 |

- 状态位用于列表展示与运营后台；**下单接口准入门禁以实时时间判断为准**（R1），状态位延迟不影响正确性。
- goods 服务内置 `StockPreheatTask`：启动/定时以 DB `t_goods_stock.total_stock` 为准重建 Redis 库存（R8）；新活动创建后立即用 `activity.stock` 预热一次（Redis 无存量，安全）。

## 4. 技术栈与版本

| 组件 | 选型 | 版本建议 |
| ---- | ---- | ---- |
| 语言/框架 | Java + Spring Boot | **Java 17, Spring Boot 3.2.x**（工程内指定，本机 JAVA_HOME 为 JDK8，见 5.3 运行说明） |
| 缓存 | Redis | 7.x（本机用 Redis 3.2.100，向下兼容） |
| 消息队列 | **RocketMQ 5.x**（本机安装运行） | - |
| 数据库 | MySQL | 8.0（本机 MySQL80） |
| ORM | MyBatis-Plus | 3.5.x |
| 鉴权 | **Sa-Token + sa-token-dao-redis**（微服务共享会话） | 1.37.x |
| 限流 | **Redis+Lua 分布式令牌桶**（网关层） | - |
| HTTP库 | OpenFeign 或 RestTemplate（服务间调用） | Spring Cloud 2023.x |
| 构建工具 | Maven | 3.8+（本机 3.9.9） |
| 压测工具 | **Apache JMeter**（本机待安装） | 5.x |
| API文档 | Knife4j（可选） | - |
| 前端 | **Vue3 + Vite + Element Plus**（独立子工程） | Node v22 已具备 |

## 5. 服务划分与工程结构

### 5.1 服务清单与端口（微服务唯一依据）

| 服务（Maven模块） | 端口 | 数据源库 | 主要职责 | 内置任务 |
| ---- | ---- | ---- | ---- | ---- |
| seckill-gateway | 8080 | 无 | 唯一的公网入口：路由转发 + **分布式限流**（Redis+Lua）+ Knife4j聚合可选 | - |
| seckill-user | 8100 | seckill_user | 注册/登录/登出/钱包（余额、模拟充值）/内部扣款与补偿退款 | 注册时建钱包 |
| seckill-goods | 8200 | seckill_goods | 活动管理/活动查询/秒杀日志之外的库存管理：真实库存扣减与回补（乐观锁） | ActivityStatusTask、StockPreheatTask |
| seckill-order | 8300 | seckill_order | 秒杀下单（Lua+MQ）、订单查询/支付/取消、MQ生产与消费、延迟消息消费、秒杀日志 | OrderTimeoutConsumer |
| seckill-scheduler | 8400 | 无（纯编排） | admin 管理（建活动/查日志，转发对应服务）、超时扫描兜底调度 | OrderTimeoutScanTask |
| seckill-web（前端） | 5173 | 无 | Vue3+Vite 用户端 + 运营端，dev 代理 /api → gateway:8080 | - |

> 服务间用 **HTTP 静态端口直连**（OpenFeign/RestTemplate），每个服务自带 Sa-Token Redis 共享会话校验鉴权；gateway 不做业务鉴权，只限流 + 路由（与 3.2 流程描述一致）。

### 5.2 Maven 多模块结构

```
seckill-plus/                                  # Maven 根（= D:\AI_study\SecKill-Plus）
├── pom.xml                                    # 父工程，统一依赖版本管理
├── seckill-common                             # 公共模块（库，不独立启动）
│   └── com.xinguo.seckill.common
│       ├── response/Result.java               # 统一响应体 {code, message, data}
│       ├── exception/BusinessException.java
│       ├── exception/GlobalExceptionHandler.java
│       ├── constant/RedisKeyConstant.java
│       ├── dto/OrderMessageDTO.java           # MQ 消息体（可放 common 供两端引用）
│       └── util/OrderNoGenerator.java         # 订单号生成（D9）
├── seckill-gateway
│   └── com.xinguo.seckill.gateway
│       ├── GatewayApplication.java            # 启动类
│       ├── filter/RateLimitFilter.java        # Redis+Lua 分布式限流
│       └── controller/RouteController.java    # 简单路由转发（RestTemplate）
├── seckill-user
│   └── com.xinguo.seckill.user
│       ├── UserApplication.java
│       ├── controller/{UserController, WalletController, WalletInternalController}.java
│       ├── service/{UserService, WalletService}.java
│       ├── mapper/{UserMapper, WalletMapper, WalletFlowMapper}.java
│       └── entity/{User, Wallet, WalletFlow}.java
├── seckill-goods
│   └── com.xinguo.seckill.goods
│       ├── GoodsApplication.java
│       ├── controller/{ActivityController, ActivityInternalController, StockInternalController}.java
│       ├── service/{ActivityService, GoodsStockService}.java
│       ├── mapper/{ActivityMapper, GoodsStockMapper}.java
│       ├── entity/{SeckillActivity, GoodsStock}.java
│       └── scheduler/{ActivityStatusTask, StockPreheatTask}.java
├── seckill-order
│   └── com.xinguo.seckill.order
│       ├── OrderApplication.java
│       ├── controller/{SeckillController, OrderController, OrderInternalController}.java
│       ├── service/{SeckillService, OrderService, CancelService, PayService}.java
│       ├── mapper/{OrderMapper, SeckillLogMapper}.java
│       ├── entity/{SeckillOrder, SeckillLog}.java
│       ├── mq/producer/OrderMessageProducer.java
│       ├── mq/consumer/{OrderMessageConsumer, OrderTimeoutConsumer}.java
│       └── client/{GoodsClient, WalletClient}.java   # 服务间调用
└── seckill-scheduler
    └── com.xinguo.seckill.scheduler
        ├── SchedulerApplication.java
        ├── scheduler/OrderTimeoutScanTask.java
        ├── admin/{AdminController, AdminService}.java
        └── client/{GoodsClient, OrderClient}.java
seckill-web/                                    # 前端（独立 Vite 工程，非 Maven）
    ├── package.json / vite.config.js
    └── src/views/{Channel, ActivityDetail, Seckill, Orders, Wallet, Login, AdminActivity, AdminLog}.vue
```

### 5.3 运行说明

- **JDK**：本机 `JAVA_HOME` 指向 JDK8，工程要求 Java 17。各可运行模块 IDE/脚本运行时需将 JDK 切到 `D:\JDK-17`（用 `.run`/脚本内 `set JAVA_HOME=D:\JDK-17`）。
- **启动顺序**：MySQL & Redis & RocketMQ（namesrv+broker）→ goods → user → order → scheduler → gateway → 前端 dev。
- **中间件**：Redis 用 `D:\Redis-x64-3.2.100\redis-server.exe` 启动；RocketMQ 需下载 rocketmq-all-5.x 解压后启动 namesrv + broker。

## 6. 数据库设计（三库独立 DDL）

### 6.0 建库

```sql
CREATE DATABASE seckill_user DEFAULT CHARSET utf8mb4;
CREATE DATABASE seckill_goods DEFAULT CHARSET utf8mb4;
CREATE DATABASE seckill_order DEFAULT CHARSET utf8mb4;
```

### 6.1 用户库 seckill_user

```sql
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
```

### 6.2 商品库 seckill_goods

```sql
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
```

### 6.3 订单库 seckill_order

```sql
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
```

> **6.3.1 的设计说明（D13）**：原方案 `UNIQUE KEY uk_activity_user(activity_id, user_id)` 与 R5"取消后可重抢"互斥——同用户同活动取消后第二次落库必撞唯一键。改用 **generated column + 部分唯一索引** 后：`active_activity_id` 在 status∈{0,1} 时是 activity_id，在 status∈{2,3} 时是 NULL；MySQL 唯一索引允许多个 NULL，因此已取消用户可再插入新订单行。DB 防重硬约束（每用户每活动至多一笔非取消态）不变，R5 重抢也物理支持。
> **limit_per_user>1 场景**：本场景全部为 1，此索引策略假设 limit=1；若未来支持多件，需重构唯一约束（如合并 pending 数量列），本版本不做。

## 7. Redis Key 设计（Redis 为跨服务共享组件）

| Key | 类型 | 说明 | TTL | 使用方 |
| ---- | ---- | ---- | ---- | ---- |
| `seckill:stock:{activityId}` | String(Int) | 活动剩余库存，goods 预热写入 | 活动结束后+1天 | order(扣)、goods(scheduler回补) |
| `seckill:user:{activityId}:{userId}` | String | 标记用户已购，值为下单时间戳；取消时删除（R5） | 活动结束后+1天 | order |
| `seckill:activity:{activityId}` | String(JSON) | 活动详情缓存（含起止时间/价），减少跨服务查询 | 随机600~900秒（防雪崩） | order(读)、goods(写)、gateway(不直接访问) |
| `seckill:bloom:activity` | Bloomfilter | 活动ID布隆过滤器，防缓存穿透（可选） | 永久 | goods |
| `seckill:ratelimit:{scope}:{key}` | Hash | **分布式令牌桶**，scope=ip/userId，key=接口路径 | 5秒滑动 | gateway |

## 8. 接口设计（含请求/响应示例，示例数据来自第 2.1 节场次表）

> 公网统一经 gateway `:8080/api/**` 进入（限流后按前缀路由转发：`/api/user/**`→8100，`/api/goods/**`→8200，`/api/seckill, /api/order/**`→8300，`/api/admin/**`→8400）。`/internal/**`（后文带"内部接口"标注）仅服务间直连调用，不暴露公网。

### 8.1 获取活动列表（闪购频道页，经 gateway）

```
GET /api/goods/activity/list?status=0&page=1&size=10
说明：status 可选 0(未开始)/1(进行中)/2(已结束)，默认全部；游客可浏览；
stock 为 Redis 实时剩余库存（gateway 转发 order 或 goods 均可取，实现时固定 goods 服务），未预热返回 DB 值

Response 200:
{
  "code": 0,
  "data": {
    "list": [{
      "activityId": 1001,
      "goodsName": "无线蓝牙耳机（降噪款）",
      "originalPrice": 299.00,
      "seckillPrice": 99.00,
      "stock": 100,
      "limitPerUser": 1,
      "startTime": "2026-09-25T12:00:00",
      "endTime": "2026-09-25T12:10:00",
      "status": 0
    }],
    "total": 5,
    "serverTime": "2026-09-25T11:58:32"
  }
}
```

### 8.2 获取活动详情（经 gateway）

```
GET /api/goods/activity/{activityId}
说明：goods 服务读取链路 缓存(seckill:activity:{id}) → 未命中且活动ID通过布隆过滤器 → 查DB → 回填缓存（随机TTL防雪崩）；
stock 为 Redis 实时剩余库存

Response 200:
{
  "code": 0, "message": "success",
  "data": {
    "activityId": 1001, "goodsId": 1,
    "goodsName": "无线蓝牙耳机（降噪款）",
    "goodsImg": "https://cdn.xinguo.com/goods/earbuds.jpg",
    "originalPrice": 299.00, "seckillPrice": 99.00,
    "stock": 37, "limitPerUser": 1,
    "startTime": "2026-09-25T12:00:00", "endTime": "2026-09-25T12:10:00",
    "serverTime": "2026-09-25T11:58:32", "status": 0
  }
}
```

### 8.3 秒杀下单（经 gateway → order 服务）

```
POST /api/seckill/do/{activityId}
Header: Authorization: Bearer {token}

Response 200（进入排队）:
{ "code": 0, "message": "排队中", "data": { "orderNo": "SK20260925120000123", "status": "QUEUED" } }

Response 4001（库存不足）: { "code": 4001, "message": "手慢了，库存不足", "data": null }
Response 4002（重复购买）: { "code": 4002, "message": "您已购买过该商品", "data": null }
Response 4003（未开始/已结束）: { "code": 4003, "message": "活动未开始或已结束", "data": null }
Response 4290（限流拒绝）: { "code": 4290, "message": "当前排队人数过多，请稍后重试", "data": null }
Response 401（未登录）: { "code": 401, "message": "请先登录", "data": null }
```

### 8.4 查询订单状态（轮询用，order 服务）

```
GET /api/order/status/{orderNo}
Header: Authorization: Bearer {token}
说明：Lua 已扣减但 MQ 尚未落库时（Redis 有用户标记、DB 无订单行）返回 QUEUED 且 payDeadline=null，
前端据此轮询；落库后返回真实状态。

Response 200:
{
  "code": 0,
  "data": { "orderNo": "SK20260925120000123", "status": "WAIT_PAY",
            "price": 99.00, "payDeadline": "2026-09-25T12:30:00" }
}
```

### 8.5 我的订单列表（order 服务）

```
GET /api/order/list?status=&page=1&size=10
Header: Authorization: Bearer {token}
说明：status 0待支付/1已支付/2已取消(超时)/3已取消(主动)，可空（全部）；在 order 库内按 user_id 查询
```

### 8.6 钱包余额查询（user 服务）

```
GET /api/user/wallet/balance
Header: Authorization: Bearer {token}
Response 200: { "code": 0, "data": { "userId": 10001, "balance": 99.00 } }
```

### 8.7 钱包充值（模拟，测试用，user 服务）

```
POST /api/user/wallet/recharge
Header: Authorization: Bearer {token}
Body: { "amount": 100.00 }
Response 200: { "code": 0, "data": { "balance": 199.00 } }
说明：user 库内写 t_wallet_flow(type=2)；纯演示，不接真实支付通道
```

### 8.8 订单支付（钱包余额，order 服务，跨服务两阶段）

```
POST /api/order/pay/{orderNo}
Header: Authorization: Bearer {token}
说明：见 3.3 时序；顺序 校验→锁行→调user扣款(阶段一)→置status=1(阶段二本地事务)→失败走补偿退款

Response 200（支付成功）:
{ "code": 0, "message": "支付成功",
  "data": { "orderNo": "SK20260925120000123", "status": 1, "paidAmount": 99.00, "balanceAfter": 0.00 } }

Response 4004（余额不足）: { "code": 4004, "message": "钱包余额不足，请先充值", "data": null }
Response 4005（订单不存在或非本人）: { "code": 4005, "message": "订单不存在或无权操作", "data": null }
Response 4006（状态不允许支付）: { "code": 4006, "message": "订单已支付或已取消，请勿重复支付", "data": null }
Response 4007（订单已超时）: { "code": 4007, "message": "订单已超过支付期限，已自动取消", "data": null }
```

### 8.9 用户主动取消订单（order 服务，仅待支付订单）

```
POST /api/order/cancel/{orderNo}
Header: Authorization: Bearer {token}
说明：校验属主 + status=0；执行取消逻辑（状态置3、Redis回补/删标记、调goods回补DB库存）
```

### 8.10 管理员创建活动（经 gateway → scheduler/admin 转发 goods）

```
POST /api/admin/activity
Body: { "goodsId": 1, "goodsName": "无线蓝牙耳机（降噪款）", "goodsImg": "https://cdn.xinguo.com/goods/earbuds.jpg",
        "originalPrice": 299.00, "seckillPrice": 99.00, "stock": 100, "limitPerUser": 1,
        "startTime": "2026-09-25T12:00:00", "endTime": "2026-09-25T12:10:00" }
说明：admin 服务调 goods 内部接口 POST /internal/activity/create 落库 + 初始化 t_goods_stock +
立即预热一次 Redis（新活动用 activity.stock，见 R8/4.6）
```

### 8.11 秒杀日志查询（运营，经 gateway → scheduler/admin 转发 order）

```
GET /api/admin/log?activityId=&page=1&size=50
说明：admin 服务调 order 内部接口 GET /internal/order/logs 读取 t_seckill_log
```

### 8.12 用户注册（user 服务，游客 → 注册用户）

```
POST /api/user/register
Body: { "username": "alice", "password": "123456", "phone": "13800000000", "nickname": "阿离" }
Response 200: { "code": 0, "data": { "userId": 10001, "token": "..." } }
说明：密码 BCrypt 加密存储；注册成功自动创建钱包（t_wallet，初始余额 0）；username 重复返回 4008
```

### 8.13 用户登录/登出（user 服务）

```
POST /api/user/login
Body: { "username": "alice", "password": "123456" }
Response 200: { "code": 0, "data": { "token": "...", "userId": 10001, "nickname": "阿离", "balance": 0.00 } }
POST /api/user/logout（Header: Authorization: Bearer {token}）
说明：token 会话存 Redis（sa-token-dao-redis），各服务共享校验
```

### 8.14 服务间内部接口汇总（/internal/**，不对公网）

| 接口 | 提供方 | 调用方 | 说明 |
| ---- | ---- | ---- | ---- |
| GET /internal/activity/{id} | goods | order | 取活动详情（订单时校验时间，回填缓存） |
| POST /internal/stock/deduct | goods | order | 乐观锁扣真实库存 {goodsId,count}，失败返回冲突码 |
| POST /internal/stock/rollback | goods | order | 回补真实库存 {goodsId,count}（D8） |
| POST /internal/wallet/deduct | user | order | 条件扣款 {userId,amount,orderNo} |
| POST /internal/wallet/refund | user | order | 补偿退款（两阶段失败时） |
| GET /internal/order/timeout-list | order | scheduler | 列出到期待支付订单 |
| POST /internal/order/timeout-cancel | order | scheduler | 对单笔执行超时取消 |
| GET /internal/order/logs | order | scheduler(admin) | 秒杀日志分页查询 |
| POST /internal/activity/create | goods | scheduler(admin) | 创建活动（含预热） |

## 9. 核心业务时序（文字版时序图，微服务视角）

```
[gateway:8080] 用户点击"立即秒杀" POST /api/seckill/do/{activityId}
   → Redis+Lua 分布式限流 │ 不通过 → 4290（写日志 LIMIT_REJECT，经 order 或 gateway 落库均可，实现时定 gateway 只返回不入库）
   → 路由转发 → [order:8300]
       → Sa-Token 登录校验 → 401
       → 读 Redis 活动缓存校验时间（未命中调 goods 内部接口回填）→ 4003
       → Lua 扣 Redis 库存 + 用户标记 │ -1→4001，-2→4002
       → 生成 orderNo、写日志 REQUEST/STOCK_DEDUCT、发 MQ、返回 QUEUED
[order mq-consumer]
   → 插入 t_seckill_order(status=0, pay_deadline=+30min)（order 库）
       → 冲突(order_no 重复/部分索引重复) → 忽略
       → 成功 → 调 goods /internal/stock/deduct（乐观锁重试3次）
            → 失败 → 订单置 3 并告警
   → 发 30 分钟延迟消息（OrderTimeoutConsumer 触发取消）
[支付] POST /api/order/pay/{orderNo}（order）
   → 本地事务 + 锁行 → 校验属主/状态/期限(4004/4005/4006/4007)
   → 调 user /internal/wallet/deduct（阶段一）→ 写流水
   → 本地置 status=1（阶段二）→ 失败则调 wallet/refund 补偿 → 4006
[超时] 延迟消息 / scheduler 扫描兜底（调 order /internal/order/timeout-list → timeout-cancel）
   → order 本地事务置 status=2/3（条件更新防重复）
   → Redis INCR 回补 + DEL 用户标记（允许重抢 R5）
   → 调 goods /internal/stock/rollback 回补 DB 库存
   → 写日志 ORDER_TIMEOUT/ORDER_CANCEL
```

## 10. 关键代码要点

### 10.1 Lua脚本 — 扣减库存（order 服务）

```lua
-- KEYS[1]=库存key, KEYS[2]=用户购买标记key
-- ARGV[1]=用户ID, ARGV[2]=标记过期秒数
if redis.call('EXISTS', KEYS[2]) == 1 then
    return -2
end
local stock = tonumber(redis.call('GET', KEYS[1]))
if not stock or stock <= 0 then
    return -1
end
redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2])
return 1
```

### 10.2 Lua脚本 — 分布式令牌桶限流（gateway 服务，Redis 3.2 兼容实现）

```lua
-- KEYS[1] = seckill:ratelimit:{scope}:{key}   （Hash: {tokens, last_refill}）
-- ARGV[1] = 容量 capacity   ARGV[2] = 每毫秒速率 rate per ms   ARGV[3] = 当前时间戳(ms)   ARGV[4] = 本次请求消耗令牌数(通常1)
local now = tonumber(ARGV[3])
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[4])
local b = redis.call('HGETALL', KEYS[1])
local tokens
local last
if #b == 0 then
    tokens = capacity
    last = now
else
    tokens = tonumber(b[2])
    last = tonumber(b[4])
end
-- 按流逝时间补令牌
local elapsed = math.max(0, now - last)
tokens = math.min(capacity, tokens + elapsed * rate)
if tokens < cost then
    redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill', now)
    redis.call('PEXPIRE', KEYS[1], 5000)
    return 0
end
tokens = tokens - cost
redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill', now)
redis.call('PEXPIRE', KEYS[1], 5000)
return 1
```

> 限流作用对象：gateway 对 `/api/seckill/**`、`/api/order/pay/**` 等热点写接口按 **IP+接口** 限流（读写分离：读列表不限）。`seckill.ratelimit.qps=200`（单 IP 阈值，压测调高或改按 userId）。

### 10.3 乐观锁扣减真实库存（goods 服务）

```sql
UPDATE t_goods_stock
SET total_stock = total_stock - 1, version = version + 1
WHERE goods_id = #{goodsId} AND total_stock > 0 AND version = #{version};
-- 返回影响行数为0则重试（重新查询version后再次UPDATE），最多重试3次
```

### 10.4 钱包条件扣款（user 服务，防负数余额）

```sql
UPDATE t_wallet
SET balance = balance - #{amount}, updated_at = NOW()
WHERE user_id = #{userId} AND balance >= #{amount};
-- 影响行数为0 → 余额不足，返回4004。行锁保证原子
-- 扣款成功后重新 SELECT balance 写入流水 balance_after，保证对账数据准确
```

### 10.5 对账任务（可选增强，面试加分项）

```
每日凌晨：
  订单侧：order 库 t_seckill_order status=1 的 sum(price)，按 order_no 汇总
  钱包侧：user 库 t_wallet_flow type=1 的 sum(amount)，按 order_no 汇总
  按 order_no 交叉比对 → 不一致（如支付成功但流水缺失/多扣）→ 告警日志，人工排查
用途：回答"补偿式两阶段万一两边不一致怎么发现"——对账兜底
```

### 10.6 服务间调用重试与超时（网络边界共识）

- 服务间 HTTP 调用设置 **connect/read timeout=3s**，顺序请求（非并发场景）。
- **扣减类接口**（stock/deduct）
  ：失败可安全重试（幂等由 order 侧订单 status 条件更新保证）。
- **退款接口**（wallet/refund）：必须保证**幂等**（实现：t_wallet_flow 增加 type=3 对 order_no 的拦截，或退款前查 flow 是否存在）。

### 10.7 application.yml 关键配置片段（order 服务示例）

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/seckill_order?useUnicode=true&characterEncoding=utf8
    username: root
    password: 4399
  redis:
    host: localhost
    port: 6379
  rocketmq:
    name-server: localhost:9876
    producer:
      group: seckill-order-producer-group

seckill:
  order:
    pay-timeout-minutes: 30   # 支付期限（R4）
  ratelimit:
    qps: 200                  # 网关分布式限流阈值（单IP），压测时调高
```

> gateway/user/goods/scheduler 各有自己的 datasource/端口配置，仅连接不同库（gateway/scheduler 无库）。

## 11. 错误码表（唯一依据，编程不许新增）

| Code | 含义 |
| ---- | ---- |
| 0 | 成功 |
| 401 | 未登录/登录失效 |
| 4001 | 库存不足 |
| 4002 | 重复购买 |
| 4003 | 活动未开始或已结束 |
| 4004 | 钱包余额不足 |
| 4005 | 订单不存在或非本人 |
| 4006 | 订单状态不允许支付（已支付/已取消） |
| 4007 | 订单已超过支付期限 |
| 4008 | 用户名已存在（注册） |
| 4009 | 内部服务调用失败（客户端兜底展示"稍后重试"） |
| 4290 | 请求被限流 |
| 5000 | 系统内部错误 |

## 12. 边界场景清单（务必测试，业务数据取自第 2.1 节）

- [ ] 100件库存（活动1001），1000并发请求同时秒杀 → 最终成功订单数必须恰好等于100，库存不为负
- [ ] 同一用户在活动期间多次快速点击 → 只生成1个非取消态订单（Redis标记+部分唯一索引双重校验）
- [ ] 同一用户超时取消（status=2）后重新抢购 → **成功**（部分唯一索引放行，R5）；同时该用户还持有旧取消单（历史保留）
- [ ] 用户在钱包余额不足时支付 → 返回4004，订单保持待支付，不产生扣款流水
- [ ] 同一订单并发支付两次 → 只成功一次（钱包只扣一次款，流水只有一条）
- [ ] 支付与超时取消在临界 30 分钟并发 → 锁行+条件更新保证不会出现"钱包扣款成功但订单已取消"（若出现，补偿退款兜底）
- [ ] 订单落库后30分钟不支付 → 延迟消息/扫描取消，Redis回补+1、goods回补DB+1、用户标记删除，该用户可重抢
- [ ] user 服务扣款成功后 order 置状态失败 → 调 wallet/refund 补偿退钱，余额与流水一致
- [ ] 活动未开始时调用下单接口 → 返回4003，不进入Lua扣减逻辑
- [ ] MQ消费者宕机重启 → 未消费完的消息不丢失（RocketMQ持久化）
- [ ] Redis重启导致库存数据丢失 → goods 服务启动时以 DB t_goods_stock.total_stock 为准重新预热（进行中活动不超卖）
- [ ] 未登录调用秒杀/支付/钱包接口 → 401
- [ ] 网关分布式限流触发 → 返回4290（压测时把 qps 调到足够高避免误伤）

## 13. 测试与压测计划

1. **单元测试**：
   - Lua扣减：JUnit+多线程模拟100线程同时调用，断言最终库存不为负、无超卖
   - Lua限流：并发调用令牌桶，断言超出容量部分被拒
   - 钱包条件扣款：并发扣款，断言余额永不为负、流水条数准确
   - 部分唯一索引：同用户同活动插两行（0,0）→ 第二行失败；取消后（2）+（0）→ 成功
   - 支付幂等：同一订单连续支付两次，断言订单只置位一次、流水一条
2. **集成/契约测试**：
   - 服务间客户端（OrderClient/GoodsClient/UserClient）用 MockRestServiceServer 或本地启动多服务联调
3. **JMeter压测**（需先安装 JMeter，本机暂无）：
   - 线程组：500/1000/2000并发，持续10秒；activityId固定(1001)，userId递增
   - 场景A：纯抢购（QPS、响应时间、错误率、库存终值）
   - 场景B：抢购+支付混合（验证 wallet 扣款并发）
   - 说明：qps=200 限流是预期行为；验证高并发正确性时调高 seckill.ratelimit.qps（如5000）或压 SeckillService 层，否则大多数请求先被限流，测不到真实扣减能力
   - 建议把压测报告截图和数据留存，写简历时用真实数字替换示例数字

## 14. 验收清单

- [ ] 高并发下库存不会出现负数或超卖（100库存1000并发恰好100单）
- [ ] 重复点击/重复提交不会生成多个非取消态订单
- [ ] 钱包支付链路完整：充值→支付→跨服务两段扣款→流水，余额不足有明确提示，失败打补偿退款
- [ ] 超时/主动取消会回滚Redis与goods库存，且该用户可重新抢购（重抢成功）
- [ ] 5 服务 + 前端可本地一键启动，路由/限流/鉴权各司其职
- [ ] Vue3 前端：用户端（频道/秒杀/轮询/订单/钱包）+ 运营端（建活动/查日志）可用
- [ ] 服务日志能清晰看到一次秒杀请求完整链路（限流→扣减→MQ→落库→支付→流水）
- [ ] 有完整JMeter压测数据（真实QPS、错误率）
- [ ] 未登录/越权访问被正确拦截（401、4005）
- [ ] 可选未勾选项：预约提醒（R10）、布隆过滤器、对账任务（10.5）、Knife4j——不实现时保持未勾选

## 15. 面试可能追问的问题（自查用）

1. 为什么用Lua脚本而不是普通的INCR/DECR？
2. Redis 库存和数据库库存（分属不同服务库）如何保持最终一致？不一致怎么发现、怎么修复？
3. MQ 消息丢失或重复消费分别怎么处理？为什么 `order_no` 唯一键能幂等去重？
4. 如果 Redis 突然宕机，系统会发生什么？预热为什么不直接用 activity.stock？
5. 分布式限流令牌桶为什么用 Lua？和单机 Guava 比好在哪？
6. 微服务下钱包扣款和订单置状态为什么要两阶段？一致性怎么保证？补偿退款幂等怎么做？
7. 用户抢到但一直不支付，库存什么时候释放？会不会被一直占着？（延迟消息+扫描双机制）
8. 为什么"取消后可重抢"能实现？（部分唯一索引的数学原理：泛 Canceled 行 active_activity_id=NULL）
9. Seata/分布式事务有没有用？为什么没用？（本机无中间件，两阶段+对账足够，且为本面试项目重点）
10. 5 个服务间 REST 调用失败怎么办？需要考虑重试/超时/幂等吗？