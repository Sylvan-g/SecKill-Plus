package com.xinguo.seckill.scheduler.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AdminService 单测（MockRestServiceServer 拦截 RestTemplate HTTP 层，验证转发语义）：
 * - 8.10 创建活动转发 goods create，返回 activityId
 * - 8.11 日志转发 order logs，activityId 可选透传
 * - 上游码透传 / 服务不可达兜底 5000
 */
class AdminServiceTest {

    private static final String GOODS = "http://goods:8200";
    private static final String ORDER = "http://order:8300";
    private static final String VALID_BODY =
            "{\"goodsName\":\"iPhone\",\"seckillPrice\":9.9,\"goodsId\":3,\"stock\":100,"
                    + "\"startTime\":\"2026-09-24T00:00:00\",\"endTime\":\"2026-09-30T23:59:59\"}";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private AdminService adminService;

    @BeforeEach
    void setUp() throws Exception {
        server = MockRestServiceServer.createServer(restTemplate);
        adminService = new AdminService(restTemplate, objectMapper);
        setValue("goodsBase", GOODS);
        setValue("orderBase", ORDER);
    }

    private void setValue(String field, String value) throws Exception {
        var f = AdminService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(adminService, value);
    }

    @Test
    void createActivityForwardsToGoodsAndReturnsId() throws Exception {
        server.expect(requestTo(GOODS + "/internal/activity/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"seckillPrice\":9.9")))
                .andRespond(withSuccess("{\"code\":0,\"data\":100}", MediaType.APPLICATION_JSON));

        Long id = adminService.createActivity(objectMapper.readTree(VALID_BODY));

        assertEquals(100L, id);
        server.verify();
    }

    @Test
    void createActivityPropagatesUpstreamCode() throws Exception {
        server.expect(requestTo(GOODS + "/internal/activity/create"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"code\":5000,\"message\":\"内部错误\"}", MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adminService.createActivity(objectMapper.readTree(VALID_BODY)));

        assertEquals(5000, ex.getCode());
        server.verify();
    }

    @Test
    void listLogsForwardsWithActivityIdAndPage() {
        server.expect(requestTo(ORDER + "/internal/order/logs?page=3&size=20&activityId=7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"list\":[{\"orderNo\":\"SK1\"}],\"total\":1}}", MediaType.APPLICATION_JSON));

        // 单层透出（review P1-1）：返回 data 分页对象 {list,total}（review P1-3），不再内嵌下游 code/message
        JsonNode node = adminService.listLogs(7L, 3, 20);

        assertTrue(node.get("list").isArray());
        assertEquals("SK1", node.get("list").get(0).path("orderNo").asText());
        assertEquals(1, node.path("total").asInt());
        server.verify();
    }

    @Test
    void listLogsSkipsActivityIdWhenNull() {
        server.expect(requestTo(ORDER + "/internal/order/logs?page=1&size=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"list\":[],\"total\":0}}", MediaType.APPLICATION_JSON));

        adminService.listLogs(null, 1, 50);

        server.verify();
    }

    @Test
    void listLogsThrows5000WhenOrderDown() {
        server.expect(requestTo(ORDER + "/internal/order/logs?page=1&size=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        BusinessException ex = assertThrows(BusinessException.class, () -> adminService.listLogs(null, 1, 50));

        assertEquals(5000, ex.getCode());
        // 对外文案模糊化，不泄漏内部细节（review P1-2）
        assertEquals("内部服务暂不可用", ex.getMessage());
        server.verify();
    }

    @Test
    void missingOrFakeCodeFallsBackTo5000() {
        // 下游返回缺 code 的非标准响应：不再透传伪码 -999，兜底 5000（review P1-2）
        server.expect(requestTo(GOODS + "/internal/activity/create"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                adminService.createActivity(objectMapper.readTree(VALID_BODY)));

        assertEquals(5000, ex.getCode());
        assertEquals("内部服务暂不可用", ex.getMessage());
        server.verify();
    }

    @Test
    void createActivityRejectsMissingMandatoryParams() {
        // 参数校验在发请求前拦截（review P2-5），不再依赖 goods 内部 NPE
        BusinessException ex = assertThrows(BusinessException.class, () ->
                adminService.createActivity(objectMapper.readTree("{\"goodsName\":\"X\"}")));

        assertEquals(5000, ex.getCode());
        server.verify(); // 未发起任何对账单请求
    }
}