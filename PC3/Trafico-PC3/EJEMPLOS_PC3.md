# PC3: GUÍA PRÁCTICA DE PRUEBAS Y EJEMPLOS

## 📋 Índice
1. [Ejemplos de Solicitudes/Respuestas](#ejemplos)
2. [Pruebas de Cliente](#pruebas-cliente)
3. [Debugging](#debugging)
4. [Casos de Error](#casos-error)

---

## Ejemplos de Solicitudes/Respuestas

### Test 1: Solicitud JSON válida - ESTADO_ACTUAL

**Envío (Cliente)**:
```json
{
  "tipo_solicitud": "ESTADO_ACTUAL",
  "interseccion": "INT-A1"
}
```

**Respuesta (Servidor)**:
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

---

### Test 2: Histórico de 2 horas - HISTORIAL_RANGO

**Envío**:
```json
{
  "tipo_solicitud": "HISTORIAL_RANGO",
  "interseccion": "INT-B3",
  "fecha_inicio": "2026-04-06T08:00:00Z",
  "fecha_fin": "2026-04-06T10:00:00Z"
}
```

**Respuesta (parcial)**:
```json
{
  "estado": "EXITO",
  "codigo": 200,
  "datos": {
    "interseccion": "INT-B3",
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
        "densidad": 30,
        "velocidad_promedio": 22.5,
        "cola": 5
      },
      {
        "timestamp": "2026-04-06T08:05:23Z",
        "estado": "CONGESTION",
        "densidad": 68,
        "velocidad_promedio": 9.8,
        "cola": 28
      },
      {
        "timestamp": "2026-04-06T08:10:42Z",
        "estado": "CONGESTION",
        "densidad": 75,
        "velocidad_promedio": 7.2,
        "cola": 35
      }
    ],
    "estadisticas": {
      "registros_totales": 120,
      "tiempo_normal": "68 min",
      "tiempo_congestion": "52 min",
      "densidad_promedio": 51.3,
      "velocidad_promedio": 14.8,
      "cola_maxima": 35,
      "pico_congestion": "08:32-08:48"
    }
  },
  "mensaje": "Histórico recuperado exitosamente"
}
```

---

### Test 3: Comando de Prioridad - ENVIAR_PRIORIDAD

**Envío**:
```json
{
  "tipo_solicitud": "ENVIAR_PRIORIDAD",
  "intersecciones": ["INT-A1", "INT-A2", "INT-B1"],
  "tipo_evento": "AMBULANCIA",
  "duracion_segundos": 40,
  "razon": "Emergencia médica - Hospital Central"
}
```

**Respuesta**:
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

**Flujo interno**:
1. Validar intersecciones existen
2. Validar duración (10-60)
3. Validar tipo_evento
4. Generar comando_id único
5. Crear socket PUSH a `tcp://192.168.1.2:6001`
6. Enviar comando JSON a ServicioAnalitica
7. Registrar en tabla `historial_comandos_prioridad`
8. Responder al cliente

---

### Test 4: Estado Global - ESTADO_GLOBAL

**Envío**:
```json
{
  "tipo_solicitud": "ESTADO_GLOBAL"
}
```

**Respuesta**:
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
    "intersecciones_criticas": [
      "INT-A1",
      "INT-B3",
      "INT-C5",
      "INT-D2",
      "INT-E4"
    ],
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

---

## Casos de Error

### Error 1: Intersección no existe - ESTADO_ACTUAL

**Envío**:
```json
{
  "tipo_solicitud": "ESTADO_ACTUAL",
  "interseccion": "INT-Z99"
}
```

**Respuesta**:
```json
{
  "estado": "ERROR",
  "codigo": 404,
  "error": "INTERSECCION_NO_ENCONTRADA",
  "mensaje": "La intersección INT-Z99 no existe en el sistema",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

---

### Error 2: Fechas inválidas - HISTORIAL_RANGO

**Envío**:
```json
{
  "tipo_solicitud": "HISTORIAL_RANGO",
  "interseccion": "INT-A1",
  "fecha_inicio": "2026-04-06T12:00:00Z",
  "fecha_fin": "2026-04-06T08:00:00Z"
}
```

**Respuesta**:
```json
{
  "estado": "ERROR",
  "codigo": 400,
  "error": "FECHAS_INVALIDAS",
  "mensaje": "La fecha de inicio (12:00) debe ser menor o igual a la fecha de fin (08:00)",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

---

### Error 3: Duración fuera de rango - ENVIAR_PRIORIDAD

**Envío**:
```json
{
  "tipo_solicitud": "ENVIAR_PRIORIDAD",
  "intersecciones": ["INT-A1"],
  "tipo_evento": "AMBULANCIA",
  "duracion_segundos": 100,
  "razon": "Test"
}
```

**Respuesta**:
```json
{
  "estado": "ERROR",
  "codigo": 400,
  "error": "DURACION_INVALIDA",
  "mensaje": "La duración debe estar entre 10 y 60 segundos (recibido: 100)",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

---

### Error 4: Tipo de evento inválido

**Envío**:
```json
{
  "tipo_solicitud": "ENVIAR_PRIORIDAD",
  "intersecciones": ["INT-A1"],
  "tipo_evento": "DRAGON",
  "duracion_segundos": 40,
  "razon": "Test"
}
```

**Respuesta**:
```json
{
  "estado": "ERROR",
  "codigo": 400,
  "error": "TIPO_EVENTO_INVALIDO",
  "mensaje": "Tipo de evento 'DRAGON' no válido. Válidos: AMBULANCIA, BOMBEROS, POLICIA, EVENTO_ESPECIAL",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

---

### Error 5: JSON mal formado

**Envío**:
```
{tipo_solicitud: "ESTADO_ACTUAL"
```

**Respuesta**:
```json
{
  "estado": "ERROR",
  "codigo": 400,
  "error": "JSON_INVALIDO",
  "mensaje": "Error al parsear JSON: Unexpected character ('\"' (code 34)) in value true",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

---

### Error 6: Tipo de solicitud desconocido

**Envío**:
```json
{
  "tipo_solicitud": "MAGIA_NEGRA"
}
```

**Respuesta**:
```json
{
  "estado": "ERROR",
  "codigo": 400,
  "error": "SOLICITUD_DESCONOCIDA",
  "mensaje": "Tipo de solicitud 'MAGIA_NEGRA' no soportado. Válidos: ESTADO_ACTUAL, HISTORIAL_RANGO, ENVIAR_PRIORIDAD, ESTADO_GLOBAL, GENERAR_REPORTE",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

---

### Error 7: BD no disponible

**Envío** (cuando BD está down):
```json
{
  "tipo_solicitud": "ESTADO_ACTUAL",
  "interseccion": "INT-A1"
}
```

**Respuesta**:
```json
{
  "estado": "ERROR",
  "codigo": 500,
  "error": "BD_NO_DISPONIBLE",
  "mensaje": "No se pudo conectar a la base de datos PostgreSQL (10.43.101.27:5432). Timeout tras 5 segundos.",
  "timestamp": "2026-04-06T14:30:45.123Z"
}
```

---

## Pruebas de Cliente Interactivo

### Escenario 1: Consultar estado y luego histórico

```bash
$ java -cp "target/classes:target/lib/*" com.trafico.clientes.ConsultorServicioMonitoreo

[CONSULTOR] Conectando a 192.168.1.3:7000...
[CONSULTOR] ✓ Conectado

╔═════════════════════════════════════════════════╗
║  CONSULTOR DE TRÁFICO                           ║
║  Gestión Inteligente de Tráfico Urbano         ║
╚═════════════════════════════════════════════════╝

1. Consultar estado actual
2. Ver histórico
3. Enviar indicación de prioridad
4. Ver estado global
5. Generar reporte
6. Ver historial de consultas
7. Salir

Seleccione opción: 1

Ingrese intersección (ej: INT-A1): INT-B3

[CONSULTOR] Enviando ESTADO_ACTUAL | INT-B3...
[CONSULTOR] Respuesta recibida en 38ms

╔═══════════════════════════════════════════╗
║ ESTADO ACTUAL - INT-B3                    ║
╠═══════════════════════════════════════════╣
║ Semáforo:          VERDE                  ║
║ Duración Fase:     25 segundos            ║
║ Densidad:          68%                    ║
║ Velocidad:         9.8 km/h               ║
║ Cola:              28 vehículos           ║
║ Timestamp:         2026-04-06 14:30:45    ║
╚═══════════════════════════════════════════╝

Presione Enter para continuar...
```

### Escenario 2: Generar alerta de ambulancia

```
Seleccione opción: 3

Ingrese intersecciones (separadas por coma): INT-A1,INT-A2,INT-B1

[CONSULTOR] Intersecciones válidas: INT-A1, INT-A2, INT-B1

Ingrese tipo de evento:
  (1) AMBULANCIA
  (2) BOMBEROS
  (3) POLICIA
  (4) EVENTO_ESPECIAL

Seleccione tipo: 1

Ingrese duración (10-60 segundos): 40

Ingrese razón (opcional): Emergencia médica - Hospital Central

[CONSULTOR] Validando parámetros...
[CONSULTOR] ✓ Todos válidos
[CONSULTOR] Enviando ENVIAR_PRIORIDAD...
[CONSULTOR] Respuesta recibida en 125ms

╔═════════════════════════════════════════════╗
║ COMANDO DE PRIORIDAD ENVIADO                ║
╠═════════════════════════════════════════════╣
║ ID Comando:         CMD-20260406-0001       ║
║ Tipo de Evento:     AMBULANCIA              ║
║ Intersecciones:     INT-A1, INT-A2, INT-B1 ║
║ Duración:           40 segundos             ║
║ Razón:              Emergencia médica -     ║
║                     Hospital Central        ║
║ Estado:             ENVIADO_A_ANALITICA     ║
║ Timestamp:          2026-04-06 14:30:45     ║
║ Confirmación:       3/3 intersecciones OK   ║
╚═════════════════════════════════════════════╝

[CONSULTOR] ✓ Comando registrado en BD

Presione Enter para continuar...
```

### Escenario 3: Ver estado global en hora pico

```
Seleccione opción: 4

[CONSULTOR] Consultando estado global del sistema...
[CONSULTOR] Respuesta recibida en 156ms

╔═══════════════════════════════════════════════╗
║ ESTADO GLOBAL DEL SISTEMA                     ║
╠═══════════════════════════════════════════════╣
║ Timestamp:          2026-04-06 14:30:45       ║
║                                               ║
║ RESUMEN POR ESTADO:                           ║
║   ✓ NORMAL:         15 intersecciones (60%)   ║
║   ⚠  CONGESTION:     8 intersecciones (32%)   ║
║   🚨 PRIORIDAD:      2 intersecciones (8%)    ║
║                                               ║
║ ESTADÍSTICAS GLOBALES:                        ║
║   Densidad promedio: 42.3%                    ║
║   Velocidad promedio: 16.8 km/h              ║
║   Cola promedio: 12.5 vehículos              ║
║                                               ║
║ INTERSECCIONES CRÍTICAS:                      ║
║   • INT-A1  (72% densidad, 8.2 km/h)         ║
║   • INT-B3  (68% densidad, 9.8 km/h)         ║
║   • INT-C5  (65% densidad, 11.5 km/h)        ║
║   • INT-D2  (60% densidad, 12.3 km/h)        ║
║   • INT-E4  (58% densidad, 13.1 km/h)        ║
║                                               ║
║ ALERTAS:                                      ║
║   [ALTO] Congestión severa en zona A          ║
║     → 5 intersecciones afectadas              ║
║     → Recomendación: Activar desvío           ║
║                                               ║
║   [MEDIO] Velocidad baja en zona B            ║
║     → 2 intersecciones afectadas              ║
║     → Recomendación: Aumentar fases verdes    ║
╚═══════════════════════════════════════════════╝

Presione Enter para continuar...
```

---

## Debugging

### Habilitar logs detallados en ServicioMonitoreo

```java
// Agregar estas líneas al inicio de procesarSolicitud()
System.out.println("[MONITOREO_DEBUG] Solicitud JSON recibida: " + solicitudJson);
System.out.println("[MONITOREO_DEBUG] Tipo de solicitud: " + tipoSolicitud);
System.out.println("[MONITOREO_DEBUG] Parámetros: " + solicitud);
System.out.println("[MONITOREO_DEBUG] Inicio procesamiento...");
// ... procesamiento ...
System.out.println("[MONITOREO_DEBUG] Respuesta generada: " + respuesta);
```

### Verificar conectividad con ServicioAnalitica

```bash
# Probar que el puerto 6001 está accesible desde PC3
telnet 192.168.1.2 6001

# Alternativa con nc (netcat)
nc -zv 192.168.1.2 6001
```

### Verificar BD PostgreSQL

```bash
# Conectarse localmente
psql -h 10.43.101.27 -U postgres -d trafico_db

# Verificar tabla
SELECT COUNT(*) as registros FROM analisis_trafico;

# Ver últimos registros
SELECT * FROM analisis_trafico ORDER BY timestamp DESC LIMIT 5;

# Ver historial de comandos prioridad
SELECT * FROM historial_comandos_prioridad ORDER BY timestamp_creacion DESC LIMIT 5;
```

### Test de comunicación ZMQ

```bash
# Terminal 1: Escuchar PULL en puerto 6001
zmq_benchmark -t pull -e tcp://*:6001

# Terminal 2: Enviar PUSH desde otro equipo
zmq_send -m '{"tipo_evento":"AMBULANCIA"}' tcp://localhost:6001
```

---

## Performance

### Benchmarks esperados

| Operación | Tiempo esperado | Notas |
|-----------|-----------------|-------|
| ESTADO_ACTUAL | 5-10ms | Consulta simple, 1 registro |
| HISTORIAL_RANGO (2h) | 10-20ms | 120 registros, ordenamiento |
| HISTORIAL_RANGO (24h) | 50-100ms | 1440 registros (límite) |
| ENVIAR_PRIORIDAD | 20-50ms | Incluye PUSH a Analítica |
| ESTADO_GLOBAL | 40-80ms | Agregación de 25 intersecciones |
| GENERAR_REPORTE | 100-200ms | Análisis completo del día |

---

**Versión**: 1.0  
**Última actualización**: 2026-04-06

