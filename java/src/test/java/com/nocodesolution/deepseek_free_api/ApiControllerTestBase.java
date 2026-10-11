package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocodesolution.deepseek_free_api.services.AuthManager;
import com.nocodesolution.deepseek_free_api.services.DeepSeekClient;
import com.nocodesolution.deepseek_free_api.services.PowSolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Общий стенд HTTP-тестов серверного слоя (порт фикстур test_server.py):
 * реальные AuthManager/ChicoryPowSolver (WASM) заменены моками, DeepSeekClient —
 * фейком через бин-фабрику. Сети и браузера нет.
 */
public abstract class ApiControllerTestBase {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockitoBean
    protected AuthManager authManager;

    @MockitoBean
    protected PowSolver powSolver;

    @MockitoBean(name = "deepSeekClientFactory")
    protected Supplier<DeepSeekClient> clientFactory;

    protected FakeDeepSeekClient fake;

    @BeforeEach
    void setUpApiControllerTestBase() {
        fake = new FakeDeepSeekClient();
        when(clientFactory.get()).thenReturn(fake);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", "ready");
        status.put("has_token", true);
        when(authManager.statusMap()).thenReturn(status);
    }

    protected void useClient(FakeDeepSeekClient client) {
        this.fake = client;
        when(clientFactory.get()).thenReturn(client);
    }

    protected ResponseEntity<String> postChat(String json) {
        return postChat(json, null);
    }

    protected ResponseEntity<String> postChat(String json, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return rest.postForEntity("/v1/chat/completions", new HttpEntity<>(json, headers), String.class);
    }

    /** Удаление чата идёт после Done на виртуальном трейде — ждём с дедлайном. */
    protected void awaitDeleted(String sessionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (fake.deleted.contains(sessionId)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Чат " + sessionId + " не был удалён");
    }
}
