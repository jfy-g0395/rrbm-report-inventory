package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "po_year_counter")
public class PoYearCounter {

    @Id
    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "last_number", nullable = false)
    private Integer lastNumber = 0;
}
