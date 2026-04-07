-- Script para crear tabla de historial de comandos de prioridad en PostgreSQL
-- Ejecutar en: trafico_db
-- Conexión: psql -h 10.43.101.27 -U postgres -d trafico_db < init_historial_comandos.sql

CREATE TABLE IF NOT EXISTS historial_comandos_prioridad (
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

-- Crear índices para optimizar consultas
CREATE INDEX IF NOT EXISTS idx_cmd_prioridad_id ON historial_comandos_prioridad(comando_id);
CREATE INDEX IF NOT EXISTS idx_cmd_prioridad_timestamp ON historial_comandos_prioridad(timestamp_creacion);
CREATE INDEX IF NOT EXISTS idx_cmd_prioridad_estado ON historial_comandos_prioridad(estado);

-- Insertar registros de ejemplo para pruebas
INSERT INTO historial_comandos_prioridad (comando_id, intersecciones, tipo_evento, duracion_segundos, razon, estado)
VALUES
  ('CMD-20260406-0001', 'INT-A1,INT-A2,INT-B1', 'AMBULANCIA', 40, 'Emergencia médica - Hospital Central', 'EJECUTADO'),
  ('CMD-20260406-0002', 'INT-C5,INT-D2', 'BOMBEROS', 60, 'Incendio en edificio comercial', 'EJECUTADO'),
  ('CMD-20260406-0003', 'INT-E4', 'POLICIA', 30, 'Persecución de vehículo', 'PENDIENTE')
ON CONFLICT (comando_id) DO NOTHING;

-- Verificar datos
SELECT * FROM historial_comandos_prioridad ORDER BY timestamp_creacion DESC;

-- Contar registros
SELECT COUNT(*) as total_comandos FROM historial_comandos_prioridad;

