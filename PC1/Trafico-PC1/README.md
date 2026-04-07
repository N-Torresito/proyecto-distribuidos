# PC1 — Gestión Inteligente de Tráfico Urbano

**Pontificia Universidad Javeriana — Introducción a Sistemas Distribuidos 2026-10**

## Estructura del proyecto

```
Trafico-PC1/
├── src/main/
│   ├── java/com/trafico/
│   │   ├── LanzadorPC1.java                 // Modo integrado (Broker + Sensores).
│   │   ├── LanzadorBroker.java              // 🆕 Modo independiente: Solo Broker.
│   │   ├── LanzadorSensores.java            // 🆕 Modo independiente: Solo Sensores.
│   │   │
│   │   ├── broker/
│   │   │   ├── ZeroMQ.java                 // Broker single-thread (diseño base).
│   │   │   └── ZMQMultihilo.java           // Broker multihilo (pruebas de rendimiento).
│   │   │
│   │   ├── config/
│   │   │   ├── ConfiguracionSistema.java   // Carga y expone config.json (Singleton).
│   │   │   └── Topicos.java                // Constantes de tópicos ZMQ (PUB/SUB).
│   │   │
│   │   ├── modelos/
│   │   │   ├── EventoCamara.java           // Modelo EVENTO_LONGITUD_COLA (Lq).
│   │   │   ├── EventoEspira.java           // Modelo EVENTO_CONTEO_VEHICULAR (Cv).
│   │   │   └── EventoGPS.java              // Modelo EVENTO_DENSIDAD_TRAFICO (Dt).
│   │   │
│   │   └── sensores/
│   │       ├── SensorTrafico.java          // Interfaz base (extends Runnable).
│   │       ├── SensorCamara.java           // Publica EVENTO_LONGITUD_COLA (PUB).
│   │       ├── SensorEspira.java           // Publica EVENTO_CONTEO_VEHICULAR (PUB).
│   │       └── SensorGPS.java              // Publica EVENTO_DENSIDAD_TRAFICO (PUB).
│   │
│   └── resources/
│       └── config.json                     // Configuración central del sistema.
├── pom.xml
├── ejecutar.sh                              // 🆕 Script de ejecución fácil.
├── GUIA_EJECUCION.md                        // 🆕 Guía paso a paso.
├── ANALISIS_REQUERIMIENTOS.md               // 🆕 Análisis detallado.
└── README.md
```

## Dependencias

- Java 17
- Maven 4
- JeroMQ 0.5.4 (ZeroMQ para Java — sin librerías nativas)
- Jackson 2.15.2 (serialización/deserialización JSON)
- PostgreSQL 42.7.3 (base de datos)

## Compilar

```bash
cd Trafico-PC1
mvn clean package
```

Genera: `target/Trafico-PC1-1.0-SNAPSHOT.jar`

## Configuración (`config.json`)

Antes de ejecutar en las 3 máquinas, edita `config.json` y ajusta las IPs reales:

| Campo | Descripción | Valor por defecto |
|-------|-------------|-------------------|
| `broker.puerto_sub` | Puerto donde el broker escucha a los sensores | 5555 |
| `broker.puerto_pub` | Puerto donde el broker publica hacia PC2 | 5556 |
| `broker.host_pc2` | IP de la máquina PC2 | 192.168.1.2 |
| `servicios.analitica.host` | IP del servicio de analítica (PC2) | 192.168.1.2 |
| `servicios.analitica.puerto_pull` | Puerto PULL del servicio de analítica | 6000 |
| `servicios.base_datos.host` | IP de la base de datos principal (PC3) | 192.168.1.3 |
| `servicios.base_datos.puerto` | Puerto de PostgreSQL | 5432 |
| `servicios.base_datos.nombre` | Nombre de la base de datos | trafico_db |
| `servicios.base_datos.usuario` | Usuario de PostgreSQL | trafico_user |
| `servicios.base_datos.password` | Contraseña de PostgreSQL | trafico_pass |
| `servicios.monitoreo.puerto_reqrep` | Puerto REQ/REP del servicio de monitoreo | 7000 |
| `semaforos.duracion_normal` | Segundos en verde — tráfico normal | 15 |
| `semaforos.duracion_congestion` | Segundos en verde — congestión | 25 |
| `semaforos.duracion_prioridad` | Segundos en verde — ola verde / ambulancia | 40 |

## Ejecución

### 🆕 Opción A: Modo Separado (RECOMENDADO - Arquitectura Distribuida Real)

Útil para simular arquitectura distribuida: broker y sensores en procesos independientes.

**Terminal 1 - Inicia el Broker:**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorBroker config.json
```

**Terminal 2 - Inicia los Sensores (después que el Broker esté listo):**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorSensores config.json
```

O usando el script:
```bash
# Terminal 1
./ejecutar.sh broker

# Terminal 2
./ejecutar.sh sensores
```

### Opción B: Modo Integrado (Todo en un proceso)

