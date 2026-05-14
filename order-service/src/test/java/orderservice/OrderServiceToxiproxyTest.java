package orderservice;

import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import orderservice.entity.Order;
import orderservice.service.OrderService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
public class OrderServiceToxiproxyTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("orderdb")
            .withUsername("postgres")
            .withPassword("postgres");

    @Autowired
    private OrderService orderService;

    static eu.rekawek.toxiproxy.Proxy kafkaProxy;

    @BeforeAll
    static void setUp() throws Exception {
        postgres.start();

        ToxiproxyClient client = new ToxiproxyClient("localhost", 8474);
        kafkaProxy = client.getProxy("kafka");
        kafkaProxy.enable();

        System.out.println("Proxy ready");
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9093");
    }

    @Test
    void testCreateOrderWithNormalKafka() {
        Order order = new Order("Normal User", 100.0, "PENDING");
        Order saved = orderService.createOrder(order);
        assertThat(saved.getId()).isNotNull();
        System.out.println("Normal test passed: " + saved.getId());
    }

    @Test
    void testCreateOrderWithKafkaLatency() throws Exception {
        kafkaProxy.toxics()
                .latency("latency", ToxicDirection.UPSTREAM, 5000);

        Order order = new Order("Latency User", 200.0, "PENDING");
        Order saved = orderService.createOrder(order);
        assertThat(saved.getId()).isNotNull();
        System.out.println("Latency test passed: " + saved.getId());

        kafkaProxy.toxics().get("latency").remove();
    }
}

/*
curl -X POST http://localhost:8474/proxies -H "Content-Type: application/json" -d "{\"name\":\"kafka\",\"listen\":\"0.0.0.0:9093\",\"upstream\":\"kafka:9092\",\"enabled\":true}" 
*/

/*
docker exec $(docker ps -qf "name=kafka") kafka-topics --create --topic order.created --bootstrap-server localhost:9092
*/
