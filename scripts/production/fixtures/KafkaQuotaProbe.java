// Disposable native fixture only: synthetic credentials, network-none broker, finite traffic.
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.quota.ClientQuotaAlteration;
import org.apache.kafka.common.quota.ClientQuotaEntity;
import org.apache.kafka.common.quota.ClientQuotaFilter;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;

public final class KafkaQuotaProbe {
  private static final String ROOT = "/run/kafka-fixture/";
  private static final int RECORDS = 512;
  private static final int BYTES = 8192;

  private static void require(boolean ok, String code) {
    if (!ok) throw new IllegalStateException(code);
  }

  private static Properties properties(String identity, String id) throws Exception {
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(Path.of(ROOT + identity + ".properties"))) {
      p.load(in);
    }
    p.setProperty("bootstrap.servers", "127.0.0.1:9092");
    p.setProperty("client.id", id);
    p.setProperty("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
    p.setProperty("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
    p.setProperty("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    p.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    p.setProperty("compression.type", "none");
    p.setProperty("delivery.timeout.ms", "60000");
    p.setProperty("max.block.ms", "10000");
    p.setProperty("buffer.memory", "1048576");
    p.setProperty("enable.auto.commit", "false");
    p.setProperty("max.poll.records", "64");
    p.setProperty("max.partition.fetch.bytes", "1048576");
    return p;
  }

  private static boolean throttled(Map<MetricName, ? extends Metric> metrics, String name) {
    return metrics.entrySet().stream().anyMatch(e -> e.getKey().name().equals(name)
        && e.getValue().metricValue() instanceof Number n && n.doubleValue() > 0);
  }

  private static void awaitQuotas(Admin admin,
      Map<ClientQuotaEntity, Map<String, Double>> desired) throws Exception {
    // Controller acknowledgement precedes broker-cache propagation; only readback is retried.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    do {
      if (desired.equals(admin.describeClientQuotas(ClientQuotaFilter.all()).entities().get(2, TimeUnit.SECONDS))) return;
      Thread.sleep(200);
    } while (System.nanoTime() < deadline);
    throw new IllegalStateException("EXACT_USER_QUOTA_READBACK_REQUIRED");
  }

  private static void traffic(String identity, int index) throws Exception {
    String topic = "quota-fixture-" + index;
    byte[] payload = new byte[BYTES];
    Arrays.fill(payload, (byte) index);
    try (KafkaProducer<byte[], byte[]> first = new KafkaProducer<>(properties(identity, "quota-a-" + index));
         KafkaProducer<byte[], byte[]> second = new KafkaProducer<>(properties(identity, "quota-b-" + index))) {
      for (int n = 0; n < RECORDS; n += 32) {
        KafkaProducer<byte[], byte[]> sender = n % 64 == 0 ? first : second;
        var pending = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 32; i++) pending.add(sender.send(new ProducerRecord<>(topic, payload)));
        for (var future : pending) future.get(30, TimeUnit.SECONDS);
      }
      require(throttled(first.metrics(), "produce-throttle-time-max")
          && throttled(second.metrics(), "produce-throttle-time-max"), "PRODUCER_QUOTA_NOT_OBSERVED");
    }
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties(identity, "quota-c-" + index))) {
      TopicPartition partition = new TopicPartition(topic, 0);
      consumer.assign(List.of(partition));
      consumer.seekToBeginning(List.of(partition));
      int seen = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (seen < RECORDS && System.nanoTime() < deadline) {
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          require(Arrays.equals(record.value(), payload), "SYNTHETIC_PAYLOAD_MISMATCH");
          seen++;
        }
      }
      require(seen == RECORDS && throttled(consumer.metrics(), "fetch-throttle-time-max"),
          "CONSUMER_QUOTA_NOT_OBSERVED");
    }
  }

  public static void main(String[] args) throws Exception {
    require(args.length == 7 && args[0].equals("65536") && args[1].equals("262144"),
        "EXACT_QUOTA_PROFILE_REQUIRED");
    double producerRate = Double.parseDouble(args[0]), consumerRate = Double.parseDouble(args[1]);
    var desired = new java.util.HashMap<ClientQuotaEntity, Map<String, Double>>();
    var alterations = new ArrayList<ClientQuotaAlteration>();
    var topics = new ArrayList<NewTopic>();
    var bindings = new ArrayList<AclBinding>();
    for (int i = 0; i < 5; i++) {
      String user = "CN=hooshix-" + args[i + 2] + "-kafka";
      var entity = new ClientQuotaEntity(Map.of(ClientQuotaEntity.USER, user));
      desired.put(entity, Map.of("producer_byte_rate", producerRate, "consumer_byte_rate", consumerRate));
      alterations.add(new ClientQuotaAlteration(entity, List.of(
          new ClientQuotaAlteration.Op("producer_byte_rate", producerRate),
          new ClientQuotaAlteration.Op("consumer_byte_rate", consumerRate))));
      String topic = "quota-fixture-" + i;
      topics.add(new NewTopic(topic, 1, (short) 1));
      for (AclOperation operation : List.of(AclOperation.READ, AclOperation.WRITE, AclOperation.DESCRIBE)) {
        bindings.add(new AclBinding(new ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL),
            new AccessControlEntry("User:" + user, "*", operation, AclPermissionType.ALLOW)));
      }
    }
    try (Admin admin = Admin.create(properties("admin", "quota-admin"))) {
      require(admin.describeClientQuotas(ClientQuotaFilter.all()).entities().get(10, TimeUnit.SECONDS).isEmpty(),
          "EMPTY_DISPOSABLE_QUOTA_STATE_REQUIRED");
      admin.createTopics(topics).all().get(15, TimeUnit.SECONDS);
      admin.createAcls(bindings).all().get(15, TimeUnit.SECONDS);
      admin.alterClientQuotas(alterations).all().get(15, TimeUnit.SECONDS);
      awaitQuotas(admin, desired);
      for (int i = 0; i < 5; i++) {
        try (Admin client = Admin.create(properties(args[i + 2], "quota-denial-" + i))) {
          try {
            client.alterClientQuotas(List.of(alterations.get(i))).all().get(10, TimeUnit.SECONDS);
            throw new IllegalStateException("CLIENT_QUOTA_MUTATION_ALLOWED");
          } catch (ExecutionException denied) {
            require(denied.getCause() instanceof ClusterAuthorizationException, "WRONG_QUOTA_DENIAL");
          }
        }
      }
      var pool = Executors.newFixedThreadPool(5);
      try {
        var jobs = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 5; i++) {
          final int index = i;
          jobs.add(() -> { traffic(args[index + 2], index); return null; });
        }
        for (var task : pool.invokeAll(jobs, 300, TimeUnit.SECONDS)) task.get();
      } finally {
        pool.shutdownNow();
        require(pool.awaitTermination(20, TimeUnit.SECONDS), "QUOTA_CLIENT_CLEANUP_FAILED");
      }
      require(desired.equals(admin.describeClientQuotas(ClientQuotaFilter.all()).entities().get(10, TimeUnit.SECONDS)),
          "USER_QUOTAS_CHANGED_BY_CLIENTS");
    }
    System.out.println("KAFKA_QUOTA_PROOF=Passed");
  }
}
