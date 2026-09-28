package com.xinguo.seckill.goods.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityDetail;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityListItem;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityListResult;
import com.xinguo.seckill.goods.dto.ActivityDTO.CreateActivityRequest;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 活动服务：列表（8.1）/ 详情（8.2）/ 创建（8.14 internal create）+ Redis 库存与详情缓存。
 *
 * 约定：
 * - Redis 库存是实时闸门（seckill:stock:{activityId}），未预热时回退 DB（8.1 注释）。
 * - 详情缓存链路：缓存命中直接返回；未命中查 DB → 回填缓存（随机 TTL 600~900s 防雪崩，8.2）。
 * - 创建活动：落库 + 初始化 t_goods_stock（DB 兜底层）+ 立即按 activity.stock 预热 Redis（4.6）。
 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    /** 详情缓存 TTL 范围（秒）：600~900 随机，防缓存雪崩（需求文档 7 节） */
    private static final long CACHE_TTL_MIN_SEC = 600;
    private static final long CACHE_TTL_MAX_SEC = 900;

    private final SeckillActivityMapper activityMapper;
    private final GoodsStockMapper goodsStockMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ActivityService(SeckillActivityMapper activityMapper,
                           GoodsStockMapper goodsStockMapper,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper) {
        this.activityMapper = activityMapper;
        this.goodsStockMapper = goodsStockMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 8.1 活动列表：status 可空（全部），按开始时间升序分页；stock 取 Redis 实时库存，未预热回退 DB */
    public ActivityListResult list(Integer status, long page, long size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 10;
        }
        LambdaQueryWrapper<SeckillActivity> qw = new LambdaQueryWrapper<SeckillActivity>()
                .orderByAsc(SeckillActivity::getStartTime);
        if (status != null) {
            qw.eq(SeckillActivity::getStatus, status);
        }
        Page<SeckillActivity> p = activityMapper.selectPage(new Page<>(page, size), qw);

        List<ActivityListItem> list = new ArrayList<>();
        for (SeckillActivity a : p.getRecords()) {
            ActivityListItem item = new ActivityListItem();
            item.activityId = a.getId();
            item.goodsName = a.getGoodsName();
            item.originalPrice = a.getOriginalPrice();
            item.seckillPrice = a.getSeckillPrice();
            item.stock = readStock(a.getId(), a.getStock());
            item.limitPerUser = a.getLimitPerUser();
            item.startTime = a.getStartTime();
            item.endTime = a.getEndTime();
            item.status = a.getStatus();
            list.add(item);
        }
        return new ActivityListResult(list, p.getTotal(), LocalDateTime.now());
    }

    /** 8.2 活动详情：缓存(seckill:activity:{id}) -> 未命中查 DB -> 回填缓存；stock 取 Redis 实时库存 */
    public ActivityDetail detail(Long activityId) {
        String cacheJson = redisTemplate.opsForValue().get(RedisKeyConstant.activityKey(activityId));
        if (cacheJson != null) {
            ActivityDetail cached = parseDetailJson(cacheJson, activityId);
            if (cached != null) {
                return cached;
            }
        }

        SeckillActivity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(4005, "活动不存在");
        }

        ActivityDetail detail = toDetail(a);
        // 回填缓存（随机 TTL 600~900s 防雪崩）
        try {
            long ttl = ThreadLocalRandom.current().nextLong(CACHE_TTL_MIN_SEC, CACHE_TTL_MAX_SEC + 1);
            String json = objectMapper.writeValueAsString(detail);
            redisTemplate.opsForValue().set(RedisKeyConstant.activityKey(activityId), json, Duration.ofSeconds(ttl));
        } catch (JsonProcessingException e) {
            log.warn("serialize activity {} cache failed", activityId, e);
        }
        return detail;
    }

    /** 8.14 内部创建活动（scheduler(admin) 调用）：落库 + 初始化真实库存 + 立即预热一次 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateActivityRequest req) {
        validateCreate(req);

        SeckillActivity a = new SeckillActivity();
        a.setGoodsId(req.goodsId);
        a.setGoodsName(req.goodsName);
        a.setGoodsImg(req.goodsImg);
        a.setOriginalPrice(req.originalPrice);
        a.setSeckillPrice(req.seckillPrice);
        a.setStock(req.stock);
        a.setLimitPerUser(req.limitPerUser != null ? req.limitPerUser : 1);
        a.setStartTime(req.startTime);
        a.setEndTime(req.endTime);
        a.setStatus(statusOf(req.startTime, req.endTime));
        activityMapper.insert(a);
        Long activityId = a.getId();

        // 初始化 DB 兜底层真实库存（乐观锁 version=0）
        GoodsStock stock = new GoodsStock();
        stock.setGoodsId(req.goodsId);
        stock.setTotalStock(req.stock);
        stock.setVersion(0);
        goodsStockMapper.insert(stock);

        // 立即预热一次：新活动 Redis 无存量，直接用 activity.stock 安全（4.6）；TTL = 活动结束 + 1 天（文档 7 节）
        Duration stockTtl = a.getEndTime().isAfter(LocalDateTime.now())
                ? Duration.between(LocalDateTime.now(), a.getEndTime()).plusDays(1)
                : Duration.ofDays(1);
        redisTemplate.opsForValue().set(RedisKeyConstant.stockKey(activityId), String.valueOf(req.stock), stockTtl);
        return activityId;
    }

    /** 预热指定活动库存（StockPreheatTask 使用；以 DB total_stock 为准重建，R8） */
    public void preheatStock(Long activityId, int totalStock) {
        redisTemplate.opsForValue().set(RedisKeyConstant.stockKey(activityId), String.valueOf(totalStock));
    }

    /** 读取实时库存：优先 Redis；未预热回退 DB 活动表 stock（8.1 注释） */
    private Integer readStock(Long activityId, Integer dbStock) {
        String v = redisTemplate.opsForValue().get(RedisKeyConstant.stockKey(activityId));
        return v != null ? Integer.valueOf(v) : dbStock;
    }

    private ActivityDetail parseDetailJson(String json, Long activityId) {
        try {
            ActivityDetail d = objectMapper.readValue(json, ActivityDetail.class);
            // 缓存命中时 stock 仍须为实时值，覆盖为 Redis 当前库存（防缓存内旧库存）
            d.stock = readStock(activityId, d.stock);
            // serverTime 须为当前时间（缓存可能滞后数分钟，防前端时间对齐偏差，8.2）
            d.serverTime = LocalDateTime.now();
            return d;
        } catch (JsonProcessingException e) {
            log.warn("deserialize activity {} cache failed, fallback to DB", activityId, e);
            return null;
        }
    }

    private ActivityDetail toDetail(SeckillActivity a) {
        ActivityDetail d = new ActivityDetail();
        d.activityId = a.getId();
        d.goodsId = a.getGoodsId();
        d.goodsName = a.getGoodsName();
        d.goodsImg = a.getGoodsImg();
        d.originalPrice = a.getOriginalPrice();
        d.seckillPrice = a.getSeckillPrice();
        d.stock = readStock(a.getId(), a.getStock());
        d.limitPerUser = a.getLimitPerUser();
        d.startTime = a.getStartTime();
        d.endTime = a.getEndTime();
        d.serverTime = LocalDateTime.now();
        d.status = a.getStatus();
        return d;
    }

    private void validateCreate(CreateActivityRequest req) {
        if (req == null) {
            throw new BusinessException(5000, "请求体不能为空");
        }
        if (req.goodsId == null || req.goodsName == null || req.goodsName.trim().isEmpty()
                || req.originalPrice == null || req.seckillPrice == null
                || req.stock == null || req.stock <= 0
                || req.startTime == null || req.endTime == null) {
            throw new BusinessException(5000, "活动参数不完整");
        }
        if (!req.endTime.isAfter(req.startTime)) {
            throw new BusinessException(5000, "结束时间必须晚于开始时间");
        }
        if (req.seckillPrice.compareTo(BigDecimal.ZERO) <= 0
                || req.originalPrice.compareTo(req.seckillPrice) < 0) {
            throw new BusinessException(5000, "价格不合法：秒杀价必须大于0且不高于原价");
        }
    }

    private Integer statusOf(LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(start)) {
            return 0;
        }
        if (!now.isBefore(end)) {
            return 2;
        }
        return 1;
    }

    /** 供 StockPreheatTask 使用：列出 DB 中所有活动（重建 Redis 库存时按需过滤） */
    public List<SeckillActivity> listAll() {
        return activityMapper.selectList(new QueryWrapper<>());
    }
}