-- Las ordenes creadas por reabastecimiento automatico permanecen PENDIENTES hasta
-- que un ADMIN revisa y confirma la compra. Se agrega el estado intermedio
-- CONFIRMADA a la restriccion de la columna estado.

ALTER TABLE ordenes_compra
    DROP CONSTRAINT ck_orden_compra_estado;

ALTER TABLE ordenes_compra
    ADD CONSTRAINT ck_orden_compra_estado
        CHECK (estado IN ('PENDIENTE', 'CONFIRMADA', 'COMPLETADA', 'CANCELADA'));
