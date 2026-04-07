# PC2: Servicio de Analítica, Control de Semáforos y Base de Datos Réplica

## 📋 Descripción

Segundo componente del proyecto **Gestión Inteligente de Tráfico Urbano (2026-10)**.

PC2 ejecuta tres servicios concurrentes en la máquina 192.168.1.2:
- **Servicio de Analítica**: Recibe eventos de sensores, procesa reglas de congestión y coordina cambios de semáforos
- **Servicio de Control de Semáforos**: Gestiona estados de semáforos (VERDE/ROJO) con duraciones dinámicas
- **Gestor de Base de Datos Réplica**: Almacena análisis en PostgreSQL con fallback a PC3

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│  PC1 (Sensores y Broker ZMQ)                               │
│  - Cámaras, Espiras, GPS → eventos JSON                    │
│  - Broker PUB/SUB en puerto 5555                           │
└─────────────────────────┬───────────────────────────────────┘
                          │ tcp://192.168.1.2:5556 (SUB)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  PC2 (Este componente)                                     │
│  ┌───────────────────────────────────────────────────────┐ │
│  │ ServicioAnalitica                                     │ │
│  │ - Recibe: EVENTO_LONGITUD_COLA (SUB)               │ │
│  │          EVENTO_CONTEO_VEHICULAR                   │ │
│  │          EVENTO_DENSIDAD_TRAFICO                   │ │
│  │ - Procesa: Reglas de congestión (NORMAL/CONGESTION)│ │
│  │ - Envía: Comandos → ServicioControlSemaForos (Queue)│ │
│  │          Datos → GestorBaseDatosReplica (PUSH 6000)│ │
│  └─────────────────────────────────────────────────────┘ │
│         │                            │                     │
│         │ Cola                       │ PUSH                │
│         │                            │                     │
│  ┌──────▼────────────────┐  ┌────────▼──────────────────┐ │
│  │ ServicioControlSemaForos      GestorBaseDatosReplica │ │
│  │                        │      │                       │ │
│  │ - Recibe: Comandos    │      │ - Recibe: Datos (PULL)│ │
│  │ - Mantiene: Mapa      │      │ - Almacena: PostgreSQL│ │
│  │   {intersección →     │      │ - Fallback: PC3      │ │
│  │   estado (V/R)}       │      │                       │ │
│  │ - Programa: Cambios   │      │                       │ │
│  │   (ScheduledExecutor) │      │                       │ │
│  └───────────────────────┘      └───────────────────────┘ │
│           │                               │                │
│           │ [SEMAFOROCTL]                 │ [BD_REPLICA]   │
│           │ Logs cambios                  │ Logs INSERTs   │
│           ▼                               ▼                │
│         stdout                          stdout             │
└─────────────────────────────────────────────────────────────┘
                          │
                          │ Fallback: 192.168.1.3:5432
                          ▼
                    PostgreSQL trafico_db
```

## 📦 Componentes Principales

### 1. ReglasCongestion.java (utils/)
Clase utilitaria con lógica estática para evaluar congestión.

```java
detectarCongestion(EventoCamara, EventoEspira, EventoGPS): String
// Retorna: "NORMAL" o "CONGESTION"

obtenerDuracionFase(estado: String): int
// Retorna: Duración en segundos (15, 25, 40)
```

**Reglas:**
- **NORMAL**: cola < 10 AND velocidad > 15 AND densidad < 40 → 15s
- **CONGESTION**: cola >= 10 OR velocidad <= 15 OR densidad >= 40 → 25s
- **PRIORIDAD**: Comando directo de Monitoreo → 40s

### 2. ServicioAnalitica.java (servicios/)
Recibe eventos del broker, procesa reglas de congestión y coordina acciones.

**Comunicación:**
- **Entrada (SUB)**: `tcp://192.168.1.2:5556` (desde PC1)
  - Tópicos: `EVENTO_LONGITUD_COLA`, `EVENTO_CONTEO_VEHICULAR`, `EVENTO_DENSIDAD_TRAFICO`
  
