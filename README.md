# Gestión Inteligente de Tráfico Urbano

Sistema distribuido en 3 máquinas para monitoreo y control de tráfico en una cuadrícula 5×5 (25 intersecciones, INT-A1 a INT-E5).

## Arquitectura

```
PC1 (10.43.xxx.xxx) — Sensores + Broker ZMQ
  Sensores → PUSH → Broker (SUB/PUB bridge, :5555/:5556)

PC2 (10.43.99.26) — Analítica + Control Semáforos + BD Réplica + MonitoreoRéplica
  Broker PUB → SUB ServicioAnalitica → REQ ServicioControlSemaforos (:6001)
                                     → PUSH GestorBDReplica (:6000)
  PC3 PUSH → PULL ServicioAnalitica (:6002)   [comandos de prioridad]
  Clientes REQ → REP ServicioMonitoreoReplica (:7001)  [failover de PC3]

PC3 (10.43.101.27) — Monitoreo Principal + BD Principal
  Clientes REQ → REP ServicioMonitoreo (:7000)
  ServicioMonitoreo PUSH → PC2:6002  [comandos de prioridad a analítica]
  GestorBDReplica PUSH → GestorBD PC3 (:5557)  [sincronización BD]
```

## Prerequisitos

- Java 17+
- Maven 3.8+
- PostgreSQL 14+ (en PC2 y PC3)
- Acceso de red entre las 3 máquinas

## Base de Datos PostgreSQL

Ejecutar en **PC2** (localhost) y **PC3** (localhost):

```sql
CREATE DATABASE trafico_db;
\c trafico_db

CREATE TABLE analisis_trafico (
    interseccion  VARCHAR(20) PRIMARY KEY,
    estado        VARCHAR(20) NOT NULL,
    timestamp     VARCHAR(30) NOT NULL,
    velocidad_promedio DOUBLE PRECISION,
    densidad      DOUBLE PRECISION,
    cola          INTEGER
);
```

Usuario por defecto: `postgres`, contraseña: vacía (ajustar en `config.json` → `servicios.base_datos.password`).

## Compilación

Cada módulo se compila independientemente desde su carpeta:

```bash
# PC1
cd PC1/Trafico-PC1
mvn clean package -q

# PC2
cd PC2/Trafico-PC2
mvn clean package -q

# PC3
cd PC3/Trafico-PC3
mvn clean package -q
```

## Orden de Arranque

**Importante:** respetar este orden para que los sockets encuentren sus pares.

### 1. PC3 — Servicio de Monitoreo + BD Principal

```bash
cd PC3/Trafico-PC3
mvn exec:java
```

Inicia:
- `ServicioMonitoreo` — REP en `:7000`, responde consultas de clientes
- `GestorBaseDatos` — PULL en `:5557`, persiste datos en PostgreSQL local

### 2. PC2 — Analítica + Control + BD Réplica + Monitoreo Réplica

```bash
cd PC2/Trafico-PC2
mvn exec:java
```

Inicia 4 hilos:
- `ServicioAnalitica` — SUB al broker, procesa eventos, REQ a control de semáforos
- `ServicioControlSemaforos` — REP en `:6001`, ejecuta cambios de fase
- `GestorBaseDatosReplica` — PULL en `:6000`, persiste en PostgreSQL local + PUSH a PC3:5557
- `ServicioMonitoreoReplica` — REP en `:7001`, failover cuando PC3 no responde

### 3. PC1 — Broker + Sensores

```bash
cd PC1/Trafico-PC1
mvn exec:java
```

Inicia:
- `Broker` — bridge SUB:5555 → PUB:5556
- `LanzadorSensores` — 15 sensores (5 cámaras, 5 espiras, 5 GPS) publicando al broker

### 4. Cliente CLI — Desde cualquier máquina

```bash
cd PC3/Trafico-PC3
mvn exec:java -Dexec.mainClass=com.trafico.clientes.ConsultorServicioMonitoreo
```

El cliente se conecta primero a PC3:7000. Si hay timeout (4s), hace **failover automático** a PC2:7001.

## Puertos

| Puerto | Protocolo | Servicio         | Máquina |
|--------|-----------|------------------|---------|
| 5555   | SUB       | Broker entrada   | PC1     |
| 5556   | PUB       | Broker salida    | PC1     |
| 5557   | PULL      | GestorBD         | PC3     |
| 6000   | PULL      | GestorBDRéplica  | PC2     |
| 6001   | REP       | ControlSemáforos | PC2     |
| 6002   | PULL      | Analítica (prioridades) | PC2 |
| 7000   | REP       | ServicioMonitoreo | PC3    |
| 7001   | REP       | MonitoreoRéplica | PC2     |

## Sensores por Intersección

Cada intersección tiene exactamente un tipo de sensor:

| Sensor | Intersecciones |
|--------|---------------|
| Cámara (EVENTO_LONGITUD_COLA) | INT-A1, INT-B3, INT-C5, INT-D2, INT-E4 |
| Espira (EVENTO_CONTEO_VEHICULAR) | INT-A2, INT-B4, INT-C1, INT-D3, INT-E5 |
| GPS (EVENTO_DENSIDAD_TRAFICO) | INT-A3, INT-B1, INT-C4, INT-D5, INT-E2 |

## Estados de Tráfico

| Estado    | Condición                          | Duración fase verde |
|-----------|-----------------------------------|---------------------|
| NORMAL    | Sin congestión detectada           | 15 s                |
| CONGESTION| Umbral superado (cola≥10, vel≤15km/h, densidad≥40%) | 25 s |
| PRIORIDAD | Comando manual (ambulancia, etc.)  | 40 s                |

## Tolerancia a Fallos

- **PC3 cae**: `GestorBDRéplica` en PC2 deja de sincronizar pero sigue persistiendo localmente. `ServicioMonitoreoReplica` en PC2 atiende nuevas consultas desde `:7001`. El cliente detecta timeout en 4s y hace failover automático.
- **PC2 cae**: PC3 sigue recibiendo consultas. Los sensores en PC1 siguen publicando (el broker sigue activo si PC1 está en pie). Sin analítica, los semáforos no reciben nuevos comandos.

## Operaciones del Cliente CLI

| Opción | Solicitud             | Descripción |
|--------|-----------------------|-------------|
| 1      | ESTADO_ACTUAL         | Estado semáforo + métricas de una intersección |
| 2      | HISTORIAL_RANGO       | Registros en rango de fechas (ISO-8601) |
| 3      | ENVIAR_PRIORIDAD      | Activar modo emergencia (AMBULANCIA/BOMBEROS/POLICIA/EVENTO_ESPECIAL) |
| 4      | ESTADO_GLOBAL         | Resumen de todas las intersecciones |
| 5      | GENERAR_REPORTE       | Reporte DIARIO o SEMANAL |
| 6      | —                     | Ver historial local de consultas |

Ejemplo de intersección: `INT-A1`, `INT-C3`.  
Ejemplo de fechas: `2026-05-22T08:00:00Z` / `2026-05-22T10:00:00Z`.

## Ajuste de IPs

Si las IPs cambian, editar `config.json` en cada módulo:

- `broker.host_pc2` → IP de PC2
- `servicios.analitica.host` → IP de PC2
- `servicios.base_datos.host` → IP de PC3
- `servicios.monitoreo.host` → IP de PC3
- `servicios.monitoreo.host_replica` → IP de PC2
