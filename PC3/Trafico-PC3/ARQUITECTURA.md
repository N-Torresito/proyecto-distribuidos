# ARQUITECTURA TÉCNICA - PC3

## 🏗️ Diagrama General del Sistema

```
┌─────────────────────────────────────────────────────────────────┐
│                    GESTIÓN DE TRÁFICO URBANO 2026-10            │
└─────────────────────────────────────────────────────────────────┘

┌────────────────────────┐          ┌────────────────────────┐
│   CLIENTE CLI          │          │   CLIENTE CLI          │
│ ConsultorServicio      │          │ ConsultorServicio      │
│ Monitoreo             │          │ Monitoreo             │
│                        │          │                        │
│ Menu Interactivo      │          │ Menu Interactivo      │
│ • Estado Actual       │          │ • Estado Actual       │
│ • Histórico           │          │ • Histórico           │
│ • Prioridad           │          │ • Prioridad           │
│ • Global              │          │ • Global              │
│ • Reporte             │          │ • Reporte             │
└────────────┬───────────┘          └───────────┬────────────┘
             │                                  │
             │ REQ JSON (Solicitud)             │ REQ JSON (Solicitud)
             │ tcp://192.168.1.3:7000          │ tcp://192.168.1.3:7000
             │                                  │
             └──────────────────┬───────────────┘
                                │
                                ▼
                    ┌──────────────────────┐
                    │  SERVIDOR PC3        │
                    │ ServicioMonitoreo    │
                    ├──────────────────────┤
                    │ Socket REQ/REP       │
                    │ Puerto: 7000         │
                    │ tcp://*:7000         │
                    └──────────┬───────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
                    ▼                     ▼
         ┌────────────────────┐  ┌──────────────────┐
         │ Controlador        │  │ Gestor BD        │
         │ Consultas          │  │ (PostgreSQL)     │
         ├────────────────────┤  ├──────────────────┤
         │ • Procesar JSON    │  │ • Consultas SQL  │
         │ • Validar entrada  │  │ • Índices        │
         │ • Generar alertas  │  │ • Transacciones  │
         │ • Respuestas       │  │                  │
         │                    │  │ Tablas:          │
         │ Tipos de solicitud:│  │ • analisis_      │
         │ 1. ESTADO_ACTUAL   │  │   trafico        │
         │ 2. HISTORIAL_RANGO│  │ • historial_     │
         │ 3. ENVIAR_PRIORIDAD│ │   comandos_      │
         │ 4. ESTADO_GLOBAL   │  │   prioridad      │
         │ 5. GENERAR_REPORTE│  │                  │
         └────────┬───────────┘  └────────┬─────────┘
                  │                       │
                  │          ┌────────────┘
                  │          │
                  │          ▼
                  │    ┌──────────────┐
                  │    │ PostgreSQL   │
                  │    │ BD: trafico_db
                  │    │ Host: 10.43.101.27
                  │    │ Puerto: 5432
                  │    └──────────────┘
                  │
                  │ PUSH JSON (Comando)
                  │ tcp://10.43.99.26:6001
                  │
                  ▼
         ┌─────────────────────┐
         │ ServicioAnalitica   │
         │ (PC2)               │
         ├─────────────────────┤
         │ • Recibe comando    │
         │ • Procesa prioridad │
         │ • Envía a Semáforos │
         │                     │
         │ Host: 10.43.99.26   │
         │ Puerto PUSH: 6001   │
         └─────────────────────┘
                  │
                  ▼
         ┌─────────────────────┐
         │ Semáforos           │
         │ (Cambio de fase)    │
         └─────────────────────┘
```

---

## 📊 Flujo de Solicitudes

### 1. ESTADO_ACTUAL
```
Cliente                Servidor                BD
   │                     │                      │
   ├─ JSON SOLICITUD ───→│                      │
   │                     ├─ SELECT * ──────────→│
   │                     │                      ├─ Query
   │                     │←─ ResultSet ────────┤
   │                     ├─ Mapear JSON       │
   │←─ JSON RESPUESTA ───│                      │
```

