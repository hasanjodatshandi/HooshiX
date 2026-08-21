package com.sajtech.notification.infrastructure.runtime.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class GrpcServerLifecycle implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerLifecycle.class);
  private static final int MAX_INBOUND_MESSAGE_BYTES = 64 * 1024;
  private static final int MAX_INBOUND_METADATA_BYTES = 16 * 1024;
  private static final long SERVER_SHUTDOWN_GRACE_SECONDS = 10;
  private static final long EXECUTOR_SHUTDOWN_GRACE_SECONDS = 1;

  private final Server server;
  private final ExecutorService requestExecutor;
  private volatile boolean running;

  public GrpcServerLifecycle(
      String bindAddress,
      int port,
      int maxConcurrentCallsPerConnection,
      BindableService service,
      ServerInterceptor tracingInterceptor,
      ServerInterceptor admissionInterceptor) {
    if (bindAddress == null || bindAddress.isBlank() || port <= 0 || port > 65_535) {
      throw new IllegalArgumentException("Invalid gRPC bind address or port");
    }
    if (maxConcurrentCallsPerConnection <= 0) {
      throw new IllegalArgumentException(
          "Maximum concurrent calls per connection must be positive");
    }
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(tracingInterceptor, "tracingInterceptor");
    Objects.requireNonNull(admissionInterceptor, "admissionInterceptor");
    requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    ServerServiceDefinition tracedService =
        ServerInterceptors.intercept(service, tracingInterceptor);
    ServerServiceDefinition admittedService =
        ServerInterceptors.intercept(tracedService, admissionInterceptor);
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress(bindAddress, port))
            .executor(requestExecutor)
            .maxConcurrentCallsPerConnection(maxConcurrentCallsPerConnection)
            .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
            .maxInboundMetadataSize(MAX_INBOUND_METADATA_BYTES)
            .addService(admittedService)
            .build();
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    try {
      server.start();
      running = true;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "NOTIFICATION_GRPC_SERVER_STARTED")
          .log("Notification gRPC server started");
    } catch (IOException exception) {
      shutdownRequestExecutor();
      throw new IllegalStateException("Unable to start Notification gRPC server", exception);
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    boolean interrupted = false;
    server.shutdown();
    try {
      if (!server.awaitTermination(SERVER_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        server.shutdownNow();
      }
    } catch (InterruptedException exception) {
      interrupted = true;
      server.shutdownNow();
    } finally {
      interrupted |= shutdownRequestExecutor();
      running = false;
      LOGGER
          .atInfo()
          .addKeyValue("eventCode", "NOTIFICATION_GRPC_SERVER_STOPPED")
          .log("Notification gRPC server stopped");
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private boolean shutdownRequestExecutor() {
    requestExecutor.shutdown();
    try {
      if (requestExecutor.awaitTermination(EXECUTOR_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        return false;
      }
      requestExecutor.shutdownNow();
      if (!requestExecutor.awaitTermination(EXECUTOR_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        LOGGER
            .atWarn()
            .addKeyValue("eventCode", "NOTIFICATION_GRPC_EXECUTOR_TERMINATION_TIMEOUT")
            .log("Notification gRPC request executor did not terminate within shutdown window");
      }
      return false;
    } catch (InterruptedException exception) {
      requestExecutor.shutdownNow();
      return true;
    }
  }
}
