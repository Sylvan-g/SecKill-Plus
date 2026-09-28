package com.xinguo.seckill.scheduler.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 超时扫描兜底任务（需求文档 3.5 / 3.4 双机制）：
 * 周期执行两件事：
 *  1. 待补标记补偿：查 order 侧 ORDER_TIMEOUT_SEND_FAIL 标记的待支付未重发订单，逐笔重发延迟消息（T10）
 *  2. 超时扫描兜底：取首页过期待支付订单，逐笔调 timeout-cancel（防延迟消息丢失）
 * order 服务不可用时仅告警，不中断任务（下轮重试）。
 *
 * 多实例并发部署安全性（review P2-5）：order 侧 cancel/resendDelayed 以行锁 + 条件更新保证终态唯一，
 * 重复取消/重发各自幂等（库存/标记回滚只发生一次）；多实例仅多一次重复 HTTP 调用，无爆炸风险。
 */
@Component
public class OrderTimeoutScanTask {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScanTask.class);

    private static final int PAGE_SIZE = 50;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${seckill.internal.order-base-url}")
    private String orderBase;

    public OrderTimeoutScanTask(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** 每 60 秒一轮（可配置），首轮延迟 10s 待服务就绪 */
    @Scheduled(initialDelay = 10_000, fixedDelayString = "${seckill.scheduler.timeout-scan-fixed-delay-ms:60000}")
    public void scan() {
        resendSendFailMarked();
        cancelExpiredOrders();
    }

    /** 待补标记补偿：对延迟消息发送失败且未重发过的待支付订单重发延迟消息（T10） */
    private void resendSendFailMarked() {
        try {
            JsonNode node = get(orderBase + "/internal/order/timeout-send-fail");
            if (node == null) {
                return;
            }
            JsonNode arr = node.path("data");
            if (!arr.isArray()) {
                return;
            }
            for (JsonNode item : arr) {
                String orderNo = item.asText();
                if (orderNo.isEmpty()) {
                    continue;
                }
                try {
                    int code = post(orderBase + "/internal/order/timeout-resend", Map.of("orderNo", orderNo));
                    if (code != 0) {
                        log.warn("[scan] resend delayed failed orderNo={} code={}", orderNo, code);
                    }
                } catch (Exception e) {
                    // 单笔调用失败不拖累其余标记（review P2-4），下轮重试
                    log.warn("[scan] resend error orderNo={}, continue next (review P2-4)", orderNo, e);
                }
            }
        } catch (Exception e) {
            log.error("[scan] resendSendFailMarked failed", e);
        }
    }

    /**
     * 超时扫描兜底：每轮仅取首页过期待支付订单并逐笔取消。
     * 取消会把订单从结果集移除，OFFSET 翻页将发生偏移漂移导致漏扫（review P1-2）；
     * 故改为每轮首屏 + 下轮收敛；若需单轮全量可取游标分页（after_deadline, after_id）。
     */
    private void cancelExpiredOrders() {
        try {
            JsonNode node = get(orderBase + "/internal/order/timeout-list?page=1&size=" + PAGE_SIZE);
            if (node == null) {
                return;
            }
            JsonNode arr = node.path("data");
            if (!arr.isArray()) {
                return;
            }
            for (JsonNode item : arr) {
                String orderNo = item.path("orderNo").asText();
                if (orderNo.isEmpty()) {
                    continue;
                }
                try {
                    int code = post(orderBase + "/internal/order/timeout-cancel", Map.of("orderNo", orderNo));
                    if (code != 0) {
                        log.warn("[scan] timeout-cancel failed orderNo={} code={}", orderNo, code);
                    }
                } catch (Exception e) {
                    // 单笔调用失败不拖累其余订单（review P2-4），下轮重试
                    log.warn("[scan] timeout-cancel error orderNo={}, continue next (review P2-4)", orderNo, e);
                }
            }
        } catch (Exception e) {
            log.error("[scan] cancelExpiredOrders failed", e);
        }
    }

    private JsonNode get(String url) throws Exception {
        String resp = restTemplate.getForObject(url, String.class);
        JsonNode node = objectMapper.readTree(resp);
        if (node.path("code").asInt(-1) != 0) {
            log.warn("[scan] order internal code!=0 url={} code={} msg={}", url, node.path("code").asInt(), node.path("message").asText());
            return null;
        }
        return node;
    }

    private int post(String url, Map<String, String> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String resp = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
        JsonNode node = objectMapper.readTree(resp);
        return node.path("code").asInt(-1);
    }
}