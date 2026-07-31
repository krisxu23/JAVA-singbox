package ua.nanit.limbo.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.Base64;
import java.util.Map;
import ua.nanit.limbo.server.Log;

/**
 * Generates proxy subscription links (VMess, Hysteria2, TUIC, VLESS Reality, SOCKS5)
 * and Base64-encoded subscription output. Extracted from NanoLimbo.java during refactoring.
 */
public final class SubscriptionGenerator {

    private static final Gson GSON = new Gson();

    private SubscriptionGenerator() {}

    /**
     * Generates and prints proxy subscription links to console and writes sub.txt to disk.
     *
     * @param env environment configuration map (read-only)
     */
    public static void generate(Map<String, String> env) throws IOException {
        String uuid = env.getOrDefault("UUID", "fe7431cb-ab1b-4205-a14c-d056f821b383");
        String argoDomain = env.getOrDefault("ARGO_DOMAIN", "");
        String serverIP = env.getOrDefault("SERVER_IP", "");
        String hy2Port = env.getOrDefault("HY2_PORT", "");
        String tuicPort = env.getOrDefault("TUIC_PORT", "");
        String realityPort = env.getOrDefault("REALITY_PORT", "");
        String s5Port = env.getOrDefault("S5_PORT", "");
        String cfIp = env.getOrDefault("CFIP", "cdns.doon.eu.org");
        String cfPort = env.getOrDefault("CFPORT", "443");
        String name = env.getOrDefault("NAME", "sbx");
        String realityPublicKey = env.getOrDefault("REALITY_PUBLIC_KEY", "");
        String realityShortId = env.getOrDefault("REALITY_SHORT_ID", "");
        if (name.isEmpty()) name = "sbx";

        // Extract cert fingerprint for Hysteria2 pinning
        String fingerprint = "";
        Path certFile = Paths.get(System.getProperty("java.io.tmpdir"), "certs", "cert.pem");
        if (certFile.toFile().exists()) {
            try (InputStream in = new FileInputStream(certFile.toFile())) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(cert.getEncoded());
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    if (sb.length() > 0) sb.append("%3A");
                    sb.append(String.format("%02X", b));
                }
                fingerprint = sb.toString();
            } catch (Exception e) {
                Log.warn("[SBX] Failed to extract cert fingerprint: " + e.getMessage());
            }
        }

        // Direct protocols use real server IP
        String directAddr = serverIP.isEmpty() ? cfIp : serverIP;
        StringBuilder sub = new StringBuilder();

        // VMess WS via Argo
        if (!argoDomain.isEmpty()) {
            JsonObject vmess = new JsonObject();
            vmess.addProperty("v", "2");
            vmess.addProperty("ps", name + "-ws-argo");
            vmess.addProperty("add", cfIp);
            vmess.addProperty("port", cfPort);
            vmess.addProperty("id", uuid);
            vmess.addProperty("aid", "0");
            vmess.addProperty("scy", "auto");
            vmess.addProperty("net", "ws");
            vmess.addProperty("type", "none");
            vmess.addProperty("host", argoDomain);
            vmess.addProperty("path", "/vmess-argo?ed=2560");
            vmess.addProperty("tls", "tls");
            vmess.addProperty("sni", argoDomain);
            vmess.addProperty("alpn", "");
            vmess.addProperty("fp", "firefox");
            vmess.addProperty("allowInsecure", "false");
            String vmessJson = GSON.toJson(vmess);
            String wsLink = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.getBytes("UTF-8"));
            sub.append(wsLink).append("\n");
        }

        // Hysteria2 links
        if (!hy2Port.isEmpty()) {
            for (String port : hy2Port.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    String link = String.format("hysteria2://%s@%s:%s/?sni=www.bing.com&insecure=1&pinSHA256=%s&alpn=h3&obfs=none#H2-%s",
                        uuid, directAddr, port, fingerprint, name);
                    sub.append(link).append("\n");
                }
            }
        }

        // TUIC links
        if (!tuicPort.isEmpty()) {
            for (String port : tuicPort.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    String link = String.format("tuic://%s:%s@%s:%s?sni=www.bing.com&congestion_control=bbr&udp_relay_mode=native&alpn=h3&allow_insecure=1#TUIC-%s",
                        uuid, uuid, directAddr, port, name);
                    sub.append(link).append("\n");
                }
            }
        }

        // VLESS Reality links
        if (!realityPort.isEmpty() && !realityPublicKey.isEmpty()) {
            for (String port : realityPort.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    String link = String.format("vless://%s@%s:%s?encryption=none&flow=xtls-rprx-vision&security=reality&sni=%s&fp=firefox&pbk=%s&type=tcp&headerType=none#Reality-%s",
                        uuid, directAddr, port, env.get("REALITY_DEST"), realityPublicKey, name);
                    sub.append(link).append("\n");
                }
            }
        }

        // SOCKS5 links
        if (!s5Port.isEmpty()) {
            for (String port : s5Port.split(",")) {
                port = port.trim();
                if (!port.isEmpty()) {
                    String user = uuid.substring(0, 8);
                    String pass = uuid.substring(uuid.length() - 12);
                    String link = String.format("socks5://%s:%s@%s:%s#S5-%s",
                        user, pass, directAddr, port, name);
                    sub.append(link).append("\n");
                }
            }
        }

        if (sub.length() > 0) {
            Log.info("\n========== Node Links ==========");
            Log.info(sub.toString());
            Log.info("================================");

            String base64 = Base64.getEncoder().encodeToString(sub.toString().getBytes("UTF-8"));
            Log.info("\n========== Subscription (Base64) ==========");
            Log.info(base64);
            Log.info("============================================\n");

            Path subFile = Paths.get("sub.txt");
            Files.write(subFile, sub.toString().getBytes("UTF-8"));
            Log.info("Subscription saved to: sub.txt");
        }
    }
}
