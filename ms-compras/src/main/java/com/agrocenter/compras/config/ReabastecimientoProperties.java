package com.agrocenter.compras.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agrocenter.compras.reabastecimiento")
public record ReabastecimientoProperties(Long proveedorId) {

    public ReabastecimientoProperties {
        proveedorId = proveedorId == null || proveedorId <= 0 ? 1L : proveedorId;
    }
}
