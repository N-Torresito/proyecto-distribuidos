# ESPECIFICACIÓN COMPLETA: PC3 - Servicio de Monitoreo y Consulta
## Gestión Inteligente de Tráfico Urbano (2026-10)

---

## 📋 CONTEXTO GENERAL

**Objetivo**: Implementar un servicio de monitoreo y consulta que permita a usuarios:
- Consultar el estado actual del sistema de tráfico en tiempo real
- Enviar indicaciones directas para cambios prioritarios (ambulancias, bomberos, eventos especiales)
- Realizar consultas históricas entre períodos específicos (horas pico, análisis de patrones)
- Obtener estadísticas globales del sistema

**Máquina de ejecución**: PC3 (192.168.1.3)  
**Puerto de comunicación**: 7000 (REQ/REP - ZeroMQ)  
**Acceso a BD**: PostgreSQL local (trafico_db)  
**Comunicación con Analítica**: PUSH a puerto 6001 (ServicioAnalitica)

---

## ⚙️ REQUISITOS FUNCIONALES

### 1. **ServicioMonitoreo.java** (Servidor REQ/REP)

**Responsabilidad**: Socket REQ/REP que recibe solicitudes JSON de clientes y envía respuestas.

**Puerto**: `tcp://*:7000` (bind)  
**Patrón**: REQ/REP (solicitud-respuesta sincrónica)

**Ciclo de vida**:
1. Crear socket REQ/REP
2. Bind en puerto 7000
3. Esperar solicitud (recvStr)
4. Procesar mediante ControladorConsultas
5. Enviar respuesta (sendStr)
6. Repetir

**Tipos de solicitudes soportadas**:

#### **1.1 ESTADO_ACTUAL** - Consultar estado de una intersección
```json
{
  "tipo_solicitud": "ESTADO_ACTUAL",
  "interseccion": "INT-A1"
}
```

**Respuesta exitosa**:
```json
{
  "estado": "EXITO",
  "codigo": 200,
  "datos": {
    "interseccion": "INT-A1",
    "estado_semaforo": "VERDE",
    "duracion_fase": 15,
    "densidad": 35,
    "velocidad_promedio": 18.5,
    "cola": 8,
    "timestamp": "2026-04-06T14:30:45.123Z"
  },
  "mensaje": "Estado actual obtenido exitosamente"
}
```

