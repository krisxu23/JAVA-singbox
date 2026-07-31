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

package ua.nanit.limbo.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.ResourceLeakDetector;
import ua.nanit.limbo.configuration.LimboConfig;
import ua.nanit.limbo.connection.ClientChannelInitializer;
import ua.nanit.limbo.connection.ClientConnection;
import ua.nanit.limbo.connection.PacketHandler;
import ua.nanit.limbo.connection.PacketSnapshots;
import ua.nanit.limbo.world.DimensionRegistry;
import ua.nanit.limbo.disguise.PlayerCountSimulator;
import ua.nanit.limbo.disguise.ServerTickSimulator;

import java.nio.file.Paths;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class LimboServer {

    private LimboConfig config;
    private PacketHandler packetHandler;
    private Connections connections;
    private DimensionRegistry dimensionRegistry;
    private ScheduledFuture<?> keepAliveTask;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private CommandManager commandManager;

    // Disguise (from minewire)
    private PlayerCountSimulator playerCountSim;
    private ServerTickSimulator tickSimulator;

    public LimboConfig getConfig() {
        return config;
    }

    public PacketHandler getPacketHandler() {
        return packetHandler;
    }

    public Connections getConnections() {
        return connections;
    }

    public DimensionRegistry getDimensionRegistry() {
        return dimensionRegistry;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public ServerTickSimulator getTickSimulator() {
        return tickSimulator;
    }

    public PlayerCountSimulator getPlayerCountSimulator() {
        return playerCountSim;
    }

    public void start() throws Exception {
        config = new LimboConfig(Paths.get("./"));
        config.load();

        Log.setLevel(config.getDebugLevel());
        Log.info("Starting server...");
        Log.info("Preparing level \"world\"");
        Log.info("Preparing start region for dimension minecraft:overworld");
        Log.info("Preparing spawn area: 1%");
        Log.info("Preparing spawn area: 2%");
        Log.info("Preparing spawn area: 5%");
        Log.info("Preparing spawn area: 8%");
        Log.info("Preparing spawn area: 15%");
        Log.info("Preparing spawn area: 20%");
        Log.info("Preparing spawn area: 35%");
        Log.info("Preparing spawn area: 60%");
        Log.info("Preparing spawn area: 80%");
        Log.info("Preparing spawn area: 99%");
        Log.info("Preparing spawn area: 100%");
        Log.info("Running delayed init tasks");
        Log.info("Done (43.096s)! For help, type \"help\"");
        ResourceLeakDetector.setLevel(Log.isDebug()
                ? ResourceLeakDetector.Level.PARANOID
                : ResourceLeakDetector.Level.SIMPLE);

        packetHandler = new PacketHandler(this);
        dimensionRegistry = new DimensionRegistry(this);
        dimensionRegistry.load(config.getDimensionType());
        connections = new Connections();

        PacketSnapshots.initPackets(this);

        startBootstrap();

        if (!config.isDisguiseEnable() || !config.isDisguiseKeepAlive()) {
            keepAliveTask = workerGroup.scheduleAtFixedRate(this::broadcastKeepAlive, 0L, 15L, TimeUnit.SECONDS);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "NanoLimbo shutdown thread"));

        Log.info("Server started on %s", config.getAddress());

        commandManager = new CommandManager();
        commandManager.registerAll(this);
        new Thread(commandManager, "CmdManager").start();

        // Start disguise simulator (from minewire)
        if (config.isDisguiseEnable()) {
            playerCountSim = new PlayerCountSimulator(
                    config.getDisguiseOnlineMin(), config.getDisguiseOnlineMax());
            playerCountSim.start();

            tickSimulator = new ServerTickSimulator(
                    connections,
                    config.isDisguiseKeepAlive(),
                    config.isDisguiseTimeUpdates(),
                    config.isDisguisePlayerSimulation()
            );
            tickSimulator.start();

            Log.info("[Disguise] Active: version=%s protocol=%d online=%d-%d",
                    config.getDisguiseVersionName(), config.getDisguiseProtocolId(),
                    config.getDisguiseOnlineMin(), config.getDisguiseOnlineMax());
        }

        System.gc();
    }

    private void startBootstrap() throws InterruptedException {
        Class<? extends ServerChannel> channelClass;

        if (config.isUseEpoll() && Epoll.isAvailable()) {
            bossGroup = new EpollEventLoopGroup(config.getBossGroupSize());
            workerGroup = new EpollEventLoopGroup(config.getWorkerGroupSize());
            channelClass = EpollServerSocketChannel.class;
            Log.debug("Using Epoll transport type");
        } else {
            bossGroup = new NioEventLoopGroup(config.getBossGroupSize());
            workerGroup = new NioEventLoopGroup(config.getWorkerGroupSize());
            channelClass = NioServerSocketChannel.class;
            Log.debug("Using Java NIO transport type");
        }

        serverChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(channelClass)
                .childHandler(new ClientChannelInitializer(this))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .localAddress(config.getAddress())
                .bind()
                .sync()
                .channel();
    }

    private void broadcastKeepAlive() {
        connections.getAllConnections().forEach(ClientConnection::sendKeepAlive);
    }

    private void stop() {
        Log.info("Stopping server...");

        if (keepAliveTask != null) {
            keepAliveTask.cancel(true);
        }

        if (playerCountSim != null) {
            playerCountSim.stop();
        }
        if (tickSimulator != null) {
            tickSimulator.stop();
        }

        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }

        Log.info("Server stopped, Goodbye!");
    }

}
