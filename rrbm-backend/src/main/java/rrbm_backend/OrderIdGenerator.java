package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class OrderIdGenerator {
    
    private final OrderIdCounterRepository counterRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("ddMMyy");
    
    public OrderIdGenerator(OrderIdCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }
    
    /**
     * Generate next order ID in format: DDMMYY-NNNNNN
     * Uses database row-level locking to prevent duplicates when multiple users create orders simultaneously
     */
    @Transactional
    public String generateOrderId() {
        LocalDate today = LocalDate.now();
        String dateKey = today.format(DATE_FORMATTER); // e.g., "181126"
        
        // Find or create counter for today with pessimistic lock
        OrderIdCounter counter = counterRepository.findByDateKeyWithLock(dateKey)
                .orElseGet(() -> {
                    OrderIdCounter newCounter = new OrderIdCounter();
                    newCounter.setDateKey(dateKey);
                    newCounter.setLastNumber(0);
                    return newCounter;
                });
        
        // Increment counter
        counter.setLastNumber(counter.getLastNumber() + 1);
        counterRepository.save(counter);
        
        // Format: DDMMYY-NNNNNN (e.g., 181126-000001)
        String sequenceNumber = String.format("%06d", counter.getLastNumber());
        return dateKey + "-" + sequenceNumber;
    }
}