- **Salida PUSH**: `tcp://*:6000` (hacia GestorBaseDatosReplica)
  - Formato: JSON con {interseccion, estado, timestamp, velocidad_promedio, densidad, cola}
  
- **Salida Cola**: Comandos asincronía a ServicioControlSemaForos
  - Formato: JSON con {interseccion, estado, duracion, timestamp}

**Comportamiento:**
- Agrupa eventos por intersección (espera los 3 tipos de sensores)
- Cuando están completos, aplica ReglasCongestion
- Envía comando a semáforo (asincronía sin bloqueos)
- Envía datos a BD para persistencia
- Imprime: `[ANALITICA] A1 | CONGESTION | Duración: 25s | 2026-04-05T...`

### 3. ServicioControlSemaForos.java (servicios/)
Gestiona estados de semáforos con cambios cronometrados.

**Comunicación:**
- **Entrada**: BlockingQueue de ServicioAnalitica
- **Estado**: Mapa {intersección → EstadoSemaforoInterseccion}

**Máquina de estados:**
```
        ROJO ───comando──> VERDE
         ▲                   │
         │                   │ Esperar duracion
         │                   │
         └───timeout─────────┘
```

**Comportamiento:**
- Lee comandos de la cola (non-blocking con timeout 1s)
- Cambia semáforo a VERDE cuando recibe comando desde ROJO
- Programa regreso a ROJO mediante ScheduledExecutor
- Si ya está VERDE, actualiza duración
- Imprime: `[SEMAFOROCTL] A1 | VERDE | duracion=25s`

### 4. GestorBaseDatosReplica.java (servicios/)
Recibe datos de analítica y los persiste en PostgreSQL.

**Comunicación:**
- **Entrada (PULL)**: `tcp://*:6000` (desde ServicioAnalitica)
- **Almacenamiento**: PostgreSQL (trafico_db)
- **Fallback**: 192.168.1.3:5432 si PC2 no está disponible

**Esquema de tabla:**
```sql
CREATE TABLE analisis_trafico (
  interseccion VARCHAR(50) PRIMARY KEY,
  estado VARCHAR(20) NOT NULL,
  timestamp VARCHAR(50) NOT NULL,
  velocidad_promedio DOUBLE PRECISION,
  densidad INTEGER,
  cola INTEGER,
  fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**Comportamiento:**
- Extrae datos del PULL socket
- Intenta INSERT/UPDATE en BD principal
- Si falla, intenta fallback a PC3 (192.168.1.3:5432)
- Imprime: `[BD_REPLICA] INSERT | A1 | 2026-04-05T...`

### 5. LanzadorPC2.java (raíz)
Punto de entrada main que orquesta todo.

**Ciclo de vida:**
1. Carga configuración desde `config.json`
2. Crea cola de comunicación Analítica↔Semáforos
3. Instancia los 3 servicios
4. Lanza en 3 hilos separados (non-daemon)
5. Instala hook de apagado limpio (Ctrl+C)
6. Mantiene proceso activo

**Apagado limpio:**
```
Ctrl+C → Shutdown Hook
  │
  ├─→ servicioAnalitica.detener()
  ├─→ servicioControlSemaForos.detener()
  ├─→ gestorBaseDatos.detener()
  │
  └─→ Esperar 5s max por cada hilo
      → Salida limpia
```

## 🔧 Configuración

### config.json
```json
{
  "ciudad": {
    "filas": ["A", "B", "C", "D"],
    "columnas": [1, 2, 3, 4],
    "total_intersecciones": 16
  },
  "broker": {
    "puerto_sub": 5556,
    "puerto_pub": 5555,
    "host_pc2": "192.168.1.2"
  },
  "trafico": {
    "velocidad_maxima_kmh": 50,
    "umbral_congestion_cola": 10,
    "umbral_congestion_velocidad": 15,
    "umbral_congestion_densidad": 40
  },
  "semaforos": {
    "duracion_normal": 15,
    "duracion_congestion": 25,
    "duracion_prioridad": 40
  },
  "servicios": {
    "analitica": {
      "host": "192.168.1.2",
      "puerto_pull": 6000
    },
    "base_datos": {
      "host": "192.168.1.2",
      "puerto": 6000
    }
  }
}
```

## 🚀 Compilación y Ejecución

### Requisitos
- Java 17+
- Maven 3.8+
- PostgreSQL 12+ (opcional, para persistencia)

### Compilar
```bash
cd Trafico-PC2
mvn clean compile
```

### Empaquetar
```bash
mvn clean package
```

### Ejecutar
```bash
# Opción 1: Desde Maven
mvn exec:java -Dexec.mainClass="com.trafico.LanzadorPC2"

