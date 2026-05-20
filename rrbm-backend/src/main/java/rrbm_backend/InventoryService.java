package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryService {

    private final ProductRepository productRepository;
    private final InventoryMovementRepository movementRepository;

    public InventoryService(ProductRepository productRepository,
                            InventoryMovementRepository movementRepository) {
        this.productRepository = productRepository;
        this.movementRepository = movementRepository;
    }

    /**
     * Deduct stock for all items in an order.
     * Called when an order is successfully created.
     *
     * For each item, we deduct from the warehouse specified in the order item.
     * If that warehouse doesn't have enough stock, we throw an exception
     * which will roll back the entire order (nothing saved).
     */
    @Transactional
    public void deductStockForOrder(Order order, Long userId) {
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                // Product was typed manually (no ID) — skip stock deduction
                // This handles the case where someone enters a product name by hand
                continue;
            }

            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new RuntimeException(
                            "Product not found: " + item.getProductName()));

            String warehouse = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";
            int qty = item.getQuantity();

            // Deduct from the correct warehouse
            switch (warehouse) {
                case "wh1":
                    if (product.getStockWh1() < qty) {
                        throw new RuntimeException(
                            "Insufficient stock in WH1 for " + product.getName()
                            + ". Available: " + product.getStockWh1() + ", Requested: " + qty);
                    }
                    product.setStockWh1(product.getStockWh1() - qty);
                    break;
                case "wh2":
                    if (product.getStockWh2() < qty) {
                        throw new RuntimeException(
                            "Insufficient stock in WH2 for " + product.getName()
                            + ". Available: " + product.getStockWh2() + ", Requested: " + qty);
                    }
                    product.setStockWh2(product.getStockWh2() - qty);
                    break;
                case "wh3":
                    if (product.getStockWh3() < qty) {
                        throw new RuntimeException(
                            "Insufficient stock in WH3 for " + product.getName()
                            + ". Available: " + product.getStockWh3() + ", Requested: " + qty);
                    }
                    product.setStockWh3(product.getStockWh3() - qty);
                    break;
                default:
                    throw new RuntimeException("Unknown warehouse: " + warehouse);
            }

            // Save updated stock
            productRepository.save(product);

            // Log the movement (negative quantity = stock going out)
            logMovement(item.getProductId(), "ORDER_OUT", warehouse, -qty,
                    order.getId(), "Order by " + order.getCustomerName(), userId);
        }
    }

    /**
     * Restore stock for all items in a cancelled order.
     * Called when an order is cancelled.
     *
     * This is the reverse of deductStockForOrder — it adds the stock back
     * to the same warehouse it was taken from.
     */
    @Transactional
    public void restoreStockForCancelledOrder(Order order, Long userId) {
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue; // No product ID = was typed manually, nothing to restore
            }

            Product product = productRepository.findById(item.getProductId())
                    .orElse(null);

            if (product == null) {
                // Product may have been deleted — just log, don't crash
                continue;
            }

            String warehouse = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";
            int qty = item.getQuantity();

            // Add stock back to the correct warehouse
            switch (warehouse) {
                case "wh1": product.setStockWh1(product.getStockWh1() + qty); break;
                case "wh2": product.setStockWh2(product.getStockWh2() + qty); break;
                case "wh3": product.setStockWh3(product.getStockWh3() + qty); break;
            }

            productRepository.save(product);

            // Log the movement (positive quantity = stock coming back)
            logMovement(item.getProductId(), "CANCELLED_RETURN", warehouse, +qty,
                    order.getId(), "Cancellation of order " + order.getId(), userId);
        }
    }

    /**
     * Write one row to inventory_movements.
     * quantity is signed: negative = out, positive = in.
     */
    private void logMovement(Long productId, String movementType,
                              String warehouse, int quantity,
                              String referenceId, String reason, Long userId) {
        InventoryMovement movement = new InventoryMovement();
        movement.setProductId(productId);
        movement.setMovementType(movementType);
        movement.setWarehouse(warehouse);
        movement.setQuantity(quantity);
        movement.setReferenceId(referenceId);
        movement.setReason(reason);
        movement.setUserId(userId);
        movementRepository.save(movement);
    }
}
