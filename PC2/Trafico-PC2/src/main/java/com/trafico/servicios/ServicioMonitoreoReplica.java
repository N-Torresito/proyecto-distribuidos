package com.trafico.servicios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Servicio de Monitoreo Réplica del PC2.
 *
 * Se activa automáticamente con PC2. Cuando PC3 falla, los clientes hacen failover
 * a este servicio (puerto 7001) que sirve consultas desde la BD réplica local.
 *
 * Patron REP: Recibe solicitudes de ConsultorServicioMonitoreo en puerto 7001.
 * Responde consultas: ESTADO_ACTUAL, HISTORIAL_RANGO, ESTADO_GLOBAL, ENVIAR_PRIORIDAD.
 */
public class ServicioMonitoreoReplica implements Runnable {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_DATE_TIME;

    private final ConfiguracionSistema config;
    private volatile boolean activo = true;

    private String DB_HOST;
    private String DB_PORT;
    private String DB_NAME;
    private String DB_USER;
    private String DB_PASSWORD;

    public ServicioMonitoreoReplica() {
        this.config = ConfiguracionSistema.getInstancia();
        ConfiguracionSistema.ServicioBaseDatos bdConfig = config.getServicios().getBase_datos();
        // La réplica está en localhost (PC2)
        this.DB_HOST = "localhost";
        this.DB_PORT = String.valueOf(bdConfig.getPuerto());
        this.DB_NAME = bdConfig.getNombre();
        this.DB_USER = bdConfig.getUsuario();
        this.DB_PASSWORD = bdConfig.getPassword();
    }

    @Override
    public void run() {
        System.out.println("[BD_REPLICA_MON] Iniciando ServicioMonitoreoReplica (puerto " +
            config.getServicios().getMonitoreo().getPuerto_replica() + ")...");

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socketRep = context.createSocket(SocketType.REP);
            String uri = "tcp://*:" + config.getServicios().getMonitoreo().getPuerto_replica();
            socketRep.bind(uri);
            socketRep.setReceiveTimeOut(500);
            System.out.println("[BD_REPLICA_MON] Escuchando en: " + uri);

            while (activo) {
                byte[] solicitudBytes = socketRep.recv(0);
                if (solicitudBytes == null) continue;

                String solicitudJson = new String(solicitudBytes, "UTF-8");
                System.out.println("[BD_REPLICA_MON] Solicitud recibida: " + solicitudJson);

                String respuestaJson = procesarSolicitud(solicitudJson);
                socketRep.send(respuestaJson.getBytes("UTF-8"), 0);
            }

            System.out.println("[BD_REPLICA_MON] Servicio finalizado.");

        } catch (Exception e) {
            System.err.println("[BD_REPLICA_MON] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String procesarSolicitud(String solicitudJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> solicitud = mapper.readValue(solicitudJson, Map.class);
            String tipo = (String) solicitud.get("tipo_solicitud");
            if (tipo == null) return error(400, "SOLICITUD_INVALIDA", "Campo tipo_solicitud requerido");

            switch (tipo) {
                case "ESTADO_ACTUAL":    return procesarEstadoActual(solicitud);
                case "HISTORIAL_RANGO":  return procesarHistorialRango(solicitud);
                case "ESTADO_GLOBAL":    return procesarEstadoGlobal();
                case "ENVIAR_PRIORIDAD": return procesarEnviarPrioridad(solicitud);
                case "GENERAR_REPORTE":  return procesarGenerarReporte(solicitud);
                default:
                    return error(400, "SOLICITUD_DESCONOCIDA", "Tipo '" + tipo + "' no soportado");
            }
        } catch (Exception e) {
            return error(500, "ERROR_INTERNO", e.getMessage());
        }
    }

