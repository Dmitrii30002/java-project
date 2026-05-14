package orderservice.event;

public class OrderEvent {
    private Long orderId;
    private String customerName;
    private Double amount;
    
    public OrderEvent() {}
    
    public OrderEvent(Long orderId, String customerName, Double amount) {
        this.orderId = orderId;
        this.customerName = customerName;
        this.amount = amount;
    }
    
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
}