# Guía de Ejecución - Sistema de Gestión de Tráfico (PC1)

El PC1 ahora cuenta con **tres formas de ejecutarse**, según tus necesidades:

## 📦 Estructura de Programas

### 1. **LanzadorBroker** - Broker ZMQ (Independiente)
Ejecuta **SOLO** el broker ZMQ que actúa como intermediario de mensajes.

```bash
java -cp Trafico-PC1.jar com.trafico.LanzadorBroker [ruta/config.json]
```

**Responsabilidades:**
- Recibe eventos de sensores mediante patrón PUB/SUB
- Reenvía eventos al servicio de analítica en PC2
- Actúa como desacoplador entre sensores y sistemas posteriores

**Ejemplo:**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorBroker config.json
```

---

### 2. **LanzadorSensores** - Sensores de Tráfico (Independiente)
Ejecuta **SOLO** los sensores (Cámara, Espira, GPS) de forma independiente.

```bash
java -cp Trafico-PC1.jar com.trafico.LanzadorSensores [ruta/config.json]
```

**Responsabilidades:**
- Genera eventos simulados de cámaras (volumen y velocidad)
- Genera eventos simulados de espiras (conteo vehicular)
- Genera eventos simulados de GPS (densidad de tráfico)
- Publica todos los eventos hacia el broker

**Ejemplo:**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorSensores config.json
```

---

### 3. **LanzadorPC1** - Todo Integrado (Modo Heredado)
Ejecuta **BROKER + SENSORES** en el mismo proceso (modo original).

```bash
java -cp Trafico-PC1.jar com.trafico.LanzadorPC1 [ruta/config.json]
```

**Responsabilidades:**
- Inicia el broker ZMQ
- Inicia todos los sensores
- Todo en un único proceso

**Ejemplo:**
```bash
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorPC1 config.json
```

---

## 🚀 Modos de Uso Recomendados

### **Opción A: Modo Separado (RECOMENDADO)**
Útil para simular una arquitectura distribuida real:

```bash
# Terminal 1: Inicia el Broker
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorBroker config.json

# Terminal 2: Inicia los Sensores (cuando el Broker esté listo)
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorSensores config.json
```

**Ventajas:**
- ✅ Simula arquitectura real distribuida
- ✅ Permite matar/reiniciar componentes independientemente
- ✅ Facilita debug de cada componente
- ✅ Mejor para testing

### **Opción B: Modo Integrado**
Útil para desarrollo rápido o demostración simple:

```bash
# Todo en una terminal
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorPC1 config.json
```

**Ventajas:**
- ✅ Un único comando
- ✅ Más simple para demostración
- ✅ Menos configuración

---

## ⚙️ Configuración (config.json)

Todos los lanzadores utilizan el mismo archivo `config.json`. Modifica según tus necesidades:

```json
{
  "broker": {
    "puerto_sub": 5555,      // Puerto donde los sensores publican
    "puerto_pub": 5556,      // Puerto hacia PC2 (Analítica)
    "host_pc2": "10.43.99.26"
  },
  "sensores": {
    "camaras": [...],        // Configuración de cámaras
    "espiras": [...],        // Configuración de espiras
    "gps": [...]             // Configuración de GPS
  }
}
```

---

## 📊 Flujo de Datos

```
MODO SEPARADO:
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│  Sensores    │ --PUB→ │  Broker ZMQ  │ --PUB→ │  PC2 (Ana.)  │
│ (Programa 2) │  :5555 │ (Programa 1) │  :5556 │              │
└──────────────┘         └──────────────┘         └──────────────┘

MODO INTEGRADO:
┌────────────────────────────────┐         ┌──────────────┐
│  Sensores + Broker             │ --PUB→ │  PC2 (Ana.)  │
│  (Un mismo Proceso)            │  :5556 │              │
└────────────────────────────────┘         └──────────────┘
```

---

## 🔧 Compilación

```bash
# Limpiar y compilar
mvn clean compile

# Generar JAR ejecutable
mvn package
```

---

## 📝 Notas Importantes

1. **Orden de Inicio (Modo Separado):**
   - Primero: Broker
   - Segundo: Sensores
   - (El Broker debe estar escuchando antes de que los sensores conecten)

2. **Archivo config.json:**
   - Busca primero en el directorio actual
   - Si no existe, carga el embebido en el JAR

3. **Puertos Predeterminados:**
   - Broker SUB: `5555` (recibe de sensores)
   - Broker PUB: `5556` (envía a PC2)

4. **Detención Limpia:**
   - Usa `Ctrl+C` para detener cualquier lanzador
   - Los hooks de apagado liberan recursos correctamente

---

## 🐛 Troubleshooting

| Problema | Solución |
|----------|----------|
| `puerto 5555 ya en uso` | Cambia en config.json o mata proceso anterior |
| `config.json no encontrado` | Coloca en dir actual o especifica ruta |
| `conexión rechazada al Broker` | Verifica que el Broker inició primero |
| Sensores no publican | Espera 1-2 segundos después de iniciar Broker |

---

## 📌 Resumen de Comandos

```bash
# Compilar
mvn clean package

# Modo A: Separado (dos terminales)
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorBroker config.json
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorSensores config.json

# Modo B: Integrado (una terminal)
java -cp target/Trafico-PC1-1.0-SNAPSHOT.jar com.trafico.LanzadorPC1 config.json
```

---

**Autor:** Sistema de Gestión Inteligente de Tráfico Urbano  
**Año:** 2026  
**Última Actualización:** Abril 2026

