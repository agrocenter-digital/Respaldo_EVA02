#!/bin/sh
# Crea las bases logicas de cada servicio (patron database-per-service).
# PostgreSQL ya crea POSTGRES_DB (db_inventario) al inicializar el volumen.
# Este script solo se ejecuta cuando el directorio de datos esta vacio.
set -eu

: "${POSTGRES_USER:?POSTGRES_USER no esta definido}"
: "${POSTGRES_DB:?POSTGRES_DB no esta definido}"

crear_base_de_datos() {
    db="$1"

    if [ "$db" = "$POSTGRES_DB" ]; then
        echo "La base '$db' ya fue creada por POSTGRES_DB, se omite."
        return 0
    fi

    existe="$(psql -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'" \
        --username "$POSTGRES_USER" --dbname "$POSTGRES_DB")"

    if [ "$existe" = "1" ]; then
        echo "La base '$db' ya existe, se omite."
        return 0
    fi

    echo "Creando base de datos '$db'..."
    psql -v ON_ERROR_STOP=1 \
        --username "$POSTGRES_USER" \
        --dbname "$POSTGRES_DB" \
        --command "CREATE DATABASE \"$db\";"
}

crear_base_de_datos db_ventas
crear_base_de_datos db_compras
crear_base_de_datos db_bff