**Respuesta error**:
```json
{
  "estado": "ERROR",
  "codigo": 404,
  "error": "INTERSECCION_NO_ENCONTRADA",
  "mensaje": "La intersección INT-Z99 no existe",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

#### **1.2 HISTORIAL_RANGO** - Consultar histórico entre fechas
```json
{
  "tipo_solicitud": "HISTORIAL_RANGO",
  "interseccion": "INT-A1",
  "fecha_inicio": "2026-04-06T08:00:00Z",
  "fecha_fin": "2026-04-06T10:00:00Z"
}
```

**Respuesta exitosa**:
```json
{
  "estado": "EXITO",
  "codigo": 200,
  "datos": {
    "interseccion": "INT-A1",
    "periodo": {
      "inicio": "2026-04-06T08:00:00Z",
      "fin": "2026-04-06T10:00:00Z",
      "duracion_minutos": 120
    },
    "total_registros": 120,
    "registros": [
      {
        "timestamp": "2026-04-06T08:00:15Z",
        "estado": "NORMAL",
        "densidad": 35,
        "velocidad_promedio": 20.5,
        "cola": 8
      },
      {
        "timestamp": "2026-04-06T08:05:23Z",
        "estado": "CONGESTION",
        "densidad": 65,
        "velocidad_promedio": 10.2,
        "cola": 25
      }
    ],
    "estadisticas": {
      "registros_totales": 120,
      "tiempo_normal": "75 min",
      "tiempo_congestion": "45 min",
      "densidad_promedio": 48.5,
      "velocidad_promedio": 15.3,
      "cola_maxima": 35,
      "pico_congestion": "08:35-08:50"
    }
  },
  "mensaje": "Histórico recuperado exitosamente"
}
```

#### **1.3 ENVIAR_PRIORIDAD** - Indicación de prioridad (ambulancia, bomberos, etc)
```json
{
  "tipo_solicitud": "ENVIAR_PRIORIDAD",
  "intersecciones": ["INT-A1", "INT-A2", "INT-B1"],
  "tipo_evento": "AMBULANCIA",
  "duracion_segundos": 40,
  "razon": "Emergencia médica - Hospital Central"
}
```

**Respuesta exitosa**:
```json
{
  "estado": "EXITO",
  "codigo": 200,
  "datos": {
    "comando_id": "CMD-20260406-0001",
    "tipo_evento": "AMBULANCIA",
    "intersecciones": ["INT-A1", "INT-A2", "INT-B1"],
    "cantidad_intersecciones": 3,
    "duracion": 40,
    "razon": "Emergencia médica - Hospital Central",
    "timestamp_envio": "2026-04-06T14:30:45.123Z",
    "estado_propagacion": "ENVIADO_A_ANALITICA",
    "confirmacion": "Comando de prioridad propagado a 3 intersecciones"
  },
  "mensaje": "Comando de prioridad enviado exitosamente"
}
```

**Validaciones**:
- Todas las intersecciones deben existir (están en config.json)
- Duración entre 10-60 segundos
- Tipo de evento válido: AMBULANCIA, BOMBEROS, POLICIA, EVENTO_ESPECIAL
- Máximo 5 intersecciones por comando

#### **1.4 ESTADO_GLOBAL** - Vista general del sistema
```json
{
  "tipo_solicitud": "ESTADO_GLOBAL"
}
```

**Respuesta exitosa**:
```json
{
  "estado": "EXITO",
  "codigo": 200,
  "datos": {
    "timestamp": "2026-04-06T14:30:45.123Z",
    "intersecciones_totales": 25,
    "estado_por_categoria": {
      "NORMAL": 15,
      "CONGESTION": 8,
      "PRIORIDAD": 2
    },
    "estadisticas_globales": {
      "densidad_promedio_sistema": 42.3,
      "velocidad_promedio_sistema": 16.8,
      "cola_promedio": 12.5
    },
    "intersecciones_criticas": ["INT-A1", "INT-B3", "INT-C5", "INT-D2", "INT-E4"],
    "alertas": [
      {
        "nivel": "ALTO",
        "tipo": "CONGESTION_SEVERA",
        "zona": "A",
        "intersecciones_afectadas": 5,
        "mensaje": "Congestión severa en zona A",
        "recomendacion": "Activar protocolo de desvío de tráfico"
      },
      {
        "nivel": "MEDIO",
        "tipo": "VELOCIDAD_BAJA",
        "zona": "B",
        "intersecciones_afectadas": 2,
        "mensaje": "Velocidad promedio muy baja en zona B",
        "recomendacion": "Considerar incrementar duración de fases verdes"
      }
    ]
  },
  "mensaje": "Estado global obtenido exitosamente"
}
```

#### **1.5 GENERAR_REPORTE** - Reportes históricos
```json
{
  "tipo_solicitud": "GENERAR_REPORTE",
  "tipo": "DIARIO",
  "fecha": "2026-04-06",
  "incluir": ["estadisticas", "alertas", "recomendaciones"]
}
```

**Respuesta exitosa**:
```json
{
  "estado": "EXITO",
  "codigo": 200,
  "datos": {
    "reporte_id": "REP-20260406-001",
    "tipo": "DIARIO",
    "fecha": "2026-04-06",
    "periodo": {
      "inicio": "2026-04-06T00:00:00Z",
      "fin": "2026-04-06T23:59:59Z"
    },
    "resumen_ejecutivo": {
      "incidentes_totales": 12,
      "tiempo_congestión_total": "2h 45min",
      "tiempo_congestión_promedio_interseccion": "6.6 min",
      "velocidad_promedio_dia": 17.2,
      "interseccion_mas_congestionada": "INT-A1 (8h 30min en congestión)"
    },
    "horas_pico": [
      {
        "periodo": "07:30-09:00",
        "nombre": "Hora pico mañana",
        "congestion_promedio": 65,
        "velocidad_promedio": 12.3
      },
      {
        "periodo": "17:00-19:00",
        "nombre": "Hora pico tarde",
        "congestion_promedio": 72,
        "velocidad_promedio": 11.5
      }
    ],
    "recomendaciones": [
      "Incrementar duración de semáforos verdes en INT-A1 durante 07:30-09:00",
      "Considerar carriles reversibles en INT-B3 durante hora pico",
      "Implementar control de acceso en zona A durante 17:00-19:00",
      "Aumentar presencia de señalización en intersecciones críticas"
    ],
    "alertas": []
  },
  "mensaje": "Reporte generado exitosamente"
}
```

---

### 2. **ControladorConsultas.java** (Lógica de procesamiento)

**Responsabilidad**: Procesa solicitudes JSON y genera respuestas.

**Métodos principales**:

```java
/**
 * Procesa una solicitud JSON del cliente
 * @param solicitudJson JSON con tipo_solicitud y parámetros
 * @return JSON con respuesta (exitosa o error)
 */
