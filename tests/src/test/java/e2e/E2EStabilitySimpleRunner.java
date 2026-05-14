package e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

public class E2EStabilitySimpleRunner {

    private static final int TOTAL_RUNS = 100;
    
    private static int passedRuns = 0;
    private static int failedRuns = 0;
    private static long totalDuration = 0;
    private static long minDuration = Long.MAX_VALUE;
    private static long maxDuration = 0;
    private static List<Integer> failedRunsList = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8081;
        RestAssured.basePath = "/api";
        
        System.out.println("=========================================");
        System.out.println("STARTING " + TOTAL_RUNS + " E2E TESTS");
        System.out.println("=========================================");
        System.out.println("Make sure services are running on:");
        System.out.println("  - Order Service: http://localhost:8081");
        System.out.println("  - Payment Service: http://localhost:8082");
        System.out.println("  - Order DB: localhost:5433");
        System.out.println("  - Payment DB: localhost:5434");
        System.out.println("=========================================\n");
    }

    @Test
    void runHundredTimes() {
        for (int run = 1; run <= TOTAL_RUNS; run++) {
            long start = System.currentTimeMillis();
            boolean passed = runSingleFlow(run);
            long duration = System.currentTimeMillis() - start;
            
            totalDuration += duration;
            if (duration < minDuration) minDuration = duration;
            if (duration > maxDuration) maxDuration = duration;
            
            if (passed) {
                passedRuns++;
                System.out.println("✅ Run " + run + " | " + duration + " ms");
            } else {
                failedRuns++;
                failedRunsList.add(run);
                System.out.println("❌ Run " + run + " | " + duration + " ms | FAILED");
            }
        }
        
        // Вывод результатов
        double avgDuration = (double) totalDuration / TOTAL_RUNS;
        double stability = (double) passedRuns / TOTAL_RUNS * 100;
        
        System.out.println("\n=========================================");
        System.out.println("E2E STABILITY TEST RESULTS");
        System.out.println("=========================================");
        System.out.println("Total runs:       " + TOTAL_RUNS);
        System.out.println("Passed:           " + passedRuns);
        System.out.println("Failed:           " + failedRuns);
        System.out.println("Stability:        " + String.format("%.2f", stability) + "%");
        System.out.println("Average duration: " + String.format("%.0f", avgDuration) + " ms (" + String.format("%.1f", avgDuration/1000.0) + " sec)");
        System.out.println("Fastest run:      " + minDuration + " ms (" + String.format("%.1f", minDuration/1000.0) + " sec)");
        System.out.println("Slowest run:      " + maxDuration + " ms (" + String.format("%.1f", maxDuration/1000.0) + " sec)");
        
        if (!failedRunsList.isEmpty()) {
            System.out.println("\n❌ Failed runs: " + failedRunsList);
        }
        System.out.println("=========================================");
    }
    
    private boolean runSingleFlow(int runId) {
        Integer orderId = null;
        
        try {
            // Уникальные данные для каждого прогона
            Map<String, Object> orderRequest = new HashMap<>();
            orderRequest.put("customerName", "E2E User " + runId);
            orderRequest.put("amount", 123.45);
            orderRequest.put("status", "PENDING");

            orderId = RestAssured.given()
                .contentType("application/json")
                .body(orderRequest)
                .when()
                .post("/orders")
                .then()
                .statusCode(200)
                .body("customerName", equalTo("E2E User " + runId))
                .body("amount", equalTo(123.45f))
                .body("status", equalTo("PENDING"))
                .extract()
                .path("id");

            String paymentDbUrl = "jdbc:postgresql://localhost:5434/paymentdb";
            
            final Integer finalOrderId = orderId;
            
            await()
                .atMost(Duration.ofSeconds(27))
                .pollInterval(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    try (Connection conn = DriverManager.getConnection(paymentDbUrl, "postgres", "postgres");
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT * FROM payments WHERE order_id = " + finalOrderId)) {
                        
                        org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                        org.assertj.core.api.Assertions.assertThat(rs.getDouble("amount")).isEqualTo(123.45);
                    }
                });
            
            return true;
            
        } catch (Exception e) {
            System.err.println("Run " + runId + " error: " + e.getMessage());
            return false;
        } finally {
            if (orderId != null) {
                cleanup(orderId);
            }
        }
    }
    
    private void cleanup(Integer orderId) {
        String paymentDbUrl = "jdbc:postgresql://localhost:5434/paymentdb";
        String orderDbUrl = "jdbc:postgresql://localhost:5433/orderdb";
        
        try (Connection conn = DriverManager.getConnection(paymentDbUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM payments WHERE order_id = " + orderId);
        } catch (Exception e) {
        }
        
        try (Connection conn = DriverManager.getConnection(orderDbUrl, "postgres", "postgres");
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM orders WHERE id = " + orderId);
        } catch (Exception e) {
        }
    }
}