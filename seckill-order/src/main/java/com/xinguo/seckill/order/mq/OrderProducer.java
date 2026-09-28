package com.xinguo.seckill.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.dto.OrderMessageDTO;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

/**
 * 秒杀预订单消息生产者（需求文档 3.2 / 8.3）：
 * Lua 扣减成功后发送 {orderNo, activityId, userId, goodsId, price}，由本服务消费者异步落库。
 * 本 ticket 只负责生产；消费（QueueConsumer）在后续 ticket 实现。
 */
@Component
public class OrderProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderProducer.class);

    private final ObjectMapper objectMapper;

    @Value("${seckill.mq.name-server}")
    private String nameServer;

    @Value("${seckill.mq.producer-group}")
    private String producerGroup;

    @Value("${seckill.mq.topic-seckill}")
    private String topic;

    private DefaultMQProducer producer;

    public OrderProducer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        // 外网/本机 MQ 多网卡时强制走内网 IP（避免 broker 返回 127.0.1.1 连不上）
        producer.setClientIP("127.0.0.1");
        producer.setSendMsgTimeout(3000);
        try {
            producer.start();
            log.info("[mq][producer] started group={} namesrv={} topic={}", producerGroup, nameServer, topic);
        } catch (Exception e) {
            // 启动失败不阻塞服务（本机未启 RocketMQ 时下单仍能走本地兜底日志），后续发送时按配置决定
            log.warn("[mq][producer] start failed: {}", e.getMessage());
            producer = null;
        }
    }

    /**
     * 发送预订单消息；返回是否成功。
     * 失败（含 producer 未启动）返回 false，由调用方回滚 Redis 库存/标记，保证"Lua 已扣未落库"不泄漏。
     */
    public boolean send(OrderMessageDTO msg) {
        try {
            if (producer == null) {
                log.warn("[mq][producer] not started, send failed orderNo={}", msg.getOrderNo());
                return false;
            }
            byte[] body = objectMapper.writeValueAsBytes(msg);
            Message message = new Message(topic, body);
            producer.send(message);
            log.info("[mq][producer] sent orderNo={}", msg.getOrderNo());
            return true;
        } catch (Exception e) {
            log.error("[mq][producer] send failed orderNo={}", msg.getOrderNo(), e);
            return false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}