-- Migración de analisis_trafico: interseccion → timestamp como PRIMARY KEY
-- Ejecutar en: trafico_db (PC3 y PC2 réplica)
-- Conexión:  psql -h <host> -U postgres -d trafico_db -f migrate_schema.sql

BEGIN;

-- 1. Guardar datos existentes en tabla temporal
CREATE TABLE IF NOT EXISTS analisis_trafico_backup AS
    SELECT * FROM analisis_trafico;

-- 2. Eliminar tabla original (PK vieja: interseccion)
DROP TABLE IF EXISTS analisis_trafico;

-- 3. Crear tabla con nueva estructura (PK: timestamp)
CREATE TABLE analisis_trafico (
    timestamp            VARCHAR(50)       PRIMARY KEY,
    interseccion         VARCHAR(50)       NOT NULL,
    estado               VARCHAR(20)       NOT NULL,
    velocidad_promedio   DOUBLE PRECISION,
    densidad             INTEGER,
    cola                 INTEGER
);

-- 4. Índice para consultas por intersección
CREATE INDEX idx_at_interseccion ON analisis_trafico(interseccion);
CREATE INDEX idx_at_timestamp    ON analisis_trafico(timestamp DESC);

-- 5. Migrar datos del backup (cada fila vieja = un evento histórico)
--    ON CONFLICT por si dos filas del backup tenían el mismo timestamp
INSERT INTO analisis_trafico
    (timestamp, interseccion, estado, velocidad_promedio, densidad, cola)
SELECT
    timestamp,
    interseccion,
    estado,
    velocidad_promedio,
    densidad,
    cola
FROM analisis_trafico_backup
ON CONFLICT (timestamp) DO NOTHING;

COMMIT;

-- Verificar resultado
SELECT COUNT(*) AS filas_migradas FROM analisis_trafico;
SELECT * FROM analisis_trafico ORDER BY timestamp DESC LIMIT 10;
