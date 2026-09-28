package com.xinguo.seckill.gateway.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * 网关上游转调客户端：
 * - 用 Apache HttpClient5（而非默认 HttpURLConnection）：后者对带 body 的 POST + 401 响应会抛
 *   HttpRetryException "cannot retry due to server authentication, in streaming mode"（T12 P1）。
 * - 连接/响应超时统一取 seckill.gateway.rest-timeout-ms；禁用自动重试（转发语义原样透传）。
 * - 4xx/5xx 视作正常响应原样返回（透传鉴权/业务语义，如 401 未登录、4001 库存不足），
 *   仅连接失败/超时等真正异常抛 RestClientException（由 ProxyFilter 兜底 5000）。
 */
@Configuration
public class GatewayRestConfig {

    private final GatewayProperties properties;

    public GatewayRestConfig(GatewayProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestTemplate gatewayRestTemplate() {
        long timeoutMs = properties.getRestTimeoutMs();
        CloseableHttpClient httpClient = HttpClients.custom()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMs))
                        .setConnectTimeout(Timeout.ofMilliseconds(timeoutMs))
                        .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                        .build())
                .build();
        RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return restTemplate;
    }
}