package com.nocodesolution.deepseek_free_api.config;

import com.nocodesolution.deepseek_free_api.config.AppProperties.Deepseek;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * RestTemplate поверх java.net.http: в отличие от HttpURLConnection
     * (SimpleClientHttpRequestFactory), блокирующее чтение ответа прерывается
     * interrupt'ом — отмена completion реально обрывает чтение SSE.
     * Таймаут чтения 0 → без таймаута (бесконечный SSE-стрим).
     */
    @Bean
    public RestTemplate restTemplate(Deepseek deepseek) {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder();
        if (deepseek.connectTimeoutMs() > 0) {
            clientBuilder.connectTimeout(Duration.ofMillis(deepseek.connectTimeoutMs()));
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(clientBuilder.build());
        if (deepseek.readTimeoutMs() > 0) {
            factory.setReadTimeout(Duration.ofMillis(deepseek.readTimeoutMs()));
        }
        return new RestTemplate(factory);
    }
}
