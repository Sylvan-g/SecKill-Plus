package com.xinguo.seckill.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.dto.OrderMessageDTO;
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
 * 秒杀预订单 MQ 消费者（需求文档 3.2 落库段）：
 * 消费 {orderNo, activityId, userId, goodsId, price}，由 SeckillOrderService 落库 + 扣真实库存 + 发延迟消息。
 */
@Component
public class OrderMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumer.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    @Value("${seckill.mq.name-server}")
    private String nameServer;

    @Value("${seckill.mq.consumer-group}")
    private String consumerGroup;

    @Value("${seckill.mq.topic-seckill}")
    private String topic;

    private DefaultMQPushConsumer consumer;

    public OrderMessageConsumer(OrderService orderService, ObjectMapper objectMapper) {
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
            consumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext context) -> {
                for (MessageExt ext : messages) {
                    try {
                        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
                        OrderMessageDTO msg = objectMapper.readValue(body, OrderMessageDTO.class);
                        orderService.processSeckillMessage(msg);
                    } catch (Exception e) {
                        log.error("[mq][consumer] process msg failed body={}", ext.getBody(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            log.info("[mq][consumer] started group={} namesrv={} topic={}", consumerGroup, nameServer, topic);
        } catch (Exception e) {
            log.warn("[mq][consumer] start failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}