/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo.protocol.packets.status;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ua.nanit.limbo.protocol.ByteMessage;
import ua.nanit.limbo.protocol.PacketOut;
import ua.nanit.limbo.protocol.registry.Version;
import ua.nanit.limbo.server.LimboServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PacketStatusResponse implements PacketOut {

    private static final Gson GSON = new Gson();

    private LimboServer server;
    private static byte[] iconCache;

    public PacketStatusResponse() { }

    public PacketStatusResponse(LimboServer server) {
        this.server = server;
    }

    @Override
    public void encode(ByteMessage msg, Version version) {
        int protocol;
        String ver;
        String desc;
        int maxPlayers;
        int online;

        // Use disguise config when enabled (from minewire)
        if (server.getConfig().isDisguiseEnable()) {
            ver = server.getConfig().getDisguiseVersionName();
            protocol = server.getConfig().getDisguiseProtocolId();
            String motd = server.getConfig().getDisguiseMotd();
            desc = motdToJson(motd);
            maxPlayers = server.getConfig().getMaxPlayers();
            online = server.getPlayerCountSimulator() != null
                    ? server.getPlayerCountSimulator().getOnline()
                    : server.getConnections().getCount();
        } else {
            int staticProtocol = server.getConfig().getPingData().getProtocol();
            if (staticProtocol > 0) {
                protocol = staticProtocol;
            } else {
                protocol = server.getConfig().getInfoForwarding().isNone()
                        ? version.getProtocolNumber()
                        : Version.getMax().getProtocolNumber();
            }
            ver = server.getConfig().getPingData().getVersion();
            desc = server.getConfig().getPingData().getDescription();
            maxPlayers = server.getConfig().getMaxPlayers();
            online = server.getConnections().getCount();
        }

        // Load server icon (64x64 PNG → base64)
        String icon = getIconBase64();
        if (icon != null) {
            msg.writeString(getResponseJson(ver, protocol, maxPlayers, online, desc, icon));
        } else {
            msg.writeString(getResponseJsonNoIcon(ver, protocol, maxPlayers, online, desc));
        }
    }

    private static final Pattern COLOR_PATTERN = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);
    private static final java.util.Map<Character, String> COLOR_MAP = new java.util.HashMap<>();
    static {
        COLOR_MAP.put('0', "black"); COLOR_MAP.put('1', "dark_blue");
        COLOR_MAP.put('2', "dark_green"); COLOR_MAP.put('3', "dark_aqua");
        COLOR_MAP.put('4', "dark_red"); COLOR_MAP.put('5', "dark_purple");
        COLOR_MAP.put('6', "gold"); COLOR_MAP.put('7', "gray");
        COLOR_MAP.put('8', "dark_gray"); COLOR_MAP.put('9', "blue");
        COLOR_MAP.put('a', "green"); COLOR_MAP.put('b', "aqua");
        COLOR_MAP.put('c', "red"); COLOR_MAP.put('d', "light_purple");
        COLOR_MAP.put('e', "yellow"); COLOR_MAP.put('f', "white");
        COLOR_MAP.put('k', "obfuscated"); COLOR_MAP.put('l', "bold");
        COLOR_MAP.put('m', "strikethrough"); COLOR_MAP.put('n', "underlined");
        COLOR_MAP.put('o', "italic"); COLOR_MAP.put('r', "reset");
    }

    /**
     * Converts Paper-style &-color MOTD to Minecraft JSON text component.
     * Supports \n line breaks and &-codes: &0-f for colors, &k-lmno for formatting.
     */
    private String motdToJson(String motd) {
        if (motd == null || motd.isEmpty()) return "{\"text\":\"\"}";
        String cleaned = motd.replace("\\n", "\n");
        String[] lines = cleaned.split("\n", -1);
        JsonArray extra = new JsonArray();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                JsonObject nl = new JsonObject();
                nl.addProperty("text", "\n");
                extra.add(nl);
            }
            String line = lines[i];
            Matcher m = COLOR_PATTERN.matcher(line);
            int lastEnd = 0;
            while (m.find()) {
                if (m.start() > lastEnd) {
                    JsonObject seg = new JsonObject();
                    seg.addProperty("text", line.substring(lastEnd, m.start()));
                    extra.add(seg);
                }
                char code = Character.toLowerCase(m.group(1).charAt(0));
                String color = COLOR_MAP.get(code);
                if (color != null) {
                    JsonObject seg = new JsonObject();
                    if ("obfuscated".equals(color) || "bold".equals(color) || "strikethrough".equals(color) || "underlined".equals(color) || "italic".equals(color)) {
                        seg.addProperty("text", "");
                        seg.addProperty(color, true);
                    } else if ("reset".equals(color)) {
                        seg.addProperty("text", "");
                    } else {
                        seg.addProperty("text", "");
                        seg.addProperty("color", color);
                    }
                    extra.add(seg);
                }
                lastEnd = m.end();
            }
            if (lastEnd < line.length()) {
                JsonObject seg = new JsonObject();
                seg.addProperty("text", line.substring(lastEnd));
                extra.add(seg);
            } else if (lastEnd == 0 && !line.isEmpty()) {
                JsonObject seg = new JsonObject();
                seg.addProperty("text", line);
                extra.add(seg);
            }
        }
        JsonObject root = new JsonObject();
        root.add("extra", extra);
        return GSON.toJson(root);
    }

    private String getIconBase64() {
        if (iconCache != null) return "data:image/png;base64," + Base64.getEncoder().encodeToString(iconCache);
        if (!server.getConfig().isDisguiseEnable()) return null;

        String iconPath = server.getConfig().getDisguiseIconPath();
        if (iconPath == null || iconPath.isEmpty()) return null;

        try {
            iconCache = Files.readAllBytes(Paths.get(iconPath));
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(iconCache);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    private String getResponseJson(String version, int protocol, int maxPlayers, int online, String description, String icon) {
        JsonObject verObj = new JsonObject();
        verObj.addProperty("name", version);
        verObj.addProperty("protocol", protocol);

        JsonObject playersObj = new JsonObject();
        playersObj.addProperty("max", maxPlayers);
        playersObj.addProperty("online", online);
        playersObj.add("sample", new JsonArray());

        JsonObject response = new JsonObject();
        response.add("version", verObj);
        response.add("players", playersObj);
        response.add("description", JsonParser.parseString(description));
        response.addProperty("favicon", icon);
        return GSON.toJson(response);
    }

    private String getResponseJsonNoIcon(String version, int protocol, int maxPlayers, int online, String description) {
        JsonObject verObj = new JsonObject();
        verObj.addProperty("name", version);
        verObj.addProperty("protocol", protocol);

        JsonObject playersObj = new JsonObject();
        playersObj.addProperty("max", maxPlayers);
        playersObj.addProperty("online", online);
        playersObj.add("sample", new JsonArray());

        JsonObject response = new JsonObject();
        response.add("version", verObj);
        response.add("players", playersObj);
        response.add("description", JsonParser.parseString(description));
        return GSON.toJson(response);
    }
}
