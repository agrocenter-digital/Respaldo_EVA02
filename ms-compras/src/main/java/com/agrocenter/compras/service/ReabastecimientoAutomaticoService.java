package com.agrocenter.compras.service;

import com.agrocenter.compras.config.ReabastecimientoProperties;
import com.agrocenter.compras.dto.OrdenCompraResponse;
import com.agrocenter.compras.entity.EstadoOrden;
import com.agrocenter.compras.messaging.event.InventarioCriticoEvent;
import com.agrocenter.compras.repository.OrdenCompraRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReabastecimientoAutomaticoService {

    private static final BigDecimal PRECIO_POR_DEFINIR = new BigDecimal("0.00");

    private final OrdenCompraPersistenceService persistenceService;
    private final OrdenCompraRepository ordenCompraRepository;
    private final ReabastecimientoProperties properties;

    public OrdenCompraResponse procesarAlerta(InventarioCriticoEvent event) {
        if (ordenCompraRepository.existeDetalleEnOrdenConEstado(
                event.productoId(),
                EstadoOrden.PENDIENTE
        )) {
            log.info(
                    "Ya existe una orden de compra pendiente para productoId={}; no se genera otra sugerencia",
                    event.productoId()
            );
            return null;
        }

        int cantidad = event.cantidadSugerida() == null || event.cantidadSugerida() <= 0
                ? 1
                : event.cantidadSugerida();

        OrdenCompraResponse orden = persistenceService.crearPendiente(
                properties.proveedorId(),
                List.of(new DetallePreparado(
                        event.productoId(),
                        cantidad,
                        PRECIO_POR_DEFINIR,
                        PRECIO_POR_DEFINIR
                )),
                PRECIO_POR_DEFINIR
        );

        log.info(
                "Orden de reabastecimiento automatica creada ordenId={} productoId={} cantidadSugerida={} proveedorId={}",
                orden.id(),
                event.productoId(),
                cantidad,
                properties.proveedorId()
        );
        return orden;
    }
}
