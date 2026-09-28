package com.xinguo.seckill.common.constant;

/**
 * Redis Key 常量与构造方法。Key 约定唯一依据见需求文档第 7 节，禁止另造。
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {
    }

    /** seckill:stock:{activityId} 活动剩余库存 */
    public static final String STOCK_PREFIX = "seckill:stock:";

    /** seckill:user:{activityId}:{userId} 用户已购标记 */
    public static final String USER_PREFIX = "seckill:user:";

    /** seckill:activity:{activityId} 活动详情缓存 */
    public static final String ACTIVITY_PREFIX = "seckill:activity:";

    /** seckill:bloom:activity 活动ID布隆过滤器 */
    public static final String BLOOM_ACTIVITY = "seckill:bloom:activity";

    /** seckill:ratelimit:{scope}:{key} 分布式令牌桶 */
    public static final String RATE_LIMIT_PREFIX = "seckill:ratelimit:";

    public static String stockKey(Object activityId) {
        return STOCK_PREFIX + activityId;
    }

    public static String userKey(Object activityId, Object userId) {
        return USER_PREFIX + activityId + ":" + userId;
    }

    public static String activityKey(Object activityId) {
        return ACTIVITY_PREFIX + activityId;
    }

    public static String rateLimitKey(Object scope, Object key) {
        return RATE_LIMIT_PREFIX + scope + ":" + key;
    }
}