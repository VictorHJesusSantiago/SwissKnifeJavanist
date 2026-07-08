package dev.swissknife.itamboot.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Entity
@Table(name="assets",uniqueConstraints=@UniqueConstraint(name="uk_asset_tag",columnNames="tag"))
public class Asset {
    @Id private UUID id;
    @Column(nullable=false) private String tag;
    @Column(nullable=false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Status status;
    private String serialNumber; private String assignedTo; private BigDecimal purchaseValue;
    private LocalDate purchaseDate; private String manufacturer; private String model; private String hostname;
    private String ipAddress; private String macAddress; private String operatingSystem; private String osVersion;
    private String locationName; private String department; private String costCenter; private String vendor;
    private LocalDate warrantyEnd; @Column(length=4000) private String notes;
    @Column(length=2000) private String tags;
    @Column(nullable=false) private Instant createdAt; private Instant updatedAt;

    protected Asset(){}
    public Asset(String tag,String name,Type type,String serialNumber,String assignedTo,BigDecimal value,LocalDate date,
        String manufacturer,String model,String hostname,String ipAddress,String macAddress,String operatingSystem,
        String osVersion,String locationName,String department,String costCenter,String vendor,LocalDate warrantyEnd,
        String notes,String tags){
        id=UUID.randomUUID();this.tag=tag;this.name=name;this.type=type;this.serialNumber=serialNumber;
        this.assignedTo=assignedTo;purchaseValue=value;purchaseDate=date;status=Status.IN_USE;createdAt=Instant.now();
        details(manufacturer,model,hostname,ipAddress,macAddress,operatingSystem,osVersion,locationName,department,
            costCenter,vendor,warrantyEnd,notes,tags);
    }
    public void update(String tag,String name,Type type,Status status,String serial,String owner,BigDecimal value,
        LocalDate date,String manufacturer,String model,String hostname,String ipAddress,String macAddress,
        String operatingSystem,String osVersion,String locationName,String department,String costCenter,String vendor,
        LocalDate warrantyEnd,String notes,String tags){
        this.tag=tag;this.name=name;this.type=type;this.status=status;serialNumber=serial;assignedTo=owner;
        purchaseValue=value;purchaseDate=date;updatedAt=Instant.now();
        details(manufacturer,model,hostname,ipAddress,macAddress,operatingSystem,osVersion,locationName,department,
            costCenter,vendor,warrantyEnd,notes,tags);
    }
    public void transition(Status next,String owner){
        if(next==null)throw new IllegalArgumentException("status é obrigatório");
        Set<Status> allowed=switch(status){
            case IN_STOCK->EnumSet.of(Status.IN_USE,Status.MAINTENANCE,Status.RETIRED,Status.LOST);
            case IN_USE->EnumSet.of(Status.IN_STOCK,Status.MAINTENANCE,Status.RETIRED,Status.LOST);
            case MAINTENANCE->EnumSet.of(Status.IN_STOCK,Status.IN_USE,Status.RETIRED);
            case RETIRED->EnumSet.noneOf(Status.class);
            case LOST->EnumSet.of(Status.IN_STOCK,Status.IN_USE,Status.RETIRED);
        };
        if(!allowed.contains(next))throw new IllegalArgumentException("Transição inválida: "+status+" -> "+next);
        if(next==Status.IN_USE&&(owner==null||owner.isBlank()))
            throw new IllegalArgumentException("assignedTo é obrigatório para ativo em uso");
        status=next;assignedTo=next==Status.IN_USE?owner.trim():null;updatedAt=Instant.now();
    }
    private void details(String manufacturer,String model,String hostname,String ipAddress,String macAddress,
        String operatingSystem,String osVersion,String locationName,String department,String costCenter,String vendor,
        LocalDate warrantyEnd,String notes,String tags){
        this.manufacturer=manufacturer;this.model=model;this.hostname=hostname;this.ipAddress=ipAddress;
        this.macAddress=macAddress;this.operatingSystem=operatingSystem;this.osVersion=osVersion;
        this.locationName=locationName;this.department=department;this.costCenter=costCenter;this.vendor=vendor;
        this.warrantyEnd=warrantyEnd;this.notes=notes;this.tags=tags;
    }
    public boolean isWarrantyExpiring(){return warrantyEnd!=null&&!warrantyEnd.isBefore(LocalDate.now())&&warrantyEnd.isBefore(LocalDate.now().plusDays(90));}
    public UUID getId(){return id;} public String getTag(){return tag;} public String getName(){return name;}
    public Type getType(){return type;} public Status getStatus(){return status;} public String getSerialNumber(){return serialNumber;}
    public String getAssignedTo(){return assignedTo;} public BigDecimal getPurchaseValue(){return purchaseValue;}
    public LocalDate getPurchaseDate(){return purchaseDate;} public String getManufacturer(){return manufacturer;}
    public String getModel(){return model;} public String getHostname(){return hostname;} public String getIpAddress(){return ipAddress;}
    public String getMacAddress(){return macAddress;} public String getOperatingSystem(){return operatingSystem;}
    public String getOsVersion(){return osVersion;} public String getLocationName(){return locationName;}
    public String getDepartment(){return department;} public String getCostCenter(){return costCenter;} public String getVendor(){return vendor;}
    public LocalDate getWarrantyEnd(){return warrantyEnd;} public String getNotes(){return notes;} public String getTags(){return tags;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
    public enum Type { COMPUTER, MOBILE, NETWORK, SERVER, SOFTWARE, OTHER }
    public enum Status { IN_STOCK, IN_USE, MAINTENANCE, RETIRED, LOST }
}
