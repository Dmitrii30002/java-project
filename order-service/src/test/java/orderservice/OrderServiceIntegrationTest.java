package orderservice;

import orderservice.entity.Order;
import orderservice.event.OrderEvent;
import orderservice.repository.OrderRepository;
import orderservice.service.OrderService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
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
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertTrue;

@Testcontainers
@SpringBootTest
public class OrderServiceIntegrationTest {

    static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.3.0")
    );

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb")
        .withUsername("testuser")
        .withPassword("testpass");


    @BeforeAll
    static void setUp() {
        kafka.start();
        postgres.start();
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @AfterAll
    static void tearDown() {
        kafka.stop();
        postgres.stop();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    // ========== ТЕСТ 1: СОХРАНЕНИЕ В БД ==========
    @Test
    void testSaveOrderToDatabase() {
        long start = System.currentTimeMillis();

        Order order = new Order("DB Test User", 150.0, "PENDING");
        
        Order savedOrder = orderService.createOrder(order);
        
        assertThat(savedOrder.getId()).isNotNull();
        assertThat(savedOrder.getCustomerName()).isEqualTo("DB Test User");
        assertThat(savedOrder.getAmount()).isEqualTo(150.0);
        
        Order fromDb = orderRepository.findById(savedOrder.getId()).orElse(null);
        assertThat(fromDb).isNotNull();
        assertThat(fromDb.getCustomerName()).isEqualTo("DB Test User");
        
        System.out.println("Test 1 passed: order saved to DB, id=" + savedOrder.getId());

        long end= System.currentTimeMillis();
        assertTrue(Double.toString((end-start)/ 1000.0), true);
    }

    // ========== ТЕСТ 2: KAFKA + КОНТРАКТ ==========
    @Test
    void testSendOrderEventToKafka() {
        long start = System.currentTimeMillis();
        // Настройка консьюмера для чтения из Kafka
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Order order = new Order("Kafka Test User", 888.88, "PENDING");
        
        Order savedOrder = orderService.createOrder(order);
        Long orderId = savedOrder.getId();
        
        AtomicReference<OrderEvent> receivedEvent = new AtomicReference<>();
        
        //ожидание сообщения из кафки
        await()
            .atMost(Duration.ofSeconds(5))
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
        
        // ПРОВЕРКА КОНТРАКТА
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getCustomerName()).isEqualTo("Kafka Test User");
        assertThat(event.getAmount()).isEqualTo(888.88);
        assertThat(event.getCustomerName()).isNotBlank();
        assertThat(event.getAmount()).isPositive();
        
        System.out.println("Test 2 passed: Kafka message sent and contract verified");

        long end= System.currentTimeMillis();
    }


}