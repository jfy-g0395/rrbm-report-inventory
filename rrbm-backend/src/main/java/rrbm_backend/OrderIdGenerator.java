package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class OrderIdGenerator {

    private final OrderIdCounterRepository counterRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("ddMMyy");

    // Single global key — counter never resets between days (V35 migration).
    private static final String GLOBAL_KEY = "GLOBAL";

    public OrderIdGenerator(OrderIdCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    /**
     * Generate next order ID in format: DDMMYY-NNNNNN
     * The DDMMYY prefix reflects the order's creation date.
     * The NNNNNN sequence is a single global counter that never resets — receipts
     * are always numbered higher than any previously issued ID, across all days.
     * Uses database row-level locking to prevent duplicates under concurrent creates.
     */
    @Transactional
    public String generateOrderId() {
        LocalDate today = LocalDate.now();
        String datePrefix = today.format(DATE_FORMATTER); // e.g., "010626"

        // Find or create the single global counter with pessimistic write lock
        OrderIdCounter counter = counterRepository.findByDateKeyWithLock(GLOBAL_KEY)
                .orElseGet(() -> {
                    OrderIdCounter newCounter = new OrderIdCounter();
                    newCounter.setDateKey(GLOBAL_KEY);
                    newCounter.setLastNumber(0);
                    return newCounter;
                });

        // Increment counter
        counter.setLastNumber(counter.getLastNumber() + 1);
        counterRepository.save(counter);

        // Format: DDMMYY-NNNNNN (e.g., 010626-000042)
        String sequenceNumber = String.format("%06d", counter.getLastNumber());
        return datePrefix + "-" + sequenceNumber;
    }

    /**
     * Generate next order ID using the given date as the DDMMYY prefix.
     * Used exclusively by the batch import pipeline for backdating — so DailyReportService
     * can find the order when it closes the daily report for that date (id LIKE 'DDMMYY-%').
     * Uses the same global counter as generateOrderId() — sequence is always monotonically
     * increasing regardless of which date prefix is used.
     */
    @Transactional
    public String generateOrderIdForDate(LocalDate date) {
        String datePrefix = date.format(DATE_FORMATTER); // e.g. "050626"

        OrderIdCounter counter = counterRepository.findByDateKeyWithLock(GLOBAL_KEY)
                .orElseGet(() -> {
                    OrderIdCounter newCounter = new OrderIdCounter();
                    newCounter.setDateKey(GLOBAL_KEY);
                    newCounter.setLastNumber(0);
                    return newCounter;
                });

        counter.setLastNumber(counter.getLastNumber() + 1);
        counterRepository.save(counter);

        return datePrefix + "-" + String.format("%06d", counter.getLastNumber());
    }
}