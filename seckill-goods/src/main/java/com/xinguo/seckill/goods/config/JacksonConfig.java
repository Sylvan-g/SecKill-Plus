package com.xinguo.seckill.goods.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * goods Jackson 配置（review P2-3）：注册 FlexibleLocalDateTimeDeserializer，
 * 使 LocalDateTime 字段同时接受 ISO 与空格时间格式，且不影响 JSON 序列化。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> builder.deserializers(new FlexibleLocalDateTimeDeserializer());
    }

    /** 供单测直接构造等效 ObjectMapper（避开 Spring 容器） */
    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
        mapper.registerModule(module);
        return mapper;
    }
}