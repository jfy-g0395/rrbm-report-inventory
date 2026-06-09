package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A category (or sub-category) for expense classification.
 *
 * Hierarchy:
 *   parent_id IS NULL  → primary category (e.g. FACILITY, UTILITY)
 *   parent_id IS NOT NULL → sub-category nested under a primary
 *
 * System-defined categories are seeded by V49 and cannot be deleted via the UI.
 * Admins may add custom sub-categories under any primary.
 */
@Data
@Entity
@Table(name = "expense_categories")
public class ExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Short all-caps code, e.g. "FACILITY".
     * Set only for primary categories; NULL for sub-categories.
     * UNIQUE in the DB (PostgreSQL UNIQUE allows multiple NULLs).
     */
    @Column(length = 30, unique = true)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * NULL = top-level primary category.
     * Non-null = id of the parent primary category.
     * Stored as a plain Long rather than @ManyToOne to keep reads lightweight;
     * the full parent object is never needed in a single entity load.
     */
    @Column(name = "parent_id")
    private Long parentId;

    /**
     * TRUE for all rows seeded by V49 (system-defined categories).
     * Custom categories created by admins will have this FALSE.
     */
    @Column(name = "is_system_defined", nullable = false)
    private boolean systemDefined = true;

    /** When true the UI prompts for a receipt/reference number on entry. */
    @Column(name = "requires_receipt", nullable = false)
    private boolean requiresReceipt = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Controls display order within a group; lower value = shown first. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
