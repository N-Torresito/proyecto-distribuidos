# 🚀 Guía de Ejecución - PC2: Gestión Inteligente de Tráfico Urbano

## ✅ Verificación de Compilación

La compilación fue **EXITOSA** ✓

```
[INFO] Building Trafico-PC2 1.0-SNAPSHOT
[INFO] Compiling 10 source files with javac [debug target 17] to target/classes
[INFO] BUILD SUCCESS
```

**Archivos compilados:**
```
✓ com/trafico/config/ConfiguracionSistema.java
✓ com/trafico/config/Topicos.java
✓ com/trafico/LanzadorPC2.java
✓ com/trafico/modelos/EventoCamara.java
✓ com/trafico/modelos/EventoEspira.java
✓ com/trafico/modelos/EventoGPS.java
✓ com/trafico/servicios/GestorBaseDatosReplica.java
✓ com/trafico/servicios/ServicioAnalitica.java
✓ com/trafico/servicios/ServicioControlSemaForos.java
✓ com/trafico/utils/ReglasCongestion.java
```

## 🎯 Estructura del Proyecto

```
Trafico-PC2/
├── pom.xml                                       (Configuración Maven)
├── README.md                                     (Documentación completa)
├── INSTRUCCIONES.md                             (Este archivo)
├── src/main/
│   ├── java/com/trafico/
│   │   ├── LanzadorPC2.java                    (Punto de entrada)
│   │   ├── config/
│   │   │   ├── ConfiguracionSistema.java       (Configuración del sistema)
│   │   │   └── Topicos.java                    (Constantes ZMQ)
│   │   ├── modelos/
│   │   │   ├── EventoCamara.java               (Modelo de cámara)
│   │   │   ├── EventoEspira.java               (Modelo de espira)
│   │   │   └── EventoGPS.java                  (Modelo de GPS)
│   │   ├── servicios/
│   │   │   ├── ServicioAnalitica.java          (Servicio de analítica)
│   │   │   ├── ServicioControlSemaForos.java   (Control de semáforos)
│   │   │   └── GestorBaseDatosReplica.java     (Gestor de BD)
│   │   └── utils/
│   │       └── ReglasCongestion.java           (Lógica de congestión)
│   └── resources/
│       └── config.json                          (Configuración de runtime)
└── target/
    └── Trafico-PC2-1.0-SNAPSHOT.jar            (JAR ejecutable)
```

## 🔧 Requisitos Previos

### Software necesario:
- **Java 17+** (verificar: `java -version`)
- **Maven 3.8+** (verificar: `mvn -v`)
- **PostgreSQL 12+** (opcional, para persistencia de datos)
- **ZeroMQ** (libzmq3, no es necesario instalar separadamente gracias a JeroMQ)

### Verificar Java:
```bash
$ java -version
openjdk version "17.0.x" 2021-09-14
OpenJDK Runtime Environment (build 17.0.x+xx)
```

## 📦 Compilar el Proyecto

### Opción 1: Compilación completa (limpiar + compilar + empaquetar)
```bash
cd Trafico-PC2
mvn clean compile package
```

### Opción 2: Solo compilar
```bash
mvn clean compile
```

### Opción 3: Compilar sin descargar dependencias nuevamente
```bash
mvn compile
```

## ▶️ Ejecutar PC2

### Opción 1: Ejecutar con Maven (recomendado para desarrollo)
```bash
mvn exec:java -Dexec.mainClass="com.trafico.LanzadorPC2"
```

### Opción 2: Ejecutar el JAR directamente
```bash
java -jar target/Trafico-PC2-1.0-SNAPSHOT.jar
```

### Opción 3: Ejecutar con classpath completo
```bash
java -cp "target/classes:target/dependency/*" com.trafico.LanzadorPC2
```

## 📋 Salida Esperada

Cuando ejecutes PC2, deberías ver:

```
╔════════════════════════════════════════════╗
║  PC2: Servicio de Analítica de Tráfico    ║
║  Gestión Inteligente de Tráfico Urbano    ║
║  2026-10                                   ║
╚════════════════════════════════════════════╝

[CONFIG] Configuración cargada desde recursos internos.
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
```

## 🛑 Detener la Aplicación

Presiona `Ctrl+C` en la terminal. Verás:

