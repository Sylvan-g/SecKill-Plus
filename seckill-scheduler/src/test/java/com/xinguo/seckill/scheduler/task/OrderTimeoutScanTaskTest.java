package com.xinguo.seckill.scheduler.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OrderTimeoutScanTask 单测（MockRestServiceServer 拦截真实 RestTemplate 的 HTTP 层）：
 * - 超时扫描：分页拉取过期待支付订单 -> 逐笔 timeout-cancel
 * - 待补标记补偿：ORDER_TIMEOUT_SEND_FAIL 标记订单 -> 逐笔 timeout-resend
 * - order 服务不可用时不抛异常，任务存活等待下轮重试
 */
class OrderTimeoutScanTaskTest {

    private static final String BASE = "http://order:8300";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OrderTimeoutScanTask task;

    @BeforeEach
    void setUp() throws Exception {
        server = MockRestServiceServer.createServer(restTemplate);
        task = new OrderTimeoutScanTask(restTemplate, objectMapper);
        var f = OrderTimeoutScanTask.class.getDeclaredField("orderBase");
        f.setAccessible(true);
        f.set(task, BASE);
    }

    private String orders(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"orderNo\":\"SK").append(i).append("\"}");
        }
        return sb.append(']').toString();
    }

    private void expectGet(String url, String body) {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectPost(String url, int times, String body) {
        for (int i = 0; i < times; i++) {
            server.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
    }

    @Test
    void eachScanCancelsOnlyFirstPageOfExpired() {
        // 每轮仅处理首页 50 条（取消会移除结果集元素，OFFSET 翻页会漂移，review P1-2）；
        // 不请求 page=2：若实现翻页将触发 unexpected request -> verify 失败（防回归）
        expectGet(BASE + "/internal/order/timeout-send-fail", "{\"code\":0,\"data\":[]}");
        expectGet(BASE + "/internal/order/timeout-list?page=1&size=50",
                "{\"code\":0,\"data\":" + orders(50) + "}");
        expectPost(BASE + "/internal/order/timeout-cancel", 50, "{\"code\":0}");

        task.scan();

        server.verify();
    }

    @Test
    void continuesAfterSinglePostException() {
        // 第一笔 POST 抛 5xx：单笔隔离（review P2-4），不拖累第二笔
        expectGet(BASE + "/internal/order/timeout-send-fail", "{\"code\":0,\"data\":[]}");
        expectGet(BASE + "/internal/order/timeout-list?page=1&size=50",
                "{\"code\":0,\"data\":[{\"orderNo\":\"SKA\"},{\"orderNo\":\"SKB\"}]}");
        server.expect(requestTo(BASE + "/internal/order/timeout-cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
        expectPost(BASE + "/internal/order/timeout-cancel", 1, "{\"code\":0}");

        assertDoesNotThrow(() -> task.scan());

        server.verify();
    }

    @Test
    void resendsMarkedOrders() {
        expectGet(BASE + "/internal/order/timeout-send-fail", "{\"code\":0,\"data\":[\"SK1\",\"SK2\"]}");
        expectPost(BASE + "/internal/order/timeout-resend", 2, "{\"code\":0}");
        expectGet(BASE + "/internal/order/timeout-list?page=1&size=50", "{\"code\":0,\"data\":[]}");

        task.scan();

        server.verify();
    }

    @Test
    void survivesOrderServiceError() {
        // order 服务返回 500：task 内部 catch，不抛不中断，等下轮重试
        server.expect(requestTo(BASE + "/internal/order/timeout-send-fail"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());
        server.expect(requestTo(BASE + "/internal/order/timeout-list?page=1&size=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertDoesNotThrow(() -> task.scan());

        server.verify();
    }

    @Test
    void continuesOnBusinessFailures() {
        // timeout-cancel 对已非待支付订单返回 4006：视为告警跳过，后序订单继续处理
        expectGet(BASE + "/internal/order/timeout-send-fail", "{\"code\":0,\"data\":[]}");
        expectGet(BASE + "/internal/order/timeout-list?page=1&size=50",
                "{\"code\":0,\"data\":[{\"orderNo\":\"SKA\"},{\"orderNo\":\"SKB\"}]}");
        expectPost(BASE + "/internal/order/timeout-cancel", 1, "{\"code\":0}");
        expectPost(BASE + "/internal/order/timeout-cancel", 1, "{\"code\":4006,\"message\":\"已支付或已取消\"}");

        assertDoesNotThrow(() -> task.scan());

        server.verify();
    }
}