# Opción 2: JAR ejecutable
java -jar target/Trafico-PC2-1.0-SNAPSHOT.jar

# Opción 3: Con classpath
java -cp target/classes:target/dependency/* com.trafico.LanzadorPC2
```

## 📊 Flujo de Datos

```
PC1 Sensores (simulados)
    │
    ├─→ EventoCamara {sensorId, interseccion, volumen: 5, velocidadPromedio: 20}
    ├─→ EventoEspira {sensorId, interseccion, vehiculosContados: 15, intervaloSegundos: 30}
    └─→ EventoGPS {sensorId, interseccion, velocidadPromedio: 18, nivelCongestion: NORMAL}
         │
         └─ Publicados en PUB/SUB del Broker

Broker PC1 (5555 PUB → 5556 SUB)
    │
    └─→ Eventos en tópicos

PC2 ServicioAnalitica (SUB 5556)
    │
    ├─→ Agrupa eventos por intersección
    │
    ├─→ Aplica ReglasCongestion.detectarCongestion()
    │   - Cola (5) < 10 ✓
    │   - Velocidad (18) > 15 ✓
    │   - Densidad (50) < 40 ✗
    │   → Resultado: CONGESTION (porque densidad >= 40)
    │
    ├─→ Envía comando a ServicioControlSemaForos (async queue)
    │   {"interseccion": "A1", "estado": "CONGESTION", "duracion": 25, ...}
    │
    └─→ Envía datos a BD (PUSH 6000)
        {"interseccion": "A1", "estado": "CONGESTION", "timestamp": "...", ...}

ServicioControlSemaForos
    │
    ├─→ Recibe comando de cola
    ├─→ Cambia A1: ROJO → VERDE
    ├─→ Programa regreso a ROJO en 25s (ScheduledExecutor)
    └─→ Imprime: [SEMAFOROCTL] A1 | VERDE | duracion=25s

GestorBaseDatosReplica (PULL 6000)
    │
    ├─→ Recibe datos
    ├─→ INSERT en trafico_db.analisis_trafico
    ├─→ Si falla: intenta fallback a PC3
    └─→ Imprime: [BD_REPLICA] INSERT | A1 | 2026-04-05T...

PostgreSQL
    └─→ Tabla analisis_trafico actualizada
```

## 📝 Logs por Pantalla

```
╔════════════════════════════════════════════╗
║  PC2: Servicio de Analítica de Tráfico    ║
║  Gestión Inteligente de Tráfico Urbano    ║
║  2026-10                                   ║
╚════════════════════════════════════════════╝

[LANZADOR] Cargando configuración...
[LANZADOR] Configuración cargada:
  - Host PC2: 192.168.1.2
  - Puerto SUB (Broker): 5556
  - Puerto PUSH/PULL (Analítica→BD): 6000

[LANZADOR] Inicializando servicios...
[LANZADOR] Lanzando servicios...
[ANALITICA] Iniciando ServicioAnalitica...
[ANALITICA] Conectado al broker en: tcp://192.168.1.2:5556
[ANALITICA] Socket PUSH enlazado en: tcp://*:6000
[SEMAFOROCTL] Iniciando ServicioControlSemaForos...
[SEMAFOROCTL] Semáforos inicializados.
[BD_REPLICA] Iniciando GestorBaseDatosReplica...
[BD_REPLICA] Base de datos inicializada correctamente.
[BD_REPLICA] Socket PULL enlazado en: tcp://*:6000
[LANZADOR] ✓ Todos los servicios iniciados
[LANZADOR] Presione Ctrl+C para detener...

--- Funcionamiento normal ---
[ANALITICA] A1 | NORMAL | Duración: 15s | 2026-04-05T14:30:45.123Z
[SEMAFOROCTL] A1 | VERDE | duracion=15s
[BD_REPLICA] INSERT | A1 | 2026-04-05T14:30:45.123Z

[ANALITICA] B2 | CONGESTION | Duración: 25s | 2026-04-05T14:30:50.456Z
[SEMAFOROCTL] B2 | VERDE | duracion=25s
[BD_REPLICA] INSERT | B2 | 2026-04-05T14:30:50.456Z

--- Apagado ---
[LANZADOR] Señal de apagado recibida (Ctrl+C)...
[LANZADOR] Esperando a que los servicios finalicen...
[ANALITICA] Servicio finalizado.
[SEMAFOROCTL] Servicio finalizado.
[BD_REPLICA] Servicio finalizado.
[LANZADOR] ✓ Servicios detenidos correctamente
[LANZADOR] PC2 finalizado.
```

## 🔌 Puertos ZeroMQ

| Servicio | Patrón | Host | Puerto | Dirección | Descripción |
|----------|--------|------|--------|-----------|-------------|
| ServicioAnalitica | SUB | 192.168.1.2 | 5556 | Entrada | Recibe eventos de PC1 |
| ServicioAnalitica | PUSH | * | 6000 | Salida | Envía datos a BD |
| GestorBaseDatosReplica | PULL | * | 6000 | Entrada | Recibe datos de Analítica |

## 🗄️ Puertos PostgreSQL

| Destino | Host | Puerto | DB | Usuario |
|---------|------|--------|----|---------| 
| Principal | localhost | 5432 | trafico_db | trafico_user |
| Fallback | 192.168.1.3 | 5432 | trafico_db | trafico_user |

## 🧪 Pruebas Manuales

### 1. Verificar compilación
```bash
mvn clean compile -X
```

### 2. Ejecutar con debug
```bash
mvn exec:java -Dexec.mainClass="com.trafico.LanzadorPC2" -DaddResources=true
```

### 3. Verificar conexiones ZMQ
```bash
# En otra terminal, escuchar en el puerto 6000
zmq_benchmark -t pull -e tcp://*:6000

# Simular envío desde otra instancia
zmq_send "tcp://localhost:6000" "test_data"
```

### 4. Verificar BD PostgreSQL
```bash
psql -h localhost -U trafico_user -d trafico_db -c "SELECT * FROM analisis_trafico ORDER BY fecha_actualizacion DESC LIMIT 5;"
```

## ⚠️ Manejo de Errores

- **BD no disponible**: Se intenta fallback a PC3, si también falla se registra pero continúa
- **Broker desconectado**: ServicioAnalitica reintentar cada 100ms
- **Cola llena**: Se descartan comandos de semáforo si la cola alcanza 100 elementos
- **Apagado forzado**: Todos los recursos se liberan correctamente

## 📚 Dependencias

```xml
<!-- Jackson para JSON -->
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.15.2</version>
</dependency>

<!-- JeroMQ (ZeroMQ para Java) -->
<dependency>
  <groupId>org.zeromq</groupId>
  <artifactId>jeromq</artifactId>
  <version>0.5.4</version>
</dependency>

<!-- PostgreSQL JDBC -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>42.7.3</version>
</dependency>
```

## 🔄 Relación con PC1 y PC3

- **PC1**: Genera sensores y broker ZMQ (entrada a PC2)
- **PC2** (este): Procesa analítica y controla semáforos
- **PC3**: Base de datos réplica (fallback para persistencia)

## 📖 Documentación Adicional

- Especificación original: `REQUISITOS_FUNCIONALES_PC2.md`
- Configuración: `src/main/resources/config.json`
- Modelos de eventos: `src/main/java/org/modelos/`
- Utilidades de configuración: `src/main/java/org/config/`

---

**Autor**: Estudiante de Sistemas Distribuidos  
**Fecha**: 2026-04  
**Proyecto**: Gestión Inteligente de Tráfico Urbano (2026-10)  
**Versión**: 1.0

