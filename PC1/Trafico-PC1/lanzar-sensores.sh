#!/usr/bin/env bash
# Lanza cada sensor como proceso JVM independiente visible en `ps aux`.
# Uso: ./lanzar-sensores.sh [config.json]
#   Sin argumento  → carga config desde recursos internos del JAR (--resources)
#   Con argumento  → ruta al config.json externo

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/Trafico-PC1.jar"
CONFIG="${1:---resources}"

if [[ ! -f "$JAR" ]]; then
  echo "[ERROR] JAR no encontrado: $JAR"
  echo "        Compila primero con:  cd '$SCRIPT_DIR' && mvn package -DskipTests"
  exit 1
fi

PIDS=()

launch() {
  local clase="$1"
  local sensor_id="$2"
  java \
    -Dtrafico.sensor.id="$sensor_id" \
    -cp "$JAR" \
    "$clase" \
    "$CONFIG" \
    "$sensor_id" &
  local pid=$!
  PIDS+=("$pid")
  printf "[SENSORES] PID %-6d  %-30s  %s\n" "$pid" "$clase" "$sensor_id"
}

cleanup() {
  echo ""
  echo "[SENSORES] Deteniendo todos los sensores..."
  for pid in "${PIDS[@]-}"; do
    kill "$pid" 2>/dev/null || true
  done
  echo "[SENSORES] Todos los sensores detenidos."
}
trap cleanup EXIT INT TERM

echo "╔════════════════════════════════════════════╗"
echo "║        SENSORES DE TRÁFICO - PC1           ║"
echo "║  Cada sensor = proceso independiente       ║"
echo "╚════════════════════════════════════════════╝"
echo "[SENSORES] Config: $CONFIG"
echo ""

# Cámaras
launch com.trafico.sensores.SensorCamara CAM-A1
launch com.trafico.sensores.SensorCamara CAM-B3
launch com.trafico.sensores.SensorCamara CAM-C5
launch com.trafico.sensores.SensorCamara CAM-D2
launch com.trafico.sensores.SensorCamara CAM-E4

# Espiras
launch com.trafico.sensores.SensorEspira ESP-A2
launch com.trafico.sensores.SensorEspira ESP-B4
launch com.trafico.sensores.SensorEspira ESP-C1
launch com.trafico.sensores.SensorEspira ESP-D3
launch com.trafico.sensores.SensorEspira ESP-E5

# GPS
launch com.trafico.sensores.SensorGPS GPS-A3
launch com.trafico.sensores.SensorGPS GPS-B1
launch com.trafico.sensores.SensorGPS GPS-C4
launch com.trafico.sensores.SensorGPS GPS-D5
launch com.trafico.sensores.SensorGPS GPS-E2

echo ""
echo "[SENSORES] ${#PIDS[@]} sensores iniciados."
echo "[SENSORES] Verifica con: ps aux | grep -E 'SensorCamara|SensorEspira|SensorGPS'"
echo "[SENSORES] Ctrl+C para detener todos."
echo ""

wait
