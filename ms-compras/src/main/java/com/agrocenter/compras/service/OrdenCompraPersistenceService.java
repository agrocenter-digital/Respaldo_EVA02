package com.agrocenter.compras.service;

import com.agrocenter.compras.dto.ConfirmarOrdenRequest;
import com.agrocenter.compras.dto.OrdenCompraResponse;
import com.agrocenter.compras.entity.DetalleOrdenCompra;
import com.agrocenter.compras.entity.EstadoOrden;
import com.agrocenter.compras.entity.OrdenCompra;
import com.agrocenter.compras.exception.OrdenCompraNoEncontradaException;
import com.agrocenter.compras.exception.SolicitudInvalidaException;
import com.agrocenter.compras.mapper.OrdenCompraMapper;
import com.agrocenter.compras.repository.OrdenCompraRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrdenCompraPersistenceService {

    private static final int MONEY_SCALE = 2;
    private static final int MONEY_PRECISION = 15;

    private final OrdenCompraRepository repository;
    private final OrdenCompraMapper mapper;

    @Transactional
    public OrdenCompraResponse crearPendiente(
            Long proveedorId,
            List<DetallePreparado> detalles,
            BigDecimal total
    ) {
        OrdenCompra orden = new OrdenCompra(proveedorId, total);
        detalles.forEach(detalle -> orden.agregarDetalle(new DetalleOrdenCompra(
                detalle.productoId(),
                detalle.cantidad(),
                detalle.precioUnitario(),
                detalle.subtotal()
        )));
        return mapper.toResponse(repository.saveAndFlush(orden));
    }

    @Transactional
    public OrdenCompraResponse confirmar(Long ordenId, ConfirmarOrdenRequest request) {
        OrdenCompra orden = buscarEntidad(ordenId);
        if (orden.getEstado() != EstadoOrden.CONFIRMADA) {
            aplicarDatosDeConfirmacion(orden, request);
            orden.confirmar();
        }
        return mapper.toResponse(orden);
    }

    private void aplicarDatosDeConfirmacion(OrdenCompra orden, ConfirmarOrdenRequest request) {
        if (request == null) {
            return;
        }
        orden.actualizarProveedor(request.proveedorId());

        if (request.detalles() != null && !request.detalles().isEmpty()) {
            request.detalles().forEach(detalle -> {
                BigDecimal precio = detalle.precioUnitario().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                BigDecimal subtotal = precio.multiply(BigDecimal.valueOf(detalle.cantidad()))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                validarMonto(subtotal);
                orden.actualizarDetalle(detalle.productoId(), detalle.cantidad(), precio, subtotal);
            });
        } else if (request.cantidad() != null) {
            orden.actualizarCantidadUnicoDetalle(request.cantidad());
        }

        BigDecimal total = request.montoTotal() != null
                ? request.montoTotal().setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : orden.calcularTotal();
        validarMonto(total);
        orden.actualizarTotal(total);
    }

    private void validarMonto(BigDecimal monto) {
        if (monto != null && monto.precision() > MONEY_PRECISION) {
            throw new SolicitudInvalidaException(
                    "PURCHASE_AMOUNT_EXCEEDS_LIMIT",
                    "El monto de la orden excede el limite permitido"
            );
        }
    }

    @Transactional
    public OrdenCompraResponse completar(Long ordenId) {
        OrdenCompra orden = buscarEntidad(ordenId);
        orden.completar();
        return mapper.toResponse(orden);
    }

    @Transactional
    public OrdenCompraResponse cancelar(Long ordenId, String motivo) {
        OrdenCompra orden = buscarEntidad(ordenId);
        orden.cancelar(motivo);
        return mapper.toResponse(orden);
    }

    @Transactional(readOnly = true)
    public List<OrdenCompraResponse> listarTodas() {
        return repository.findAllByOrderByFechaCreacionDesc().stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrdenCompraResponse obtenerPorId(Long id) {
        return mapper.toResponse(buscarEntidad(id));
    }

    private OrdenCompra buscarEntidad(Long id) {
        return repository.findWithDetallesById(id)
                .orElseThrow(OrdenCompraNoEncontradaException::new);
    }
}