### 2. ENVIAR_PRIORIDAD
```
Cliente                Servidor                BD              Analítica
   │                     │                      │                   │
   ├─ JSON SOLICITUD ───→│                      │                   │
   │                     ├─ VALIDAR            │                   │
   │                     ├─ PUSH COMANDO ──────────────────────────→│
   │                     ├─ INSERT ────────────→│                   │
   │                     │                      ├─ INSERT          │
   │←─ JSON RESPUESTA ───│                      │←─ OK             │
```

### 3. HISTORIAL_RANGO
```
Cliente                Servidor                BD
   │                     │                      │
   ├─ JSON SOLICITUD ───→│                      │
   │                     ├─ SELECT * (rango)──→│
   │                     │                      ├─ Query (1000 máx)
   │                     │←─ List<ResultSet>──┤
   │                     ├─ Calcular Stats    │
   │                     ├─ Mapear JSON       │
   │←─ JSON RESPUESTA ───│                      │
```

---

## 🔄 Ciclo de Vida de una Solicitud

```
┌─────────────────────────────────────────────────┐
│ 1. CLIENTE PREPARA JSON                         │
│    {                                            │
│      "tipo_solicitud": "ESTADO_ACTUAL",        │
│      "interseccion": "INT-A1"                   │
│    }                                            │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 2. CLIENTE ENVÍA (REQ)                          │
│    socket.send(json)                            │
│    Espera respuesta (bloqueante)                │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 3. SERVIDOR RECIBE                              │
│    json = socket.recv()                         │
│    Log: [MONITOREO] Solicitud recibida          │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 4. CONTROLADOR PROCESA                          │
│    - Parsear JSON                               │
│    - Validar tipo_solicitud                     │
│    - Validar parámetros                         │
│    - Delegar al método específico               │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 5. CONSULTAR BD                                 │
│    - Conectar con timeout                       │
│    - Ejecutar SQL                               │
│    - Mapear resultados                          │
│    - Desconectar                                │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 6. GENERAR RESPUESTA                            │
│    - Crear estructura JSON                      │
│    - Agregar timestamps                         │
│    - Serializar con ObjectMapper                │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 7. SERVIDOR ENVÍA (REP)                         │
│    socket.send(json)                            │
│    Log: [MONITOREO] Respuesta enviada           │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 8. CLIENTE RECIBE                               │
│    respuesta = socket.recv()                    │
│    Log: [CONSULTOR] Respuesta en XXms           │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 9. CLIENTE PRESENTA                             │
│    - Parsear JSON                               │
│    - Validar estado (EXITO/ERROR)               │
│    - Formatear datos                            │
│    - Mostrar tabla ASCII                        │
└─────────────┬───────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────┐
│ 10. USUARIO VE RESULTADO                        │
│     ╔══════════════════╗                        │
│     ║ ESTADO ACTUAL    ║                        │
│     ╠══════════════════╣                        │
│     ║ Semáforo: VERDE ║                        │
│     ║ Densidad: 35%   ║                        │
│     ╚══════════════════╝                        │
└─────────────────────────────────────────────────┘
```

---

## 🔌 Puertos y Protocolos

```
TCP Layer
┌────────────────────────────────────────────────┐
│ REQ/REP (Solicitud-Respuesta)                  │
├────────────────────────────────────────────────┤
│ Cliente          ←→   Servidor                 │
│ 192.168.1.X     tcp://192.168.1.3:7000        │
│ (REQ socket)         (REP socket)              │
│                                                │
│ JSON → JSON                                     │
│ Sincrónico (bloqueante)                        │
│ Timeout: 5000ms (cliente)                      │
└────────────────────────────────────────────────┘

┌────────────────────────────────────────────────┐
│ PUSH/PULL (Asincrónico)                        │
├────────────────────────────────────────────────┤
│ Servidor PC3      →   Servidor Analítica       │
│ (PUSH socket)         (PULL socket)            │
│ tcp://10.43.99.26:6001                         │
│                                                │
│ JSON (Comando Prioridad)                       │
│ Asincrónico (no bloqueante)                    │
└────────────────────────────────────────────────┘

┌────────────────────────────────────────────────┐
│ PostgreSQL (TCP)                               │
├────────────────────────────────────────────────┤
│ Servidor           →   BD PostgreSQL           │
│ (JDBC Driver)          (trafico_db)            │
│ tcp://10.43.101.27:5432                        │
│                                                │
│ SQL Queries/Results                            │
│ Timeout: 5 segundos                            │
└────────────────────────────────────────────────┘
```

