package dev.swissknife.itamboot.api;

import dev.swissknife.itamboot.domain.Asset.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AssetRequest(
    @NotBlank String tag,@NotBlank String name,@NotNull Type type,Status status,
    String serialNumber,String assignedTo,@PositiveOrZero BigDecimal purchaseValue,LocalDate purchaseDate,
    String manufacturer,String model,String hostname,String ipAddress,String macAddress,
    String operatingSystem,String osVersion,String locationName,String department,String costCenter,
    String vendor,LocalDate warrantyEnd,@Size(max=4000) String notes,List<String> tags
) {
    public String tagsText(){return tags==null?"":String.join(",",tags);}
}
