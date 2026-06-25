package rrbm_backend;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds human-readable descriptions for cash-ledger rows on the read side, so the
 * Cash Flow page and the daily report can show WHAT an expense deduction was for
 * (its category + particulars) instead of a generic "Cash expense #41".
 *
 * Computed live (not stored), so existing and future rows read consistently.
 * Only expense OUTFLOWS (referenceType=EXPENSE, amount &lt; 0) are described; reversal
 * rows (positive deltas from a void/decrease) keep their explicit note. Non-expense
 * rows (opening, add-cash, deposit, adjustment, cash sale) are absent from the map.
 */
@Service
public class CashEntryDescriber {

    private static final String REF_EXPENSE = "EXPENSE";
    private static final int    MAX_CATEGORIES = 3;

    private final ExpenseRepository         expenseRepository;
    private final ExpenseCategoryRepository categoryRepository;

    public CashEntryDescriber(ExpenseRepository expenseRepository,
                              ExpenseCategoryRepository categoryRepository) {
        this.expenseRepository  = expenseRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * @return entryId → description, only for expense-outflow rows that resolve to
     *         a category and/or notes. Callers fall back to the stored note otherwise.
     */
    public Map<Long, String> describe(List<CashLedgerEntry> entries) {
        Map<Long, String> result = new HashMap<>();
        if (entries == null || entries.isEmpty()) return result;

        // Group describable cash rows by their expense id.
        Map<Long, List<CashLedgerEntry>> rowsByExpense = new HashMap<>();
        for (CashLedgerEntry e : entries) {
            if (!REF_EXPENSE.equalsIgnoreCase(e.getReferenceType())) continue;
            if (e.getAmount() == null || e.getAmount().signum() >= 0) continue; // outflows only
            if (e.getReferenceId() == null) continue;
            try {
                Long expId = Long.parseLong(e.getReferenceId().trim());
                rowsByExpense.computeIfAbsent(expId, k -> new ArrayList<>()).add(e);
            } catch (NumberFormatException ignored) { /* keep stored note */ }
        }
        if (rowsByExpense.isEmpty()) return result;

        // Sub-category id → name (single read of the small reference table).
        Map<Long, String> categoryNames = new HashMap<>();
        for (ExpenseCategory c : categoryRepository.findAll()) {
            categoryNames.put(c.getId(), c.getName());
        }

        // Expenses with their line items eagerly loaded (one query, no lazy init).
        for (Expense exp : expenseRepository.findByIdInWithItems(rowsByExpense.keySet())) {
            LinkedHashSet<String> cats = new LinkedHashSet<>();
            if (exp.getItems() != null) {
                for (ExpenseItem it : exp.getItems()) {
                    if (it.getCategoryId() == null) continue;
                    String name = categoryNames.get(it.getCategoryId());
                    if (name != null && !name.isBlank()) cats.add(name.trim());
                }
            }
            String label = buildLabel(cats, exp.getNotes());
            if (label == null) continue;
            for (CashLedgerEntry row : rowsByExpense.getOrDefault(exp.getId(), List.of())) {
                result.put(row.getId(), label);
            }
        }
        return result;
    }

    /** "Cat1, Cat2 — notes" — categories capped at MAX, notes appended when present. */
    private String buildLabel(LinkedHashSet<String> cats, String notes) {
        String catPart = null;
        if (!cats.isEmpty()) {
            List<String> list = new ArrayList<>(cats);
            java.util.Collections.sort(list);  // deterministic order across all views
            if (list.size() > MAX_CATEGORIES) {
                catPart = String.join(", ", list.subList(0, MAX_CATEGORIES)) + ", …";
            } else {
                catPart = String.join(", ", list);
            }
        }
        String notePart = (notes != null && !notes.isBlank()) ? notes.trim() : null;
        if (catPart == null && notePart == null) return null;
        if (catPart == null) return notePart;
        if (notePart == null) return catPart;
        return catPart + " — " + notePart;
    }
}
