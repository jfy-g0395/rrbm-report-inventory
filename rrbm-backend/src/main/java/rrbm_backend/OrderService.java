package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final OrderIdGenerator orderIdGenerator;
    private final UserRepository userRepository;
    
    public OrderService(OrderRepository orderRepository, 
                       OrderIdGenerator orderIdGenerator,
                       UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.orderIdGenerator = orderIdGenerator;
        this.userRepository = userRepository;
    }
    
    /**
     * Create a new order with items
     */
    @Transactional
    public Order createOrder(Order order, Long createdByUserId) {
        // Generate unique order ID
        String orderId = orderIdGenerator.generateOrderId();
        order.setId(orderId);
        
        // Set created by user
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        order.setCreatedBy(creator);
        
        // Link all items to this order
        order.getItems().forEach(item -> item.setOrder(order));
        
        // Calculate totals
        order.calculateTotals();
        
        // Save order (cascade will save items too)
        return orderRepository.save(order);
    }
    
    /**
     * Get today's orders
     */
    public List<Order> getTodaysOrders() {
        return orderRepository.findByCreatedAtDate(LocalDate.now());
    }
    
    /**
     * Get all orders
     */
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
    
    /**
     * Get order by ID
     */
    public Order getOrderById(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
    }
    
    /**
     * Update order status.
     * Valid transitions:
     *   ACTIVE → DELIVERED  (order completed)
     *   ACTIVE → PENDING    (put on hold for changes)
     *   PENDING → ACTIVE    (resume after changes)
     *   ACTIVE/PENDING → CANCELLED (cancel with master key — use cancelOrder instead)
     */
    @Transactional
    public Order updateStatus(String orderId, String newStatus) {
        Order order = getOrderById(orderId);
        String currentStatus = order.getStatus();
        
        // Validate the transition
        if ("CANCELLED".equals(currentStatus)) {
            throw new RuntimeException("Cannot change status of a cancelled order");
        }
        if ("DELIVERED".equals(currentStatus) && !"ACTIVE".equals(newStatus)) {
            throw new RuntimeException("Delivered orders can only be set back to Active");
        }
        
        // Validate the new status value
        if (!List.of("ACTIVE", "PENDING", "DELIVERED", "CLOSED").contains(newStatus)) {
            throw new RuntimeException("Invalid status: " + newStatus);
        }
        
        order.setStatus(newStatus);
        return orderRepository.save(order);
    }
    
    /**
     * Cancel order with master key validation
     */
    @Transactional
    public Order cancelOrder(String orderId, String masterKey, Long cancelledByUserId, String reason) {
        // TODO: Validate master key against settings table
        // For now, we'll implement this in the next iteration
        
        Order order = getOrderById(orderId);
        
        if ("CANCELLED".equals(order.getStatus())) {
            throw new RuntimeException("Order is already cancelled");
        }
        
        User cancelledBy = userRepository.findById(cancelledByUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        order.setStatus("CANCELLED");
        order.setCancelledBy(cancelledBy);
        order.setCancelledAt(java.time.LocalDateTime.now());
        order.setCancellationReason(reason);
        
        return orderRepository.save(order);
    }
    
    /**
     * Search orders by customer name
     */
    public List<Order> searchByCustomerName(String customerName) {
        return orderRepository.findByCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(customerName);
    }
}
