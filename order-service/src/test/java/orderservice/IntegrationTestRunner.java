package orderservice;

import orderservice.entity.Order;
import orderservice.event.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
public class IntegrationTestRunner {

    static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.3.0")
    );

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb")
        .withUsername("testuser")
        .withPassword("testpass");

    @Autowired
    private orderservice.service.OrderService orderService;

    @Autowired
    private orderservice.repository.OrderRepository orderRepository;

    @BeforeAll
    static void setUp() {
        kafka.start();
        postgres.start();
        System.out.println("=========================================");
        System.out.println("STARTING 100 INTEGRATION TESTS");
        System.out.println("=========================================");
        System.out.println("Kafka: " + kafka.getBootstrapServers());
        System.out.println("Postgres: " + postgres.getJdbcUrl());
        System.out.println("=========================================\n");
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Test
    void runHundredTimes() {
        int totalRuns = 100;
        int passed = 0;
        int failed = 0;
        List<Integer> failedRuns = new ArrayList<>();
        List<Long> dbDurations = new ArrayList<>();
        List<Long> kafkaDurations = new ArrayList<>();

        for (int run = 1; run <= totalRuns; run++) {
            System.out.println("\n--- RUN " + run + " ---");
            
            long dbStart = System.currentTimeMillis();
            boolean dbPassed = testDatabaseSave(run);
            long dbDuration = System.currentTimeMillis() - dbStart;
            dbDurations.add(dbDuration);
            
            long kafkaStart = System.currentTimeMillis();
            boolean kafkaPassed = testKafkaEvent(run);
            long kafkaDuration = System.currentTimeMillis() - kafkaStart;
            kafkaDurations.add(kafkaDuration);
            
            boolean bothPassed = dbPassed && kafkaPassed;
            
            if (bothPassed) {
                passed++;
                System.out.printf("✅ Run %d | DB: %d ms | Kafka: %d ms%n", run, dbDuration, kafkaDuration);
            } else {
                failed++;
                failedRuns.add(run);
                System.out.printf("❌ Run %d | DB: %s | Kafka: %s%n", 
                    run, 
                    dbPassed ? dbDuration + " ms" : "FAILED",
                    kafkaPassed ? kafkaDuration + " ms" : "FAILED");
            }
        }

        printResults("DATABASE TEST", dbDurations, passed, failed, failedRuns);
        printResults("KAFKA TEST", kafkaDurations, passed, failed, failedRuns);
    }

    private boolean testDatabaseSave(int runId) {
        try {
            String uniqueName = "DB_User_" + runId + "_" + System.currentTimeMillis();
            Order order = new Order(uniqueName, 150.0, "PENDING");
            
            Order savedOrder = orderService.createOrder(order);
            
            if (savedOrder.getId() == null) return false;
            if (!savedOrder.getCustomerName().equals(uniqueName)) return false;
            if (savedOrder.getAmount() != 150.0) return false;
            
            Order fromDb = orderRepository.findById(savedOrder.getId()).orElse(null);
            if (fromDb == null) return false;
            if (!fromDb.getCustomerName().equals(uniqueName)) return false;
            
            // Чистим за собой
            orderRepository.deleteById(savedOrder.getId());
            
            return true;
        } catch (Exception e) {
            System.err.println("DB Test error in run " + runId + ": " + e.getMessage());
            return false;
        }
    }

    private boolean testKafkaEvent(int runId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + runId);  // уникальный group.id
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        try {
            String uniqueName = "Kafka_User_" + runId + "_" + System.currentTimeMillis();
            Order order = new Order(uniqueName, 888.88, "PENDING");
            
            Order savedOrder = orderService.createOrder(order);
            Long orderId = savedOrder.getId();
            
            AtomicReference<OrderEvent> receivedEvent = new AtomicReference<>();
            
            await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    try (KafkaConsumer<String, OrderEvent> consumer = new KafkaConsumer<>(props)) {
                        consumer.subscribe(Collections.singletonList("order.created"));
                        var records = consumer.poll(Duration.ofSeconds(2));
                        
                        for (ConsumerRecord<String, OrderEvent> record : records) {
                            if (record.value() != null && record.value().getOrderId().equals(orderId)) {
                                receivedEvent.set(record.value());
                                break;
                            }
                        }
                        assertThat(receivedEvent.get()).isNotNull();
                    }
                });
            
            OrderEvent event = receivedEvent.get();
            
            assertThat(event.getOrderId()).isEqualTo(orderId);
            assertThat(event.getCustomerName()).isEqualTo(uniqueName);
            assertThat(event.getAmount()).isEqualTo(888.88);
            assertThat(event.getCustomerName()).isNotBlank();
            assertThat(event.getAmount()).isPositive();
            
            orderRepository.deleteById(orderId);
            
            return true;
        } catch (Exception e) {
            System.err.println("Kafka Test error in run " + runId + ": " + e.getMessage());
            return false;
        }
    }

    private void printResults(String testName, List<Long> durations, int passed, int failed, List<Integer> failedRuns) {
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        
        for (long d : durations) {
            sum += d;
            if (d < min) min = d;
            if (d > max) max = d;
        }
        
        double avg = durations.isEmpty() ? 0 : (double) sum / durations.size();
        double stability = (double) passed / 100 * 100;
        
        System.out.println("\n========== " + testName + " RESULTS ==========");
        System.out.println("Total runs:      100");
        System.out.println("Passed:          " + passed);
        System.out.println("Failed:          " + failed);
        System.out.println("Stability:       " + String.format("%.2f", stability) + "%");
        System.out.println("Average time:    " + String.format("%.0f", avg) + " ms (" + String.format("%.1f", avg/1000.0) + " sec)");
        System.out.println("Fastest run:     " + min + " ms (" + String.format("%.1f", min/1000.0) + " sec)");
        System.out.println("Slowest run:     " + max + " ms (" + String.format("%.1f", max/1000.0) + " sec)");
        
        if (!failedRuns.isEmpty()) {
            System.out.println("Failed runs:     " + failedRuns);
        }
        System.out.println("=========================================\n");
    }
}