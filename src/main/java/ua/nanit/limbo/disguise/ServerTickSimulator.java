package ua.nanit.limbo.disguise;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import ua.nanit.limbo.connection.ClientConnection;
import ua.nanit.limbo.server.Connections;
import ua.nanit.limbo.protocol.packets.play.PacketKeepAlive;
import ua.nanit.limbo.protocol.packets.play.PacketTimeUpdate;
import ua.nanit.limbo.server.Log;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends realistic server tick packets to all connected players.
 * Ported from minewire/handler.go keepalive + time update + motion tickers.
 *
 * A real Minecraft server sends:
 * - Keep-Alive every ~15 seconds (with jitter)
 * - Time Update every ~1 second (20 ticks)
 *
 * This makes the server appear authentic to DPI and monitoring tools.
 */
public final class ServerTickSimulator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Connections connections;
    private final PlayerMotion motion;
    private final boolean keepAliveEnabled;
    private final boolean timeUpdatesEnabled;
    private final boolean playerSimulationEnabled;
    private ScheduledExecutorService scheduler;
    private long worldTime = 0;
    private long lastTickMillis = System.currentTimeMillis();

    public ServerTickSimulator(Connections connections) {
        this(connections, true, true, true);
    }

    public ServerTickSimulator(
            Connections connections,
            boolean keepAliveEnabled,
            boolean timeUpdatesEnabled,
            boolean playerSimulationEnabled
    ) {
        this.connections = connections;
        this.motion = new PlayerMotion();
        this.keepAliveEnabled = keepAliveEnabled;
        this.timeUpdatesEnabled = timeUpdatesEnabled;
        this.playerSimulationEnabled = playerSimulationEnabled;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ServerTickSim");
            t.setDaemon(true);
            return t;
        });

        if (keepAliveEnabled) {
            scheduleKeepAlive();
        }

        if (timeUpdatesEnabled) {
            scheduler.scheduleAtFixedRate(this::sendTimeUpdate, 1, 1, TimeUnit.SECONDS);
        }

        if (playerSimulationEnabled) {
            scheduler.scheduleAtFixedRate(this::updateMotion, 2, 2, TimeUnit.SECONDS);
        }

        Log.info("[Disguise] Server tick simulator started (keepalive=%s, timeupdate=%s, motion=%s)",
                keepAliveEnabled ? "13-17s jitter" : "off",
                timeUpdatesEnabled ? "1s" : "off",
                playerSimulationEnabled ? "2s" : "off");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public PlayerMotion getMotion() {
        return motion;
    }

    private void scheduleKeepAlive() {
        int delay = 13 + RANDOM.nextInt(5); // 13-17 seconds with jitter
        scheduler.schedule(() -> {
            sendKeepAlive();
            scheduleKeepAlive(); // reschedule with new random delay
        }, delay, TimeUnit.SECONDS);
    }

    private void sendKeepAlive() {
        try {
            PacketKeepAlive pkt = new PacketKeepAlive();
            pkt.setId(ThreadLocalRandom.current().nextLong());
            for (ClientConnection conn : connections.getAllConnections()) {
                conn.sendPacket(pkt);
            }
        } catch (Exception e) {
            Log.debug("KeepAlive broadcast failed: %s", e.toString());
        }
    }

    private void sendTimeUpdate() {
        try {
            long now = System.currentTimeMillis();
            long elapsedMillis = now - lastTickMillis;
            lastTickMillis = now;
            worldTime += elapsedMillis / 50; // 1 tick = 50ms, real-time speed
            // timeOfDay cycles every 24000 ticks (20 min = 480000ms real time)
            long timeOfDay = worldTime % 24000;
            PacketTimeUpdate pkt = new PacketTimeUpdate(worldTime, timeOfDay);
            for (ClientConnection conn : connections.getAllConnections()) {
                conn.sendPacket(pkt);
            }
        } catch (Exception e) {
            Log.debug("TimeUpdate broadcast failed: %s", e.toString());
        }
    }

    private void updateMotion() {
        motion.update();
    }
}