    private String procesarEstadoActual(Map<String, Object> solicitud) {
        String interseccion = (String) solicitud.get("interseccion");
        if (interseccion == null || interseccion.isBlank())
            return error(400, "PARAMETRO_INVALIDO", "interseccion requerida");

        try (Connection conn = getConnection()) {
            String sql = "SELECT interseccion, estado, timestamp, velocidad_promedio, densidad, cola " +
                         "FROM analisis_trafico WHERE interseccion = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, interseccion);
                ResultSet rs = ps.executeQuery();
                if (!rs.next())
                    return error(404, "INTERSECCION_SIN_DATOS", "Sin datos para " + interseccion);

                Map<String, Object> datos = new HashMap<>();
                datos.put("interseccion", rs.getString("interseccion"));
                datos.put("estado_semaforo", rs.getString("estado"));
                datos.put("timestamp", rs.getString("timestamp"));
                datos.put("velocidad_promedio", rs.getObject("velocidad_promedio"));
                datos.put("densidad", rs.getObject("densidad"));
                datos.put("cola", rs.getObject("cola"));
                datos.put("duracion_fase", obtenerDuracionFase(rs.getString("estado")));
                datos.put("fuente", "REPLICA_PC2");

                return exito(200, datos, "Estado actual (desde réplica PC2)");
            }
        } catch (Exception e) {
            return error(500, "BD_ERROR", "Error BD réplica: " + e.getMessage());
        }
    }

    private String procesarHistorialRango(Map<String, Object> solicitud) {
        String interseccion = (String) solicitud.get("interseccion");
        String fechaInicioStr = (String) solicitud.get("fecha_inicio");
        String fechaFinStr = (String) solicitud.get("fecha_fin");

        if (interseccion == null || fechaInicioStr == null || fechaFinStr == null)
            return error(400, "PARAMETRO_INVALIDO", "interseccion, fecha_inicio y fecha_fin requeridos");

        try (Connection conn = getConnection()) {
            String sql = "SELECT interseccion, estado, timestamp, velocidad_promedio, densidad, cola " +
                         "FROM analisis_trafico WHERE interseccion = ? AND timestamp >= ? AND timestamp <= ? " +
                         "ORDER BY timestamp DESC LIMIT 100";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, interseccion);
                ps.setString(2, fechaInicioStr);
                ps.setString(3, fechaFinStr);
                ResultSet rs = ps.executeQuery();

                List<Map<String, Object>> registros = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> reg = new HashMap<>();
                    reg.put("interseccion", rs.getString("interseccion"));
                    reg.put("estado", rs.getString("estado"));
                    reg.put("timestamp", rs.getString("timestamp"));
                    reg.put("velocidad_promedio", rs.getObject("velocidad_promedio"));
                    reg.put("densidad", rs.getObject("densidad"));
                    reg.put("cola", rs.getObject("cola"));
                    registros.add(reg);
                }

                Map<String, Object> periodo = new HashMap<>();
                periodo.put("inicio", fechaInicioStr);
                periodo.put("fin", fechaFinStr);

                Map<String, Object> datos = new HashMap<>();
                datos.put("interseccion", interseccion);
                datos.put("periodo", periodo);
                datos.put("total_registros", registros.size());
                datos.put("registros", registros);
                datos.put("estadisticas", calcularEstadisticas(registros));
                datos.put("fuente", "REPLICA_PC2");

                return exito(200, datos, "Histórico (desde réplica PC2)");
            }
        } catch (Exception e) {
            return error(500, "BD_ERROR", "Error BD réplica: " + e.getMessage());
        }
    }

    private String procesarEstadoGlobal() {
        try (Connection conn = getConnection()) {
            String sql = "SELECT interseccion, estado, velocidad_promedio, densidad, cola FROM analisis_trafico";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();

                Map<String, Integer> porCategoria = new HashMap<>();
                porCategoria.put("NORMAL", 0);
                porCategoria.put("CONGESTION", 0);
                porCategoria.put("PRIORIDAD", 0);

                double velSum = 0; double denSum = 0; int colaSum = 0; int total = 0;
                List<String> criticas = new ArrayList<>();

                while (rs.next()) {
                    String estado = rs.getString("estado");
                    porCategoria.merge(estado, 1, Integer::sum);
                    Object vel = rs.getObject("velocidad_promedio");
                    Object den = rs.getObject("densidad");
                    Object cola = rs.getObject("cola");
                    if (vel != null) velSum += ((Number) vel).doubleValue();
                    if (den != null) denSum += ((Number) den).doubleValue();
                    if (cola != null) colaSum += ((Number) cola).intValue();
                    if ("CONGESTION".equals(estado)) criticas.add(rs.getString("interseccion"));
                    total++;
                }

                Map<String, Object> estadisticas = new HashMap<>();
                estadisticas.put("velocidad_promedio_sistema", total > 0 ? Math.round(velSum / total * 10.0) / 10.0 : 0);
                estadisticas.put("densidad_promedio_sistema", total > 0 ? Math.round(denSum / total * 10.0) / 10.0 : 0);
                estadisticas.put("cola_promedio", total > 0 ? Math.round((double) colaSum / total * 10.0) / 10.0 : 0);

                Map<String, Object> datos = new HashMap<>();
                datos.put("intersecciones_totales", total);
                datos.put("estado_por_categoria", porCategoria);
                datos.put("estadisticas_globales", estadisticas);
                datos.put("intersecciones_criticas", criticas);
                datos.put("fuente", "REPLICA_PC2");
                datos.put("timestamp", java.time.Instant.now().toString());

                return exito(200, datos, "Estado global (desde réplica PC2)");
            }
        } catch (Exception e) {
            return error(500, "BD_ERROR", "Error BD réplica: " + e.getMessage());
        }
    }

    private String procesarEnviarPrioridad(Map<String, Object> solicitud) {
        // Reenviar al servicio de analítica (mismo flujo que PC3)
        try {
            @SuppressWarnings("unchecked")
            List<String> intersecciones = (List<String>) solicitud.get("intersecciones");
            String tipoEvento = (String) solicitud.get("tipo_evento");
            Object duracionObj = solicitud.get("duracion_segundos");
            String razon = (String) solicitud.get("razon");

            if (intersecciones == null || tipoEvento == null || duracionObj == null)
                return error(400, "PARAMETRO_INVALIDO", "intersecciones, tipo_evento y duracion_segundos requeridos");

            int duracion = ((Number) duracionObj).intValue();
            String comandoId = "CMD-REP-" + System.currentTimeMillis();

            // Enviar PUSH a analítica (mismo puerto 6002)
            try (ZContext ctx = new ZContext()) {
                ZMQ.Socket pushSocket = ctx.createSocket(SocketType.PUSH);
                pushSocket.setLinger(1000); // espera hasta 1s para que el mensaje salga antes de cerrar
                String endpoint = "tcp://localhost:" + config.getServicios().getAnalitica().getPuerto_pull_monitoreo();
                pushSocket.connect(endpoint);
                Thread.sleep(100); // deja que ZMQ establezca la conexión TCP

                Map<String, Object> comando = new HashMap<>();
                comando.put("comando_id", comandoId);
                comando.put("intersecciones", intersecciones);
                comando.put("tipo_evento", tipoEvento);
                comando.put("duracion_segundos", duracion);
                comando.put("razon", razon != null ? razon : "");
                comando.put("timestamp", java.time.Instant.now().toString());

                pushSocket.send(mapper.writeValueAsString(comando).getBytes(), 0);
                System.out.println("[BD_REPLICA_MON] Prioridad enviada a analítica: " + comandoId);
            }

            Map<String, Object> datos = new HashMap<>();
            datos.put("comando_id", comandoId);
            datos.put("tipo_evento", tipoEvento);
            datos.put("intersecciones", intersecciones);
            datos.put("duracion", duracion);
            datos.put("estado_propagacion", "ENVIADO_VIA_REPLICA");
            datos.put("timestamp_envio", java.time.Instant.now().toString());
            datos.put("confirmacion", "Comando propagado desde réplica PC2");

            return exito(200, datos, "Prioridad enviada (vía réplica PC2)");

        } catch (Exception e) {
            return error(500, "ERROR_ENVIO", "Error enviando prioridad: " + e.getMessage());
        }
    }

    private String procesarGenerarReporte(Map<String, Object> solicitud) {
        String tipo = (String) solicitud.get("tipo");
        String fecha = (String) solicitud.get("fecha");
        if (tipo == null || fecha == null)
            return error(400, "PARAMETRO_INVALIDO", "tipo y fecha requeridos");

        try (Connection conn = getConnection()) {
            String fechaFin = fecha + "T23:59:59Z";
            String fechaInicio = "DIARIO".equals(tipo) ? fecha + "T00:00:00Z" :
                                  LocalDateTime.parse(fecha + "T00:00:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                      .minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

            String sql = "SELECT estado, velocidad_promedio, interseccion FROM analisis_trafico " +
                         "WHERE timestamp >= ? AND timestamp <= ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, fechaInicio);
                ps.setString(2, fechaFin);
                ResultSet rs = ps.executeQuery();

                int incidentes = 0; double velSum = 0; int count = 0;
                Map<String, Integer> congestionPorInter = new HashMap<>();

                while (rs.next()) {
                    if ("CONGESTION".equals(rs.getString("estado"))) {
                        incidentes++;
                        congestionPorInter.merge(rs.getString("interseccion"), 1, Integer::sum);
                    }
                    Object vel = rs.getObject("velocidad_promedio");
                    if (vel != null) { velSum += ((Number) vel).doubleValue(); count++; }
                }

                String masCongestionada = congestionPorInter.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("N/A");

                Map<String, Object> resumen = new HashMap<>();
                resumen.put("incidentes_totales", incidentes);
                resumen.put("velocidad_promedio_dia", count > 0 ? Math.round(velSum / count * 10.0) / 10.0 : 0);
                resumen.put("interseccion_mas_congestionada", masCongestionada);

                Map<String, Object> datos = new HashMap<>();
                datos.put("reporte_id", "REP-" + tipo + "-" + fecha);
                datos.put("tipo", tipo);
                datos.put("fecha", fecha);
                datos.put("resumen_ejecutivo", resumen);
                datos.put("fuente", "REPLICA_PC2");

                return exito(200, datos, "Reporte generado (desde réplica PC2)");
            }
        } catch (Exception e) {
            return error(500, "BD_ERROR", "Error generando reporte: " + e.getMessage());
        }
    }

    private Map<String, Object> calcularEstadisticas(List<Map<String, Object>> registros) {
        double velSum = 0; double denSum = 0; int colaMax = 0; int count = 0;
        for (Map<String, Object> r : registros) {
            Object vel = r.get("velocidad_promedio");
            Object den = r.get("densidad");
            Object cola = r.get("cola");
            if (vel != null) { velSum += ((Number) vel).doubleValue(); count++; }
            if (den != null) denSum += ((Number) den).doubleValue();
            if (cola != null) colaMax = Math.max(colaMax, ((Number) cola).intValue());
        }
        Map<String, Object> stats = new HashMap<>();
        stats.put("velocidad_promedio", count > 0 ? Math.round(velSum / count * 10.0) / 10.0 : 0);
        stats.put("densidad_promedio", count > 0 ? Math.round(denSum / count * 10.0) / 10.0 : 0);
        stats.put("cola_maxima", colaMax);
        return stats;
    }

    private int obtenerDuracionFase(String estado) {
        if (config == null) return 15;
        return switch (estado) {
            case "CONGESTION" -> config.getSemaforos().getDuracion_congestion();
            case "PRIORIDAD"  -> config.getSemaforos().getDuracion_prioridad();
            default           -> config.getSemaforos().getDuracion_normal();
        };
    }

    private Connection getConnection() throws SQLException {
        try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException ignored) {}
        String url = "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME;
        return DriverManager.getConnection(url, DB_USER, DB_PASSWORD);
    }

    private String exito(int codigo, Map<String, Object> datos, String mensaje) {
        try {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("estado", "EXITO");
            resp.put("codigo", codigo);
            resp.put("mensaje", mensaje);
            resp.put("datos", datos);
            return mapper.writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"estado\":\"ERROR\",\"mensaje\":\"Serialization error\"}";
        }
    }

    private String error(int codigo, String tipo, String mensaje) {
        try {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("estado", "ERROR");
            resp.put("codigo", codigo);
            resp.put("error", tipo);
            resp.put("mensaje", mensaje);
            return mapper.writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"estado\":\"ERROR\",\"codigo\":500}";
        }
    }

    public void detener() {
        activo = false;
    }
}
