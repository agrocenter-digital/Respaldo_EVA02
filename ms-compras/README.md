# AgroCenter Digital - ms-compras

Microservicio encargado de la gestión de órdenes de compra a proveedores y del reabastecimiento automático de existencias en **AgroCenter Digital**. Opera con aislamiento de datos **Database-per-Service** sobre `db_compras`.

---

## 1. Arquitectura y Enrutamiento en AWS

En la infraestructura de producción (EC2 Monolítica en subred pública de la VPC por defecto de AWS Academy):
* **Puerto**: `8083`
* **Red**: Docker bridge compartida (`agrocenter-network`).
* **Comunicación Interna**: Resoluble por nombre de contenedor (`http://ms-compras:8083`).
* **Comunicación con Inventario**: Al confirmar una orden de compra, invoca directamente a `ms-inventario` (`http://ms-inventario:8081/api/inventario/stock/incrementar` o `/entrada`) con clave idempotente `COMPRA-<id>` para aumentar las existencias físicas.
* **Persistencia**: Conectado a su base de datos `db_compras` en PostgreSQL.

```text
Frontend (Vercel) -> bff-web (Puerto 8080 en EC2)
                         |
                         v (red Docker: http://ms-compras:8083)
                    ms-compras (Puerto 8083)
                         |---> db_compras (PostgreSQL)
                         `---> ms-inventario (http://ms-inventario:8081 / entrada de stock)
```

---

## 2. Seguridad y Control de Acceso

Configurado como **OAuth2 Resource Server** con AWS Cognito:

* **Roles y Permisos**:
  - `POST /api/compras`: Exclusivo para administradores (`ROLE_ADMIN` o scope `compras.write`).
  - `GET /api/compras`: Consulta de órdenes por `ROLE_ADMIN`.
  - `GET /api/compras/{id}`: Detalle de orden por `ROLE_ADMIN`.
* **Validación de Tokens de Cognito**:
  - Exige `token_use: "access"`.
  - Soporta validación de `client_id` y `aud`.
  - Mapeo consistente de autoridades y roles mediante `CognitoAuthoritiesConverter`.

---

## 3. Endpoints Principales

| Método | Ruta | Acceso | Descripción |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/compras` | `ROLE_ADMIN` | Registra una orden de compra e incrementa stock en inventario |
| `GET` | `/api/compras` | `ROLE_ADMIN` | Listado histórico de compras |
| `GET` | `/api/compras/{id}` | `ROLE_ADMIN` | Consulta de orden específica |
| `GET` | `/actuator/health` | **Público** | Health check de Fargate y ALB |

---

## 4. Despliegue CI/CD

El repositorio implementa el pipeline automatizado [`.github/workflows/deploy.yml`](file:///.github/workflows/deploy.yml):
* **Disparador**: `git push origin main`.
* **Acción**: Construcción de imagen Docker multi-etapa con Java 21 y publicación en Docker Hub:
  ```text
  tag: <DOCKERHUB_USERNAME>/agrocenter-ms-compras:latest
  ```
* **Actualización en ECS**: Tarea de Fargate asociada al Target Group `tg-ms-compras` que se recarga automáticamente.

---

## 5. Pruebas y Validación Local

```bash
# Ejecutar suite de pruebas unitarias y de integración
./mvnw clean test
```
* Valida transacciones de compra, cálculo financiero con `BigDecimal`, integración HTTP con inventario, compensación ante fallos y control de acceso con Spring Security.