public String procesarSolicitud(String solicitudJson) throws Exception

/**
 * Procesa tipo ESTADO_ACTUAL
 */
private String procesarEstadoActual(Map<String, Object> solicitud) throws SQLException

/**
 * Procesa tipo HISTORIAL_RANGO
 */
private String procesarHistorialRango(Map<String, Object> solicitud) throws SQLException

/**
 * Procesa tipo ENVIAR_PRIORIDAD
 * Envía comando a ServicioAnalitica mediante PUSH
 */
private String procesarEnviarPrioridad(Map<String, Object> solicitud) throws Exception

/**
 * Procesa tipo ESTADO_GLOBAL
 */
private String procesarEstadoGlobal() throws SQLException

/**
 * Procesa tipo GENERAR_REPORTE
 */
private String procesarGenerarReporte(Map<String, Object> solicitud) throws SQLException

/**
 * Valida que la intersección existe
 */
private boolean validarInterseccion(String interseccion)

/**
 * Valida rango de fechas
 */
private boolean validarFechas(LocalDateTime inicio, LocalDateTime fin)
```

**Comunicación con ServicioAnalitica**:
- Para ENVIAR_PRIORIDAD: Socket PUSH a `tcp://192.168.1.2:6001`
- Mensaje: JSON con {intersecciones, tipo_evento, duracion_segundos, razon, comando_id, timestamp}

---

### 3. **GestorBaseDatosConsultas.java** (Acceso a BD)

**Responsabilidad**: Ejecutar consultas SELECT en la tabla `analisis_trafico`.

**Métodos principales**:

```java
/**
 * Obtiene el estado más reciente de una intersección
 */
public Map<String, Object> obtenerEstadoActual(String interseccion) throws SQLException

/**
 * Obtiene histórico entre dos timestamps
 */
public List<Map<String, Object>> obtenerHistorial(String interseccion, 
                                                   LocalDateTime inicio, 
                                                   LocalDateTime fin) throws SQLException

/**
 * Obtiene todas las intersecciones con su estado actual
 */
public List<Map<String, Object>> obtenerTodasLasIntersecciones() throws SQLException

/**
 * Calcula estadísticas para una lista de registros
 */
public Map<String, Object> calcularEstadisticas(List<Map<String, Object>> registros)

/**
 * Obtiene registros críticos (CONGESTION)
 */
public List<Map<String, Object>> obtenerInterseccionesCriticas() throws SQLException
```

**Consultas SQL**:
```sql
-- Estado actual de una intersección (más reciente)
SELECT * FROM analisis_trafico 
WHERE interseccion = ? 
ORDER BY timestamp DESC 
LIMIT 1;

-- Histórico en rango de fechas
SELECT * FROM analisis_trafico 
WHERE interseccion = ? 
  AND timestamp >= ? 
  AND timestamp <= ?
ORDER BY timestamp ASC;

-- Todas las intersecciones (estado más reciente)
SELECT DISTINCT ON (interseccion) * FROM analisis_trafico 
ORDER BY interseccion, timestamp DESC;

-- Estadísticas: congestión por período
SELECT estado, COUNT(*) as total, AVG(densidad) as densidad_promedio
FROM analisis_trafico 
WHERE timestamp >= ? AND timestamp <= ?
GROUP BY estado;
```

---

### 4. **ConsultorServicioMonitoreo.java** (Cliente CLI interactivo)

