package com.agrocenter.compras.messaging.event;

import java.time.Instant;

public record InventarioCriticoEvent(
        Long productoId,
        String sku,
        String nombre,
        Integer stockActual,
        Integer stockMinimo,
        Integer cantidadSugerida,
        Instant detectadoEn
) {
}
