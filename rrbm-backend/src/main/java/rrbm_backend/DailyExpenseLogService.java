package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Builds and stores the persisted per-day expense snapshot ("Expense Log", V89).
 *
 * <p>{@link #snapshotForDate} is called whenever a day is closed (manual daily close, import
 * close, or the Add Records "create daily report" checkbox). It reuses the existing expense
 * queries so the stored snapshot matches the live daily expense report, then upserts one
 * {@link DailyExpenseLog} row per date. Idempotent: re-closing a day overwrites its snapshot.
 */
@Service
public class DailyExpenseLogService {

    private static final Logger log = LoggerFactory.getLogger(DailyExpenseLogService.class);

    private final ExpenseRepository         expenseRepository;
    private final DailyExpenseLogRepository logRepository;
    private final ObjectMapper              mapper = new ObjectMapper();

    public DailyExpenseLogService(ExpenseRepository expenseRepository,
                                  DailyExpenseLogRepository logRepository) {
        this.expenseRepository = expenseRepository;
        this.logRepository     = logRepository;
    }

    /** Build (or refresh) the persisted expense snapshot for {@code date}. Never throws to the
     *  caller — a snapshot failure must not roll back the daily close that triggered it. Runs in
     *  its own transaction (REQUIRES_NEW) so a failure here can't poison the close transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void snapshotForDate(LocalDate date, Long userId) {
        if (date == null) return;
        try {
            List<Expense> expenses = expenseRepository.findByDateWithItems(date).stream()
                    .filter(e -> !e.isVoided() && !"VOIDED".equalsIgnoreCase(e.getStatus()))
                    .toList();

            List<Map<String, Object>> entries = new ArrayList<>();
            for (Expense e : expenses) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id",              e.getId());
                em.put("adminName",       e.getAdminName());
                em.put("paymentMethod",   e.getPaymentMethod());
                em.put("notes",           e.getNotes());
                em.put("referenceNumber", e.getReferenceNumber());
                em.put("totalAmount",     e.getTotalAmount());
                em.put("recordingOnly",   e.isRecordingOnly());
                em.put("imported",        e.isImported());
                List<Map<String, Object>> items = new ArrayList<>();
                for (ExpenseItem it : e.getItems()) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("description", it.getItemDescription());
                    im.put("categoryId",  it.getCategoryId());
                    im.put("amount",      it.getAmount());
                    items.add(im);
                }
                em.put("items", items);
                entries.add(em);
            }

            List<Map<String, Object>> byCategory = new ArrayList<>();
            for (Object[] r : expenseRepository.sumByPrimaryCategoryForDate(date)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code",    r[0]);
                m.put("name",    r[1]);
                m.put("total",   r[2]);
                m.put("entries", r[3]);
                byCategory.add(m);
            }

            List<Map<String, Object>> byPaymentMethod = new ArrayList<>();
            for (Object[] r : expenseRepository.sumByPaymentMethodForDate(date)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("method", r[0]);
                m.put("total",  r[1]);
                m.put("count",  r[2]);
                byPaymentMethod.add(m);
            }

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("entries",         entries);
            snapshot.put("byCategory",      byCategory);
            snapshot.put("byPaymentMethod", byPaymentMethod);

            BigDecimal total = expenseRepository.sumNonVoidedForDate(date);
            if (total == null) total = BigDecimal.ZERO;

            DailyExpenseLog row = logRepository.findByReportDate(date).orElseGet(DailyExpenseLog::new);
            row.setReportDate(date);
            row.setTotalAmount(total);
            row.setEntryCount(entries.size());
            row.setSnapshotJson(mapper.writeValueAsString(snapshot));
            row.setCreatedBy(userId);
            row.setCreatedAt(java.time.OffsetDateTime.now());
            logRepository.save(row);
        } catch (Exception ex) {
            log.warn("Failed to build expense-log snapshot for {}: {}", date, ex.getMessage());
        }
    }
}