**Responsabilidad**: Interfaz de usuario interactiva para enviar solicitudes.

**Características**:
- Menú principal con 5 opciones
- Lectura de parámetros desde stdin
- Validación de entrada
- Conexión REQ a puerto 7000
- Presentación formateada de respuestas
- Historial de últimas solicitudes

**Menú**:
```
╔═══════════════════════════════════════════════════════╗
║  CONSULTOR DE TRÁFICO - Gestión Inteligente          ║
║  Conexión: 192.168.1.3:7000 (REQ/REP)                ║
╚═══════════════════════════════════════════════════════╝

1. Consultar estado actual de intersección
2. Ver histórico de período específico
3. Enviar indicación de prioridad (ambulancia, etc)
4. Ver estado global del sistema
5. Generar reporte
6. Ver historial de consultas
7. Salir

Seleccione opción: _
```

**Opción 1: Estado actual**
```
Ingrese intersección (ej: INT-A1): INT-A1

[CONSULTOR] Enviando solicitud ESTADO_ACTUAL...
[CONSULTOR] Respuesta recibida en 45ms

╔════════════════════════════════════════════╗
║ ESTADO ACTUAL - INT-A1                     ║
╠════════════════════════════════════════════╣
║ Semáforo: VERDE                            ║
║ Duración Fase: 15 segundos                 ║
║ Densidad: 35%                              ║
║ Velocidad: 18.5 km/h                       ║
║ Cola: 8 vehículos                          ║
║ Timestamp: 2026-04-06 14:30:45             ║
╚════════════════════════════════════════════╝
```

**Opción 3: Enviar prioridad**
```
Ingrese intersecciones (separadas por coma): INT-A1,INT-A2,INT-B1
Tipo de evento (AMBULANCIA/BOMBEROS/POLICIA/EVENTO_ESPECIAL): AMBULANCIA
Duración (10-60 segundos): 40
Razón: Emergencia médica - Hospital Central

[CONSULTOR] Enviando comando de prioridad...
[CONSULTOR] Confirmación recibida en 78ms

╔════════════════════════════════════════════════╗
║ COMANDO DE PRIORIDAD ENVIADO                   ║
╠════════════════════════════════════════════════╣
║ ID: CMD-20260406-0001                          ║
║ Tipo: AMBULANCIA                               ║
║ Intersecciones: INT-A1, INT-A2, INT-B1        ║
║ Duración: 40 segundos                          ║
║ Estado: ENVIADO_A_ANALITICA                    ║
║ Timestamp: 2026-04-06 14:30:45                 ║
║ Confirmación: Comando propagado a 3 intx      ║
╚════════════════════════════════════════════════╝
```

---

### 5. **LanzadorPC3.java** (Punto de entrada)

**Ciclo de vida**:
1. Banner ASCII
2. Cargar config.json
3. Crear instancia de ServicioMonitoreo
4. Crear hilo para ServicioMonitoreo (daemon=false)
5. Instalar hook de apagado limpio
6. Esperar entrada (Thread.sleep en loop o leer stdin)

**Estructura**:
```java
public static void main(String[] args) {
    // Banner
    System.out.println("╔════════════════════════════════════════════╗");
    System.out.println("║  PC3: Servicio de Monitoreo y Consulta    ║");
    System.out.println("║  Gestión Inteligente de Tráfico Urbano    ║");
    System.out.println("║  2026-10                                   ║");
    System.out.println("╚════════════════════════════════════════════╝");
    
    // Cargar config
    ConfiguracionSistema config = ConfiguracionSistema.cargarDesdeRecursos();
    
    // Crear servicio
    ServicioMonitoreo servicio = new ServicioMonitoreo();
    
    // Lanzar hilo
    Thread hilo = new Thread(servicio, "Hilo-ServicioMonitoreo");
    hilo.setDaemon(false);
    hilo.start();
    
    // Hook de apagado
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        servicio.detener();
        // Esperar
    }));
    
    // Mantener vivo
    while(true) Thread.sleep(1000);
}
```

---

## 🔌 ARQUITECTURA DE COMUNICACIÓN

