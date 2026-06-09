package rrbm_backend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "settings")
public class Settings {

    @Id
    @Column(name = "key_name", length = 80)
    private String keyName;

    @Column(length = 500)
    private String value;

    @Column(length = 255)
    private String description;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    public String getKeyName()                       { return keyName; }
    public void   setKeyName(String keyName)         { this.keyName = keyName; }
    public String getValue()                         { return value; }
    public void   setValue(String value)             { this.value = value; }
    public String getDescription()                   { return description; }
    public void   setDescription(String desc)        { this.description = desc; }
    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void   setUpdatedAt(LocalDateTime t)      { this.updatedAt = t; }
    public Long   getUpdatedBy()                     { return updatedBy; }
    public void   setUpdatedBy(Long id)              { this.updatedBy = id; }
}
