package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

/**
 * A single line item within an Expense record.
 */
@Data
@Entity
@Table(name = "expense_items")
public class ExpenseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @Column(name = "item_description", nullable = false, length = 300)
    private String itemDescription;

    /**
     * FK to expense_categories.id (added V49).
     * Nullable so pre-existing free-text rows remain valid.
     * New entries entered through the UI are expected to supply a category.
     */
    @Column(name = "category_id")
    private Long categoryId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;
}