```
[LANZADOR] Señal de apagado recibida (Ctrl+C)...
[LANZADOR] Esperando a que los servicios finalicen...
[ANALITICA] Servicio finalizado.
[SEMAFOROCTL] Servicio finalizado.
[BD_REPLICA] Servicio finalizado.
[LANZADOR] ✓ Servicios detenidos correctamente
[LANZADOR] PC2 finalizado.
```

## 🔌 Configuración de Red

Antes de ejecutar, asegúrate de que:

1. **PC1 está ejecutándose** en la máquina:
   - Broker PUB en puerto 5555
   - Broker SUB en puerto 5556 (donde PC2 se conecta)

2. **PC2 espera en**:
   - `tcp://192.168.1.2:5556` para recibir eventos (SUB)
   - `tcp://*:6000` para enviar datos a BD (PUSH)
   - `tcp://*:6000` para recibir datos el gestor de BD (PULL)

3. **PostgreSQL (opcional)**:
   - Host: `localhost` o `192.168.1.3`
   - Puerto: `5432`
   - Base de datos: `trafico_db`
   - Usuario: `trafico_user`
   - Contraseña: `trafico_pass`

## 📊 Verificar que PC2 está funcionando

### Ver puertos abiertos:
```bash
netstat -tlnp | grep java
# Deberías ver:
# tcp  0  0 0.0.0.0:6000  0.0.0.0:*  LISTEN  <pid>/java
```

### Ver procesos Java:
```bash
ps aux | grep LanzadorPC2
```

### Ver logs en tiempo real:
```bash
# Si redireccionaste a archivo
tail -f pc2.log
```

## 🐛 Troubleshooting

### Error: "Cannot find symbol 'EventoCamara'"
**Causa**: Compilación incompleta
**Solución**:
```bash
mvn clean compile
```

### Error: "Address already in use: bind"
**Causa**: Puerto 6000 ya está en uso
**Solución**:
```bash
# Buscar qué está usando el puerto
lsof -i :6000

# Matar el proceso
kill -9 <PID>

# O cambiar el puerto en config.json
```

### Error: "Connection refused" (ZMQ)
**Causa**: PC1 no está corriendo
**Solución**:
```bash
# Asegúrate de que PC1 esté ejecutándose en 192.168.1.2:5556
ping 192.168.1.2
telnet 192.168.1.2 5556
```

### Error: "No se pudo conectar a BD"
**Causa**: PostgreSQL no disponible (normal en desarrollo)
**Solución**: Es opcional. El sistema continúa funcionando sin persistencia.

## 📈 Monitoreo de Recursos

### Ver CPU y memoria:
```bash
top -p $(pgrep -f LanzadorPC2)
```

### Ver tráfico de red (si tienes ZMQ tools):
```bash
zmq_monitor tcp://127.0.0.1:6000
```

## 📚 Documentación Relacionada

- **README.md**: Documentación completa del proyecto
- **pom.xml**: Dependencias y configuración Maven
- **src/main/resources/config.json**: Configuración de runtime

## 🔄 Ciclo de Desarrollo

```
1. Cambios en código
   ↓
2. mvn clean compile
   ↓
3. mvn package (opcional)
   ↓
4. mvn exec:java -Dexec.mainClass="com.trafico.LanzadorPC2"
   ↓
5. Validar salida en consola
   ↓
6. Presionar Ctrl+C para detener
```

## ⚙️ Variables de Entorno (opcional)

```bash
# Aumentar heap memory si necesario
export JAVA_OPTS="-Xmx512m -Xms256m"
mvn exec:java -Dexec.mainClass="com.trafico.LanzadorPC2"
```

## 🎓 Notas Académicas

Este es el **PC2** del proyecto de Sistemas Distribuidos:
- **PC1**: Sensores y Broker ZMQ ✓ (Completado en PC1)
- **PC2**: Analítica, Control de Semáforos y BD Réplica ✓ (Este proyecto)
- **PC3**: Base de Datos Réplica (servidor fallback)

Cada componente se ejecuta en un **hilo independiente** sin bloqueos.

## 📞 Soporte

Si encuentras problemas:
1. Verifica los logs en consola
2. Consulta `README.md` para detalles técnicos
3. Revisa la configuración en `config.json`

---

**Última actualización**: 2026-04-05  
**Versión**: 1.0  
**Estado**: ✅ COMPILADO Y LISTO PARA EJECUTAR