---

## 📦 Estructura de Clases

```
com.trafico
├── LanzadorPC3
│   ├── main()
│   ├── mostrarBanner()
│   └── mostrarConfiguracion()
│
├── servicios
│   ├── ServicioMonitoreo (implements Runnable)
│   │   ├── run()
│   │   ├── iniciarServicio()
│   │   ├── loopAceptacion()
│   │   ├── extraerTipoSolicitud()
│   │   └── detener()
│   │
│   └── ControladorConsultas
│       ├── procesarSolicitud()
│       ├── procesarEstadoActual()
│       ├── procesarHistorialRango()
│       ├── procesarEnviarPrioridad()
│       ├── procesarEstadoGlobal()
│       ├── procesarGenerarReporte()
│       ├── validarInterseccion()
│       ├── validarFechas()
│       ├── esValidoTipoEvento()
│       ├── enviarComandoPrioridad()
│       ├── generarAlertas()
│       ├── generarExitoResponse()
│       ├── generarErrorResponse()
│       └── cerrar()
│
├── bd
│   └── GestorBaseDatosConsultas
│       ├── obtenerConexion()
│       ├── obtenerEstadoActual()
│       ├── obtenerHistorial()
│       ├── obtenerTodasLasIntersecciones()
│       ├── obtenerInterseccionesCriticas()
│       ├── calcularEstadisticas()
│       ├── encontrarPicoCongestion()
│       ├── mapearRegistro()
│       ├── registrarComandoPrioridad()
│       └── obtenerHorasPico()
│
├── clientes
│   └── ConsultorServicioMonitoreo
│       ├── conectar()
│       ├── mostrarMenu()
│       ├── mostrarOpciones()
│       ├── consultarEstadoActual()
│       ├── consultarHistorial()
│       ├── enviarPrioridad()
│       ├── consultarEstadoGlobal()
│       ├── generarReporte()
│       ├── mostrarHistorial()
│       ├── enviarSolicitud()
│       ├── presentarRespuesta()
│       ├── presentarExito()
│       ├── presentarError()
│       ├── presentarEstadoActual()
│       ├── presentarHistorial()
│       ├── presentarPrioridad()
│       ├── presentarEstadoGlobal()
│       ├── presentarReporte()
│       ├── formatearTimestamp()
│       ├── padRight()
│       ├── cerrar()
│       └── main()
│
└── config
    ├── ConfiguracionSistema
    │   ├── cargar()
    │   ├── cargarDesdeRecursos()
    │   └── getInstancia()
    │
    └── Topicos
        ├── CAMARA
        ├── ESPIRA
        ├── GPS
        └── SEPARADOR
```

---

## 🗄️ Esquema de Base de Datos

```
┌─────────────────────────────────────────┐
│ analisis_trafico                        │
├─────────────────────────────────────────┤
│ interseccion VARCHAR(50) [IX]           │
│ estado VARCHAR(20) [IX]                 │
│ timestamp VARCHAR(50) [IX]              │
│ velocidad_promedio DOUBLE               │
│ densidad INTEGER                        │
│ cola INTEGER                            │
│ fecha_actualizacion TIMESTAMP           │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ historial_comandos_prioridad [NEW]      │
├─────────────────────────────────────────┤
│ id SERIAL [PK]                          │
│ comando_id VARCHAR(50) [UQ, IX]         │
│ intersecciones TEXT                     │
│ tipo_evento VARCHAR(50)                 │
│ duracion_segundos INTEGER               │
│ razon TEXT                              │
│ timestamp_creacion TIMESTAMP [IX]       │
│ timestamp_ejecucion TIMESTAMP           │
│ estado VARCHAR(20)                      │
└─────────────────────────────────────────┘

Índices:
✓ idx_analisis_trafico_interseccion
✓ idx_analisis_trafico_timestamp
✓ idx_analisis_trafico_estado
✓ idx_cmd_prioridad_id
✓ idx_cmd_prioridad_timestamp
```

