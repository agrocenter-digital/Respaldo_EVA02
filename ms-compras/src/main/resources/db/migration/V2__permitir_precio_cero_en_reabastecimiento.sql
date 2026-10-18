-- Las ordenes de reabastecimiento automatico se crean como PENDIENTES con precio
-- pendiente de definir (0.00) hasta que un ADMIN las revise y complete.
-- Se relajan las restricciones para permitir ese estado intermedio.

ALTER TABLE detalles_orden_compra
    DROP CONSTRAINT ck_detalle_compra_precio;

ALTER TABLE detalles_orden_compra
    ADD CONSTRAINT ck_detalle_compra_precio CHECK (precio_unitario >= 0);

ALTER TABLE detalles_orden_compra
    DROP CONSTRAINT ck_detalle_compra_subtotal;

ALTER TABLE detalles_orden_compra
    ADD CONSTRAINT ck_detalle_compra_subtotal CHECK (subtotal >= 0);