Lanza broker + 5 cámaras + 5 espiras + 5 sensores GPS en un único proceso.

```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorPC1 config.json
```

O usando el script:
```bash
./ejecutar.sh todo
```

### Opción C: Componentes Individuales (Avanzado)

**Solo el Broker single-thread (diseño base):**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.broker.ZeroMQ config.json
```

**Broker multihilo (pruebas de rendimiento):**
```bash
# Con 4 workers (por defecto)
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.broker.ZMQMultihilo config.json

# Con N workers específicos
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.broker.ZMQMultihilo config.json 8
```

**Sensores de cámara:**
```bash
# Lanza todos los sensores de cámara configurados
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.sensores.SensorCamara config.json

# Lanza solo un sensor específico por ID
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.sensores.SensorCamara config.json CAM-A1
```

**Sensores de espira:**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.sensores.SensorEspira config.json
```

**Sensores GPS:**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.sensores.SensorGPS config.json
```

## Cuadrícula de la ciudad (5×5)

```
     1       2       3       4       5
A  INT-A1  INT-A2  INT-A3  INT-A4  INT-A5
B  INT-B1  INT-B2  INT-B3  INT-B4  INT-B5
C  INT-C1  INT-C2  INT-C3  INT-C4  INT-C5
D  INT-D1  INT-D2  INT-D3  INT-D4  INT-D5
E  INT-E1  INT-E2  INT-E3  INT-E4  INT-E5
```

**Distribución de sensores (5 de cada tipo, 15 en total):**

| Sensor  | Intersección | Tipo   | Intervalo | Variable medida         |
|---------|-------------|--------|-----------|-------------------------|
| CAM-A1  | INT-A1      | Cámara | 5 seg     | Longitud de cola (Lq)   |
| CAM-B3  | INT-B3      | Cámara | 5 seg     | Longitud de cola (Lq)   |
| CAM-C5  | INT-C5      | Cámara | 5 seg     | Longitud de cola (Lq)   |
| CAM-D2  | INT-D2      | Cámara | 5 seg     | Longitud de cola (Lq)   |
| CAM-E4  | INT-E4      | Cámara | 5 seg     | Longitud de cola (Lq)   |
| ESP-A2  | INT-A2      | Espira | 30 seg    | Conteo vehicular (Cv)   |
| ESP-B4  | INT-B4      | Espira | 30 seg    | Conteo vehicular (Cv)   |
| ESP-C1  | INT-C1      | Espira | 30 seg    | Conteo vehicular (Cv)   |
| ESP-D3  | INT-D3      | Espira | 30 seg    | Conteo vehicular (Cv)   |
| ESP-E5  | INT-E5      | Espira | 30 seg    | Conteo vehicular (Cv)   |
| GPS-A3  | INT-A3      | GPS    | 8 seg     | Densidad/velocidad (Dt) |
| GPS-B1  | INT-B1      | GPS    | 8 seg     | Densidad/velocidad (Dt) |
| GPS-C4  | INT-C4      | GPS    | 8 seg     | Densidad/velocidad (Dt) |
| GPS-D5  | INT-D5      | GPS    | 8 seg     | Densidad/velocidad (Dt) |
| GPS-E2  | INT-E2      | GPS    | 8 seg     | Densidad/velocidad (Dt) |

## Patrón de comunicación ZMQ en PC1

```
[SensorCamara PUB] ──┐
[SensorEspira PUB] ──┤── tcp://localhost:5555 ──► [ZeroMQ SUB]
[SensorGPS    PUB] ──┘                                  │
                                                   tcp://*:5556
                                                        │
                                            [PC2 Analítica SUB] ◄──
```

## Reglas de congestión (definidas en config.json)

| Estado            | Condición                                         | Duración verde |
|-------------------|---------------------------------------------------|----------------|
| Tráfico normal    | cola < 10 AND velocidad > 15 AND densidad < 40   | 15 seg         |
| Congestión        | cola >= 10 OR velocidad <= 15 OR densidad >= 40  | 25 seg         |
| Prioridad (ola verde / ambulancia) | Indicación directa del servicio de monitoreo | 40 seg |

## Formato de mensajes ZMQ

Todos los mensajes siguen el formato: `TOPICO {payload_json}`

```
EVENTO_LONGITUD_COLA {"sensor_id":"CAM-C5","tipo_sensor":"camara","interseccion":"INT-C5","volumen":8,"velocidad_promedio":22.3,"timestamp":"2026-03-15T10:00:00Z"}

EVENTO_CONTEO_VEHICULAR {"sensor_id":"ESP-B4","tipo_sensor":"espira_inductiva","interseccion":"INT-B4","vehiculos_contados":15,"intervalo_segundos":30,"timestamp_inicio":"...","timestamp_fin":"..."}

EVENTO_DENSIDAD_TRAFICO {"sensor_id":"GPS-A3","tipo_sensor":"gps","interseccion":"INT-A3","nivel_congestion":"NORMAL","velocidad_promedio":28.7,"timestamp":"..."}
```