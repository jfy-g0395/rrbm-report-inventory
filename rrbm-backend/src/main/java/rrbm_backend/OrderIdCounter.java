package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "order_id_counter")
public class OrderIdCounter {
    
    @Id
    @Column(name = "date_key", length = 6)
    private String dateKey; // Format: DDMMYY
    
    @Column(name = "last_number", nullable = false)
    private Integer lastNumber = 0;
}