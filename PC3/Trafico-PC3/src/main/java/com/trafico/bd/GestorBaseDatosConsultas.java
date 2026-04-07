package com.trafico.bd;

import com.trafico.config.ConfiguracionSistema;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Gestor de consultas SELECT para acceso a la base de datos PostgreSQL.
 * Específicamente para consultas de análisis de tráfico en PC3.
 */
public class GestorBaseDatosConsultas {
    private ConfiguracionSistema config;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final int TIMEOUT_CONEXION = 5; // segundos
    private static final int MAX_REGISTROS = 1000; // Límite de registros retornados

    public GestorBaseDatosConsultas(ConfiguracionSistema config) {
        this.config = config;
    }

    /**
     * Obtiene una conexión a la base de datos con timeout.
     */
    private Connection obtenerConexion() throws SQLException {
        ConfiguracionSistema.ServicioBaseDatos bdConfig = config.getServicios().getBase_datos();
        String url = String.format("jdbc:postgresql://%s:%d/%s",
                bdConfig.getHost(),
                bdConfig.getPuerto(),
                bdConfig.getNombre());

        DriverManager.setLoginTimeout(TIMEOUT_CONEXION);
        return DriverManager.getConnection(url, bdConfig.getUsuario(), bdConfig.getPassword());
    }