```
┌─────────────────────────────────────────────────────────┐
│ CLIENTE (ConsultorServicioMonitoreo)                    │
│ ├─ Solicitud JSON                                       │
│ │  (tipo_solicitud, parámetros)                         │
│ └─ REQ socket → tcp://192.168.1.3:7000                  │
└──────────────────────┬──────────────────────────────────┘
                       │ REQ
                       ▼
┌─────────────────────────────────────────────────────────┐
│ SERVICIO MONITOREO (ServicioMonitoreo) - PC3            │
│ ├─ REP socket (puerto 7000)                             │
│ ├─ Recibe solicitud JSON                                │
│ ├─ Procesa mediante ControladorConsultas                │
│ │  ├─ Consulta BD PostgreSQL (GestorBD)                 │
│ │  └─ Si es PRIORIDAD: envía PUSH a Analítica          │
│ └─ Genera respuesta JSON                                │
└──────────────────────┬──────────────────────────────────┘
                       │ REP
                       ▼
┌─────────────────────────────────────────────────────────┐
│ CLIENTE (ConsultorServicioMonitoreo)                    │
│ ├─ Respuesta JSON (estado/datos/error)                 │
│ └─ Presenta resultados formateados                      │
└─────────────────────────────────────────────────────────┘

COMUNICACIÓN CON ANALÍTICA (ENVIAR_PRIORIDAD):
┌──────────────────────────────────────┐
│ ServicioMonitoreo (PUSH socket)      │
│ → tcp://192.168.1.2:6001             │
└─────────────────┬────────────────────┘
                  │ PUSH
                  ▼
┌──────────────────────────────────────┐
│ ServicioAnalitica (PULL socket)      │
│ Recibe comando PRIORIDAD             │
│ → Procesa como estado PRIORIDAD      │
│ → Envía a ServicioControlSemaForos  │
└──────────────────────────────────────┘
```

---

## 📊 ESQUEMA DE BASE DE DATOS

**Tabla existente**: `analisis_trafico`
```sql
CREATE TABLE analisis_trafico (
  interseccion VARCHAR(50) NOT NULL,
  estado VARCHAR(20) NOT NULL,
  timestamp VARCHAR(50) NOT NULL,
  velocidad_promedio DOUBLE PRECISION,
  densidad INTEGER,
  cola INTEGER,
  fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Se recomienda agregar índices
CREATE INDEX idx_analisis_trafico_interseccion ON analisis_trafico(interseccion);
CREATE INDEX idx_analisis_trafico_timestamp ON analisis_trafico(timestamp);
CREATE INDEX idx_analisis_trafico_estado ON analisis_trafico(estado);
```

**Tabla nueva recomendada**: `historial_comandos_prioridad`
```sql
CREATE TABLE historial_comandos_prioridad (
  id SERIAL PRIMARY KEY,
  comando_id VARCHAR(50) UNIQUE NOT NULL,
  intersecciones TEXT NOT NULL,
  tipo_evento VARCHAR(50) NOT NULL,
  duracion_segundos INTEGER NOT NULL,
  razon TEXT,
  timestamp_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  timestamp_ejecucion TIMESTAMP,
  estado VARCHAR(20) DEFAULT 'PENDIENTE'
);

CREATE INDEX idx_cmd_prioridad_id ON historial_comandos_prioridad(comando_id);
CREATE INDEX idx_cmd_prioridad_timestamp ON historial_comandos_prioridad(timestamp_creacion);
```

---

## 🎯 CONFIGURACIÓN (config.json)

```json
{
  "servicios": {
    "monitoreo": {
      "puerto_reqrep": 7000
    },
    "analitica": {
      "host": "10.43.99.26",
      "puerto_pull": 6000,
      "puerto_push_semaforoctl": 6001
    },
    "base_datos": {
      "host": "10.43.101.27",
      "puerto": 5432,
      "nombre": "trafico_db",
      "usuario": "postgres",
      "password": ""
    }
  },
  "sensores": {
    "camaras": [
      {"interseccion": "INT-A1"},
      {"interseccion": "INT-B3"},
      {"interseccion": "INT-C5"},
      {"interseccion": "INT-D2"},
      {"interseccion": "INT-E4"}
    ]
  }
}
```

---

## 📁 ESTRUCTURA DE DIRECTORIOS

