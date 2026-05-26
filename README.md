# Gestión Inteligente de Tráfico Urbano

Sistema distribuido en 3 máquinas para monitoreo y control de tráfico en tiempo real. Cuadrícula 5×5 (25 intersecciones, INT-A1 a INT-E5). Comunicación exclusivamente mediante ZeroMQ (JeroMQ).

---

## Cómo funciona el sistema

### Arquitectura general

El sistema combina dos estilos arquitectónicos:

**Cliente/Servidor (REQ/REP)**
- El *Consultor de Usuario* (cliente REQ) envía consultas al *Servicio de Monitoreo* en PC3 (servidor REP, puerto 7000).
- Si PC3 no responde en 4 segundos, el cliente hace **failover automático** al *ServicioMonitoreoRéplica* en PC2 (puerto 7001) sin intervención del usuario.
- El *Servicio de Analítica* (cliente REQ) envía comandos de cambio de semáforo al *Servicio de Control de Semáforos* (servidor REP, puerto 6001) y espera ACK antes de continuar.

**Orientada a Eventos (PUB/SUB y PUSH/PULL)**
- 15 sensores publican eventos continuamente al broker ZMQ en PC1.
- El broker enruta los eventos por tópico (CAMARA, ESPIRA, GPS) a todos los suscriptores.
- La analítica en PC2 reacciona a cada evento de forma asíncrona: detecta el estado de tráfico y escribe en la BD sin bloquear la recepción de nuevos eventos.

### Flujo de datos completo

```
Sensores (PC1)
  │  PUB → tópico CAMARA/ESPIRA/GPS
  ▼
Broker ZMQ (PC1, :5555/:5556)
  │  PUB → todos los suscriptores
  ▼
ServicioAnalitica (PC2)
  │  Detecta NORMAL / CONGESTION / PRIORIDAD
  ├─ REQ → ServicioControlSemaforos (PC2, :6001)   ← ACK
  └─ PUSH → GestorBaseDatosReplica (PC2, :6000)
               │  Guarda en BD réplica local (PostgreSQL)
               └─ PUSH → GestorBaseDatos (PC3, :5557)   ← guarda en BD principal

ConsultorUsuario (cualquier PC)
  │  REQ → ServicioMonitoreo (PC3, :7000)
  │        │  Lee BD principal PostgreSQL
  │        └─ PUSH → ServicioAnalitica (PC2, :6002)  [solo para ENVIAR_PRIORIDAD]
  │
  └─ [si timeout 4s] REQ → ServicioMonitoreoReplica (PC2, :7001)
                           │  Lee BD réplica local
                           └─ PUSH → ServicioAnalitica (PC2, :6002)
```

### Detección de estado de tráfico

Cada intersección tiene exactamente **un tipo de sensor**. La analítica procesa el evento en cuanto llega (sin esperar otros sensores). Si hay múltiples sensores para una misma intersección, usa un buffer con timeout de 5 s para agruparlos.

| Condición detectada | Estado | Duración fase verde |
|---------------------|--------|---------------------|
| Cola < 10 AND velocidad > 15 km/h AND densidad < 40% | NORMAL | 15 s |
| Cola ≥ 10 OR velocidad ≤ 15 km/h OR densidad ≥ 40% | CONGESTION | 25 s |
| Comando manual del operador | PRIORIDAD | 40 s |

### Tolerancia a fallos

Cuando PC3 cae:
1. El cliente detecta timeout en ≤ 4 s en el socket REQ.
2. Cierra el socket y abre uno nuevo conectado a PC2:7001.
3. Imprime `[FAILOVER]` en pantalla y `[MODO REPLICA]` en el menú.
4. Todas las consultas siguientes van a PC2 hasta que se reinicie la sesión.

`GestorBaseDatosReplica` en PC2 siempre guarda localmente. Si la conexión a PC3 falla, sigue guardando en local sin interrupciones.

---

## Estructura del proyecto

```
proyecto-distribuidos/
├── PC1/Trafico-PC1/          ← Broker ZMQ + 15 sensores
├── PC2/Trafico-PC2/          ← Analítica + Semáforos + BD Réplica + Monitoreo Réplica
├── PC3/Trafico-PC3/          ← Monitoreo Principal + BD Principal
└── README.md
```

Cada módulo es un proyecto Maven independiente con su propio `pom.xml`.

---

## Prerequisitos

- Java 17+
- Maven 3.8+
- PostgreSQL 14+ (en PC2 y PC3)
- Acceso de red entre las 3 máquinas

---

## Base de Datos PostgreSQL

Ejecutar en **PC2** y en **PC3** (cada una en su propio PostgreSQL local):

```sql
CREATE DATABASE trafico_db;
\c trafico_db

CREATE TABLE analisis_trafico (
    interseccion       VARCHAR(20) PRIMARY KEY,
    estado             VARCHAR(20) NOT NULL,
    timestamp          VARCHAR(30) NOT NULL,
    velocidad_promedio DOUBLE PRECISION,
    densidad           DOUBLE PRECISION,
    cola               INTEGER
);
```

Usuario: `postgres`. Contraseña: vacía por defecto (ajustar en `config.json` → `servicios.base_datos.password`).

---

## Compilación

```bash
# PC1
cd PC1/Trafico-PC1 && mvn clean package -q

# PC2
cd PC2/Trafico-PC2 && mvn clean package -q

# PC3
cd PC3/Trafico-PC3 && mvn clean package -q
```

---

