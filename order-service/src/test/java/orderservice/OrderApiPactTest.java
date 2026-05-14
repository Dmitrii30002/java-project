package orderservice;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(PactConsumerTestExt.class)
public class OrderApiPactTest {

    @Pact(consumer = "order-service-client", provider = "order-service")
    public RequestResponsePact createOrderPact(PactDslWithProvider builder) {
        PactDslJsonBody requestBody = new PactDslJsonBody()
            .stringType("customerName", "Test User")
            .numberType("amount", 100.0)
            .stringType("status", "PENDING");

        PactDslJsonBody responseBody = new PactDslJsonBody()
            .integerType("id", 1)
            .stringType("customerName", "Test User")
            .numberType("amount", 100.0)
            .stringType("status", "PENDING")
            .stringType("createdAt");

        return builder
            .given("create order")
            .uponReceiving("a request to create an order")
            .path("/api/orders")
            .method("POST")
            .headers("Content-Type", "application/json")
            .body(requestBody)
            .willRespondWith()
            .status(200)
            .body(responseBody)
            .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "createOrderPact", port = "8081")
    void testCreateOrderApi(MockServer mockServer) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        
        String requestJson = "{\"customerName\":\"Test User\",\"amount\":100.0,\"status\":\"PENDING\"}";
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
        
        ResponseEntity<String> response = restTemplate.exchange(
            mockServer.getUrl() + "/api/orders",
            HttpMethod.POST,
            entity,
            String.class
        );
        
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).contains("Test User");
        assertThat(response.getBody()).contains("100.0");
        
        System.out.println("Pact API test passed!");
    }
}