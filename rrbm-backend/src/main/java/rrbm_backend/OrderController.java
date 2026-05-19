package rrbm_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rrbm_backend.dto.CreateOrderRequest;
import rrbm_backend.dto.OrderResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {
    
    private final OrderService orderService;
    
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }
    
    /**
     * Create a new order
     * POST /api/orders
     */
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request, 
                                        @RequestHeader("Authorization") String authHeader) {
        try {
            // TODO: Extract user ID from JWT token in Authorization header
            // For now, hardcode to admin user (ID: 3)
            Long userId = 3L;
            
            // Build Order entity from request
            Order order = new Order();
            order.setCustomerName(request.getCustomerName());
            order.setSource(request.getSource());
            order.setAgentName(request.getAgentName());
            order.setFbPage(request.getFbPage());
            order.setEcommercePlatform(request.getEcommercePlatform());
            order.setPaymentMode(request.getPaymentMode() != null ? request.getPaymentMode() : "CASH");
            order.setDiscount(request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO);
            order.setNotes(request.getNotes());
            
            // Build OrderItems
            request.getItems().forEach(itemReq -> {
                OrderItem item = new OrderItem();
                item.setProductId(itemReq.getProductId());
                item.setProductName(itemReq.getProductName());
                item.setQuantity(itemReq.getQuantity());
                item.setUnitPrice(itemReq.getUnitPrice());
                item.setWarehouse(itemReq.getWarehouse());
                order.addItem(item);
            });
            
            // Create order
            Order savedOrder = orderService.createOrder(order, userId);
            
            // Convert to response DTO
            OrderResponse response = convertToResponse(savedOrder);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
            
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Get today's orders
     * GET /api/orders/today
     */
    @GetMapping("/today")
    public ResponseEntity<List<OrderResponse>> getTodaysOrders() {
        List<Order> orders = orderService.getTodaysOrders();
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all orders
     * GET /api/orders
     */
    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<Order> orders = orderService.getAllOrders();
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get order by ID
     * GET /api/orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable String id) {
        try {
            Order order = orderService.getOrderById(id);
            OrderResponse response = convertToResponse(order);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Update order status
     * PUT /api/orders/{id}/status
     * Body: { "status": "DELIVERED" }
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(@PathVariable String id,
                                               @RequestBody Map<String, String> request) {
        try {
            String newStatus = request.get("status");
            if (newStatus == null || newStatus.isEmpty()) {
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "Status is required"));
            }
            
            Order updatedOrder = orderService.updateStatus(id, newStatus);
            OrderResponse response = convertToResponse(updatedOrder);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Cancel an order
     * POST /api/orders/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable String id,
                                        @RequestBody Map<String, String> request,
                                        @RequestHeader("Authorization") String authHeader) {
        try {
            String masterKey = request.get("masterKey");
            String reason = request.get("reason");
            
            if (masterKey == null || masterKey.isEmpty()) {
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "Master key is required"));
            }
            
            // TODO: Extract user ID from JWT token
            Long userId = 3L;
            
            Order cancelledOrder = orderService.cancelOrder(id, masterKey, userId, reason);
            OrderResponse response = convertToResponse(cancelledOrder);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Search orders by customer name
     * GET /api/orders/search?customerName=xxx
     */
    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(@RequestParam String customerName) {
        List<Order> orders = orderService.searchByCustomerName(customerName);
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Helper method to convert Order entity to OrderResponse DTO
     */
    private OrderResponse convertToResponse(Order order) {
        List<OrderResponse.OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderResponse.OrderItemResponse(
                    item.getId(),
                    item.getProductName(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getSubtotal(),
                    item.getWarehouse()
                ))
                .collect(Collectors.toList());
        
        return new OrderResponse(
            order.getId(),
            order.getCustomerName(),
            order.getSource(),
            order.getAgentName(),
            order.getFbPage(),
            order.getEcommercePlatform(),
            order.getPaymentMode(),
            order.getSubtotal(),
            order.getDiscount(),
            order.getTotal(),
            order.getStatus(),
            order.getNotes(),
            order.getCreatedAt(),
            order.getCreatedBy().getFullName(),
            itemResponses
        );
    }
}
