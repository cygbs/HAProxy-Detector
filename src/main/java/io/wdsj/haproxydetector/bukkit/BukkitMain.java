package io.wdsj.haproxydetector.bukkit;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.utility.MinecraftReflection;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.wdsj.haproxydetector.MetricsId;
import io.wdsj.haproxydetector.ProxyWhitelist;
import org.bstats.charts.SimplePie;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import io.wdsj.haproxydetector.ReflectionUtil;
import zone.rong.imaginebreaker.ImagineBreaker;

import static io.wdsj.haproxydetector.ReflectionUtil.sneakyThrow;

public final class BukkitMain extends JavaPlugin implements Listener {
    static Logger logger;

    private IInjectionStrategy injectionStrategy;

    @Override
    public void onLoad() {
        logger = getLogger();
        logger.info("Starting ImagineBreaker...");
        try {
            ImagineBreaker.openBootModules();
            ImagineBreaker.wipeFieldFilters();
            ImagineBreaker.wipeMethodFilters();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onEnable() {
        try {
            Path path = this.getDataFolder().toPath().resolve("whitelist.conf");
            ProxyWhitelist whitelist = ProxyWhitelist.loadOrDefault(path).orElse(null);
            if (whitelist == null) {
                logger.warning("!!! ==============================");
                logger.warning("!!! Proxy whitelist is disabled in the config.");
                logger.warning("!!! This is EXTREMELY DANGEROUS, don't do this in production!");
                logger.warning("!!! ==============================");
            } else if (whitelist.size() == 0) {
                logger.warning("Proxy whitelist is empty. This will disallow all proxies!");
            }
            ProxyWhitelist.whitelist = whitelist;
        } catch (IOException e) {
            throw new RuntimeException("failed to load proxy whitelist", e);
        }

        if (!ProtocolLibrary.getPlugin().isEnabled()) {
            logger.severe("Required dependency ProtocolLib is not enabled, exiting");
            this.setEnabled(false);
            return;
        }
        String plVersion = ProtocolLibrary.getPlugin().getDescription().getVersion();

        try {
            if (ReflectionUtil.hasClass("com.comphenix.protocol.injector.netty.ProtocolInjector")) {
                injectionStrategy = createInjectionStrategy1();
            } else if (ReflectionUtil.hasClass(
                    "com.comphenix.protocol.injector.netty.manager.NetworkManagerInjector")) {
                injectionStrategy = createInjectionStrategy2();
            } else {
                throw new UnsupportedOperationException("unsupported ProtocolLib version " + plVersion);
            }

            injectionStrategy.inject();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new UnsupportedOperationException("unsupported ProtocolLib version " + plVersion, e);
        } catch (ReflectiveOperationException e) {
            sneakyThrow(e);
        }

        // 注册事件监听器，用于修正玩家地址
        getServer().getPluginManager().registerEvents(this, this);

        try {
            Metrics metrics = new Metrics(this, 21070);
            metrics.addCustomChart(MetricsId.createWhitelistCountChart());
            metrics.addCustomChart(new SimplePie(MetricsId.KEY_PROTOCOLLIB_VERSION,
                    () -> ProtocolLibrary.getPlugin().getDescription().getVersion()));
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to start metrics", t);
        }
        logger.info("HAProxyDetector is now enabled!");
    }

    // Use separated methods to make sure the strategy classes won't be loaded
    // until they're actually used.
    private static IInjectionStrategy createInjectionStrategy1() throws ReflectiveOperationException {
        return new InjectionStrategyA(logger);
    }

    private static IInjectionStrategy createInjectionStrategy2() throws ReflectiveOperationException {
        return new InjectionStrategyB(logger);
    }

    @Override
    public void onDisable() {
        if (injectionStrategy != null) {
            try {
                injectionStrategy.uninject();
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    static ChannelHandler getNetworkManager(ChannelPipeline pipeline) {
        Class<? extends ChannelHandler> networkManagerClass = (Class<? extends ChannelHandler>) MinecraftReflection.getNetworkManagerClass();
        ChannelHandler networkManager = null;
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (networkManagerClass.isAssignableFrom(entry.getValue().getClass())) {
                networkManager = entry.getValue();
                break;
            }
        }

        if (networkManager == null) {
            throw new IllegalArgumentException("NetworkManager not found in channel pipeline " + pipeline.names());
        }

        return networkManager;
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        fixPlayerAddress(event.getPlayer());
    }

    private void fixPlayerAddress(Player player) {
        try {
            // CraftPlayer 中的 PlayerConnection 字段名在 1.12.2 是 netServerHandler
            Field netServerHandlerField = player.getClass().getDeclaredField("netServerHandler");
            netServerHandlerField.setAccessible(true);
            Object playerConnection = netServerHandlerField.get(player);
            if (playerConnection == null) return;

            // 获取 NetworkManager 字段
            Field networkManagerField = playerConnection.getClass().getDeclaredField("networkManager");
            networkManagerField.setAccessible(true);
            Object networkManager = networkManagerField.get(playerConnection);
            if (networkManager == null) return;

            // 获取 NetworkManager 中的 socketAddress（已被 HAProxy 修改）
            Field socketAddressField = networkManager.getClass().getDeclaredField("socketAddress");
            socketAddressField.setAccessible(true);
            SocketAddress realAddress = (SocketAddress) socketAddressField.get(networkManager);
            if (realAddress == null) return;

            // 尝试更新 CraftPlayer 的 address 字段（如果存在）
            try {
                Field addressField = player.getClass().getDeclaredField("address");
                addressField.setAccessible(true);
                SocketAddress currentAddress = (SocketAddress) addressField.get(player);
                if (realAddress.equals(currentAddress)) return; // 已经是正确的，无需更新
                addressField.set(player, realAddress);
            } catch (NoSuchFieldException ignored) {
                // 某些版本可能没有该字段，忽略
            }

            // 更新 PlayerConnection 的 address 字段（如果存在）
            try {
                Field connAddressField = playerConnection.getClass().getDeclaredField("address");
                connAddressField.setAccessible(true);
                connAddressField.set(playerConnection, realAddress);
            } catch (NoSuchFieldException ignored) {
                // 忽略
            }

            logger.log(Level.INFO, "Fixed player address for {0} to {1}", new Object[]{player.getName(), realAddress});
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to fix player address for " + player.getName(), e);
        }
    }
}
