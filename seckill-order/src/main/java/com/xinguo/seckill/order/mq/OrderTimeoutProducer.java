package com.xinguo.seckill.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 超时取消延迟消息生产者（需求文档 3.4 / 4.4 / 3.4 双机制）：
 * 订单落库后发送延迟消息，OrderTimeoutConsumer 消费后触发超时取消。
 * 延迟档位依据 pay-timeout-minutes 在 RocketMQ 默认 18 级延迟中就近映射（P2-5 修复：
 * 不再写死 30min，档位与配置脱钩；发送失败返回 false，由 OrderService 落待补标记、scheduler 兜底）。
 * RocketMQ 默认延迟级别：1s,5s,10s,30s,1m,2m,3m,4m,5m,6m,7m,8m,9m,10m,20m,30m,1h,2h。
 */
@Component
public class OrderTimeoutProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutProducer.class);

    /** 支持的支付期限(分钟) -> RocketMQ 默认延迟级别；取"不小于配置的最早档位"，避免提前取消 */
    private static final int[] SUPPORTED_MINUTES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 60, 120};
    private static final int[] SUPPORTED_LEVELS = {5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18};

    private final ObjectMapper objectMapper;

    @Value("${seckill.mq.name-server}")
    private String nameServer;

    @Value("${seckill.mq.producer-group}")
    private String producerGroup;

    @Value("${seckill.mq.topic-timeout}")
    private String timeoutTopic;

    private DefaultMQProducer producer;

    public OrderTimeoutProducer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.setClientIP("127.0.0.1");
        producer.setSendMsgTimeout(3000);
        try {
            producer.start();
            log.info("[mq][timeout-producer] started topic={}", timeoutTopic);
        } catch (Exception e) {
            log.warn("[mq][timeout-producer] start failed: {}", e.getMessage());
            producer = null;
        }
    }

    /**
     * 发送延迟消息，消息体 {orderNo}。
     * 返回 false 表示发送失败（MQ 不可用/档位不支持），由调用方落待补标记并依赖 scheduler 扫描兜底（3.4 双机制）。
     */
    public boolean sendDelayed(String orderNo, int delayMinutes) {
        int delayLevel = resolveDelayLevel(delayMinutes);
        if (delayLevel <= 0) {
            log.warn("[mq][timeout-producer] unsupported delay minutes {} for orderNo={}, rely on scheduler", delayMinutes, orderNo);
            return false;
        }
        try {
            if (producer == null) {
                log.warn("[mq][timeout-producer] not started, drop delayed msg orderNo={}", orderNo);
                return false;
            }
            byte[] body = objectMapper.writeValueAsBytes(Map.of("orderNo", orderNo));
            Message message = new Message(timeoutTopic, body);
            message.setDelayTimeLevel(delayLevel);
            producer.send(message);
            log.info("[mq][timeout-producer] sent delayed orderNo={} delayMinutes={} delayLevel={}", orderNo, delayMinutes, delayLevel);
            return true;
        } catch (Exception e) {
            log.error("[mq][timeout-producer] send delayed failed orderNo={}", orderNo, e);
            return false;
        }
    }

    /** 将支付期限(分钟)映射为最近的支持延迟级别（不小于配置，避免提前触发超时取消） */
    private int resolveDelayLevel(int delayMinutes) {
        for (int i = 0; i < SUPPORTED_MINUTES.length; i++) {
            if (SUPPORTED_MINUTES[i] >= delayMinutes) {
                return SUPPORTED_LEVELS[i];
            }
        }
        return -1;
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}