package com.sajtech.conversation.infrastructure.runtime.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class GrpcServerLifecycle implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerLifecycle.class);
  private static final int MAX_MESSAGE_BYTES = 72 * 1024;
  private static final int MAX_METADATA_BYTES = 16 * 1024;
  private final Server server;
  private final ExecutorService executor;
  private volatile boolean running;

  public GrpcServerLifecycle(
      String bindAddress,
      int port,
      int maximumConcurrentCalls,
      BindableService service,
      List<ServerInterceptor> interceptors) {
    if (bindAddress == null
        || bindAddress.isBlank()
        || port < 1
        || port > 65_535
        || maximumConcurrentCalls < 1) {
      throw new IllegalArgumentException("Conversation gRPC configuration is invalid");
    }
    Objects.requireNonNull(service);
    List<ServerInterceptor> safeInterceptors = List.copyOf(interceptors);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port))
            .executor(executor)
            .maxConcurrentCallsPerConnection(maximumConcurrentCalls)
            .maxInboundMessageSize(MAX_MESSAGE_BYTES)
            .maxInboundMetadataSize(MAX_METADATA_BYTES)
            .addService(ServerInterceptors.interceptForward(service, safeInterceptors))
            .build();
  }

  @Override
  public synchronized void start() {
    if (running) return;
    try {
      server.start();
      running = true;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "CONVERSATION_GRPC_STARTED")
          .log("Conversation gRPC server started");
    } catch (IOException exception) {
      executor.shutdownNow();
      throw new IllegalStateException("Unable to start Conversation gRPC server", exception);
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) return;
    boolean interrupted = false;
    server.shutdown();
    try {
      if (!server.awaitTermination(10, TimeUnit.SECONDS)) server.shutdownNow();
    } catch (InterruptedException exception) {
      interrupted = true;
      server.shutdownNow();
    } finally {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(1, TimeUnit.SECONDS)) executor.shutdownNow();
      } catch (InterruptedException exception) {
        interrupted = true;
        executor.shutdownNow();
      }
      running = false;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "CONVERSATION_GRPC_STOPPED")
          .log("Conversation gRPC server stopped");
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
