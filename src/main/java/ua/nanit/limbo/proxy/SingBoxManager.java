package ua.nanit.limbo.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import ua.nanit.limbo.server.Log;

/**
 * Manages sing-box proxy via JNA native .so loading (eooce/sbx-native).
 *
 * Downloads sbx.so from https://{arch}.31888.xyz, loads it via JNA,
 * and calls StartSingBox() / StopSingBox() — no child processes.
 */
public final class SingBoxManager {

    private static NativeLibrary sbxLib;
    private static Function stopSingBox;
    private static boolean running = false;

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static String publicRealityKey = "";

    private SingBoxManager() {}

    // ==================== JNA Native Service ====================

    /**
     * Downloads sbx.so (if not cached), loads via JNA, and starts sing-box
     * in a background daemon thread.
     */
    public static void start(Map<String, String> env) throws Exception {
        String arch = detectArch();
        String baseUrl = "https://" + arch + ".31888.xyz";
        Path libPath = downloadLibrary(baseUrl + "/sbx.so", "sbx.so", env);

        // Generate config JSON on disk
        Path configPath = generateConfig(env);

        Log.info("[SBX] Loading native library: " + libPath);
        sbxLib = NativeLibrary.getInstance(libPath.toString());
        Function startFn = sbxLib.getFunction("StartSingBox");
        stopSingBox = sbxLib.getFunction("StopSingBox");

        // Build payload JSON
        JsonObject payload = new JsonObject();
        payload.addProperty("config", configPath.toAbsolutePath().toString().replace("\\", "/"));
        payload.addProperty("workingDir", ".");
        payload.addProperty("disableColor", true);
        String payloadJson = GSON.toJson(payload);

        Log.info("[SBX] Starting sing-box native...");
        Thread t = new Thread(() -> {
            try {
                int code = startFn.invokeInt(new Object[]{payloadJson});
                Log.info("[SBX] sing-box native exited with code " + code);
            } catch (Exception e) {
                Log.warn("[SBX] sing-box native error: " + e.getMessage());
            }
        }, "sbx-native");
        t.setDaemon(true);
        t.start();
        running = true;
        Log.info("[SBX] sing-box native started");
    }

    /**
     * Stops sing-box by calling StopSingBox() via JNA.
     */
    public static void shutdown() {
        if (running && stopSingBox != null) {
            try {
                int code = stopSingBox.invokeInt(new Object[]{});
                running = false;
                Log.info("[SBX] sing-box stopped with code " + code);
            } catch (Exception e) {
                Log.warn("[SBX] Error stopping sing-box: " + e.getMessage());
            }
        }
    }

    // ==================== .so Library Download ====================