```
Trafico-PC3/
├── src/main/
│   ├── java/com/trafico/
│   │   ├── LanzadorPC3.java                    ← Punto de entrada
│   │   ├── config/
│   │   │   ├── ConfiguracionSistema.java       ← Reutilizar de PC2
│   │   │   └── Topicos.java                    ← Reutilizar de PC2
│   │   ├── servicios/
│   │   │   ├── ServicioMonitoreo.java          ← Servidor REQ/REP
│   │   │   └── ControladorConsultas.java       ← Lógica de solicitudes
│   │   ├── clientes/
│   │   │   └── ConsultorServicioMonitoreo.java ← Cliente CLI
│   │   └── bd/
│   │       └── GestorBaseDatosConsultas.java   ← Acceso a BD
│   └── resources/
│       └── config.json                         ← Reutilizar de PC2
├── pom.xml
└── README.md
```

---

## 🛠️ TECNOLOGÍAS

- **Java 17** - Lenguaje
- **ZeroMQ (JeroMQ 0.5.4)** - Patrón REQ/REP
- **Jackson 2.15.2** - Serialización JSON
- **PostgreSQL JDBC 42.7.3** - Acceso a BD
- **Maven** - Gestión de dependencias

---

## 📝 LOGS POR PANTALLA

```
╔════════════════════════════════════════════╗
║  PC3: Servicio de Monitoreo y Consulta    ║
║  Gestión Inteligente de Tráfico Urbano    ║
║  2026-10                                   ║
╚════════════════════════════════════════════╝

[LANZADOR] Cargando configuración...
[LANZADOR] Configuración cargada:
  - Puerto Monitoreo: 7000
  - Host Analítica: 10.43.99.26
  - Puerto Analítica (Prioridad): 6001
  - BD: trafico_db en 10.43.101.27:5432

[LANZADOR] Inicializando servicios...
[MONITOREO] Iniciando ServicioMonitoreo...
[MONITOREO] Socket REQ/REP enlazado en: tcp://*:7000
[LANZADOR] ✓ Servicio iniciado
[LANZADOR] Presione Ctrl+C para detener...

--- OPERACIONES EN TIEMPO REAL ---

[MONITOREO] Solicitud recibida: ESTADO_ACTUAL | INT-A1
[MONITOREO] Consultando BD...
[MONITOREO] Respuesta generada (5ms): 245 bytes
[MONITOREO] Respuesta enviada

[MONITOREO] Solicitud recibida: HISTORIAL_RANGO | INT-B3 | 08:00-10:00
[MONITOREO] Consultando BD (120 registros)...
[MONITOREO] Estadísticas calculadas
[MONITOREO] Respuesta generada (12ms): 3456 bytes
[MONITOREO] Respuesta enviada

[MONITOREO] Solicitud recibida: ENVIAR_PRIORIDAD | AMBULANCIA
[MONITOREO] Validando intersecciones: INT-A1, INT-A2, INT-B1
[MONITOREO] Generando comando ID: CMD-20260406-0001
[MONITOREO] Enviando PUSH a ServicioAnalitica (192.168.1.2:6001)...
[MONITOREO] ✓ Comando enviado exitosamente
[MONITOREO] Registrando en BD: historial_comandos_prioridad
[MONITOREO] Respuesta generada (78ms): 512 bytes
[MONITOREO] Respuesta enviada

[MONITOREO] Solicitud recibida: ESTADO_GLOBAL
[MONITOREO] Consultando todas las intersecciones...
[MONITOREO] Analizando alertas...
[MONITOREO] Respuesta generada (45ms): 2890 bytes
[MONITOREO] Respuesta enviada

--- APAGADO ---

[LANZADOR] Señal de apagado recibida (Ctrl+C)...
[MONITOREO] Detener solicitado
[MONITOREO] Socket cerrado
[LANZADOR] ✓ Servicio detenido correctamente
[LANZADOR] PC3 finalizado.
```

---

## ✅ CHECKLIST DE IMPLEMENTACIÓN

