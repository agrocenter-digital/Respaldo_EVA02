package com.agrocenter.compras.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record ConfirmarOrdenRequest(
        @Positive Long proveedorId,
        @Positive Integer cantidad,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal montoTotal,
        @Size(max = 100) List<@NotNull @Valid DetalleConfirmacionRequest> detalles
) {

    public record DetalleConfirmacionRequest(
            @NotNull @Positive Long productoId,
            @NotNull @Positive Integer cantidad,
            @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal precioUnitario
    ) {
    }
}
