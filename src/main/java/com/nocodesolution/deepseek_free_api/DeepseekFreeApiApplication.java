package com.nocodesolution.deepseek_free_api;

import com.nocodesolution.deepseek_free_api.cli.CliRunner;
import com.nocodesolution.deepseek_free_api.config.Auth;
import com.nocodesolution.deepseek_free_api.config.Cors;
import com.nocodesolution.deepseek_free_api.config.Deepseek;
import com.nocodesolution.deepseek_free_api.config.Privacy;
import com.nocodesolution.deepseek_free_api.config.Proxy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        Proxy.class,
        Cors.class,
        Deepseek.class,
        Auth.class,
        Privacy.class
})
public class DeepseekFreeApiApplication {

    public static void main(String[] args) {
        if (CliRunner.isCliRequest(args)) {
            SpringApplication app = new SpringApplication(DeepseekFreeApiApplication.class);
            app.setAdditionalProfiles("cli");
            System.exit(SpringApplication.exit(app.run(args)));
            return;
        }
        SpringApplication.run(DeepseekFreeApiApplication.class, args);
    }
}
