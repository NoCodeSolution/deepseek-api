package com.nocodesolution.deepseek_free_api.config;

import com.nocodesolution.deepseek_free_api.browser.BrowserAuth;
import com.nocodesolution.deepseek_free_api.browser.BrowserAuthService;
import com.nocodesolution.deepseek_free_api.config.Auth;
import com.nocodesolution.deepseek_free_api.config.Deepseek;
import com.nocodesolution.deepseek_free_api.config.Privacy;
import com.nocodesolution.deepseek_free_api.services.AuthManager;
import com.nocodesolution.deepseek_free_api.services.AuthStorage;
import com.nocodesolution.deepseek_free_api.services.CompletionService;
import com.nocodesolution.deepseek_free_api.services.Credentials;
import com.nocodesolution.deepseek_free_api.services.DeepSeekClient;
import com.nocodesolution.deepseek_free_api.services.ModelResolver;
import com.nocodesolution.deepseek_free_api.services.PowSolver;
import com.nocodesolution.deepseek_free_api.services.PromptSanitizer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** Сборка auth-подсистемы из properties (конструкторы классов — без Spring). */
@Configuration
public class ServiceConfig {

    @Bean
    public AuthStorage authStorage(Deepseek deepseek, Auth auth) {
        return new AuthStorage(Path.of(auth.dir()), deepseek.baseUrl());
    }

    @Bean
    public BrowserAuth browserAuth(AuthStorage authStorage, Deepseek deepseek, Auth auth) {
        return new BrowserAuthService(
                deepseek.baseUrl(),
                Path.of(auth.dir()).resolve("browser-profile"),
                auth.email(),
                auth.password(),
                auth.headlessLoginTimeoutS(),
                authStorage);
    }

    @Bean
    public AuthManager authManager(AuthStorage authStorage, BrowserAuth browserAuth, Auth auth) {
        return new AuthManager(
                authStorage,
                browserAuth,
                auth.interactiveLogin(),
                auth.email(),
                auth.password());
    }

    /** Сервер стартует в любом состоянии auth; startup не открывает окно. */
    @Bean
    public ApplicationRunner authManagerStartup(AuthManager authManager) {
        return args -> authManager.startup();
    }

    @Bean
    public ModelResolver modelResolver(Deepseek deepseek) {
        return new ModelResolver(deepseek);
    }

    @Bean
    public PromptSanitizer promptSanitizer(Privacy privacy) {
        List<String> patterns = privacy.maskPatterns() == null
                ? List.of()
                : Arrays.stream(privacy.maskPatterns().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        return new PromptSanitizer(privacy.maskSecrets(), patterns);
    }

    /** Фабрика клиентов со свежими кредами из AuthManager. */
    @Bean
    public Supplier<DeepSeekClient> deepSeekClientFactory(
            RestTemplate restTemplate, Deepseek deepseek, AuthManager authManager, PowSolver powSolver) {
        return () -> {
            Credentials creds = authManager.credentials().orElse(new Credentials("", ""));
            DeepSeekClient client = new DeepSeekClient(
                    restTemplate,
                    deepseek.baseUrl(),
                    deepseek.completionPath(),
                    deepseek.chatDeletePath(),
                    creds.cookieHeader(),
                    creds.token(),
                    powSolver);
            client.setDebug(deepseek.logSse());
            return client;
        };
    }

    @Bean(destroyMethod = "shutdown")
    public CompletionService completionService(
            AuthManager authManager,
            PromptSanitizer promptSanitizer,
            Deepseek deepseek,
            ModelResolver modelResolver,
            Supplier<DeepSeekClient> deepSeekClientFactory) {
        return new CompletionService(authManager, promptSanitizer, deepseek, modelResolver, deepSeekClientFactory);
    }
}
