package com.agrocenter.compras.repository;

import com.agrocenter.compras.entity.EstadoOrden;
import com.agrocenter.compras.entity.OrdenCompra;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrdenCompraRepository extends JpaRepository<OrdenCompra, Long> {

    @EntityGraph(attributePaths = "detalles")
    Optional<OrdenCompra> findWithDetallesById(Long id);

    @EntityGraph(attributePaths = "detalles")
    List<OrdenCompra> findAllByOrderByFechaCreacionDesc();

    @Query("""
            select (count(detalle) > 0)
            from DetalleOrdenCompra detalle
            where detalle.productoId = :productoId
              and detalle.ordenCompra.estado = :estado
            """)
    boolean existeDetalleEnOrdenConEstado(
            @Param("productoId") Long productoId,
            @Param("estado") EstadoOrden estado
    );
}
