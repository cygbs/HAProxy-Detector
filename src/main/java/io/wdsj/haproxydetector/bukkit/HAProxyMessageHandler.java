package io.wdsj.haproxydetector.bukkit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.logging.Level;

import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.utility.MinecraftReflection;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.wdsj.haproxydetector.ReflectionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

@Sharable
class HAProxyMessageHandler extends SimpleChannelInboundHandler<HAProxyMessage> {
    private static volatile MethodHandle freeAddressSetter;
    private final MethodHandle addressSetter;
    private final Object networkManager;

    public HAProxyMessageHandler(ChannelHandler networkManager) {
        this.networkManager = networkManager;
        if (freeAddressSetter == null) {
            synchronized (HAProxyMessageHandler.class) {
                if (freeAddressSetter == null) {
                    Field f = FuzzyReflection.fromClass(MinecraftReflection.getNetworkManagerClass(), true)
                            .getFieldByType("socketAddress", SocketAddress.class);
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                    }

                    try {
                        freeAddressSetter = MethodHandles.lookup().unreflectSetter(f);
                    } catch (IllegalAccessException e) {
                        ReflectionUtil.sneakyThrow(e);
                        throw new AssertionError("unreachable");
                    }
                }
            }
        }

        this.addressSetter = freeAddressSetter.bindTo(networkManager);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HAProxyMessage msg) {
        SocketAddress realAddress = new InetSocketAddress(msg.sourceAddress(), msg.sourcePort());
        BukkitMain.logger.log(Level.INFO, "Set remote address via proxy {0} -> {1}",
                new Object[] { ctx.channel().remoteAddress(), realAddress });
        try {
            addressSetter.invokeExact(realAddress);
            // 额外更新玩家对象中的地址（如果玩家已登录）
            updatePlayerAddress(realAddress);
        } catch (Throwable e) {
            ReflectionUtil.sneakyThrow(e);
        }
    }

    @Override
    public boolean isSharable() {
        return true;
    }

    private void updatePlayerAddress(SocketAddress realAddress) {
        try {
            // 通过 NetworkManager 获取关联的 Player
            Field playerField = networkManager.getClass().getDeclaredField("player");
            playerField.setAccessible(true);
            Object playerObj = playerField.get(networkManager);
            if (playerObj == null) return;

            Player player = (Player) playerObj;
            // 更新 CraftPlayer 的 address 字段
            String cbPackage = Bukkit.getServer().getClass().getPackage().getName();
            String version = cbPackage.substring(cbPackage.lastIndexOf('.') + 1);
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Field addressField = craftPlayerClass.getDeclaredField("address");
            addressField.setAccessible(true);
            addressField.set(player, realAddress);

            // 更新 PlayerConnection 中的 address（如果有）
            Field connectionField = craftPlayerClass.getDeclaredField("connection");
            connectionField.setAccessible(true);
            Object connection = connectionField.get(player);
            if (connection != null) {
                Field connAddressField = connection.getClass().getDeclaredField("address");
                connAddressField.setAccessible(true);
                connAddressField.set(connection, realAddress);
            }
        } catch (Exception e) {
            // 静默失败，不影响主要功能
            BukkitMain.logger.log(Level.FINE, "Failed to update player address: " + e.getMessage());
        }
    }
}
