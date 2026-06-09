package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class PurchaseOrderService {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMddyy");

    private final PoYearCounterRepository counterRepository;

    public PurchaseOrderService(PoYearCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    /**
     * Generate the next PO number in format: PO-MMDDYY-XXXXX
     *
     * The MMDDYY prefix reflects the PO's creation date.
     * The XXXXX sequence counts all POs created in the calendar year and
     * resets to 00001 on January 1 each year.
     *
     * Uses a row-level pessimistic write lock on po_year_counter to prevent
     * duplicate numbers when two POs are created simultaneously.
     *
     * Example: 87th PO of 2026 created on June 3 → PO-060326-00087
     */
    @Transactional
    public String generatePoNumber(LocalDate date) {
        int year = date.getYear();

        // Lock the counter row for this year. If no row exists yet (first PO
        // of a new calendar year), insert one before attempting the lock.
        PoYearCounter counter = counterRepository.findByYearWithLock(year)
                .orElseGet(() -> {
                    PoYearCounter newCounter = new PoYearCounter();
                    newCounter.setYear(year);
                    newCounter.setLastNumber(0);
                    return counterRepository.save(newCounter);
                });

        counter.setLastNumber(counter.getLastNumber() + 1);
        counterRepository.save(counter);

        String datePrefix = date.format(DATE_FORMATTER); // e.g. "060326"
        return String.format("PO-%s-%05d", datePrefix, counter.getLastNumber());
    }
}
