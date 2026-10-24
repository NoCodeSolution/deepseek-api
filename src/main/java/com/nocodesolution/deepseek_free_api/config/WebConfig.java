package com.nocodesolution.deepseek_free_api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocodesolution.deepseek_free_api.config.Cors;
import com.nocodesolution.deepseek_free_api.config.Proxy;
import com.nocodesolution.deepseek_free_api.support.ApiKeyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(Proxy proxy, ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyFilter(proxy, objectMapper));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(Cors cors) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (!StringUtils.hasText(cors.origin())) {
                    return;
                }
                registry.addMapping("/**")
                        .allowedOrigins(cors.origin())
                        .allowedMethods("*")
                        .allowedHeaders("*");
            }
        };
    }
}