    /**
     * Obtiene el estado más reciente de una intersección
     */
    public Map<String, Object> obtenerEstadoActual(String interseccion) throws SQLException {
        String sql = "SELECT * FROM analisis_trafico WHERE interseccion = ? ORDER BY timestamp DESC LIMIT 1";

        try (Connection conn = obtenerConexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, interseccion);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapearRegistro(rs);
                }
            }
        }
        return null;
    }

    /**
     * Obtiene histórico entre dos timestamps
     */
    public List<Map<String, Object>> obtenerHistorial(String interseccion,
                                                      LocalDateTime inicio,
                                                      LocalDateTime fin) throws SQLException {
        List<Map<String, Object>> registros = new ArrayList<>();
        String sql = "SELECT * FROM analisis_trafico WHERE interseccion = ? " +
                     "AND timestamp >= ? AND timestamp <= ? " +
                     "ORDER BY timestamp ASC LIMIT ?";

        try (Connection conn = obtenerConexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, interseccion);
            stmt.setString(2, inicio.format(ISO_FORMATTER));
            stmt.setString(3, fin.format(ISO_FORMATTER));
            stmt.setInt(4, MAX_REGISTROS);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(mapearRegistro(rs));
                }
            }
        }
        return registros;
    }

    /**
     * Obtiene todas las intersecciones con su estado actual (más reciente)
     */
    public List<Map<String, Object>> obtenerTodasLasIntersecciones() throws SQLException {
        List<Map<String, Object>> intersecciones = new ArrayList<>();
        String sql = "SELECT DISTINCT ON (interseccion) * FROM analisis_trafico " +
                     "ORDER BY interseccion, timestamp DESC";

        try (Connection conn = obtenerConexion();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    intersecciones.add(mapearRegistro(rs));
                }
            }
        }
        return intersecciones;
    }

    /**
     * Obtiene intersecciones críticas (en congestión)
     */
    public List<Map<String, Object>> obtenerInterseccionesCriticas() throws SQLException {
        List<Map<String, Object>> criticas = new ArrayList<>();
        String sql = "SELECT DISTINCT ON (interseccion) * FROM analisis_trafico " +
                     "WHERE estado = 'CONGESTION' " +
                     "ORDER BY interseccion, timestamp DESC";

        try (Connection conn = obtenerConexion();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    criticas.add(mapearRegistro(rs));
                }
            }
        }
        return criticas;
    }

    /**
     * Calcula estadísticas para una lista de registros
     */
    public Map<String, Object> calcularEstadisticas(List<Map<String, Object>> registros) {
        Map<String, Object> stats = new HashMap<>();

        if (registros.isEmpty()) {
            stats.put("registros_totales", 0);
            stats.put("tiempo_normal", "0 min");
            stats.put("tiempo_congestion", "0 min");
            stats.put("densidad_promedio", 0.0);
            stats.put("velocidad_promedio", 0.0);
            stats.put("cola_maxima", 0);
            stats.put("pico_congestion", "N/A");
            return stats;
        }

        int totalRegistros = registros.size();
        double densidadSum = 0;
        double velocidadSum = 0;
        int colaMax = 0;
        int tiempoNormal = 0;
        int tiempoCongestión = 0;

        for (Map<String, Object> registro : registros) {
            String estado = (String) registro.get("estado");
            if ("NORMAL".equals(estado)) {
                tiempoNormal++;
            } else if ("CONGESTION".equals(estado)) {
                tiempoCongestión++;
            }

            Object densidad = registro.get("densidad");
            if (densidad != null) {
                densidadSum += ((Number) densidad).doubleValue();
            }

            Object velocidad = registro.get("velocidad_promedio");
            if (velocidad != null) {
                velocidadSum += ((Number) velocidad).doubleValue();
            }

            Object cola = registro.get("cola");
            if (cola != null) {
                int colaVal = ((Number) cola).intValue();
                if (colaVal > colaMax) colaMax = colaVal;
            }
        }

        stats.put("registros_totales", totalRegistros);
        stats.put("tiempo_normal", tiempoNormal + " min");
        stats.put("tiempo_congestion", tiempoCongestión + " min");
        stats.put("densidad_promedio", Math.round(densidadSum / totalRegistros * 10.0) / 10.0);
        stats.put("velocidad_promedio", Math.round(velocidadSum / totalRegistros * 10.0) / 10.0);
        stats.put("cola_maxima", colaMax);
        stats.put("pico_congestion", encontrarPicoCongestion(registros));

        return stats;
    }

    /**
     * Encuentra el período de máxima congestión
     */
    private String encontrarPicoCongestion(List<Map<String, Object>> registros) {
        if (registros.isEmpty()) return "N/A";

        int indexMax = 0;
        int densidadMax = 0;

        for (int i = 0; i < registros.size(); i++) {
            Object densidad = registros.get(i).get("densidad");
            if (densidad != null) {
                int d = ((Number) densidad).intValue();
                if (d > densidadMax) {
                    densidadMax = d;
                    indexMax = i;
                }
            }
        }

        if (indexMax >= registros.size()) return "N/A";

        Object timestamp = registros.get(indexMax).get("timestamp");
        if (timestamp != null) {
            return timestamp.toString().substring(11, 16) + "-" +
                   (indexMax + 1 < registros.size() ?
                    registros.get(indexMax + 1).get("timestamp").toString().substring(11, 16) :
                    timestamp.toString().substring(11, 16));
        }
        return "N/A";
    }

    /**
     * Mapea un resultado de BD a un mapa de objetos
     */
    private Map<String, Object> mapearRegistro(ResultSet rs) throws SQLException {
        Map<String, Object> registro = new HashMap<>();
        registro.put("interseccion", rs.getString("interseccion"));
        registro.put("estado", rs.getString("estado"));
        registro.put("timestamp", rs.getString("timestamp"));
        registro.put("velocidad_promedio", rs.getDouble("velocidad_promedio"));
        registro.put("densidad", rs.getInt("densidad"));
        registro.put("cola", rs.getInt("cola"));
        return registro;
    }

    /**
     * Registra un comando de prioridad en la tabla historial_comandos_prioridad
     */
    public void registrarComandoPrioridad(String comandoId, List<String> intersecciones,
                                         String tipoEvento, int duracion, String razon) throws SQLException {
        String sql = "INSERT INTO historial_comandos_prioridad " +
                     "(comando_id, intersecciones, tipo_evento, duracion_segundos, razon, estado) " +
                     "VALUES (?, ?, ?, ?, ?, 'PENDIENTE')";

        try (Connection conn = obtenerConexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, comandoId);
            stmt.setString(2, String.join(",", intersecciones));
            stmt.setString(3, tipoEvento);
            stmt.setInt(4, duracion);
            stmt.setString(5, razon);
            stmt.executeUpdate();
        }
    }

    /**
     * Obtiene estadísticas de horas pico para un día específico
     */
    public List<Map<String, Object>> obtenerHorasPico(LocalDateTime inicio, LocalDateTime fin) throws SQLException {
        List<Map<String, Object>> horasPico = new ArrayList<>();
        String sql = "SELECT * FROM analisis_trafico WHERE timestamp >= ? AND timestamp <= ? " +
                     "ORDER BY timestamp ASC";

        try (Connection conn = obtenerConexion();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, inicio.format(ISO_FORMATTER));
            stmt.setString(2, fin.format(ISO_FORMATTER));

            try (ResultSet rs = stmt.executeQuery()) {
                Map<Integer, Map<String, Object>> statsHora = new HashMap<>();

                while (rs.next()) {
                    String timestamp = rs.getString("timestamp");
                    int hora = Integer.parseInt(timestamp.substring(11, 13));

                    statsHora.putIfAbsent(hora, new HashMap<>());
                    Map<String, Object> stats = statsHora.get(hora);

                    @SuppressWarnings("unchecked")
                    List<Integer> densidades = (List<Integer>) stats.getOrDefault("densidades", new ArrayList<>());
                    densidades.add(rs.getInt("densidad"));
                    stats.put("densidades", densidades);

                    @SuppressWarnings("unchecked")
                    List<Double> velocidades = (List<Double>) stats.getOrDefault("velocidades", new ArrayList<>());
                    velocidades.add(rs.getDouble("velocidad_promedio"));
                    stats.put("velocidades", velocidades);
                }

                // Procesar horas pico (máxima congestión)
                for (Map.Entry<Integer, Map<String, Object>> entry : statsHora.entrySet()) {
                    Map<String, Object> horaData = entry.getValue();
                    @SuppressWarnings("unchecked")
                    List<Integer> densidades = (List<Integer>) horaData.get("densidades");
                    @SuppressWarnings("unchecked")
                    List<Double> velocidades = (List<Double>) horaData.get("velocidades");

                    if (!densidades.isEmpty()) {
                        double densidadPromedio = densidades.stream().mapToInt(Integer::intValue).average().orElse(0);
                        double velocidadPromedio = velocidades.stream().mapToDouble(Double::doubleValue).average().orElse(0);

                        if (densidadPromedio > 50) { // Umbral de hora pico
                            Map<String, Object> pico = new HashMap<>();
                            pico.put("hora", String.format("%02d", entry.getKey()));
                            pico.put("congestion_promedio", Math.round(densidadPromedio * 10.0) / 10.0);
                            pico.put("velocidad_promedio", Math.round(velocidadPromedio * 10.0) / 10.0);
                            horasPico.add(pico);
                        }
                    }
                }
            }
        }
        return horasPico;
    }
}

