package com.nocodesolution.deepseek_free_api.cli;

import com.nocodesolution.deepseek_free_api.services.auth.BrowserAuth;
import com.nocodesolution.deepseek_free_api.exceptions.BrowserAuthException;
import com.nocodesolution.deepseek_free_api.services.auth.AuthStorage;
import com.nocodesolution.deepseek_free_api.exceptions.AuthStorageException;
import java.io.IOException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI-режимы: --manual, --import, --login, --connect.
 * Объекты собираются вручную, без Spring-контекста; режимы не стартуют Tomcat.
 */
public final class CliRunner {

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

    private CliRunner() {
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

    /** Выполняет CLI-режим и возвращает exit-код. */
    public static int run(String[] args) {
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

    private static int runImport(String cookiesFile, String userToken) {
        try {
            CliAuthFactory.defaultStorage().importCookies(cookiesFile, userToken);
        } catch (AuthStorageException | IOException exc) {
            log.error("Ошибка: {}", exc.getMessage());
            return 1;
        }
        log.info("Cookies и токен импортированы.");
        log.info("Запускайте: deepseek-free-api");
        return 0;
    }

    private static int runLogin() {
        BrowserAuth browser = CliAuthFactory.defaultBrowser();
        String email = CliAuthFactory.env("DS_EMAIL");
        String password = CliAuthFactory.env("DS_PASSWORD");
        try {
            if (email != null && !email.isEmpty() && password != null && !password.isEmpty()) {
                try {
                    browser.headlessCredentialsLogin();
                    log.info("Авторизация DeepSeek сохранена.");
                    return 0;
                } catch (BrowserAuthException exc) {
                    log.info("Headless-логин не удался ({}), открываю окно...", exc.getMessage());
                } catch (Exception exc) {
                    // сырые исключения логируем по типу — не светим креды в stderr
                    log.info("Headless-логин упал ({}), открываю окно...", exc.getClass().getSimpleName());
                }
            }
            browser.loginAndSaveAuth();
            log.info("Авторизация DeepSeek сохранена.");
            return 0;
        } catch (Exception exc) {
            log.error("Ошибка: {}", exc.getMessage());
            return 1;
        }
    }

    private static int runConnect(int cdpPort) {
        try {
            CliAuthFactory.defaultBrowser().connectToRunningChrome(cdpPort);
        } catch (Exception exc) {
            log.error("Ошибка: {}", exc.getMessage());
            return 1;
        }
        log.info("Подключение готово. Запускайте: deepseek-free-api");
        return 0;
    }

    private static void printHelp() {
        new HelpFormatter().printHelp("deepseek-free-api", OPTIONS, true);
    }
}