## Orden de arranque

**Respetar este orden** — los sockets que hacen `connect()` necesitan que el lado `bind()` ya esté activo.

### 1. PC3 — Monitoreo Principal + BD Principal
```bash
cd PC3/Trafico-PC3
mvn exec:java
```
Inicia:
- `ServicioMonitoreo` — REP en `:7000`
- `GestorBaseDatos` — PULL en `:5557`

### 2. PC2 — Analítica + Semáforos + BD Réplica + Monitoreo Réplica
```bash
cd PC2/Trafico-PC2
mvn exec:java
```
Inicia 4 hilos en paralelo:
- `ServicioAnalitica` — SUB al broker, REQ a semáforos, PUSH a BD
- `ServicioControlSemaforos` — REP en `:6001`
- `GestorBaseDatosReplica` — PULL en `:6000`, PUSH a PC3:5557
- `ServicioMonitoreoReplica` — REP en `:7001` (siempre activo, failover de PC3)

### 3. PC1 — Broker + Sensores
```bash
cd PC1/Trafico-PC1
mvn exec:java
```
Inicia:
- `BrokerZMQ` — SUB:5555 → PUB:5556
- 15 sensores (5 cámaras cada 5s, 5 GPS cada 8s, 5 espiras cada 30s)

### 4. Cliente — Desde cualquier máquina
```bash
cd PC3/Trafico-PC3
mvn exec:java -Dexec.mainClass=com.trafico.clientes.ConsultorServicioMonitoreo
```
- Conecta a PC3:7000 (primario)
- Failover automático a PC2:7001 si PC3 no responde en 4 s

---

## Puertos ZMQ

| Puerto | Patrón | Dirección | Servicio |
|--------|--------|-----------|----------|
| 5555 | SUB/PUB | PC1 BIND | Broker — entrada sensores |
| 5556 | PUB | PC1 BIND | Broker — salida hacia analítica |
| 5557 | PULL | PC3 BIND | GestorBaseDatos principal |
| 6000 | PULL | PC2 BIND | GestorBaseDatosReplica |
| 6001 | REP | PC2 BIND | ServicioControlSemaforos |
| 6002 | PULL | PC2 BIND | ServicioAnalitica — comandos de prioridad desde monitoreo |
| 7000 | REP | PC3 BIND | ServicioMonitoreo principal |
| 7001 | REP | PC2 BIND | ServicioMonitoreoReplica (failover) |

---

## Sensores por intersección

Cada intersección tiene exactamente un tipo de sensor:

| Tipo de sensor | Intersecciones | Intervalo |
|----------------|---------------|-----------|
| Cámara (`CAMARA`) — longitud de cola y velocidad | INT-A1, INT-B3, INT-C5, INT-D2, INT-E4 | 5 s |
| GPS (`GPS`) — velocidad promedio | INT-A3, INT-B1, INT-C4, INT-D5, INT-E2 | 8 s |
| Espira inductiva (`ESPIRA`) — conteo vehicular | INT-A2, INT-B4, INT-C1, INT-D3, INT-E5 | 30 s |

---

## Operaciones del cliente CLI

| Opción | Nombre | Parámetros |
|--------|--------|-----------|
| 1 | Consultar estado actual | Intersección (ej: `INT-A1`) |
| 2 | Ver histórico de período | Intersección + fechas ISO-8601 (ej: `2026-05-22T08:00:00Z`) |
| 3 | Enviar indicación de prioridad | Intersecciones, tipo (AMBULANCIA/BOMBEROS/POLICIA/EVENTO_ESPECIAL), duración 10–60 s |
| 4 | Ver estado global del sistema | — |
| 5 | Generar reporte | Tipo (DIARIO/SEMANAL) + fecha YYYY-MM-DD |
| 6 | Ver historial de consultas | — |
| 7 | Salir | — |

---

## Ajuste de IPs

Si las IPs de red cambian, editar `src/main/resources/config.json` en cada módulo:

| Campo | Valor actual | Descripción |
|-------|-------------|-------------|
| `broker.host_pc2` | `10.43.99.26` | IP de PC2 |
| `servicios.analitica.host` | `10.43.99.26` | IP de PC2 |
| `servicios.base_datos.host` | `10.43.101.27` | IP de PC3 (BD principal) |
| `servicios.monitoreo.host` | `10.43.101.27` | IP de PC3 (monitoreo) |
| `servicios.monitoreo.host_replica` | `10.43.99.26` | IP de PC2 (réplica) |

---

## Prueba de tolerancia a fallos

1. Iniciar el sistema completo (PC3 → PC2 → PC1).
2. Abrir el cliente y hacer una consulta (opción 4 — estado global).
3. Detener PC3 con Ctrl+C.
4. Volver al cliente y hacer otra consulta — debe mostrar `[FAILOVER]` y responder desde PC2 en ≤ 4 s.
5. El menú mostrará `[MODO REPLICA] Servidor: 10.43.99.26:7001`.

---

## Broker multihilo (pruebas de rendimiento)

Para comparar rendimiento base vs. multihilo, cambiar la clase principal en `PC1/Trafico-PC1/pom.xml`:

```xml
<!-- Broker simple (base) -->
<mainClass>com.trafico.LanzadorPC1</mainClass>

<!-- Broker multihilo -->
<mainClass>com.trafico.LanzadorPC1</mainClass>
<!-- editar LanzadorPC1.java para usar ZMQMultihilo en lugar de ZeroMQ -->
```
