package io.wdsj.haproxydetector.bukkit;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.reflect.FuzzyReflection;
import io.netty.channel.*;
import io.wdsj.haproxydetector.HAProxyDetectorHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class InjectionStrategyB implements IInjectionStrategy {
    private final Logger logger;

    private Field handlerField;
    private ChannelInboundHandler injectorInitializer;
    private ChannelInboundHandler originalHandler;

    public InjectionStrategyB(Logger logger) { this.logger = logger; }

    @Override
    public void inject() throws ReflectiveOperationException {
        try {
            this.uninject();
        } catch (Throwable ignored) {
        }

        Class<?> networkManagerInjectorClass = Class.forName(
                "com.comphenix.protocol.injector.netty.manager.NetworkManagerInjector");
        Class<?> injectionChannelInitializerClass = Class.forName(
                "com.comphenix.protocol.injector.netty.manager.InjectionChannelInitializer");

        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        Field injectorField = FuzzyReflection.fromObject(pm, true)
                .getFieldByType("networkManagerInjector", networkManagerInjectorClass);
        injectorField.setAccessible(true);
        Object networkManagerInjector = injectorField.get(pm);

        Field injectorInitializerField = FuzzyReflection.fromClass(networkManagerInjectorClass, true)
                .getFieldByType("pipelineInjectorHandler", injectionChannelInitializerClass);
        injectorInitializerField.setAccessible(true);
        this.injectorInitializer = (ChannelInboundHandler) injectorInitializerField.get(networkManagerInjector);

        this.handlerField = FuzzyReflection.fromClass(injectionChannelInitializerClass, true)
                .getFieldByType("handler", ChannelInboundHandler.class);
        handlerField.setAccessible(true);
        this.originalHandler = (ChannelInboundHandler) handlerField.get(injectorInitializer);

        ChannelInboundHandler myHandler = (ChannelInboundHandler) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[] { ChannelInboundHandler.class },
                (proxy, method, args) -> {
                    if ("channelActive".equals(method.getName())) {
                        ChannelHandlerContext ctx = (ChannelHandlerContext) args[0];
                        // 先注入处理器
                        doInject(ctx.channel());
                        // 然后恢复原始处理器并让其执行
                        ctx.pipeline().remove((ChannelHandler) proxy);
                        ctx.pipeline().addFirst("protocol_lib_inbound_inject", originalHandler);
                        // 让事件继续传播，原始处理器会在之后被调用
                        ctx.fireChannelActive();
                        return null;
                    } else {
                        return method.invoke(originalHandler, args);
                    }
                });
        handlerField.set(injectorInitializer, myHandler);
    }

    @Override
    public void uninject() throws ReflectiveOperationException {
        if (this.handlerField != null && this.injectorInitializer != null && this.originalHandler != null) {
            handlerField.set(injectorInitializer, this.originalHandler);
            this.injectorInitializer = null;
            this.originalHandler = null;
        }
    }

    void doInject(Channel ch) {
        if (ch.eventLoop().inEventLoop()) {
            try {
                ChannelPipeline pipeline = ch.pipeline();
                if (!ch.isOpen() || pipeline.get("haproxy-detector") != null)
                    return;

                if (pipeline.get("haproxy-decoder") != null) {
                    pipeline.remove("haproxy-decoder");
                }

                ChannelHandler haproxyHandler;
                if (pipeline.get("haproxy-handler") != null) {
                    haproxyHandler = pipeline.remove("haproxy-handler");
                } else {
                    ChannelHandler networkManager = BukkitMain.getNetworkManager(pipeline);
                    haproxyHandler = new HAProxyMessageHandler(networkManager);
                }

                HAProxyDetectorHandler detector = new HAProxyDetectorHandler(logger, haproxyHandler);
                // 强制添加到管道最前面
                pipeline.addFirst("haproxy-detector", detector);
            } catch (Throwable t) {
                if (logger != null)
                    logger.log(Level.WARNING, "Exception while injecting proxy detector", t);
                else
                    t.printStackTrace();
            }
        } else {
            ch.eventLoop().execute(() -> this.doInject(ch));
        }
    }
}
