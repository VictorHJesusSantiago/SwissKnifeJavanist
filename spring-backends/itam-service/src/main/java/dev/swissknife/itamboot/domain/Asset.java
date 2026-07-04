package dev.swissknife.itamboot.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name="assets",uniqueConstraints=@UniqueConstraint(name="uk_asset_tag",columnNames="tag"))
public class Asset {
    @Id private UUID id;
    @Column(nullable=false) private String tag;
    @Column(nullable=false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Status status;
    private String serialNumber;
    private String assignedTo;
    private BigDecimal purchaseValue;
    private LocalDate purchaseDate;
    @Column(nullable=false) private Instant createdAt;
    private Instant updatedAt;

    protected Asset(){}
    public Asset(String tag,String name,Type type,String serialNumber,String assignedTo,BigDecimal value,LocalDate date){
        id=UUID.randomUUID();this.tag=tag;this.name=name;this.type=type;this.serialNumber=serialNumber;
        this.assignedTo=assignedTo;purchaseValue=value;purchaseDate=date;status=Status.IN_USE;createdAt=Instant.now();
    }
    public void update(String tag,String name,Type type,Status status,String serial,String owner,BigDecimal value,LocalDate date){
        this.tag=tag;this.name=name;this.type=type;this.status=status;serialNumber=serial;
        assignedTo=owner;purchaseValue=value;purchaseDate=date;updatedAt=Instant.now();
    }
    public UUID getId(){return id;} public String getTag(){return tag;} public String getName(){return name;}
    public Type getType(){return type;} public Status getStatus(){return status;} public String getSerialNumber(){return serialNumber;}
    public String getAssignedTo(){return assignedTo;} public BigDecimal getPurchaseValue(){return purchaseValue;}
    public LocalDate getPurchaseDate(){return purchaseDate;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
    public enum Type { COMPUTER, MOBILE, NETWORK, SERVER, SOFTWARE, OTHER }
    public enum Status { IN_STOCK, IN_USE, MAINTENANCE, RETIRED, LOST }
}
