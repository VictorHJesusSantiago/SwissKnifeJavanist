package dev.swissknife.itamboot.api;

import dev.swissknife.itamboot.domain.Asset.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AssetRequest(
    @NotBlank String tag, @NotBlank String name, @NotNull Type type, Status status,
    String serialNumber, String assignedTo, @PositiveOrZero BigDecimal purchaseValue, LocalDate purchaseDate
) {}
