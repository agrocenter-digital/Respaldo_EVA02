package com.agrocenter.compras.messaging;

import com.agrocenter.compras.dto.OrdenCompraResponse;
import com.agrocenter.compras.messaging.audit.AuditoriaEventos;
import com.agrocenter.compras.messaging.event.InventarioCriticoEvent;
import com.agrocenter.compras.service.ReabastecimientoAutomaticoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventarioCriticoEventConsumer {

    private final ReabastecimientoAutomaticoService reabastecimientoService;
    private final AuditoriaEventos auditoriaEventos;

    @KafkaListener(topics = KafkaTopics.INVENTARIO_CRITICO)
    public void onInventarioCritico(InventarioCriticoEvent event) {
        if (event == null || event.productoId() == null) {
            log.warn("Alerta de inventario critico invalida ignorada: {}", event);
            auditoriaEventos.registrar(
                    KafkaTopics.INVENTARIO_CRITICO,
                    AuditoriaEventos.CONSUMIDO,
                    "desconocido",
                    AuditoriaEventos.ERROR,
                    "Alerta de inventario critico invalida"
            );
            return;
        }

        String clave = String.valueOf(event.productoId());
        log.info(
                "Alerta de inventario critico recibida productoId={} stockActual={} stockMinimo={}",
                event.productoId(),
                event.stockActual(),
                event.stockMinimo()
        );

        try {
            OrdenCompraResponse orden = reabastecimientoService.procesarAlerta(event);
            auditoriaEventos.registrar(
                    KafkaTopics.INVENTARIO_CRITICO,
                    AuditoriaEventos.CONSUMIDO,
                    clave,
                    AuditoriaEventos.EXITO,
                    orden == null
                            ? "productoId=" + event.productoId() + " orden pendiente existente, omitida"
                            : "productoId=" + event.productoId() + " ordenId=" + orden.id()
            );
        } catch (RuntimeException exception) {
            auditoriaEventos.registrar(
                    KafkaTopics.INVENTARIO_CRITICO,
                    AuditoriaEventos.CONSUMIDO,
                    clave,
                    AuditoriaEventos.ERROR,
                    "productoId=" + event.productoId() + " error=" + exception.getMessage()
            );
            log.error(
                    "No fue posible generar la orden de reabastecimiento productoId={}",
                    event.productoId(),
                    exception
            );
        }
    }
}