    /**
     * Downloads a native .so library from URL if not already cached in FILE_PATH.
     */
    private static Path downloadLibrary(String url, String fileName, Map<String, String> env) throws IOException {
        String filePath = env.getOrDefault("FILE_PATH", "world");
        Path dir = Paths.get(filePath).normalize();
        Path target = dir.resolve(fileName);

        if (target.toFile().exists()) {
            Log.info("[SBX] Using cached native library: " + target);
            return target;
        }

        Files.createDirectories(dir);
        Path tmp = dir.resolve(fileName + ".download");

        Log.info("[SBX] Downloading " + url + " -> " + target);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);
            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " for " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(tmp) == 0) {
                Files.deleteIfExists(tmp);
                throw new IOException("Downloaded library is empty: " + url);
            }
            String expectedSha = env.getOrDefault("SBX_LIB_SHA256", "").trim();
            if (!expectedSha.isEmpty()) {
                String actual = sha256Hex(tmp);
                if (!expectedSha.equalsIgnoreCase(actual)) {
                    Files.deleteIfExists(tmp);
                    throw new IOException("sbx.so SHA-256 mismatch (expected " + expectedSha + ", got " + actual + ")");
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            if (!target.toFile().setExecutable(true, false)) {
                Log.warn("[SBX] Failed to set executable on " + target);
            }
            Log.info("[SBX] Downloaded " + fileName + " (" + target.toFile().length() + " bytes)");
        } finally {
            conn.disconnect();
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
        return target;
    }

    /**
     * Detects the CPU architecture for .so download URL.
     */
    private static String detectArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            return "amd64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        } else {
            throw new RuntimeException("Unsupported architecture: " + arch + " (only amd64/arm64 supported by sbx-native)");
        }
    }
    private static boolean hasOpenSSL() {
        try {
            int exit = new ProcessBuilder("openssl", "version")
                .redirectErrorStream(true).start().waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }


    // ==================== Sing-Box Config Generation ====================

    /**
     * Generates a sing-box JSON configuration file and returns its path.
     */
    public static Path generateConfig(Map<String, String> env) throws Exception {
        String uuid = env.getOrDefault("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b383");
        String hy2Port = env.getOrDefault("HY2_PORT", "");
        String tuicPort = env.getOrDefault("TUIC_PORT", "");
        String realityPort = env.getOrDefault("REALITY_PORT", "");
        String s5Port = env.getOrDefault("S5_PORT", "");
        String argoDomain = env.getOrDefault("ARGO_DOMAIN", "");
        String argoPort = env.getOrDefault("ARGO_PORT", "8001");
        String cfIp = env.getOrDefault("CFIP", "cdns.doon.eu.org");
        String realityPrivateKey = env.getOrDefault("REALITY_PRIVATE_KEY", "");
        String realityShortId = env.getOrDefault("REALITY_SHORT_ID", "");

        String serverAddr = argoDomain.isEmpty() ? cfIp : argoDomain;

        // Generate self-signed cert for TLS protocols
        Path certDir = Paths.get(System.getProperty("java.io.tmpdir"), "certs");
        Files.createDirectories(certDir);
        Path certFile = certDir.resolve("cert.pem");
        Path keyFile = certDir.resolve("key.pem");
        if (!certFile.toFile().exists() && hasOpenSSL()) {
            Log.info("[SBX] Generating self-signed certificate...");
            Log.info("[SBX] Generating self-signed certificate...");
            try {
                ProcessBuilder pb1 = new ProcessBuilder("openssl", "ecparam", "-genkey", "-name", "prime256v1",
                    "-out", keyFile.toAbsolutePath().toString());
                pb1.redirectErrorStream(true);
                Process keyGen = pb1.start();
                int keyExit = keyGen.waitFor();
                if (keyExit != 0) {
                    throw new IOException("openssl ecparam exited with code " + keyExit);
                }

                ProcessBuilder pb2 = new ProcessBuilder("openssl", "req", "-new", "-x509", "-days", "3650",
                    "-key", keyFile.toAbsolutePath().toString(),
                    "-out", certFile.toAbsolutePath().toString(),
                    "-subj", "/CN=bing.com");
                pb2.redirectErrorStream(true);
                Process certGen = pb2.start();
                int certExit = certGen.waitFor();
                if (certExit != 0) {
                    throw new IOException("openssl req exited with code " + certExit);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while generating certificate");
            }
        }

        String certPath = certFile.toAbsolutePath().toString().replace("\\", "/");
        String keyPath = keyFile.toAbsolutePath().toString().replace("\\", "/");

        JsonArray inbounds = new JsonArray();

        // VMess WS inbound
        String disableArgo = env.getOrDefault("DISABLE_ARGO", "false");
        if (!"true".equalsIgnoreCase(disableArgo)) {
            JsonObject transport = new JsonObject();
            transport.addProperty("type", "ws");
            transport.addProperty("path", "/vmess-argo");
            transport.addProperty("max_early_data", 2560);
            transport.addProperty("early_data_header_name", "Sec-WebSocket-Protocol");

            JsonObject user = new JsonObject();
            user.addProperty("uuid", uuid);
            user.addProperty("alterId", 0);
            JsonArray users = new JsonArray();
            users.add(user);

            JsonObject vmessInbound = new JsonObject();
            vmessInbound.addProperty("type", "vmess");
            vmessInbound.addProperty("tag", "vmess-ws");
            vmessInbound.addProperty("listen", "0.0.0.0");
            vmessInbound.addProperty("listen_port", Integer.parseInt(argoPort));
            vmessInbound.add("users", users);
            vmessInbound.add("transport", transport);
            inbounds.add(vmessInbound);
        }

        // SOCKS5 inbound
        if (!s5Port.isEmpty()) {
            for (String port : s5Port.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    JsonObject user = new JsonObject();
                    user.addProperty("username", uuid.substring(0, 8));
                    user.addProperty("password", uuid.substring(uuid.length() - 12));
                    JsonArray users = new JsonArray();
                    users.add(user);

                    JsonObject s5 = new JsonObject();
                    s5.addProperty("type", "socks");
                    s5.addProperty("tag", "socks5-in");
                    s5.addProperty("listen", "0.0.0.0");
                    s5.addProperty("listen_port", Integer.parseInt(port));
                    s5.add("users", users);
                    inbounds.add(s5);
                }
            }
        }

        // Hysteria2 inbound
        if (!hy2Port.isEmpty()) {
            for (String port : hy2Port.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    JsonObject user = new JsonObject();
                    user.addProperty("password", uuid);
                    JsonArray users = new JsonArray();
                    users.add(user);

                    JsonArray alpn = new JsonArray();
                    alpn.add("h3");

                    JsonObject tls = new JsonObject();
                    tls.addProperty("enabled", true);
                    tls.add("alpn", alpn);
                    tls.addProperty("min_version", "1.3");
                    tls.addProperty("max_version", "1.3");
                    tls.addProperty("certificate_path", certPath);
                    tls.addProperty("key_path", keyPath);

                    JsonObject h2 = new JsonObject();
                    h2.addProperty("type", "hysteria2");
                    h2.addProperty("tag", "hysteria2");
                    h2.addProperty("listen", "0.0.0.0");
                    h2.addProperty("listen_port", Integer.parseInt(port));
                    h2.add("users", users);
                    h2.addProperty("ignore_client_bandwidth", false);
                    h2.addProperty("masquerade", "https://bing.com");
                    h2.add("tls", tls);
                    inbounds.add(h2);
                }
            }
        }

        // TUIC inbound
        if (!tuicPort.isEmpty()) {
            for (String port : tuicPort.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    JsonObject user = new JsonObject();
                    user.addProperty("uuid", uuid);
                    user.addProperty("password", uuid);
                    JsonArray users = new JsonArray();
                    users.add(user);

                    JsonArray alpn = new JsonArray();
                    alpn.add("h3");

                    JsonObject tls = new JsonObject();
                    tls.addProperty("enabled", true);
                    tls.add("alpn", alpn);
                    tls.addProperty("certificate_path", certPath);
                    tls.addProperty("key_path", keyPath);

                    JsonObject tuic = new JsonObject();
                    tuic.addProperty("type", "tuic");
                    tuic.addProperty("tag", "tuic");
                    tuic.addProperty("listen", "0.0.0.0");
                    tuic.addProperty("listen_port", Integer.parseInt(port));
                    tuic.add("users", users);
                    tuic.addProperty("congestion_control", "bbr");
                    tuic.addProperty("zero_rtt_handshake", false);
                    tuic.add("tls", tls);
                    inbounds.add(tuic);
                }
            }
        }

        // VLESS Reality inbound
        if (!realityPort.isEmpty() && !realityPrivateKey.isEmpty()) {
            String realityServer = env.getOrDefault("REALITY_DEST", "www.google.com");
            for (String port : realityPort.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    JsonObject user = new JsonObject();
                    user.addProperty("uuid", uuid);
                    user.addProperty("flow", "xtls-rprx-vision");
                    JsonArray users = new JsonArray();
                    users.add(user);

                    JsonObject handshake = new JsonObject();
                    handshake.addProperty("server", realityServer);
                    handshake.addProperty("server_port", 443);

                    JsonObject reality = new JsonObject();
                    reality.addProperty("enabled", true);
                    reality.add("handshake", handshake);
                    reality.addProperty("private_key", realityPrivateKey);
                    JsonArray shortIds = new JsonArray();
                    shortIds.add(realityShortId);
                    reality.add("short_id", shortIds);

                    JsonObject tls = new JsonObject();
                    tls.addProperty("enabled", true);
                    tls.addProperty("server_name", realityServer);
                    tls.add("reality", reality);

                    JsonObject vless = new JsonObject();
                    vless.addProperty("type", "vless");
                    vless.addProperty("tag", "vless-reality");
                    vless.addProperty("listen", "0.0.0.0");
                    vless.addProperty("listen_port", Integer.parseInt(port));
                    vless.add("users", users);
                    vless.add("tls", tls);
                    inbounds.add(vless);
                }
            }
        }

        // Build top-level config
        JsonObject ntp = new JsonObject();
        ntp.addProperty("enabled", true);
        ntp.addProperty("server", "time.apple.com");
        ntp.addProperty("server_port", 123);
        ntp.addProperty("interval", "60m");

        JsonObject localDns = new JsonObject();
        localDns.addProperty("tag", "local");
        localDns.addProperty("type", "local");
        JsonArray dnsServers = new JsonArray();
        dnsServers.add(localDns);
        JsonObject dns = new JsonObject();
        dns.add("servers", dnsServers);
        dns.addProperty("strategy", NetworkDetector.detectDNSStrategy());

        JsonObject directOutbound = new JsonObject();
        directOutbound.addProperty("type", "direct");
        directOutbound.addProperty("tag", "direct");
        JsonArray outbounds = new JsonArray();
        outbounds.add(directOutbound);

        JsonObject log = new JsonObject();
        log.addProperty("level", "error");

        JsonObject config = new JsonObject();
        config.add("log", log);
        config.add("ntp", ntp);
        config.add("dns", dns);
        config.add("inbounds", inbounds);
        config.add("outbounds", outbounds);

        String configJson = GSON.toJson(config);

        Path configPath = Paths.get(System.getProperty("java.io.tmpdir"), "sing-box-config.json");
        Files.write(configPath, configJson.getBytes("UTF-8"));
        return configPath;
    }

    // ==================== Reality Key Generation (Pure Java X25519) ====================

    /**
     * Generates Reality X25519 keypair using pure Java (no sing-box binary needed).
     * Populates REALITY_PRIVATE_KEY, REALITY_SHORT_ID, and REALITY_PUBLIC_KEY in the map.
     */
    public static void generateRealityKeysIfNeeded(Map<String, String> env) throws Exception {
        String privateKey = env.getOrDefault("REALITY_PRIVATE_KEY", "");
        String shortId = env.getOrDefault("REALITY_SHORT_ID", "");

        if (!privateKey.isEmpty()) {
            Log.info("[SBX] Using provided Reality private key");
            try {
                byte[] privBytes = decodeBase64Url(privateKey);
                byte[] clamped = clampPrivateKey(privBytes);
                byte[] pubBytes = x25519(clamped, basepoint());
                publicRealityKey = base64Url(pubBytes);
                env.put("REALITY_PUBLIC_KEY", publicRealityKey);
            } catch (Exception e) {
                Log.warn("[SBX] Could not derive public key from provided private key: " + e.getMessage());
            }
            if (shortId.isEmpty()) {
                env.put("REALITY_SHORT_ID", randomShortId());
                Log.info("[SBX] Generated missing Reality ShortId");
            }
            return;
        }

        String realityPort = env.getOrDefault("REALITY_PORT", "");
        if (realityPort.isEmpty()) {
            Log.info("[SBX] No REALITY_PORT set, skipping Reality key generation");
            return;
        }

        // Generate fresh keypair + short id
        Log.info("[SBX] Generating Reality keypair (pure Java)...");
        byte[] privBytes = new byte[32];
        RANDOM.nextBytes(privBytes);
        privBytes = clampPrivateKey(privBytes);
        byte[] pubBytes = x25519(privBytes, basepoint());

        privateKey = base64Url(privBytes);
        publicRealityKey = base64Url(pubBytes);

        if (shortId.isEmpty()) {
            shortId = randomShortId();
            env.put("REALITY_SHORT_ID", shortId);
        }

        env.put("REALITY_PRIVATE_KEY", privateKey);
        env.put("REALITY_PUBLIC_KEY", publicRealityKey);

        Log.info("[SBX] Reality PublicKey: " + publicRealityKey);
        Log.info("[SBX] Reality ShortId generated");
        Log.info("[SBX] PrivateKey generated (not printed for security)");
    }

    // ==================== Pure Java X25519 Implementation ====================

    private static byte[] clampPrivateKey(byte[] input) {
        if (input.length != 32) throw new IllegalArgumentException("X25519 private key must be 32 bytes");
        byte[] key = input.clone();
        key[0] &= (byte) 248;
        key[31] &= (byte) 127;
        key[31] |= (byte) 64;
        return key;
    }

    private static byte[] basepoint() {
        byte[] bp = new byte[32];
        bp[0] = 9;
        return bp;
    }

    private static byte[] x25519(byte[] scalar, byte[] u) {
        BigInteger p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
        BigInteger a24 = BigInteger.valueOf(121665);
        byte[] k = clampPrivateKey(scalar);
        BigInteger x1 = decodeLittleEndian(u);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = ((k[t / 8] & 0xff) >> (t % 8)) & 1;
            swap ^= kt;
            if (swap != 0) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(p);
            BigInteger aa = a.multiply(a).mod(p);
            BigInteger b = x2.subtract(z2).mod(p);
            BigInteger bb = b.multiply(b).mod(p);
            BigInteger e = aa.subtract(bb).mod(p);
            BigInteger c = x3.add(z3).mod(p);
            BigInteger d = x3.subtract(z3).mod(p);
            BigInteger da = d.multiply(a).mod(p);
            BigInteger cb = c.multiply(b).mod(p);
            x3 = da.add(cb).multiply(da.add(cb)).mod(p);
            z3 = x1.multiply(da.subtract(cb).multiply(da.subtract(cb)).mod(p)).mod(p);
            x2 = aa.multiply(bb).mod(p);
            z2 = e.multiply(aa.add(a24.multiply(e)).mod(p)).mod(p);
        }
        if (swap != 0) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        BigInteger result = x2.multiply(z2.modInverse(p)).mod(p);
        return encodeLittleEndian(result);
    }

    private static BigInteger decodeLittleEndian(byte[] input) {
        byte[] reversed = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            reversed[input.length - 1 - i] = input[i];
        }
        return new BigInteger(1, reversed);
    }

    private static byte[] encodeLittleEndian(BigInteger value) {
        byte[] output = new byte[32];
        BigInteger n = value;
        BigInteger mask = BigInteger.valueOf(0xff);
        for (int i = 0; i < 32; i++) {
            output[i] = n.and(mask).byteValue();
            n = n.shiftRight(8);
        }
        return output;
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] decodeBase64Url(String str) {
        return Base64.getUrlDecoder().decode(str);
    }

    private static String randomShortId() {
        byte[] sid = new byte[8];
        RANDOM.nextBytes(sid);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : sid) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
