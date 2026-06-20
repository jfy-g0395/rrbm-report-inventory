package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "daily_reports")
public class DailyReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_date", unique = true, nullable = false)
    private LocalDate reportDate;

    @Column(name = "total_orders") private int totalOrders;
    @Column(name = "total_revenue") private BigDecimal totalRevenue = BigDecimal.ZERO;
    @Column(name = "total_cancelled") private int totalCancelled;
    @Column(name = "cancelled_amount") private BigDecimal cancelledAmount = BigDecimal.ZERO;
    @Column(name = "total_items_sold") private int totalItemsSold;
    @Column(name = "top_product") private String topProduct;
    @Column(name = "top_product_qty") private int topProductQty;
    @Column(name = "walk_in_count") private int walkInCount;
    @Column(name = "agent_count") private int agentCount;
    @Column(name = "ecommerce_count") private int ecommerceCount;
    @Column(name = "fb_page_count") private int fbPageCount;
    @Column(name = "closed_by") private Long closedBy;
    @Column(name = "closed_at") private OffsetDateTime closedAt;
    @Column(name = "notes") private String notes;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    // ── Transaction-ledger accounting snapshot (V15) ──────────────────
    // Populated at close time from the transactions table.
    // Nullable so existing closed reports remain valid.
    @Column(name = "gross_sales")        private BigDecimal grossSales;
    @Column(name = "refunds_total")      private BigDecimal refundsTotal;
    @Column(name = "adjustments_total")  private BigDecimal adjustmentsTotal;
    @Column(name = "net_sales")          private BigDecimal netSales;
    @Column(name = "total_transactions") private Integer totalTransactions;

    // ── Force-close unfulfilled order tracking (V27) ──────────────────
    @Column(name = "unfulfilled_orders") private int unfulfilledOrders = 0;
    @Column(name = "unfulfilled_amount") private BigDecimal unfulfilledAmount = BigDecimal.ZERO;

    // ── Expense tracking (V61) ──────────────────────────────────────
    @Column(name = "total_expenses") private BigDecimal totalExpenses = BigDecimal.ZERO;
    @Column(name = "expenses_count") private int expensesCount;

    // ── Pizza box volume (V75) ──────────────────────────────────────
    // Total quantity sold for products in the "Pizza Box" category that day.
    @Column(name = "total_pizza_boxes") private int totalPizzaBoxes;

    // --- Getters & Setters ---
    public Long getId() { return id; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }
    public int getTotalOrders() { return totalOrders; }
    public void setTotalOrders(int totalOrders) { this.totalOrders = totalOrders; }
    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }
    public int getTotalCancelled() { return totalCancelled; }
    public void setTotalCancelled(int totalCancelled) { this.totalCancelled = totalCancelled; }
    public BigDecimal getCancelledAmount() { return cancelledAmount; }
    public void setCancelledAmount(BigDecimal cancelledAmount) { this.cancelledAmount = cancelledAmount; }
    public int getTotalItemsSold() { return totalItemsSold; }
    public void setTotalItemsSold(int totalItemsSold) { this.totalItemsSold = totalItemsSold; }
    public String getTopProduct() { return topProduct; }
    public void setTopProduct(String topProduct) { this.topProduct = topProduct; }
    public int getTopProductQty() { return topProductQty; }
    public void setTopProductQty(int topProductQty) { this.topProductQty = topProductQty; }
    public int getWalkInCount() { return walkInCount; }
    public void setWalkInCount(int walkInCount) { this.walkInCount = walkInCount; }
    public int getAgentCount() { return agentCount; }
    public void setAgentCount(int agentCount) { this.agentCount = agentCount; }
    public int getEcommerceCount() { return ecommerceCount; }
    public void setEcommerceCount(int ecommerceCount) { this.ecommerceCount = ecommerceCount; }
    public int getFbPageCount() { return fbPageCount; }
    public void setFbPageCount(int fbPageCount) { this.fbPageCount = fbPageCount; }
    public Long getClosedBy() { return closedBy; }
    public void setClosedBy(Long closedBy) { this.closedBy = closedBy; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public BigDecimal getGrossSales()              { return grossSales; }
    public void setGrossSales(BigDecimal v)        { this.grossSales = v; }
    public BigDecimal getRefundsTotal()            { return refundsTotal; }
    public void setRefundsTotal(BigDecimal v)      { this.refundsTotal = v; }
    public BigDecimal getAdjustmentsTotal()        { return adjustmentsTotal; }
    public void setAdjustmentsTotal(BigDecimal v)  { this.adjustmentsTotal = v; }
    public BigDecimal getNetSales()                { return netSales; }
    public void setNetSales(BigDecimal v)          { this.netSales = v; }
    public Integer getTotalTransactions()          { return totalTransactions; }
    public void setTotalTransactions(Integer v)    { this.totalTransactions = v; }

    public int getUnfulfilledOrders()                      { return unfulfilledOrders; }
    public void setUnfulfilledOrders(int v)                { this.unfulfilledOrders = v; }
    public BigDecimal getUnfulfilledAmount()               { return unfulfilledAmount; }
    public void setUnfulfilledAmount(BigDecimal v)         { this.unfulfilledAmount = v; }

    public BigDecimal getTotalExpenses()                   { return totalExpenses; }
    public void setTotalExpenses(BigDecimal v)             { this.totalExpenses = v; }
    public int getExpensesCount()                          { return expensesCount; }
    public void setExpensesCount(int v)                    { this.expensesCount = v; }

    public int getTotalPizzaBoxes()                        { return totalPizzaBoxes; }
    public void setTotalPizzaBoxes(int v)                  { this.totalPizzaBoxes = v; }

    public OffsetDateTime getCreatedAt()                   { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt)     { this.createdAt = createdAt; }
}
