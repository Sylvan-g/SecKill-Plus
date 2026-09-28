package com.xinguo.seckill.order.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 服务间内部 HTTP 客户端（需求文档 8.14 / 3.2-3.4）：
 * order 调用 goods（扣/回补真实库存）、user（钱包条件扣款/补偿退款）。
 * 返回统一 Result{code, message, data}，code=0 表示成功。
 */
@Component
public class InternalClient {

    private static final Logger log = LoggerFactory.getLogger(InternalClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${seckill.internal.goods-base-url}")
    private String goodsBase;

    @Value("${seckill.internal.user-base-url}")
    private String userBase;

    public InternalClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** 调用结果（code/data 提取，避免强依赖 Result 泛型） */
    public static class InternalResult {
        public int code;
        public String message;
        public JsonNode data;

        public InternalResult(int code, String message, JsonNode data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }
    }

    /** POST /internal/stock/deduct 扣真实库存（goods，乐观锁）；返回 code，0 成功 */
    public InternalResult deductStock(Long goodsId, int count) {
        return postInternal(goodsBase, "/internal/stock/deduct", Map.of("goodsId", goodsId, "count", count));
    }

    /** POST /internal/stock/rollback 回补真实库存（goods，D8） */
    public InternalResult rollbackStock(Long goodsId, int count) {
        return postInternal(goodsBase, "/internal/stock/rollback", Map.of("goodsId", goodsId, "count", count));
    }

    /** GET /internal/activity/{id} 取活动详情（order 时间门禁缓存未命中时回填，需求 3.2） */
    public InternalResult getActivity(Long activityId) {
        try {
            String resp = restTemplate.getForObject(goodsBase + "/internal/activity/" + activityId, String.class);
            JsonNode node = objectMapper.readTree(resp);
            int code = node.path("code").asInt(-1);
            return new InternalResult(code, node.path("message").asText(), node.path("data"));
        } catch (Exception e) {
            log.error("[internal] getActivity failed activityId={}", activityId, e);
            return new InternalResult(5000, "内部服务调用失败:" + e.getMessage(), null);
        }
    }

    /** POST /internal/wallet/deduct 条件扣款（user，阶段一）；返回扣款后余额 */
    public InternalResult deductWallet(Long userId, BigDecimal amount, String orderNo) {
        return postInternal(userBase, "/internal/wallet/deduct",
                Map.of("userId", userId, "amount", amount, "orderNo", orderNo));
    }

    /** POST /internal/wallet/refund 补偿退款（user，两阶段兜底） */
    public InternalResult refundWallet(Long userId, BigDecimal amount, String orderNo) {
        return postInternal(userBase, "/internal/wallet/refund",
                Map.of("userId", userId, "amount", amount, "orderNo", orderNo));
    }

    private InternalResult postInternal(String base, String path, Map<String, Object> body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String resp = restTemplate.postForObject(base + path, entity, String.class);
            JsonNode node = objectMapper.readTree(resp);
            int code = node.path("code").asInt(-1);
            return new InternalResult(code, node.path("message").asText(), node.path("data"));
        } catch (Exception e) {
            log.error("[internal] {} call failed", path, e);
            return new InternalResult(5000, "内部服务调用失败:" + e.getMessage(), null);
        }
    }
}