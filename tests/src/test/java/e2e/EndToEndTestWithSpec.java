package e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

public class EndToEndTestWithSpec {

    private static Integer lastOrderId;

    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8081;
        RestAssured.basePath = "/api";
    }

    @AfterEach
    void cleanUp() {
        if (lastOrderId != null) {
            String paymentDbUrl = "jdbc:postgresql://localhost:5433/paymentdb";
            try (Connection conn = DriverManager.getConnection(paymentDbUrl, "postgres", "postgres");
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM payments WHERE order_id = " + lastOrderId);
            } catch (Exception e) {
                System.out.println("Cleanup failed: " + e.getMessage());
            }
        }
    }

    @Test
    void testFullOrderFlow() {
        long startTime = System.currentTimeMillis();

        Map<String, Object> orderRequest = new HashMap<>();
        orderRequest.put("customerName", "E2E User");
        orderRequest.put("amount", 123.45);
        orderRequest.put("status", "PENDING");

        lastOrderId = RestAssured.given()
            .contentType("application/json")
            .body(orderRequest)
            .when()
            .post("/orders")
            .then()
            .statusCode(200)
            .body("customerName", equalTo("E2E User"))
            .body("amount", equalTo(123.45f))
            .body("status", equalTo("PENDING"))
            .extract()
            .path("id");


        String paymentDbUrl = "jdbc:postgresql://localhost:5434/paymentdb";

        await()
            .atMost(Duration.ofSeconds(35))
            .pollInterval(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                try (Connection conn = DriverManager.getConnection(paymentDbUrl, "postgres", "postgres");
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM payments WHERE order_id = " + lastOrderId)) {
                    
                    org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(rs.getDouble("amount")).isEqualTo(123.45);
                }
            });

            
        try (Connection conn = DriverManager.getConnection(paymentDbUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM payments WHERE order_id = " + lastOrderId);
        } catch (Exception e) {
            System.out.println("Cleanup failed: " + e.getMessage());
        }

        String orderDbUrl = "jdbc:postgresql://localhost:5433/orderdb";
        try (Connection conn = DriverManager.getConnection(orderDbUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM orders WHERE id = " + lastOrderId);
        } catch (Exception e) {
            System.out.println("Cleanup failed: " + e.getMessage());
        }

        long paymentTime = System.currentTimeMillis();
        System.out.println("Time: " + (paymentTime - startTime));
    }
}