package rrbm_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommissionService {

    private static final Logger log = LoggerFactory.getLogger(CommissionService.class);

    private final CommissionPeriodRepository periodRepository;
    private final CommissionEntryRepository  entryRepository;
    private final OrderRepository            orderRepository;

    public CommissionService(CommissionPeriodRepository periodRepository,
                             CommissionEntryRepository entryRepository,
                             OrderRepository orderRepository) {
        this.periodRepository = periodRepository;
        this.entryRepository  = entryRepository;
        this.orderRepository  = orderRepository;
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
            // No OPEN period covers this date. Two distinct sub-cases:
            //   • Backdated late import (order date precedes the earliest OPEN period):
            //     commission is still earned on the sale date but paid out in the next
            //     available cut-off → assign to the earliest OPEN period.
            //   • Future-dated order (date falls after every OPEN period — typically the
            //     covering period has not been created yet): do NOT misfile it into an
            //     earlier open period. Defer entry creation; the covering period's backfill
            //     (findOrdersWithoutCommissionEntries) will claim the order once created.
            CommissionPeriod earliestOpen = periodRepository
                    .findByStatusOrderByStartDateDesc("OPEN")
                    .stream()
                    .min(Comparator.comparing(CommissionPeriod::getStartDate))
                    .orElse(null);
            if (earliestOpen == null) {
                log.warn("No OPEN commission period exists for order {} (date={}). Commission entry skipped.",
                         savedOrder.getId(), orderDate);
                return;
            }
            if (orderDate.isBefore(earliestOpen.getStartDate())) {
                period = earliestOpen;  // backdated late import → next cut-off
            } else {
                log.info("Order {} (date={}) is not covered by any OPEN period and is not backdated; "
                         + "commission entry deferred until a covering period is created.",
                         savedOrder.getId(), orderDate);
                return;
            }
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

    /**
     * Backfills commission entries for existing orders that fall within the given period's
     * date range but don't have entries yet. This handles the case where orders were placed
     * before the period was opened.
     *
     * @param period The period to backfill for
     * @return Map with backfill statistics: agentsProcessed, ordersProcessed, entriesCreated
     */
    @Transactional
    public Map<String, Object> backfillEntriesForPeriod(CommissionPeriod period) {
        LocalDate startDate = period.getStartDate();
        LocalDate endDate = period.getEndDate();
        Long periodId = period.getId();

        int agentsProcessed = 0;
        int ordersProcessed = 0;
        int entriesCreated = 0;

        // Find all agents with orders in the date range
        List<Long> agentIds = orderRepository.findAgentIdsWithOrdersInRange(startDate, endDate);

        for (Long agentId : agentIds) {
            agentsProcessed++;

            // Find orders for this agent in the date range that don't have commission entries
            List<Order> orders = orderRepository.findOrdersWithoutCommissionEntries(
                agentId, startDate, endDate);

            for (Order order : orders) {
                // Double-check: skip if order already has entries (race condition guard)
                if (entryRepository.existsByOrderId(order.getId())) continue;

                // Create commission entries for this order
                LocalDate orderDate = order.getCreatedAt().toLocalDate();

                for (OrderItem item : order.getItems()) {
                    if (item.getOpAmount() == null) continue;

                    CommissionEntry entry = new CommissionEntry();
                    entry.setPeriodId(periodId);
                    entry.setAgentId(agentId);
                    entry.setOrderId(order.getId());
                    entry.setOrderItemId(item.getId());
                    entry.setOrderDate(orderDate);
                    entry.setProductName(item.getProductName());
                    entry.setQuantity(item.getQuantity());
                    entry.setBasePrice(item.getBasePrice());
                    entry.setOpRate(item.getOpRate());
                    entry.setOpPerUnit(item.getOpPerUnit());
                    entry.setOpAmount(item.getOpAmount());
                    entryRepository.save(entry);
                    entriesCreated++;
                }

                ordersProcessed++;
            }
        }

        log.info("Backfill complete for period {}: {} agents, {} orders, {} entries created",
                 periodId, agentsProcessed, ordersProcessed, entriesCreated);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("agentsProcessed", agentsProcessed);
        stats.put("ordersProcessed", ordersProcessed);
        stats.put("entriesCreated", entriesCreated);
        return stats;
    }
}
