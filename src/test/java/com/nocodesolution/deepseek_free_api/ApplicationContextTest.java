package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nocodesolution.deepseek_free_api.services.auth.BrowserAuth;
import com.nocodesolution.deepseek_free_api.controllers.ApiController;
import com.nocodesolution.deepseek_free_api.services.pow.ChicoryPowSolver;
import com.nocodesolution.deepseek_free_api.services.completion.CompletionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Регрессионный тест сборки полного Spring-контекста с реальными бинами
 * (поймал баг «No default constructor» в ChicoryPowSolver, невидимый для
 * контроллер-тестов из-за @MockitoBean). BrowserAuth замокан, чтобы
 * AuthManager.startup не открывал браузер; WASM грузится лениво, сети нет.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationContextTest {

    @MockitoBean
    BrowserAuth browserAuth;

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsWithRealBeans() {
        assertNotNull(context.getBean(ChicoryPowSolver.class));
        assertNotNull(context.getBean(CompletionService.class));
        assertNotNull(context.getBean(ApiController.class));
    }
}
