package rrbm_backend;

import jakarta.persistence.*;

/**
 * Maps a "set product" to one of its component products.
 * E.g. "Cochinillo Set" → [Cochinillo Top ×1, Cochinillo Bottom ×1].
 *
 * Plain column IDs — no lazy-loaded associations.
 */
@Entity
@Table(name = "product_set_components")
public class ProductSetComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "set_product_id", nullable = false)
    private Long setProductId;

    @Column(name = "component_product_id", nullable = false)
    private Long componentProductId;

    @Column(name = "quantity_per_set", nullable = false)
    private Integer quantityPerSet = 1;

    public Long getId()                            { return id; }
    public void setId(Long id)                     { this.id = id; }

    public Long getSetProductId()                  { return setProductId; }
    public void setSetProductId(Long setProductId) { this.setProductId = setProductId; }

    public Long getComponentProductId()                      { return componentProductId; }
    public void setComponentProductId(Long componentProductId) { this.componentProductId = componentProductId; }

    public Integer getQuantityPerSet()                  { return quantityPerSet; }
    public void setQuantityPerSet(Integer quantityPerSet) { this.quantityPerSet = quantityPerSet; }
}
