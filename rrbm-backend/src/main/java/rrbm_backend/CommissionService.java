package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
public class CommissionService {

    private final CommissionPeriodRepository periodRepository;
    private final CommissionEntryRepository  entryRepository;

    public CommissionService(CommissionPeriodRepository periodRepository,
                             CommissionEntryRepository entryRepository) {
        this.periodRepository = periodRepository;
        this.entryRepository  = entryRepository;
    }

    /**
     * Creates commission_entry rows for each qualifying item on a newly saved order.
     * Qualifying: order has an agentId, the item has opAmount set, and an OPEN period
     * covers the order date. If no such period exists, nothing is written.
     */
    @Transactional
    public void createEntriesForOrder(Order savedOrder, Long userId) {
        if (savedOrder.getAgentId() == null) return;
        if (entryRepository.existsByOrderId(savedOrder.getId())) return;

        LocalDate orderDate = savedOrder.getCreatedAt().toLocalDate();

        // ── Find the commission period to assign this entry to ───────────────
        // Normal path: find an OPEN period that covers the order date.
        // Late-import path: if the covering period is CLOSED (backdated order),
        //   assign to the earliest available OPEN period — the next payout cycle.
        //   The entry's orderDate still records the actual sale date for audit.
        List<CommissionPeriod> coveringOpen = periodRepository
                .findByStartDateLessThanEqualAndEndDateGreaterThanEqual(orderDate, orderDate)
                .stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .toList();

        CommissionPeriod period;
        if (!coveringOpen.isEmpty()) {
            period = coveringOpen.get(0);  // on-time order: normal assignment
        } else {
            // No OPEN period covers this date — late import into a CLOSED period.
            // Business rule: commission is still earned on the sale date but paid out
            // in the next available cut-off (earliest OPEN period).
            period = periodRepository
                    .findByStatusOrderByStartDateDesc("OPEN")
                    .stream()
                    .min(Comparator.comparing(CommissionPeriod::getStartDate))
                    .orElse(null);
            if (period == null) return;    // no open period anywhere — skip
        }

        for (OrderItem item : savedOrder.getItems()) {
            if (item.getOpAmount() == null) continue;

            CommissionEntry entry = new CommissionEntry();
            entry.setPeriodId(period.getId());
            entry.setAgentId(savedOrder.getAgentId());
            entry.setOrderId(savedOrder.getId());
            entry.setOrderItemId(item.getId());
            entry.setOrderDate(orderDate);
            entry.setProductName(item.getProductName());
            entry.setQuantity(item.getQuantity());
            entry.setBasePrice(item.getBasePrice());
            entry.setOpRate(item.getOpRate());
            entry.setOpPerUnit(item.getOpPerUnit());
            entry.setOpAmount(item.getOpAmount());
            entryRepository.save(entry);
        }
    }
}
