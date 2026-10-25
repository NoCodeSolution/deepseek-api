package com.nocodesolution.deepseek_free_api.cli;

import com.nocodesolution.deepseek_free_api.exceptions.AuthStorageException;
import com.nocodesolution.deepseek_free_api.exceptions.BrowserAuthException;
import com.nocodesolution.deepseek_free_api.services.auth.AuthStorage;
import com.nocodesolution.deepseek_free_api.services.auth.BrowserAuth;
import java.io.IOException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * CLI-режимы: --manual, --import, --login, --connect.
 * Выполняются внутри Spring-контекста, поэтому подхватываются auth.* из application.properties.
 */
@Component
@Profile("cli")
public class CliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private static final Options OPTIONS = new Options()
            .addOption("h", "help", false, "Вывести справку")
            .addOption("m", "manual", false, "Инструкция по ручному экспорту сессии")
            .addOption("l", "login", false, "Выполнить вход (headless или интерактивный)")
            .addOption(Option.builder("i")
                    .longOpt("import")
                    .hasArgs()
                    .numberOfArgs(2)
                    .argName("cookies.json> <userToken")
                    .desc("Импортировать cookies и токен")
                    .build())
            .addOption(Option.builder("c")
                    .longOpt("connect")
                    .hasArg()
                    .argName("port")
                    .optionalArg(true)
                    .desc("Подключиться к запущенному Chrome по CDP-порту (по умолчанию 9222)")
                    .build());

    private final BrowserAuth browserAuth;
    private final AuthStorage authStorage;
    private final ApplicationContext context;

    public CliRunner(BrowserAuth browserAuth, AuthStorage authStorage, ApplicationContext context) {
        this.browserAuth = browserAuth;
        this.authStorage = authStorage;
        this.context = context;
    }

    public static boolean isCliRequest(String[] args) {
        try {
            CommandLine line = new DefaultParser().parse(OPTIONS, args, true);
            return line.hasOption("help")
                    || line.hasOption("manual")
                    || line.hasOption("import")
                    || line.hasOption("login")
                    || line.hasOption("connect");
        } catch (ParseException e) {
            return false;
        }
    }

    @Override
    public void run(String... args) {
        int exitCode = runCli(args);
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int runCli(String[] args) {
        CommandLine line;
        try {
            line = new DefaultParser().parse(OPTIONS, args);
        } catch (ParseException e) {
            log.error("Ошибка разбора аргументов: {}", e.getMessage());
            printHelp();
            return 1;
        }

        if (line.hasOption("help")) {
            printHelp();
            return 0;
        }
        if (line.hasOption("manual")) {
            AuthStorage.printManualInstructions();
            return 0;
        }
        if (line.hasOption("import")) {
            String[] values = line.getOptionValues("import");
            if (values == null || values.length < 2) {
                log.error("--import требует 2 аргумента: <cookies.json> \"<userToken>\"");
                return 1;
            }
            return runImport(values[0], values[1]);
        }
        if (line.hasOption("login")) {
            return runLogin();
        }
        if (line.hasOption("connect")) {
            String portValue = line.getOptionValue("connect", "9222");
            int port;
            try {
                port = Integer.parseInt(portValue);
            } catch (NumberFormatException e) {
                log.error("Невалидный порт для --connect: {}", portValue);
                return 1;
            }
            return runConnect(port);
        }
        printHelp();
        return 0;
    }

    private int runImport(String cookiesFile, String userToken) {
        try {
            authStorage.importCookies(cookiesFile, userToken);
        } catch (AuthStorageException | IOException exc) {
            log.error("Ошибка: {}", exc.getMessage());
            return 1;
        }
        log.info("Cookies и токен импортированы.");
        log.info("Запускайте: deepseek-free-api");
        return 0;
    }

    private int runLogin() {
        try {
            try {
                browserAuth.headlessCredentialsLogin();
                log.info("Авторизация DeepSeek сохранена.");
                return 0;
            } catch (BrowserAuthException exc) {
                log.info("Headless-логин не удался ({}), открываю окно...", exc.getMessage());
            } catch (Exception exc) {
                log.info("Headless-логин упал ({}), открываю окно...", exc.getClass().getSimpleName());
            }
            browserAuth.loginAndSaveAuth();
            log.info("Авторизация DeepSeek сохранена.");
            return 0;
        } catch (Exception exc) {
            log.error("Ошибка: {}", exc.getMessage());
            return 1;
        }
    }

    private int runConnect(int cdpPort) {
        try {
            browserAuth.connectToRunningChrome(cdpPort);
        } catch (Exception exc) {
            log.error("Ошибка: {}", exc.getMessage());
            return 1;
        }
        log.info("Подключение готово. Запускайте: deepseek-free-api");
        return 0;
    }

    private void printHelp() {
        new HelpFormatter().printHelp("deepseek-free-api", OPTIONS, true);
    }
}
