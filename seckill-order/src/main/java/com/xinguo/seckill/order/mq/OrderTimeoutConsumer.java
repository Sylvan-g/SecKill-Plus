package com.xinguo.seckill.order.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.order.service.OrderService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 超时取消延迟消息消费者（需求文档 4.4）：
 * 消费 {orderNo} 触发 order 服务超时取消（status=2），与 scheduler 扫描兜底形成双机制（3.4）。
 */
@Component
public class OrderTimeoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutConsumer.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    @Value("${seckill.mq.name-server}")
    private String nameServer;

    @Value("${seckill.mq.timeout-message-group}")
    private String consumerGroup;

    @Value("${seckill.mq.topic-timeout}")
    private String topic;

    private DefaultMQPushConsumer consumer;

    public OrderTimeoutConsumer(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            consumer = new DefaultMQPushConsumer(consumerGroup);
            consumer.setNamesrvAddr(nameServer);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            consumer.subscribe(topic, "*");
            consumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext ctx) -> {
                for (MessageExt ext : messages) {
                    String body = new String(ext.getBody(), StandardCharsets.UTF_8);
                    try {
                        JsonNode node = objectMapper.readTree(body);
                        String orderNo = node.path("orderNo").asText();
                        if (!orderNo.isEmpty()) {
                            // 未到支付期限（延迟档位就近映射提前触达/脏消息）则跳过，等待真正触发（review P2-5）
                            if (!orderService.deadlinePassed(orderNo)) {
                                continue;
                            }
                            // 幂等：cancel 内条件更新防重复；确定性业务失败（4005 订单不存在 / 4006 已支付或已取消）不再重试（review P1-2）
                            orderService.cancel(orderNo, null, OrderService.ST_TIMEOUT_CANCELLED);
                        }
                    } catch (BusinessException e) {
                        // 确定性业务失败 -> 消费成功，避免同一订单无限重投
                        log.warn("[mq][timeout-consumer] biz skip body={} code={} msg={}", body, e.getCode(), e.getMessage());
                    } catch (Exception e) {
                        log.error("[mq][timeout-consumer] cancel failed body={}", body, e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            log.info("[mq][timeout-consumer] started group={} topic={}", consumerGroup, topic);
        } catch (Exception e) {
            log.warn("[mq][timeout-consumer] start failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}