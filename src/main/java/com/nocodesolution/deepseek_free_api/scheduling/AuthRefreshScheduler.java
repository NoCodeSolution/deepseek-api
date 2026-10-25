package com.nocodesolution.deepseek_free_api.scheduling;

import com.nocodesolution.deepseek_free_api.services.auth.AuthManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Фоновое обновление авторизации. */
@Component
public class AuthRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthRefreshScheduler.class);

    private final AuthManager authManager;

    public AuthRefreshScheduler(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Scheduled(fixedDelayString = "#{${auth.refresh-interval-h:6} * 60 * 60 * 1000}")
    public void backgroundRefresh() {
        try {
            authManager.refresh(false);
        } catch (Exception exc) {
            log.warn("Фоновый refresh не удался: {}", exc.getMessage());
        }
    }
}