- [ ] **LanzadorPC3.java** - Punto de entrada, carga config, inicia servicio
- [ ] **ServicioMonitoreo.java** - Socket REQ/REP en puerto 7000, loop de recepción
- [ ] **ControladorConsultas.java** - Procesamiento de 5 tipos de solicitudes
- [ ] **GestorBaseDatosConsultas.java** - Consultas SELECT optimizadas
- [ ] **ConsultorServicioMonitoreo.java** - Cliente CLI con menú interactivo
- [ ] **config.json** - Incluye puerto_reqrep y datos de conexión a Analítica
- [ ] **Tabla historial_comandos_prioridad** - Creada en BD PostgreSQL
- [ ] **Tests manuales** - Cada tipo de solicitud funciona correctamente
- [ ] **Validaciones** - Todas las entradas están validadas
- [ ] **Manejo de errores** - Excepciones capturadas y reportadas en JSON
- [ ] **Documentación** - README con ejemplos de uso

---

## 🧪 EJEMPLO DE EJECUCIÓN COMPLETA

### Cliente empieza:
```bash
$ java -cp ".:target/classes:target/lib/*" com.trafico.clientes.ConsultorServicioMonitoreo

[CONSULTOR] Conectando a ServicioMonitoreo (192.168.1.3:7000)...
[CONSULTOR] ✓ Conectado

Menú mostrado...
Seleccione opción: 1

Ingrese intersección: INT-A1

[CONSULTOR] Enviando ESTADO_ACTUAL | INT-A1...
[CONSULTOR] Respuesta recibida en 42ms

╔════════════════════════════════════════════╗
║ ESTADO ACTUAL - INT-A1                     ║
╠════════════════════════════════════════════╣
║ Semáforo: VERDE                            ║
║ Duración: 15s                              ║
║ Densidad: 35%                              ║
║ Velocidad: 18.5 km/h                       ║
║ Cola: 8 vehículos                          ║
║ Timestamp: 2026-04-06 14:30:45             ║
╚════════════════════════════════════════════╝

Presione Enter para continuar...
```

### Servidor muestra:
```
[MONITOREO] Solicitud recibida: ESTADO_ACTUAL | INT-A1
[MONITOREO] Consultando BD...
[MONITOREO] Respuesta generada (5ms): 245 bytes
[MONITOREO] Respuesta enviada
```

---

## 🔒 VALIDACIONES Y SEGURIDAD

- ✅ Validar JSON bien formado (try-catch ObjectMapper)
- ✅ Validar tipo_solicitud válido (enum de 5 tipos)
- ✅ Validar intersecciones existen (verificar en config.sensores)
- ✅ Validar fechas en formato ISO-8601
- ✅ Validar fecha_inicio <= fecha_fin
- ✅ Validar duración entre 10-60 segundos
- ✅ Validar tipo_evento está en lista permitida
- ✅ Limitar registros retornados (máximo 1000 por consulta)
- ✅ Implementar timeout en conexión BD (5 segundos)
- ✅ Loguear todas las solicitudes con timestamp

---

## 🚀 PRÓXIMOS PASOS

1. Copiar estructura base de PC2 a PC3 (config, modelos, dependencias)
2. Implementar ServicioMonitoreo.java con REQ/REP socket
3. Crear ControladorConsultas.java (5 métodos de procesamiento)
4. Implementar GestorBaseDatosConsultas.java (consultas SELECT)
5. Crear ConsultorServicioMonitoreo.java (cliente interactivo)
6. Crear tabla historial_comandos_prioridad en BD
7. Compilar y pruebas unitarias
8. Pruebas de integración con PC1 y PC2
9. Documentar API completa

---

## 📚 NOTAS IMPORTANTES

- **Reutilizar**: ConfiguracionSistema.java, Topicos.java, modelos, config.json de PC2
- **Diferencia con PC2**: PC3 NO es hilo (es una aplicación servidor)
- **Conexión Analítica**: Socket PUSH en ControladorConsultas solo para ENVIAR_PRIORIDAD
- **Historial**: Las solicitudes HISTORIAL_RANGO deben limitar máximo 1000 registros
- **Alertas**: Calcularlas sobre-la-marcha en procesarEstadoGlobal()
- **Timeout**: Implementar timeout de 5 segundos en consultas BD

---

**Autor**: Estudiante de Sistemas Distribuidos  
**Fecha**: 2026-04-06  
**Proyecto**: Gestión Inteligente de Tráfico Urbano (2026-10)  
**Versión**: 1.0

