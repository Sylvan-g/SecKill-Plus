package com.xinguo.seckill.goods.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 兼容双格式的 LocalDateTime 反序列化器（review P2-3）：
 * 主路径保持 ISO（"2026-09-24T00:00:00"），同时兼容业务常用空格格式（"2026-09-24 00:00:00"），
 * 避免 admin/前端提交空格格式时被 500 拒收。序列化行为不变（仍 ISO）。
 */
public class FlexibleLocalDateTimeDeserializer extends LocalDateTimeDeserializer {

    private static final DateTimeFormatter SPACE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public FlexibleLocalDateTimeDeserializer() {
        super(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext context) throws IOException {
        String text = p.getValueAsString();
        if (text != null && text.indexOf(' ') > 0) {
            return LocalDateTime.parse(text.trim(), SPACE_FORMAT);
        }
        return super.deserialize(p, context);
    }
}