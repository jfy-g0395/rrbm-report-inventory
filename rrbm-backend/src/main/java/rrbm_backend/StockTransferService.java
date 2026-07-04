package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lifecycle for scheduled inter-warehouse stock transfers. State transitions
 * (create → approve → complete, plus reschedule/reject/cancel) live here; the
 * actual stock movement on COMPLETE is delegated to
 * {@link InventoryService#applyStockTransfer}. Role-gating (approvers) is enforced
 * by the controller — this service assumes the caller is authorised.
 */
@Service
public class StockTransferService {

    private final StockTransferRepository transferRepository;
    private final InventoryService inventoryService;
    private final ProductRepository productRepository;
    private final ActivityLogService activityLogService;

    public StockTransferService(StockTransferRepository transferRepository,
                                InventoryService inventoryService,
                                ProductRepository productRepository,
                                ActivityLogService activityLogService) {
        this.transferRepository = transferRepository;
        this.inventoryService   = inventoryService;
        this.productRepository  = productRepository;
        this.activityLogService = activityLogService;
    }

    /** One requested line: move qty of productId from→to warehouse. */
    public record LineSpec(Long productId, String fromWarehouse, String toWarehouse, Integer quantity) {}

    /** wh1/wh2 keep their code; wh3 shows as "Balagtas" in user-facing text. */
    static String whLabel(String wh) {
        if (wh == null) return "?";
        return switch (wh.trim().toLowerCase()) {
            case "wh1" -> "WH1";
            case "wh2" -> "WH2";
            case "wh3" -> "Balagtas";
            default    -> wh;
        };
    }

    private static String normWh(String wh, String productLabel) {
        if (wh == null || wh.isBlank())
            throw new RuntimeException("Warehouse is required for \"" + productLabel + "\".");
        String n = wh.trim().toLowerCase();
        if (!n.equals("wh1") && !n.equals("wh2") && !n.equals("wh3"))
            throw new RuntimeException("Invalid warehouse \"" + wh + "\" for \"" + productLabel
                    + "\". Must be wh1, wh2, or Balagtas (wh3).");
        return n;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public List<StockTransfer> list(String status) {
        return (status == null || status.isBlank())
                ? transferRepository.findAllByOrderByCreatedAtDesc()
                : transferRepository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
    }

    public StockTransfer get(Long id) {
        return transferRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Stock transfer not found: " + id));
    }

    // ── Create (records request only — no stock change) ───────────────────────

    @Transactional
    public StockTransfer create(List<LineSpec> lines, LocalDate scheduledDate, String notes,
                                Long userId, String userName) {
        if (lines == null || lines.isEmpty())
            throw new RuntimeException("A stock move needs at least one product line.");

        StockTransfer transfer = new StockTransfer();
        transfer.setStatus("PENDING");
        transfer.setScheduledDate(scheduledDate);
        transfer.setNotes(notes);
        transfer.setRequestedBy(userId);
        transfer.setRequestedByName(userName);

        for (LineSpec spec : lines) {
            if (spec.productId() == null)
                throw new RuntimeException("Each stock move line must reference a product.");
            Product product = productRepository.findById(spec.productId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + spec.productId()));
            if (Boolean.TRUE.equals(product.getIsSet()))
                throw new RuntimeException("Set product \"" + product.getName()
                        + "\" cannot be transferred — move its components instead.");

            String from = normWh(spec.fromWarehouse(), product.getName());
            String to   = normWh(spec.toWarehouse(),   product.getName());
            if (from.equals(to))
                throw new RuntimeException("Source and destination must differ for \""
                        + product.getName() + "\".");
            int qty = spec.quantity() != null ? spec.quantity() : 0;
            if (qty <= 0)
                throw new RuntimeException("Quantity must be greater than zero for \""
                        + product.getName() + "\".");

            StockTransferItem item = new StockTransferItem();
            item.setTransfer(transfer);
            item.setProductId(product.getId());
            item.setProductCode(product.getProductCode());
            item.setProductName(product.getName());
            item.setFromWarehouse(from);
            item.setToWarehouse(to);
            item.setQuantity(qty);
            transfer.getItems().add(item);
        }

        transfer.appendLog("Requested by " + (userName != null ? userName : "user #" + userId)
                + " (" + transfer.getItems().size() + " line(s)).");
        StockTransfer saved = transferRepository.save(transfer);
        activityLogService.log(userId, userName, "STOCK_TRANSFER_REQUEST",
                "Requested stock move #" + saved.getId() + " with " + saved.getItems().size() + " line(s)",
                "STOCK_TRANSFER", String.valueOf(saved.getId()));
        return saved;
    }

    // ── Approve ────────────────────────────────────────────────────────────────

    @Transactional
    public StockTransfer approve(Long id, Long userId, String userName) {
        StockTransfer t = get(id);
        requireStatus(t, "approve", "PENDING");
        t.setStatus("APPROVED");
        t.setApprovedBy(userId);
        t.setApprovedByName(userName);
        t.setApprovedAt(LocalDateTime.now());
        t.appendLog("Approved by " + safeName(userName, userId) + ".");
        StockTransfer saved = transferRepository.save(t);
        activityLogService.log(userId, userName, "STOCK_TRANSFER_APPROVE",
                "Approved stock move #" + id, "STOCK_TRANSFER", String.valueOf(id));
        return saved;
    }

    // ── Reschedule (repeatable; not allowed once terminal/completed) ────────────

    @Transactional
    public StockTransfer reschedule(Long id, LocalDate newDate, Long userId, String userName) {
        StockTransfer t = get(id);
        requireStatus(t, "reschedule", "PENDING", "APPROVED");
        LocalDate old = t.getScheduledDate();
        t.setScheduledDate(newDate);
        t.appendLog("Rescheduled from " + (old != null ? old : "—") + " to "
                + (newDate != null ? newDate : "—") + " by " + safeName(userName, userId) + ".");
        StockTransfer saved = transferRepository.save(t);
        activityLogService.log(userId, userName, "STOCK_TRANSFER_RESCHEDULE",
                "Rescheduled stock move #" + id + " to " + newDate, "STOCK_TRANSFER", String.valueOf(id));
        return saved;
    }

    // ── Reject ──────────────────────────────────────────────────────────────────

    @Transactional
    public StockTransfer reject(Long id, String reason, Long userId, String userName) {
        StockTransfer t = get(id);
        requireStatus(t, "reject", "PENDING", "APPROVED");
        t.setStatus("REJECTED");
        t.setRejectReason(reason);
        t.appendLog("Rejected by " + safeName(userName, userId)
                + (reason != null && !reason.isBlank() ? " — " + reason : "") + ".");
        StockTransfer saved = transferRepository.save(t);
        activityLogService.log(userId, userName, "STOCK_TRANSFER_REJECT",
                "Rejected stock move #" + id, "STOCK_TRANSFER", String.valueOf(id));
        return saved;
    }

    // ── Cancel ──────────────────────────────────────────────────────────────────

    @Transactional
    public StockTransfer cancel(Long id, Long userId, String userName) {
        StockTransfer t = get(id);
        requireStatus(t, "cancel", "PENDING", "APPROVED");
        t.setStatus("CANCELLED");
        t.appendLog("Cancelled by " + safeName(userName, userId) + ".");
        StockTransfer saved = transferRepository.save(t);
        activityLogService.log(userId, userName, "STOCK_TRANSFER_CANCEL",
                "Cancelled stock move #" + id, "STOCK_TRANSFER", String.valueOf(id));
        return saved;
    }

    // ── Complete (the only path that moves stock) ───────────────────────────────

    @Transactional
    public StockTransfer complete(Long id, Long userId, String userName) {
        StockTransfer t = get(id);
        requireStatus(t, "complete", "APPROVED");

        for (StockTransferItem item : t.getItems()) {
            String reason = "Stock move #" + id + ": " + item.getQuantity() + " × \""
                    + item.getProductName() + "\" " + whLabel(item.getFromWarehouse())
                    + " → " + whLabel(item.getToWarehouse());
            inventoryService.applyStockTransfer(
                    item.getProductId(), item.getFromWarehouse(), item.getToWarehouse(),
                    item.getQuantity(), String.valueOf(id), reason, userId);
        }

        t.setStatus("COMPLETED");
        t.setCompletedAt(LocalDateTime.now());
        t.appendLog("Completed by " + safeName(userName, userId)
                + " — stock moved for " + t.getItems().size() + " line(s).");
        StockTransfer saved = transferRepository.save(t);
        activityLogService.log(userId, userName, "STOCK_TRANSFER_COMPLETE",
                "Completed stock move #" + id + " (" + t.getItems().size() + " line(s))",
                "STOCK_TRANSFER", String.valueOf(id));
        return saved;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void requireStatus(StockTransfer t, String action, String... allowed) {
        for (String s : allowed) if (s.equals(t.getStatus())) return;
        throw new RuntimeException("Cannot " + action + " a stock move that is " + t.getStatus()
                + " (allowed only when " + String.join(" or ", allowed) + ").");
    }

    private static String safeName(String userName, Long userId) {
        return userName != null && !userName.isBlank() ? userName : "user #" + userId;
    }
}
