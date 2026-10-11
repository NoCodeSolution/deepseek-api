package com.nocodesolution.deepseek_free_api.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocodesolution.deepseek_free_api.config.AppProperties.Proxy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Проверка Bearer-ключа (порт proxy_api_key dependency из Python).
 * Без @Component: регистрация только явная, через FilterRegistrationBean
 * в WebConfig — иначе Spring Boot дополнительно вставляет любой Filter-бин
 * в общую цепочку, и фильтр оказывается в ней дважды.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private final Proxy proxy;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(Proxy proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = proxy.apiKey();
        if (!StringUtils.hasText(apiKey) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.equals("Bearer " + apiKey)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "authentication_error");
        error.put("message", "Invalid or missing API key");
        objectMapper.writeValue(response.getWriter(), objectMapper.createObjectNode().set("error", error));
    }
}