---

## ⚙️ Configuración (config.json)

```json
{
  "ciudad": {
    "filas": ["A","B","C","D","E"],
    "columnas": [1,2,3,4,5],
    "total_intersecciones": 25
  },
  "servicios": {
    "monitoreo": {
      "puerto_reqrep": 7000  ← PC3
    },
    "analitica": {
      "host": "10.43.99.26",
      "puerto_push_semaforoctl": 6001  ← Hacia PC2
    },
    "base_datos": {
      "host": "10.43.101.27",
      "puerto": 5432,
      "nombre": "trafico_db",
      "usuario": "postgres"
    }
  },
  "sensores": {
    "camaras": [
      {"sensor_id":"CAM-A1","interseccion":"INT-A1"},
      {"sensor_id":"CAM-B3","interseccion":"INT-B3"},
      ...
    ]
  }
}
```

---

## 🔐 Validaciones por Solicitud

### ESTADO_ACTUAL
```
✓ interseccion no vacía
✓ interseccion existe en config
✓ BD retorna datos
```

### HISTORIAL_RANGO
```
✓ interseccion existe
✓ fecha_inicio formato ISO-8601
✓ fecha_fin formato ISO-8601
✓ fecha_inicio <= fecha_fin
✓ Máximo 1000 registros
```

### ENVIAR_PRIORIDAD
```
✓ intersecciones no vacía
✓ máximo 5 intersecciones
✓ todas las intersecciones existen
✓ tipo_evento en [AMBULANCIA, BOMBEROS, POLICIA, EVENTO_ESPECIAL]
✓ duracion entre 10-60 segundos
```

### ESTADO_GLOBAL
```
✓ Sin parámetros (siempre válido)
```

### GENERAR_REPORTE
```
✓ tipo en [DIARIO, SEMANAL]
✓ fecha formato YYYY-MM-DD
```

---

## 📈 Métricas y Performance

```
Tamaño Máximo de Respuesta:
┌─────────────────────────┬────────────┐
│ Tipo                    │ Bytes      │
├─────────────────────────┼────────────┤
│ ESTADO_ACTUAL           │ 300-500    │
│ HISTORIAL_RANGO (2h)    │ 20-50KB    │
│ HISTORIAL_RANGO (24h)   │ 200-500KB  │
│ ENVIAR_PRIORIDAD        │ 500-1KB    │
│ ESTADO_GLOBAL           │ 2-5KB      │
│ GENERAR_REPORTE         │ 5-20KB     │
└─────────────────────────┴────────────┘

Latencia Esperada:
┌─────────────────────────┬────────────┐
│ Operación               │ Latencia   │
├─────────────────────────┼────────────┤
│ ESTADO_ACTUAL           │ 5-10ms     │
│ HISTORIAL_RANGO (2h)    │ 10-20ms    │
│ HISTORIAL_RANGO (24h)   │ 50-100ms   │
│ ENVIAR_PRIORIDAD        │ 20-50ms    │
│ ESTADO_GLOBAL           │ 40-80ms    │
│ GENERAR_REPORTE         │ 100-200ms  │
└─────────────────────────┴────────────┘
```

---

## 🔄 Estados de Tráfico

```
NORMAL
├─ Densidad: 0-40%
├─ Velocidad: > 15 km/h
├─ Cola: < 10 vehículos
└─ Color: Verde ✓

CONGESTION
├─ Densidad: > 40%
├─ Velocidad: < 15 km/h
├─ Cola: > 10 vehículos
└─ Color: Rojo ⚠️

PRIORIDAD
├─ Evento: AMBULANCIA, BOMBEROS, etc
├─ Duración: 10-60 segundos
├─ Acción: Ola verde
└─ Color: Azul 🚨
```

---

**Versión**: 1.0  
**Fecha**: 2026-04-06  
**Proyecto**: Gestión Inteligente de Tráfico Urbano

