package com.xinguo.seckill.goods.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.goods.dto.ActivityDTO.CreateActivityRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 时间格式契约测试（review P2-3）：LocalDateTime 字段需同时兼容 ISO 与空格两种格式。
 */
class JacksonDateTimeConfigTest {

    private final ObjectMapper mapper = JacksonConfig.mapper();

    @Test
    void parsesIsoFormat() throws Exception {
        String json = "{\"startTime\":\"2026-09-24T00:00:00\",\"endTime\":\"2026-09-30T23:59:59\"}";

        CreateActivityRequest req = mapper.readValue(json, CreateActivityRequest.class);

        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 0, 0), req.startTime);
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59, 59), req.endTime);
    }

    @Test
    void parsesSpaceFormat() throws Exception {
        String json = "{\"startTime\":\"2026-09-24 00:00:00\",\"endTime\":\"2026-09-30 23:59:59\"}";

        CreateActivityRequest req = mapper.readValue(json, CreateActivityRequest.class);

        assertEquals(LocalDateTime.of(2026, 9, 24, 0, 0, 0), req.startTime);
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59, 59), req.endTime);
    }
}