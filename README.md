# AgroCenter Digital

Plataforma de comercio y gestión agrícola construida con arquitectura de **microservicios** sobre Java 21 / Spring Boot y un frontend **Next.js desplegado en Vercel**. El sistema cubre el ciclo operativo completo de un centro de insumos agrícolas: catálogo público con modalidad **minorista y de cotización (B2B)**, autenticación con **AWS Cognito**, checkout transaccional con descuento de inventario, panel administrativo para gestionar existencias, ventas, compras y mensajería, y un flujo **orientado a eventos con Apache Kafka (KRaft)** que desacopla el descuento de stock y el reabastecimiento automático.

Todo el backend y la base de datos conviven en una **única instancia EC2** orquestada con **Docker Compose**, mientras que el frontend se sirve de forma desacoplada desde el **Edge CDN de Vercel**.

---

## Tabla de Contenidos

- [Visión General](#visión-general)
- [Arquitectura](#arquitectura)
- [Estructura del Repositorio](#estructura-del-repositorio)
- [Componentes del Proyecto](#componentes-del-proyecto)
  - [front-web](#1-front-web--spa--nextjs-en-vercel)
  - [bff-web](#2-bff-web--backend-for-frontend)
  - [ms-inventario](#3-ms-inventario--microservicio-de-inventario)
  - [ms-ventas](#4-ms-ventas--microservicio-de-ventas)
  - [ms-compras](#5-ms-compras--microservicio-de-compras)
- [Modelo de Datos](#modelo-de-datos)
- [Flujos Principales](#flujos-principales)
- [Arquitectura Orientada a Eventos (Kafka)](#arquitectura-orientada-a-eventos-kafka)
- [Seguridad y Autenticación](#seguridad-y-autenticación)
- [Infraestructura y Despliegue](#infraestructura-y-despliegue)
- [Ejecución Local](#ejecución-local)
- [Variables de Entorno](#variables-de-entorno)
- [Endpoints Principales](#endpoints-principales)
- [Tecnologías Utilizadas](#tecnologías-utilizadas)

---

## Visión General

AgroCenter Digital es una tienda en línea especializada en insumos agrícolas (semillas, fertilizantes, herramientas, riego, etc.) con dos públicos bien diferenciados:

- **Visitantes anónimos** navegan la portada corporativa y el catálogo público de productos sin autenticarse.
- **Clientes registrados** (autenticados vía AWS Cognito con Authorization Code + PKCE o federación con Google) pueden agregar productos minoristas al carrito y confirmar una compra que descuenta stock en tiempo real.
- **Clientes mayoristas / B2B** solicitan cotizaciones desde productos marcados como "solo cotización". Estas consultas no crean ventas ni mueven stock: quedan registradas para que un administrador las gestione.
- **Administradores** acceden a un panel con gestión de inventario, historial de ventas, órdenes de abastecimiento, consultas B2B, un monitor de APIs, una consola de peticiones y un módulo de mensajería Kafka (tópicos, reglas y trazabilidad de eventos).

**Principios arquitectónicos:**

- **Frontend desacoplado en Vercel**: `front-web` opera 100% independiente en el Edge CDN de Vercel (HTTPS). Las llamadas `/api/*` se reescriben vía `next.config.ts` hacia la EC2 por HTTP, evitando *Mixed Content* y sin necesidad de certificados SSL en el backend.
- **EC2 monolítica (AWS Academy)**: todo el backend (BFF, 3 microservicios, PostgreSQL y Kafka) corre en una sola instancia dentro de la subred pública de la VPC por defecto, reduciendo costo y complejidad sin perder el desacoplamiento lógico de microservicios.
- **BFF Pattern**: el frontend solo conversa con `bff-web` en el puerto `8080`; este valida los JWT de Cognito, aplica CORS, propaga credenciales y orquesta a los microservicios por DNS interno de Docker.
- **Database per Service**: cada microservicio mantiene su propia base lógica (`db_inventario`, `db_ventas`, `db_compras`) y el BFF usa `db_bff` para sus reglas de negocio. No hay acceso cruzado a tablas entre servicios.
- **Orientado a eventos (Apache Kafka KRaft)**: `ms-ventas` publica en `ordenes-compras`, `ms-inventario` lo consume de forma **idempotente** y emite `inventario-critico`, y `ms-compras` reacciona generando órdenes de reabastecimiento. El flujo síncrono se conserva como fuente de verdad inmediata.
- **Stateless sessions**: ningún servicio almacena sesión; la autenticación viaja como JWT de Cognito en cada petición.
- **Idempotencia**: los endpoints de escritura (checkout, compras) exigen `Idempotency-Key` y el inventario garantiza operaciones idempotentes por referencia única.
- **Containerización**: cada servicio se empaqueta como imagen Docker multi-stage (JRE Alpine) con usuario no-root, `read_only`, `cap_drop: ALL` y `no-new-privileges`.

---

## Arquitectura

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           INTERNET / CLIENTE                            │
│                                                                         │
│   ┌──────────────────────┐                                              │
│   │     front-web        │  Next.js 15 / React 19 / TypeScript          │
│   │  (Vercel Edge CDN)   │  Portada + Catálogo + Cotización + /admin    │
│   └──────────┬───────────┘                                              │
│              │ HTTPS  ·  rewrite /api/* → EC2                            │
│              │ Bearer JWT (Cognito)                                      │
│              ▼ Puerto 8080                                               │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │  INSTANCIA EC2 (Subred Pública - VPC AWS Academy)                    │ │
│ │  Red Docker Bridge: agrocenter-network                              │ │
│ │                                                                     │ │
│ │   ┌──────────────────────┐                                          │ │
│ │   │      bff-web         │  Spring Boot 3.5 / Java 21               │ │
│ │   │   (Puerto 8080)      │  Orquestación · CORS · Validación JWT    │ │
│ │   │  Puerta de entrada   │  Agregación · db_bff (reglas)            │ │
│ │   └──┬───────┬───────┬───┘                                          │ │
│ │      │       │       │  HTTP interno (Docker DNS)                    │ │
│ │      ▼       ▼       ▼                                              │ │
│ │   ┌──────┐ ┌──────┐ ┌──────┐                                        │ │
│ │   │ ms-  │ │ ms-  │ │ ms-  │  Spring Boot / Java 21                 │ │
│ │   │inven-│ │ven-  │ │com-  │  Contenedores con aislamiento por BD   │ │
│ │   │tario │ │ tas  │ │ pras │                                        │ │
│ │   │:8081 │ │:8082 │ │:8083 │                                        │ │
│ │   └──┬───┘ └──┬───┘ └──┬───┘                                        │ │
│ │      │        │        │   Eventos asíncronos                       │ │
│ │      │        │        │   ┌──────────────────────────┐             │ │
│ │      │        │        │   │     Apache Kafka KRaft   │             │ │
│ │      │        │        │   │   agrocenter-kafka:9092  │             │ │
│ │      │        │        │   └──────────────────────────┘             │ │
│ │      │        │        │     • ordenes-compras                      │ │
│ │      │        │        │     • inventario-critico                   │ │
│ │      ▼        ▼        ▼                                            │ │
│ │   ┌────────────────────────┐                                        │ │
│ │   │      PostgreSQL 16     │  Multi-database por servicio:          │ │
│ │   │   (Puerto 5432 / EC2)  │  • db_inventario                       │ │
│ │   │                        │  • db_ventas                           │ │
│ │   │                        │  • db_compras                          │ │
│ │   │                        │  • db_bff (reglas de encaminamiento)   │ │
│ │   └────────────────────────┘                                        │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

**Cadena de comunicación completa:**

```
Navegador
  └─▶ Vercel (front-web)  ── rewrite /api/* ──▶  EC2 :8080 (bff-web)
                                                     │
                    ┌────────────────────────────────┼──────────────────────────────┐
                    ▼                                ▼                              ▼
              ms-inventario:8081             ms-ventas:8082                ms-compras:8083
                    │                                │                              │
                    │                            Kafka ──▶ ordenes-compras           │
                    │◀── stock/entrada/salida ◀───────┘ (consumo idempotente)        │
                    │                                └──────────────────────────────┘
                    └── inventario-critico ──▶ Kafka ──▶ ms-compras (reabastecimiento)
```

---

## Estructura del Repositorio

```
agrocenter_digital/
├── front-web/          → SPA Next.js desplegada en Vercel (Frontend)
├── bff-web/            → Backend For Frontend (Orquestador y puerta de entrada)
├── ms-inventario/      → Microservicio de Inventario (productos, stock, movimientos)
├── ms-ventas/          → Microservicio de Ventas (checkout, consultas B2B)
├── ms-compras/         → Microservicio de Compras (abastecimiento, reabastecimiento)
├── docker/
│   └── postgres/       → Script de creación de las 4 bases lógicas
├── docker-compose.yml  → Orquestación completa del backend en la EC2
└── .env.example        → Plantilla de variables de entorno para la EC2
```

Cada carpeta de componente mantiene su **propio repositorio Git independiente**, con su pipeline (`.github/`) y su `Dockerfile`.

---

## Componentes del Proyecto

### 1. `front-web` — SPA / Next.js en Vercel

| Propiedad | Valor |
|:---|:---|
| **Framework** | Next.js 15 (App Router) / React 19 / TypeScript 5.9 |
| **Hosting** | Vercel (Edge CDN) — `https://front-web-seven.vercel.app` |
| **Puerto local** | `3000` |
| **Estilos** | Tailwind CSS 4 + CSS Variables (`globals.css`, `corporate.css`) |
| **Iconografía** | `lucide-react` |
| **Autenticación** | AWS Cognito: Authorization Code + PKCE (S256) + Google Federation |
| **Node** | `>= 22.13` |

**Páginas y rutas:**

| Ruta | Descripción |
|:---|:---|
| `/` | Portada corporativa (Nosotros, Soluciones, Contacto) con anclas `#nosotros`, `#soluciones`, `#contacto` |
| `/productos` | Catálogo **minorista**: búsqueda, filtro por categoría y ordenamiento; carrito y checkout |
| `/productos/mayoristas` | Catálogo **solo cotización (B2B)**: abre diálogo de cotización, no crea ventas |
| `/admin` | Panel de administración con 7 módulos (ver abajo) |

**Panel `/admin` — módulos (pestañas):**

1. **Inventario** — CRUD de productos y auditoría de existencias en `db_inventario.productos`.
2. **Ventas** — historial de órdenes de clientes (`db_ventas.ventas`).
3. **Compras** — órdenes de abastecimiento a proveedores (`db_compras.ordenes_compra`).
4. **Consultas B2B** — bandeja de solicitudes de cotización con cambio de estado (PENDIENTE / RESPONDIDO / ARCHIVADO).
5. **Monitor APIs** — *pings* en vivo a los endpoints del BFF con latencia y status HTTP.
6. **Consola** — playground para ejecutar llamadas HTTP arbitrarias con inyección automática del Bearer JWT.
7. **Mensajería** — administración Kafka: tópicos (particiones, réplicas, offsets, salud), reglas de encaminamiento y trazabilidad de eventos en vivo.

**Comportamientos clave:**

- **Proxy sin Mixed Content**: en el navegador todas las llamadas usan rutas relativas `/api/...`; Next.js las reescribe a `BACKEND_INTERNAL_URL` (EC2) desde el servidor. Ver `next.config.ts:9`.
- **Cliente HTTP** (`lib/api.ts`): prioriza el `accessToken` sobre el `idToken`, inyecta `Authorization: Bearer` y genera automáticamente `Idempotency-Key` en cada `POST` si no existe.
- **Carrito y checkout minorista**: envía `POST /api/bff/ventas` con `items: [{ productoId, cantidad }]`; el backend recalcula precios y valida/descuenta stock. No integra pagos ni persiste dirección de despacho.
- **Cotización B2B**: los productos con `soloCotizacion: true` se muestran solo en `/productos/mayoristas`; el formulario envía `POST /api/bff/consultas` y solo confirma cuando el backend persistió la consulta.
- **Sesión demo**: si no hay variables de Cognito configuradas, la UI ofrece un modo de prueba local en memoria.

**Archivos principales:**

```
front-web/
├── app/
│   ├── layout.tsx                 → Layout raíz + metadata SEO/OpenGraph
│   ├── components/PublicShell.tsx → Envuelve las rutas públicas con AgroCenterApp
│   ├── AgroCenterApp.tsx          → Cliente principal de la tienda (catálogo, carrito, checkout, cotización)
│   ├── page.tsx                   → Portada (metadata)
│   ├── productos/page.tsx         → Catálogo minorista
│   ├── productos/mayoristas/      → Catálogo de cotización B2B
│   ├── admin/page.tsx             → Panel admin (7 pestañas)
│   ├── admin/components/          → MessagingAdmin, ConsultasB2BAdmin
│   └── components/                → ProductCard, ProductAction, QuoteDialog, InquiryForm, Contact
├── lib/
│   ├── api.ts                     → Cliente HTTP con Bearer JWT e Idempotency-Key
│   ├── cognito.ts                 → Cliente AWS Cognito (PKCE, tokens, sesión)
│   ├── catalog.ts                 → Tipos y utilidades del catálogo
│   ├── company.ts / demo-catalog.ts
│   └── admin-access.ts            → Control de acceso al panel
├── next.config.ts                 → Rewrites /api/* y /actuator/* hacia la EC2
└── package.json
```

---

### 2. `bff-web` — Backend For Frontend

| Propiedad | Valor |
|:---|:---|
| **Framework** | Spring Boot 3.5.7 / Java 21 |
| **Build** | Maven (`./mvnw`) |
| **Puerto** | `8080` (único puerto público) |
| **Base de datos** | `db_bff` (tabla `reglas_distribucion` para reglas de encaminamiento) |
| **Seguridad** | Spring Security OAuth2 Resource Server (JWKS Cognito) + JWT de desarrollo HS256 |
| **Contenedor** | Dockerfile multi-stage (Maven build → JRE Alpine, usuario no-root) |

**Responsabilidades:**

- **Punto de entrada único** del frontend: todas las peticiones del SPA pasan por el BFF.
- **Validación de JWT**: verifica tokens de Cognito contra el JWKS público, valida `audience` y `token_use`, y extrae roles (`ADMIN`, `CLIENTE`) del claim `cognito:groups`.
- **CORS dinámico**: orígenes permitidos configurables, incluyendo comodines para `*.vercel.app`.
- **Propagación de Bearer**: reenvía el JWT del cliente hacia los microservicios en cada llamada protegida.
- **Errores estandarizados**: traduce fallos de downstream a respuestas JSON consistentes (`DOWNSTREAM_UNAVAILABLE`, `DOWNSTREAM_SECURITY_MISMATCH`, etc.).
- **Timeouts configurables** por microservicio (connect + response independientes).
- **Observabilidad**: `CorrelationIdFilter` genera/propaga `X-Correlation-ID` en todas las peticiones y lo incluye en los logs.

**Controladores:**

| Controlador | Ruta base | Descripción |
|:---|:---|:---|
| `CatalogController` | `/api/bff/catalogo`, `/api/bff/productos/**` | Proxy público al catálogo y detalle de producto/stock |
| `InventoryController` | `/api/bff/inventario`, `/api/bff/admin/inventario` | CRUD de productos, stock bajo, entrada de stock y movimientos (ADMIN) |
| `SalesController` | `/api/bff/ventas`, `/api/bff/checkout`, `/api/bff/pedidos` | Crear venta idempotente, listar ventas, mis pedidos |
| `PurchasesController` | `/api/bff/compras`, `/api/bff/admin/compras` | Listar/crear órdenes de compra y confirmar orden (ADMIN) |
| `ConsultasController` | `/api/bff/consultas`, `/api/bff/admin/consultas` | Alta pública de consultas B2B y gestión por ADMIN |
| `DashboardController` | `/api/bff/admin/dashboard` | Datos agregados del panel administrativo |
| `MessagingAdminController` | `/api/bff/admin/mensajeria` | Tópicos Kafka, reglas de encaminamiento y trazabilidad (ADMIN) |
| `HealthController` | `/api/bff/health`, `/api/health` | Sonda de disponibilidad del BFF |

**Clientes HTTP internos** (todos usan `ClientCallExecutor` y `ForwardedHeadersInterceptor`):

| Cliente | Servicio destino | URL por defecto (prod) |
|:---|:---|:---|
| `InventoryClient` | `ms-inventario` | `http://ms-inventario:8081` |
| `SalesClient` | `ms-ventas` | `http://ms-ventas:8082` |
| `PurchasesClient` | `ms-compras` | `http://ms-compras:8083` |
| `ConsultasClient` | `ms-ventas` (consultas B2B) | `http://ms-ventas:8082` |
| `MessagingClient` | microservicios (eventos) | — |

**Archivos principales:**

```
bff-web/
├── src/main/java/com/agrocenter/bff/
│   ├── config/       → SecurityConfig, RestClientConfig, ServiceProperties,
│   │                   CorsProperties, CognitoJwtConfiguration, DevJwtConfiguration
│   ├── client/       → Inventory/Sales/Purchases/Consultas/Messaging + ClientCallExecutor
│   ├── controller/   → 8 controladores REST
│   ├── security/     → CognitoAuthoritiesConverter, validadores y entry points REST
│   ├── service/      → DashboardService, TopicAdminService, ReglasEncaminamientoService,
│   │                   MessagingQueryService
│   ├── entity/       → ReglaNegocio (reglas_distribucion, db_bff)
│   ├── observability/→ CorrelationIdFilter
│   └── exception/    → GlobalExceptionHandler, DownstreamException
└── src/main/resources/
    ├── application.yml / -dev / -docker / -prod
```

---

### 3. `ms-inventario` — Microservicio de Inventario

| Propiedad | Valor |
|:---|:---|
| **Framework** | Spring Boot 4.1 / Java 21 |
| **Puerto** | `8081` |
| **Base de datos** | `db_inventario` (PostgreSQL 16, `ddl-auto=update`) |
| **Mensajería** | Consumidor de `ordenes-compras` + productor de `inventario-critico` |

**Responsabilidades:**

- Gestión completa del catálogo de productos agrícolas (CRUD, filtros por categoría/nombre/activo).
- Marca de producto **solo cotización** (`soloCotizacion`) según `CATALOG_QUOTE_ONLY_SKUS`, para separar el catálogo minorista del B2B.
- Control de existencias con **bloqueo pesimista** (`findByIdForUpdate`) e idempotencia por referencia.
- Operaciones de stock: **validar disponibilidad**, **entrada** (origen `MS_COMPRAS`) y **salida** (origen `MS_VENTAS`).
- Registro de movimientos con stock anterior/posterior y trazabilidad de origen.
- Catálogo público (sin autenticación) para la vitrina de la tienda.
- **Consumo idempotente** de `ordenes-compras`: descuenta stock por la referencia `VENTA-{id}`, por lo que no duplica el movimiento ya aplicado síncronamente.
- **Publicación** de `inventario-critico` cuando una salida deja el stock en o por debajo del mínimo (solo tras confirmar la transacción).

**Entidades JPA:**

| Entidad | Tabla | Campos principales |
|:---|:---|:---|
| `Producto` | `productos` | `id`, `sku` (unique), `nombre`, `categoria`, `descripcion`, `precioVenta`, `stockActual`, `stockMinimo`, `activo`, `version` (opt. lock) |
| `MovimientoInventario` | `movimientos_inventario` | `id`, `producto`, `tipoMovimiento` (ENTRADA/SALIDA), `cantidad`, `stockAnterior`, `stockPosterior`, `referencia`, `origen` |

> **Idempotencia**: `movimientos_inventario` tiene la restricción única `uk_movimiento_idempotencia (producto_id, tipo_movimiento, referencia)`.

**Controladores:**

| Controlador | Endpoints principales |
|:---|:---|
| `ProductoController` | `GET/POST /api/inventario/productos`, `GET /{id}`, `GET /sku/{sku}`, `PUT /{id}`, `PATCH /{id}/estado` |
| `StockController` | `POST /api/inventario/stock/validar`, `POST /stock/entrada`, `POST /stock/salida` |
| `MovimientoController` | `GET /api/inventario/movimientos`, `GET /productos/{id}/movimientos` |
| `DevTokenController` | `POST /api/dev/token` (solo perfil `dev`, genera JWT local) |
| `MensajeriaAdminController` | `GET /api/inventario/admin/mensajeria/eventos` (ADMIN) |

**Mensajería (Kafka):**

| Componente | Rol | Tópico |
|:---|:---|:---|
| `OrdenCompraEventConsumer` | Consumidor (`@KafkaListener`) | `ordenes-compras` |
| `InventarioCriticoNotifier` | Productor (`@TransactionalEventListener(AFTER_COMMIT)`) | `inventario-critico` |

---

### 4. `ms-ventas` — Microservicio de Ventas

| Propiedad | Valor |
|:---|:---|
| **Framework** | Spring Boot 3.5.7 / Java 21 |
| **Puerto** | `8082` |
| **Base de datos** | `db_ventas` (Flyway + `ddl-auto=validate`) |
| **Migraciones** | `V1__create_ventas_schema.sql`, `V2__crear_tabla_consultas_b2b.sql` |

**Responsabilidades:**

- Registro transaccional de órdenes de compra de clientes.
- **Flujo de dos fases**: crea la venta en `PENDIENTE`, consulta producto y valida disponibilidad, descuenta stock en `ms-inventario`, confirma como `CONFIRMADA`; si falla, **compensa** el stock descontado y cancela con motivo.
- **Idempotencia** por `(cliente_id, idempotency_key)` (constraint unique) más hash del request para detectar replays; responde `Idempotent-Replay: true` cuando corresponde.
- Rechaza productos con `soloCotizacion` en el checkout minorista (`PRODUCTO_SOLO_COTIZACION`).
- **Validación de propiedad** (anti-IDOR): un cliente solo consulta sus propios pedidos.
- Reporte global de ventas para administradores.
- Gestión de **consultas B2B** (alta pública, listado y cambio de estado por ADMIN).
- Publicación asíncrona *best-effort* del evento de venta confirmada en `ordenes-compras`.

**Entidades JPA:**

| Entidad | Tabla | Campos principales |
|:---|:---|:---|
| `Venta` | `ventas` | `id`, `clienteId`, `fechaCreacion`, `estado` (PENDIENTE/CONFIRMADA/CANCELADA), `subtotal`, `total`, `idempotencyKey`, `requestHash`, `motivoCancelacion` |
| `DetalleVenta` | `detalles_venta` | `id`, `venta_id` (FK), `productoId`, `sku`, `nombreProducto`, `cantidad`, `precioUnitario`, `subtotal` |
| `ConsultaB2B` | `consulta_b2b` | `id`, `skuProducto`, `nombreProducto`, `cantidad`, `nombreCliente`, `correoContacto`, `mensaje`, `origen`, `estado`, `fechaCreacion` |

> **Consulta B2B**: `origen` ∈ {`PRODUCTO_MAYORISTA`, `CONTACTO_HOME`} y `estado` ∈ {`PENDIENTE`, `RESPONDIDO`, `ARCHIVADO`}.

**Controladores:**

| Controlador | Endpoints principales |
|:---|:---|
| `VentaController` | `POST /api/ventas`, `GET /{id}`, `GET /mis-pedidos`, `GET /api/ventas` (ADMIN) |
| `ConsultaB2BController` | `POST /api/ventas/consultas`, `GET` (ADMIN), `PATCH /{id}/estado` (ADMIN) |
| `MensajeriaAdminController` | `GET /api/ventas/admin/mensajeria/eventos` (ADMIN) |

**Comunicación inter-servicio (WebClient `InventoryClient`):** `consultarProducto`, `validarDisponibilidad`, `descontarStock`, `compensarStock` hacia `INVENTORY_SERVICE_URL`.

**Mensajería (Kafka):**

| Componente | Rol | Tópico |
|:---|:---|:---|
| `VentaEventPublisher` | Productor (`KafkaTemplate`, best-effort) | `ordenes-compras` |

---

### 5. `ms-compras` — Microservicio de Compras

| Propiedad | Valor |
|:---|:---|
| **Framework** | Spring Boot 3.5.7 / Java 21 |
| **Puerto** | `8083` |
| **Base de datos** | `db_compras` (Flyway + `ddl-auto=validate`) |
| **Migraciones** | `V1__create_compras_schema.sql`, `V2__permitir_precio_cero_en_reabastecimiento.sql`, `V3__agregar_estado_confirmada.sql` |

**Responsabilidades:**

- Registro de órdenes de abastecimiento a proveedores.
- **Compra manual**: crea la orden `PENDIENTE` y realiza la **entrada de stock** en `ms-inventario` (referencia `COMPRA-{id}`); al confirmar el ingreso marca la orden como `COMPLETADA`; si falla, compensa el stock y cancela.
- **Confirmación de orden**: `PATCH /{id}/confirmar` persiste los datos definitivos (proveedor, cantidades, precios, total) y deja la orden en estado `CONFIRMADA`.
- **Reabastecimiento automático**: consume `inventario-critico` y crea una orden `PENDIENTE` (precio y proveedor por definir) evitando duplicados cuando ya existe una orden pendiente para el producto.

**Estados de orden:** `PENDIENTE` → `CONFIRMADA` → `COMPLETADA`, o `CANCELADA`.

**Entidades JPA:**

| Entidad | Tabla | Campos principales |
|:---|:---|:---|
| `OrdenCompra` | `ordenes_compra` | `id`, `proveedorId`, `fechaCreacion`, `estado`, `total`, `motivoCancelacion`, `createdAt`, `updatedAt` |
| `DetalleOrdenCompra` | `detalles_orden_compra` | `id`, `ordenCompra` (FK), `productoId`, `cantidad`, `precioUnitario`, `subtotal` |

**Controladores:**

| Controlador | Endpoints principales |
|:---|:---|
| `OrdenCompraController` | `POST /api/compras`, `GET`, `GET /{id}`, `PATCH /{id}/confirmar` |
| `MensajeriaAdminController` | `GET /api/compras/admin/mensajeria/eventos` (ADMIN) |

**Comunicación inter-servicio (WebClient `InventoryClient`):** `ingresarStock` y `compensarEntrada` hacia `INVENTORY_SERVICE_URL`.

**Mensajería (Kafka):**

| Componente | Rol | Tópico |
|:---|:---|:---|
| `InventarioCriticoEventConsumer` | Consumidor (`@KafkaListener`) | `inventario-critico` |
| `ReabastecimientoAutomaticoService` | Crea la orden `PENDIENTE` sugerida (proveedor vía `COMPRAS_AUTO_PROVEEDOR_ID`) | — |

---

## Modelo de Datos

```
┌──────────────────────────┐     ┌──────────────────────────┐     ┌──────────────────────────┐
│      db_inventario       │     │        db_ventas         │     │       db_compras         │
│                          │     │                          │     │                          │
│  ┌────────────────────┐  │     │  ┌────────────────────┐  │     │  ┌────────────────────┐  │
│  │     productos      │  │     │  │      ventas        │  │     │  │   ordenes_compra   │  │
│  ├────────────────────┤  │     │  ├────────────────────┤  │     │  ├────────────────────┤  │
│  │ id (PK)            │  │     │  │ id (PK)            │  │     │  │ id (PK)            │  │
│  │ sku (UK)           │  │     │  │ cliente_id         │  │     │  │ proveedor_id       │  │
│  │ nombre             │  │     │  │ estado             │  │     │  │ estado             │  │
│  │ categoria          │  │     │  │ subtotal / total   │  │     │  │ total              │  │
│  │ precio_venta       │  │     │  │ idempotency_key    │  │     │  │ fecha_creacion     │  │
│  │ stock_actual       │  │     │  │ fecha_creacion     │  │     │  └────────┬───────────┘  │
│  │ stock_minimo       │  │     │  └────────┬───────────┘  │     │           │ 1:N          │
│  │ activo             │  │     │           │ 1:N          │     │  ┌────────▼───────────┐  │
│  │ version (Opt Lock) │  │     │  ┌────────▼───────────┐  │     │  │ detalles_orden_    │  │
│  └────────────────────┘  │     │  │  detalles_venta    │  │     │  │     compra         │  │
│                          │     │  ├────────────────────┤  │     │  ├────────────────────┤  │
│  ┌────────────────────┐  │     │  │ producto_id        │  │     │  │ producto_id        │  │
│  │ movimientos_       │  │     │  │ cantidad           │  │     │  │ cantidad           │  │
│  │   inventario       │  │     │  │ precio_unitario    │  │     │  │ precio_unitario    │  │
│  ├────────────────────┤  │     │  │ subtotal           │  │     │  │ subtotal           │  │
│  │ tipo_movimiento E/S│  │     │  └────────────────────┘  │     │  └────────────────────┘  │
│  │ cantidad           │  │     │                          │     │                          │
│  │ stock_ant/post     │  │     │   ┌────────────────────┐ │     │                          │
│  │ referencia (UK) *  │  │     │   │   consulta_b2b     │ │     │                          │
│  └────────────────────┘  │     │   ├────────────────────┤ │     │                          │
│                          │     │   │ sku / nombre       │ │     │                          │
│        * UK idempotencia │     │   │ nombre_cliente     │ │     │                          │
│   (producto_id, tipo,    │     │   │ correo_contacto    │ │     │                          │
│    referencia)           │     │   │ origen / estado    │ │     │                          │
└──────────────────────────┘     │   └────────────────────┘ │     └──────────────────────────┘
                                 └──────────────────────────┘

┌──────────────────────────┐
│         db_bff           │
│  ┌────────────────────┐  │
│  │ reglas_distribucion│  │
│  ├────────────────────┤  │
│  │ id (PK)            │  │
│  │ nombre             │  │
│  │ topico_origen      │  │
│  │ topico_destino     │  │
│  │ condicion          │  │
│  │ estado (ACTIVA)    │  │
│  └────────────────────┘  │
└──────────────────────────┘
```

**Multi-database**: las 4 bases lógicas se crean automáticamente al iniciar PostgreSQL mediante `docker/postgres/init-databases.sh`.

---

## Flujos Principales

### Flujo de Compra Minorista (Checkout de Cliente)

```
1. Cliente navega /productos y agrega productos minoristas al carrito.
2. Confirma la compra → front-web envía POST /api/bff/ventas con Bearer JWT e Idempotency-Key.
3. bff-web valida el JWT, extrae el clienteId (subject) y reenvía a ms-ventas.
4. ms-ventas verifica idempotencia (cliente_id, idempotency_key); si ya existía, responde replay.
5. ms-ventas consulta cada producto en ms-inventario: valida activo, precio y que NO sea solo-cotización;
   valida disponibilidad de stock.
6. ms-ventas crea la Venta PENDIENTE en db_ventas.
7. ms-ventas descuenta stock en ms-inventario (referencia VENTA-{id}) por cada ítem.
8. Si todo el stock se descuenta → Venta CONFIRMADA y publica el evento en el tópico ordenes-compras.
9. Si falla → ms-ventas compensa el stock ya descontado y cancela la venta con motivo.
10. ms-inventario consume el evento y verifica/idempotiza con la referencia VENTA-{id} (sin doble descuento).
11. bff-web retorna la respuesta al frontend.
```

### Flujo de Cotización B2B

```
1. Cliente (o visitante) abre /productos/mayoristas y pulsa "Contactar y cotizar".
2. El diálogo muestra producto, SKU, presentación y cantidad editable.
3. Se envía POST /api/bff/consultas (público, sin JWT) → bff-web → ms-ventas.
4. ms-ventas registra la ConsultaB2B en estado PENDIENTE (origen PRODUCTO_MAYORISTA).
5. El frontend muestra el comprobante solo cuando el backend confirmó la persistencia.
6. Un ADMIN revisa la bandeja "Consultas B2B" y actualiza el estado (RESPONDIDO / ARCHIVADO).
```

> No se crea una venta ni se mueve stock en este flujo.

### Flujo de Abastecimiento Manual (Compra a Proveedor)

```
1. Admin crea la orden desde el panel → POST /api/bff/compras con Bearer JWT.
2. bff-web valida ROLE_ADMIN y reenvía a ms-compras.
3. ms-compras crea la OrdenCompra PENDIENTE (referencia COMPRA-{id}) y realiza la ENTRADA de stock en ms-inventario.
4. Si el ingreso se confirma → orden COMPLETADA; si falla → compensa stock y cancela.
5. bff-web retorna la respuesta al frontend.
```

### Flujo de Reabastecimiento Automático (Event-Driven)

```
1. Una venta descuenta stock en ms-inventario y deja stockActual <= stockMinimo.
2. Al confirmar la transacción, ms-inventario publica InventarioCriticoEvent en inventario-critico.
3. ms-compras consume el evento y verifica que no exista una orden PENDIENTE del mismo producto.
4. ms-compras crea una OrdenCompra PENDIENTE (precio y proveedor por definir).
5. Un ADMIN revisa, ajusta (proveedor, cantidades, precios) y confirma la orden → CONFIRMADA.
```

---

## Arquitectura Orientada a Eventos (Kafka)

El backend incorpora **Apache Kafka en modo KRaft** (`agrocenter-kafka`, `kafka:9092`) como bus de eventos interno. Cada microservicio se conecta mediante `SPRING_KAFKA_BOOTSTRAP_SERVERS` y los tópicos se declaran al arrancar (`KafkaAdmin` + beans `NewTopic`).

**Tópicos:**

| Tópico | Productor | Consumidor | Payload |
|:---|:---|:---|:---|
| `ordenes-compras` | `ms-ventas` | `ms-inventario` | `OrdenCompraEvent` (`ventaId`, `clienteId`, `total`, `items[]`) |
| `inventario-critico` | `ms-inventario` | `ms-compras` | `InventarioCriticoEvent` (`productoId`, `sku`, `stockActual`, `stockMinimo`, `cantidadSugerida`) |

**Cadena de eventos:**

```
ms-ventas ──(venta confirmada)──▶ [ordenes-compras] ──▶ ms-inventario
                                                          │ descuenta stock (idempotente)
                                                          ▼
                                                stock <= stockMinimo
                                                          │
                          [inventario-critico] ◀──────────┘
                                    │
                                    ▼
                              ms-compras ──▶ OrdenCompra PENDIENTE (reabastecimiento)
```

**Decisiones de diseño:**

- **Sin doble descuento**: el descuento síncrono y el evento usan la misma referencia `VENTA-{id}`. El constraint único de `movimientos_inventario` hace que el consumidor detecte el duplicado sin volver a descontar.
- **Publicación best-effort**: el productor de ventas usa `max.block.ms=5000`; un broker caído no revierte ni bloquea una venta ya confirmada.
- **Sin alertas fantasma**: `InventarioCriticoNotifier` publica únicamente con `@TransactionalEventListener(AFTER_COMMIT)`, cuando el movimiento realmente se confirmó.
- **Consumo idempotente**: el consumidor de `inventario-critico` no genera una nueva orden si ya existe una `PENDIENTE` para el producto.
- **Desacoplamiento por estructura**: los eventos viajan en JSON sin *type headers* (`spring.json.add.type.headers=false`), evitando acoplar el nombre de clase entre servicios.

**Administración y trazabilidad:**

El BFF expone `/api/bff/admin/mensajeria` (solo `ROLE_ADMIN`) que agrega información del broker y de los microservicios:

| Endpoint | Descripción |
|:---|:---|
| `GET /topicos` | Particiones, réplicas, offsets (`latest`) y estado (`ACTIVO`/`DEGRADADO`) |
| `GET /reglas` | Lista las reglas de encaminamiento (persistidas en `db_bff`) |
| `POST /reglas` | Registra una regla (nombre y tópico de origen validados) |
| `DELETE /reglas/{id}` | Elimina una regla (`204`; `404` si no existe) |
| `GET /eventos?limite=` | Trazabilidad de eventos agregada de los 3 microservicios |

Los tópicos consultados se controlan con `KAFKA_TOPICS`. Cada microservicio publica su propia auditoría de eventos en memoria (`GET /api/{ventas,inventario,compras}/admin/mensajeria/eventos`) indicando dirección (`EMITIDO`/`CONSUMIDO`), clave, estado (`EXITO`/`ERROR`) y detalle. Si un microservicio no responde, el BFF devuelve las trazas restantes junto con `advertencias`.

---

## Seguridad y Autenticación

| Capa | Mecanismo |
|:---|:---|
| **Frontend → Cognito** | OAuth 2.0 Authorization Code con PKCE (S256) + Google Identity Federation |
| **Frontend → BFF** | Bearer JWT en `Authorization` (access_token de Cognito) |
| **BFF (validación)** | Spring Security OAuth2 Resource Server contra JWKS de Cognito (valida `aud`, `token_use`) |
| **BFF → Microservicios** | Propagación del Bearer original vía `ForwardedHeadersInterceptor` |
| **Microservicios** | Cada uno valida el JWT de forma independiente contra el mismo JWKS |
| **Roles** | `ADMIN` y `CLIENTE`, extraídos del claim `cognito:groups` |
| **Dev tokens** | Perfil `dev` habilita `POST /api/dev/token` para emitir JWT locales (HS256, `DEV_JWT_SECRET`) |

**Reglas de acceso en el BFF (`SecurityConfig.java`):**

| Ruta | Acceso |
|:---|:---|
| `/actuator/health`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**` | Público |
| `GET /api/bff/catalogo/**` | Público (vitrina minorista y cotización) |
| `POST /api/bff/consultas` | Público (alta de consulta B2B) |
| `OPTIONS /**` | Público (preflight CORS) |
| `/api/bff/admin/**`, `/api/bff/inventario/**`, `/api/bff/compras/**` | Solo `ROLE_ADMIN` |
| Todo lo demás | Requiere autenticación |

**Cabeceras transversales:** `X-Correlation-ID` (trazabilidad), `Idempotency-Key` / `Idempotent-Replay` (idempotencia).

---

## Infraestructura y Despliegue

| Componente | Plataforma | Detalle |
|:---|:---|:---|
| `front-web` | **Vercel** | Despliegue automático desde Git; Edge CDN global; rewrites `/api/*` → EC2 |
| `bff-web` | **AWS EC2** | Contenedor Docker, único puerto público `8080` |
| `ms-inventario` | **AWS EC2** | Contenedor Docker en red interna, puerto `8081` |
| `ms-ventas` | **AWS EC2** | Contenedor Docker en red interna, puerto `8082` |
| `ms-compras` | **AWS EC2** | Contenedor Docker en red interna, puerto `8083` |
| `kafka` | **AWS EC2** | Apache Kafka KRaft (`agrocenter-kafka:9092`) con volumen `kafka-data` |
| `postgres` | **AWS EC2 (Docker)** | PostgreSQL 16 con `db_inventario`, `db_ventas`, `db_compras`, `db_bff` |
| Autenticación | **AWS Cognito** | User Pool + App Client (PKCE) con Google como identity provider |
| Orquestación | **Docker Compose** | Red puente `agrocenter-network` con control de memoria JVM |

### Despliegue en AWS EC2 (Paso a Paso)

#### 1. Configurar Security Group
En la consola de AWS (EC2 > Security Groups) habilita las reglas *Inbound*:
- **SSH (22)**: tu IP, solo para administración.
- **Custom TCP (8080)**: `0.0.0.0/0` para `bff-web` (o restringir a Vercel).

#### 2. Clonar y configurar entorno
```bash
git clone <URL_DEL_REPOSITORIO>
cd agrocenter_digital
cp .env.example .env
nano .env   # POSTGRES_PASSWORD, COGNITO_*, ALLOWED_ORIGINS, etc.
```

#### 3. Levantar todo el backend
```bash
docker compose up -d --build
docker compose ps
curl http://localhost:8080/actuator/health
```

#### 4. Conectar con Vercel
En **Vercel > Settings > Environment Variables**:
- `BACKEND_INTERNAL_URL`: `http://<IP_PUBLICA_EC2>:8080`
- `NEXT_PUBLIC_COGNITO_DOMAIN`, `NEXT_PUBLIC_COGNITO_CLIENT_ID`, `NEXT_PUBLIC_COGNITO_REDIRECT_URI`

`next.config.ts` reescribe `/api/*` y `/actuator/*` hacia `BACKEND_INTERNAL_URL`, por lo que el navegador siempre habla HTTPS con Vercel y evita el bloqueo de *Mixed Content*.

---

## Ejecución Local

### Prerrequisitos
- **Java 21** (Temurin recomendado) · **Node.js ≥ 22.13** · **Docker** (opcional pero recomendado)
- **PostgreSQL 16** con `db_inventario`, `db_ventas`, `db_compras`, `db_bff`
- **Apache Kafka** (opcional; requerido para el flujo de eventos)

### Opción A — Todo con Docker Compose (recomendado)
```bash
cp .env.example .env
docker compose up -d --build
# BFF: http://localhost:8080/actuator/health
```

### Opción B — Servicios por separado

```bash
# 1) Microservicios (requieren PostgreSQL y Kafka accesibles)
export DB_PASSWORD=tu_password
export SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092

cd ms-inventario && chmod +x mvnw && ./mvnw spring-boot:run   # :8081
cd ms-ventas     && chmod +x mvnw && ./mvnw spring-boot:run   # :8082
cd ms-compras    && chmod +x mvnw && ./mvnw spring-boot:run   # :8083

# 2) BFF
cd bff-web && chmod +x mvnw && ./mvnw spring-boot:run          # :8080
#   Swagger UI (perfil dev): http://localhost:8080/swagger-ui.html

# 3) Frontend
cd front-web && npm install
export NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
export NEXT_PUBLIC_COGNITO_DOMAIN=https://tu-dominio.auth.us-east-1.amazoncognito.com
export NEXT_PUBLIC_COGNITO_CLIENT_ID=tu_client_id
export NEXT_PUBLIC_COGNITO_REDIRECT_URI=http://localhost:3000
npm run dev                                                    # :3000
```

### Kafka local (broker KRaft independiente)
```bash
docker run -d --name agrocenter-kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
  apache/kafka:3.9.0
```

> Los tópicos `ordenes-compras` e `inventario-critico` se crean automáticamente al arrancar los microservicios.

---

## Variables de Entorno

### Raíz (`.env` para Docker Compose)

| Variable | Descripción | Valor por defecto |
|:---|:---|:---|
| `DB_HOST` / `DB_PORT` | Host y puerto de PostgreSQL | `postgres` / `5432` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | Credenciales PostgreSQL | `postgres` / (obligatorio) |
| `SPRING_PROFILES_ACTIVE` | Perfil Spring Boot | `prod` |
| `SWAGGER_ENABLED` | Habilita Swagger UI | `false` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Broker Kafka | `kafka:9092` |
| `COMPRAS_AUTO_PROVEEDOR_ID` | Proveedor por defecto para reabastecimiento | `1` |
| `COGNITO_ISSUER_URI` / `COGNITO_JWK_SET_URI` / `COGNITO_AUDIENCE` | Configuración Cognito | `us-east-1_...` / `agrocenter-api` |
| `DEV_JWT_SECRET` | Secreto HS256 para tokens de desarrollo | (256 bits) |
| `ALLOWED_ORIGINS` / `ALLOWED_ORIGIN_PATTERNS` | CORS del BFF | `https://front-web-seven.vercel.app,...` |
| `BFF_PORT` | Puerto público del BFF en la EC2 | `8080` |
| `INVENTARIO_PORT` / `VENTAS_PORT` / `COMPRAS_PORT` | Puertos internos | `8081` / `8082` / `8083` |
| `JAVA_TOOL_OPTIONS` | Límite de RAM por JVM | `-XX:MaxRAMPercentage=20.0 ...` |

### `bff-web`

| Variable | Descripción | Valor por defecto |
|:---|:---|:---|
| `SERVER_PORT` | Puerto del BFF | `8080` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | Datasource `db_bff` | — |
| `INVENTORY_SERVICE_URL` / `SALES_SERVICE_URL` / `PURCHASES_SERVICE_URL` | URLs de microservicios | `http://ms-*:808x` |
| `MS_*_CONNECT_TIMEOUT` / `MS_*_RESPONSE_TIMEOUT` | Timeouts por servicio | `5s` / `10s`–`20s` |
| `COGNITO_ISSUER_URI` / `COGNITO_JWK_SET_URI` / `COGNITO_AUDIENCE` | Validación JWT | — |
| `ALLOWED_ORIGINS` / `ALLOWED_ORIGIN_PATTERNS` | CORS | `*.vercel.app` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Broker para el módulo de mensajería | `localhost:9092` |
| `KAFKA_TOPICS` | Tópicos gestionados por el panel | `ordenes-compras,inventario-critico` |
| `SWAGGER_ENABLED` | Swagger UI | `true` |

### Microservicios (`ms-inventario`, `ms-ventas`, `ms-compras`)

| Variable | Descripción |
|:---|:---|
| `SERVER_PORT` | Puerto del servicio (`8081`, `8082`, `8083`) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | Datasource PostgreSQL propio |
| `COGNITO_ISSUER_URI` / `COGNITO_JWK_SET_URI` / `COGNITO_AUDIENCE` | Validación JWT |
| `INVENTORY_SERVICE_URL` | URL de ms-inventario (solo en ventas y compras) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Dirección del broker Kafka |
| `CATALOG_QUOTE_ONLY_SKUS` | SKUs solo-cotización separados por coma (solo inventario) |
| `COMPRAS_AUTO_PROVEEDOR_ID` | Proveedor por defecto para reabastecimiento (solo compras) |
| `DEV_JWT_SECRET` / `DEV_JWT_ISSUER` / `DEV_JWT_TTL_SECONDS` | JWT de desarrollo (perfil `dev`) |

### `front-web`

| Variable | Descripción |
|:---|:---|
| `BACKEND_INTERNAL_URL` | URL del BFF en la EC2 (usada por los rewrites de Next.js) |
| `NEXT_PUBLIC_API_BASE_URL` | URL base del BFF (fallback / local) |
| `NEXT_PUBLIC_COGNITO_DOMAIN` | Dominio del Hosted UI de Cognito |
| `NEXT_PUBLIC_COGNITO_CLIENT_ID` | App Client ID de Cognito |
| `NEXT_PUBLIC_COGNITO_REDIRECT_URI` | URI de redirección post-login |
| `NEXT_PUBLIC_WHATSAPP_NUMBER` / `NEXT_PUBLIC_CONTACT_*` | Datos de contacto comercial (opcionales) |

---

## Endpoints Principales

### Públicos (sin autenticación)

| Método | Ruta | Descripción |
|:---|:---|:---|
| `GET` | `/api/bff/catalogo` | Catálogo para la vitrina |
| `POST` | `/api/bff/consultas` | Alta de consulta / cotización B2B |
| `GET` | `/api/bff/health`, `/actuator/health` | Health check del BFF |
| `POST` | `/api/dev/token` | Emisión de JWT local (solo perfil `dev`) |

### Protegidos (requieren Bearer JWT)

| Método | Ruta | Rol | Descripción |
|:---|:---|:---|:---|
| `POST` | `/api/bff/ventas` | CLIENTE / ADMIN | Checkout idempotente |
| `GET` | `/api/bff/ventas/mis-pedidos` | CLIENTE / ADMIN | Pedidos del cliente |
| `GET` | `/api/bff/ventas/{id}` | CLIENTE / ADMIN | Detalle de una venta (anti-IDOR) |
| `GET` | `/api/bff/admin/ventas` | ADMIN | Historial global de ventas |
| `GET` | `/api/bff/admin/inventario` | ADMIN | Listar productos con stock |
| `POST` | `/api/bff/inventario/productos` | ADMIN | Crear producto |
| `PUT` | `/api/bff/inventario/productos/{id}` | ADMIN | Actualizar producto |
| `PATCH` | `/api/bff/inventario/productos/{id}/estado` | ADMIN | Activar/desactivar producto |
| `GET` | `/api/bff/inventario/stock-bajo` | ADMIN | Productos con stock crítico |
| `POST` | `/api/bff/inventario/stock/entrada` | ADMIN | Entrada de stock |
| `GET` | `/api/bff/inventario/movimientos` | ADMIN | Movimientos de inventario |
| `POST` | `/api/bff/compras` | ADMIN | Crear orden de abastecimiento |
| `GET` | `/api/bff/admin/compras` | ADMIN | Historial de compras |
| `PATCH` | `/api/bff/compras/{id}/confirmar` | ADMIN | Confirmar orden (persistir datos) |
| `GET` | `/api/bff/admin/consultas` | ADMIN | Listar consultas B2B |
| `PATCH` | `/api/bff/admin/consultas/{id}/estado` | ADMIN | Cambiar estado de una consulta |
| `GET` | `/api/bff/admin/dashboard` | ADMIN | Datos agregados del dashboard |
| `GET` | `/api/bff/admin/mensajeria/topicos` | ADMIN | Estado de tópicos Kafka |
| `GET` | `/api/bff/admin/mensajeria/reglas` | ADMIN | Reglas de encaminamiento |
| `POST` | `/api/bff/admin/mensajeria/reglas` | ADMIN | Crear regla |
| `DELETE` | `/api/bff/admin/mensajeria/reglas/{id}` | ADMIN | Eliminar regla |
| `GET` | `/api/bff/admin/mensajeria/eventos` | ADMIN | Trazabilidad de eventos |

---

## Tecnologías Utilizadas

| Categoría | Tecnología |
|:---|:---|
| **Backend** | Java 21, Spring Boot 3.5.7 (`ms-ventas`, `ms-compras`, `bff-web`) y 4.1 (`ms-inventario`), Spring Security (OAuth2 Resource Server), Spring Data JPA, Hibernate, Flyway |
| **Mensajería** | Apache Kafka 3.9 (KRaft), Spring Kafka (`KafkaTemplate`, `@KafkaListener`, `KafkaAdmin`) |
| **Comunicación HTTP** | Spring `RestClient` (BFF) y `WebClient` (microservicios) con timeouts configurables |
| **Frontend** | Next.js 15 (App Router), React 19, TypeScript 5.9, Tailwind CSS 4, Lucide React |
| **Base de datos** | PostgreSQL 16, estrategia *database-per-service* (4 bases lógicas) |
| **Autenticación** | AWS Cognito (OAuth 2.0 PKCE + Google Federation) y JWT HS256 para desarrollo |
| **Contenedores** | Docker multi-stage (JRE Alpine, usuario no-root, `read_only`, `cap_drop: ALL`) |
| **Orquestación** | Docker Compose en EC2 (AWS Academy) |
| **Frontend hosting** | Vercel (Edge CDN) con rewrites hacia la EC2 |
| **Documentación API** | SpringDoc OpenAPI 3 / Swagger UI |
| **Observabilidad** | `X-Correlation-ID` + MDC en todos los servicios |
| **Build tools** | Maven (Java), npm (Node.js) |
