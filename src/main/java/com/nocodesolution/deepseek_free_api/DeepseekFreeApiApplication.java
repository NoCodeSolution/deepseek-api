package com.nocodesolution.deepseek_free_api;

import com.nocodesolution.deepseek_free_api.cli.CliRunner;
import com.nocodesolution.deepseek_free_api.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AppProperties.Proxy.class,
        AppProperties.Cors.class,
        AppProperties.Deepseek.class,
        AppProperties.Auth.class,
        AppProperties.Privacy.class
})
public class DeepseekFreeApiApplication {

    public static void main(String[] args) {
        if (CliRunner.isCliRequest(args)) {
            System.exit(CliRunner.run(args));
            return;
        }
        SpringApplication.run(DeepseekFreeApiApplication.class, args);
    }
}
