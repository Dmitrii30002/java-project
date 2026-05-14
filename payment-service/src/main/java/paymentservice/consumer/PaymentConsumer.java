package paymentservice.consumer;

import orderservice.event.OrderEvent;
import paymentservice.entity.Payment;
import paymentservice.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.kafka.annotation.KafkaListener;

@Service
public class PaymentConsumer {
    
    private final PaymentRepository paymentRepository;
    
    public PaymentConsumer(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }
    
    @KafkaListener(topics = "order.created", groupId = "payment-group")
    @Transactional
    public void handleOrderCreated(OrderEvent event) {
        System.out.println("📨 Received order event: OrderId=" + event.getOrderId() + 
                           ", Amount=" + event.getAmount());
        
        Payment payment = new Payment(
            event.getOrderId(),
            event.getAmount(),
            "COMPLETED"
        );

        try {
            long sleepTime = ThreadLocalRandom.current().nextInt(23000, 24301);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        paymentRepository.save(payment);
        System.out.println("Payment saved for order: " + event.getOrderId());
    }
}