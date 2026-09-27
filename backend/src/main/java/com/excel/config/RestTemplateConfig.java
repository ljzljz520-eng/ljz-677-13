package com.excel.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;

/**
 * 调用国家平台使用的 RestTemplate
 */
@Configuration
@RequiredArgsConstructor
public class RestTemplateConfig {

    private final ReportQueueProperties properties;

    @Bean("nationalPlatformRestTemplate")
    public RestTemplate nationalPlatformRestTemplate(RestTemplateBuilder builder) {
        RestTemplate restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
        // 平台用 HTTP 4xx 表达业务校验失败、5xx 表达系统异常，均需拿到响应体落库，故不抛异常
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response)
                    throws IOException {
                // 仅视为传输成功，HTTP状态与业务结果统一由响应体判断
                return false;
            }
        });
        return restTemplate;
    }
}
