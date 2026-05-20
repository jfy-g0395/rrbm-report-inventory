package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import rrbm_backend.ActivityLogService;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderIdGenerator orderIdGenerator;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final MasterKeyService masterKeyService;  // NEW
    private final ActivityLogService activityLogService;

    public OrderService(OrderRepository orderRepository,
                        OrderIdGenerator orderIdGenerator,
                        UserRepository userRepository,
                        InventoryService inventoryService,
                        MasterKeyService masterKeyService,
                        ActivityLogService activityLogService) {
        this.orderRepository = orderRepository;
        this.orderIdGenerator = orderIdGenerator;
        this.userRepository = userRepository;
        this.inventoryService = inventoryService;
        this.masterKeyService = masterKeyService;
        this.activityLogService = activityLogService;
    }

    /**
     * Create a new order with items.
     *
     * The @Transactional annotation means ALL of the following happen
     * as one atomic unit — if any step fails, EVERYTHING rolls back:
     *   1. Generate order ID
     *   2. Save the order and its items
     *   3. Deduct stock from inventory
     *   4. Log inventory movements
     *
     * So if someone orders 200 units but only 50 are in stock,
     * the order is NOT saved and the customer gets an error message.
     */
    @Transactional
    public Order createOrder(Order order, Long createdByUserId) {
        // Generate unique order ID (e.g. 190526-000004)
        String orderId = orderIdGenerator.generateOrderId();
        order.setId(orderId);

        // Set the user who created this order
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + createdByUserId));
        order.setCreatedBy(creator);

        // Link all items to this order (required for the FK relationship)
        order.getItems().forEach(item -> item.setOrder(order));

        // Calculate subtotal, discount, total
        order.calculateTotals();

        // Save order first (items cascade-save with it)
        Order savedOrder = orderRepository.save(order);
        // Log order creation
        activityLogService.log(createdByUserId, "order_create", "Created order " + orderId);

        // NOW deduct stock — if this throws, the whole transaction rolls back
        // including the order save above. The database stays clean.
        inventoryService.deductStockForOrder(savedOrder, createdByUserId);

        return savedOrder;
    }

    /**
     * Get today's orders (for the New Order view summary table)
     */
    public List<Order> getTodaysOrders() {
        return orderRepository.findByCreatedAtDate(LocalDate.now());
    }

    /**
     * Get all orders (for the Order List view)
     */
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    /**
     * Get a single order by ID
     */
    public Order getOrderById(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
    }

    /**
     * Update order status.
     * Valid transitions:
     *   ACTIVE → DELIVERED  (order completed/picked up)
     *   ACTIVE → PENDING    (put on hold)
     *   PENDING → ACTIVE    (resume after hold)
     *   ACTIVE/PENDING → CANCELLED (use cancelOrder instead)
     */
    @Transactional
    public Order updateStatus(String orderId, String newStatus) {
        Order order = getOrderById(orderId);
        String current = order.getStatus();

        if ("CANCELLED".equals(current)) {
            throw new RuntimeException("Cannot change status of a cancelled order");
        }
        if (!List.of("ACTIVE", "PENDING", "DELIVERED", "CLOSED").contains(newStatus)) {
            throw new RuntimeException("Invalid status: " + newStatus);
        }

        order.setStatus(newStatus);
        return orderRepository.save(order);
    }

    /**
     * Cancel an order and restore its stock.
     * Requires a master key (TODO: validate against settings table).
     */
    @Transactional
    public Order cancelOrder(String orderId, String masterKey, Long cancelledByUserId, String reason) {
        // Validate master key before proceeding
        if (!masterKeyService.validateMasterKey(masterKey)) {
            throw new RuntimeException("Invalid master key");
        }
        Order order = getOrderById(orderId);

        if ("CANCELLED".equals(order.getStatus())) {
            throw new RuntimeException("Order is already cancelled");
        }

        User cancelledBy = userRepository.findById(cancelledByUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update order status
        order.setStatus("CANCELLED");
        order.setCancelledBy(cancelledBy);
        order.setCancelledAt(java.time.LocalDateTime.now());
        order.setCancellationReason(reason);

        Order savedOrder = orderRepository.save(order);
        // Log cancellation
        activityLogService.log(cancelledByUserId, "order_cancel", "Cancelled order " + orderId);

        // Restore stock back to inventory (also logs the CANCELLED_RETURN movement)
        inventoryService.restoreStockForCancelledOrder(savedOrder, cancelledByUserId);

        return savedOrder;
    }

    /**
     * Search orders by customer name
     */
    public List<Order> searchByCustomerName(String customerName) {
        return orderRepository.findByCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(customerName);
    }
}
