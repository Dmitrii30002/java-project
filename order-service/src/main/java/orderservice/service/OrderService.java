package orderservice.service;

import orderservice.entity.Order;
import orderservice.event.OrderEvent;
import orderservice.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.core.KafkaTemplate;

@Service
public class OrderService {
    
    private static final String ORDER_TOPIC = "order.created";
    
    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
    
    public OrderService(OrderRepository orderRepository, 
                        KafkaTemplate<String, OrderEvent> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }
    
    @Transactional
    public Order createOrder(Order order) {
        order.setStatus("PENDING");
        Order savedOrder = orderRepository.save(order);
        
        OrderEvent event = new OrderEvent(
            savedOrder.getId(),
            savedOrder.getCustomerName(),
            savedOrder.getAmount()
        );
        
        kafkaTemplate.send(ORDER_TOPIC, event);
        
        return savedOrder;
    }

    public Order getOrder(Long id) {
        return orderRepository.findById(id).orElse(null);
    }
}