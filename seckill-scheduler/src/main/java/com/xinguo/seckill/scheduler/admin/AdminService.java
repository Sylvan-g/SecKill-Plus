package com.xinguo.seckill.scheduler.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * admin 转发服务（需求文档 8.10 / 8.11）：
 * scheduler 无业务库，作为编排层把 admin 请求转发到对应服务的内部接口：
 * - POST /api/admin/activity -> goods POST /internal/activity/create（建活动含预热）
 * - GET  /api/admin/log       -> order GET /internal/order/logs（秒杀日志分页）
 * 上游业务码(100-9999)按码透传；下游非标准响应/服务不可达统一兜底 5000，
 * 且对外文案模糊化，不含内部 URL/异常细节（review P1-2）。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${seckill.internal.goods-base-url}")
    private String goodsBase;

    @Value("${seckill.internal.order-base-url}")
    private String orderBase;

    public AdminService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** 8.10 创建活动：参数校验 + 转发 goods /internal/activity/create，返回 activityId */
    public Long createActivity(JsonNode body) {
        validateCreateBody(body);
        JsonNode node = post(goodsBase + "/internal/activity/create", body);
        return node.path("data").asLong(0);
    }

    /**
     * 8.11 秒杀日志：转发 order /internal/order/logs（activityId 可选，分页）。
     * 单层透出 data（order logs 现返回 {list,total} 分页对象，见 review P1-3），供前端直接分页。
     */
    public JsonNode listLogs(Long activityId, long page, long size) {
        StringBuilder url = new StringBuilder(orderBase)
                .append("/internal/order/logs?page=").append(page)
                .append("&size=").append(size);
        if (activityId != null) {
            url.append("&activityId=").append(activityId);
        }
        JsonNode node = get(url.toString());
        return node.get("data");
    }

    /** 基础必填/边界校验（review P2-5）：缺关键字段不再依赖 goods 内部 NPE，提前给出可读文案 */
    private void validateCreateBody(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BusinessException(5000, "参数不合法：请求体需为 JSON 对象");
        }
        if (!body.hasNonNull("goodsId") || !body.hasNonNull("stock") || !body.hasNonNull("seckillPrice")) {
            throw new BusinessException(5000, "参数不合法：goodsId/stock/seckillPrice 为必填");
        }
        if (body.path("stock").asInt(0) <= 0) {
            throw new BusinessException(5000, "参数不合法：stock 必须大于 0");
        }
        if (body.path("seckillPrice").asDouble(0) <= 0) {
            throw new BusinessException(5000, "参数不合法：seckillPrice 必须大于 0");
        }
        if (!body.has("startTime") || !body.has("endTime")) {
            throw new BusinessException(5000, "参数不合法：startTime/endTime 为必填");
        }
        if (!body.has("goodsName") || body.path("goodsName").asText().isEmpty()) {
            throw new BusinessException(5000, "参数不合法：goodsName 为必填");
        }
    }

    private JsonNode get(String url) {
        try {
            String resp = restTemplate.getForObject(url, String.class);
            return check(resp, url);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 详情只进日志，对外模糊化，避免泄漏内部 URL/连接细节（review P1-2）
            log.error("[admin] internal call failed url={}", url, e);
            throw new BusinessException(5000, "内部服务暂不可用");
        }
    }

    private JsonNode post(String url, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            return check(resp, url);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[admin] internal call failed url={}", url, e);
            throw new BusinessException(5000, "内部服务暂不可用");
        }
    }

    /**
     * 校验下游响应：code==0 放行；合法业务码(100-9999)透传语义（含下游 message）；
     * 缺失/空/伪造码（如 -1）兜底 5000，不外泄伪码（review P1-2）。
     */
    private JsonNode check(String resp, String url) throws Exception {
        JsonNode node = objectMapper.readTree(resp);
        JsonNode codeNode = node.get("code");
        int code = (codeNode == null || codeNode.isNull()) ? -999 : codeNode.asInt(-999);
        if (code != 0) {
            if (code >= 100 && code <= 9999) {
                log.warn("[admin] internal code!=0 url={} code={} msg={}", url, code, node.path("message").asText());
                throw new BusinessException(code, node.path("message").asText("内部服务错误"));
            }
            log.warn("[admin] internal abnormal response url={} rawCode={}", url, code);
            throw new BusinessException(5000, "内部服务暂不可用");
        }
        return node;
    }
}