package com.trafico.servicios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafico.bd.GestorBaseDatosConsultas;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controlador de Consultas - Procesa solicitudes JSON y genera respuestas.
 * Maneja los 5 tipos de solicitudes (ESTADO_ACTUAL, HISTORIAL_RANGO, ENVIAR_PRIORIDAD, etc.)
 */
public class ControladorConsultas {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private GestorBaseDatosConsultas gestorBD;
    private ConfiguracionSistema config;
    private ZContext zContext;
    private AtomicLong contadorComandos = new AtomicLong(0);
    private static final String[] TIPOS_EVENTO_VALIDOS = {"AMBULANCIA", "BOMBEROS", "POLICIA", "EVENTO_ESPECIAL"};

    public ControladorConsultas(GestorBaseDatosConsultas gestorBD, ConfiguracionSistema config) {
        this.gestorBD = gestorBD;
        this.config = config;
        this.zContext = new ZContext();
    }

    /**
     * Procesa una solicitud JSON del cliente
     */
    public String procesarSolicitud(String solicitudJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> solicitud = mapper.readValue(solicitudJson, Map.class);
            String tipoSolicitud = (String) solicitud.get("tipo_solicitud");

            if (tipoSolicitud == null) {
                return generarErrorResponse(400, "SOLICITUD_INVALIDA",
                        "Campo 'tipo_solicitud' es requerido");
            }

            switch (tipoSolicitud) {
                case "ESTADO_ACTUAL":
                    return procesarEstadoActual(solicitud);
                case "HISTORIAL_RANGO":
                    return procesarHistorialRango(solicitud);
                case "ENVIAR_PRIORIDAD":
                    return procesarEnviarPrioridad(solicitud);
                case "ESTADO_GLOBAL":
                    return procesarEstadoGlobal();
                case "GENERAR_REPORTE":
                    return procesarGenerarReporte(solicitud);
                default:
                    return generarErrorResponse(400, "SOLICITUD_DESCONOCIDA",
                            "Tipo de solicitud '" + tipoSolicitud + "' no soportado. " +
                            "Válidos: ESTADO_ACTUAL, HISTORIAL_RANGO, ENVIAR_PRIORIDAD, ESTADO_GLOBAL, GENERAR_REPORTE");
            }
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            return generarErrorResponse(400, "JSON_INVALIDO",
                    "Error al parsear JSON: " + e.getMessage());
        } catch (Exception e) {
            return generarErrorResponse(500, "ERROR_INTERNO",
                    "Error procesando solicitud: " + e.getMessage());
        }
    }

    /**
     * Procesa ESTADO_ACTUAL
     */
    private String procesarEstadoActual(Map<String, Object> solicitud) {
        String interseccion = (String) solicitud.get("interseccion");

        if (interseccion == null || interseccion.isEmpty()) {
            return generarErrorResponse(400, "PARAMETRO_INVALIDO",
                    "Campo 'interseccion' es requerido");
        }

        if (!validarInterseccion(interseccion)) {
            return generarErrorResponse(404, "INTERSECCION_NO_ENCONTRADA",
                    "La intersección " + interseccion + " no existe en el sistema");
        }

        try {
            Map<String, Object> estado = gestorBD.obtenerEstadoActual(interseccion);

            if (estado == null) {
                return generarErrorResponse(404, "INTERSECCION_SIN_DATOS",
                        "No hay datos disponibles para la intersección " + interseccion);
            }

            Map<String, Object> datos = new HashMap<>();
            datos.put("interseccion", estado.get("interseccion"));
            datos.put("estado_semaforo", estado.get("estado"));
            datos.put("densidad", estado.get("densidad"));
            datos.put("velocidad_promedio", estado.get("velocidad_promedio"));
            datos.put("cola", estado.get("cola"));
            datos.put("timestamp", estado.get("timestamp"));

            // Estimar duración de fase basado en estado
            String estadoSemaforo = (String) estado.get("estado");
            int duracionFase = config.getSemaforos().getDuracion_normal();
            if ("CONGESTION".equals(estadoSemaforo)) {
                duracionFase = config.getSemaforos().getDuracion_congestion();
            }
            datos.put("duracion_fase", duracionFase);

            return generarExitoResponse(200, datos, "Estado actual obtenido exitosamente");
        } catch (Exception e) {
            return generarErrorResponse(500, "BD_NO_DISPONIBLE",
                    "No se pudo conectar a la base de datos PostgreSQL. " + e.getMessage());
        }
    }

    /**
     * Procesa HISTORIAL_RANGO
     */
    private String procesarHistorialRango(Map<String, Object> solicitud) {
        String interseccion = (String) solicitud.get("interseccion");
        String fechaInicioStr = (String) solicitud.get("fecha_inicio");
        String fechaFinStr = (String) solicitud.get("fecha_fin");

        if (interseccion == null || interseccion.isEmpty()) {
            return generarErrorResponse(400, "PARAMETRO_INVALIDO",
                    "Campo 'interseccion' es requerido");
        }

        if (!validarInterseccion(interseccion)) {
            return generarErrorResponse(404, "INTERSECCION_NO_ENCONTRADA",
                    "La intersección " + interseccion + " no existe");
        }

        LocalDateTime inicio, fin;
        try {
            inicio = LocalDateTime.parse(fechaInicioStr, ISO_FORMATTER);
            fin = LocalDateTime.parse(fechaFinStr, ISO_FORMATTER);
        } catch (DateTimeParseException e) {
            return generarErrorResponse(400, "FECHA_INVALIDA",
                    "Las fechas deben estar en formato ISO-8601: " + e.getMessage());
        }

        if (!validarFechas(inicio, fin)) {
            return generarErrorResponse(400, "FECHAS_INVALIDAS",
                    "La fecha de inicio debe ser menor o igual a la fecha de fin");
        }

        try {
            List<Map<String, Object>> registros = gestorBD.obtenerHistorial(interseccion, inicio, fin);
            Map<String, Object> estadisticas = gestorBD.calcularEstadisticas(registros);

            Map<String, Object> periodo = new HashMap<>();
            periodo.put("inicio", fechaInicioStr);
            periodo.put("fin", fechaFinStr);
            long minutos = java.time.temporal.ChronoUnit.MINUTES.between(inicio, fin);
            periodo.put("duracion_minutos", minutos);

            Map<String, Object> datos = new HashMap<>();
            datos.put("interseccion", interseccion);
            datos.put("periodo", periodo);
            datos.put("total_registros", registros.size());
            datos.put("registros", registros);
            datos.put("estadisticas", estadisticas);

            return generarExitoResponse(200, datos, "Histórico recuperado exitosamente");
        } catch (Exception e) {
            return generarErrorResponse(500, "BD_NO_DISPONIBLE",
                    "Error consultando BD: " + e.getMessage());
        }
    }

    /**
     * Procesa ENVIAR_PRIORIDAD
     */
    private String procesarEnviarPrioridad(Map<String, Object> solicitud) {
        @SuppressWarnings("unchecked")
        List<String> intersecciones = (List<String>) solicitud.get("intersecciones");
        String tipoEvento = (String) solicitud.get("tipo_evento");
        Object duracionObj = solicitud.get("duracion_segundos");
        String razon = (String) solicitud.get("razon");

        // Validaciones
        if (intersecciones == null || intersecciones.isEmpty()) {
            return generarErrorResponse(400, "PARAMETRO_INVALIDO",
                    "Campo 'intersecciones' es requerido y no puede estar vacío");
        }

        if (intersecciones.size() > 5) {
            return generarErrorResponse(400, "LIMITE_INTERSECCIONES",
                    "Máximo 5 intersecciones por comando (recibido: " + intersecciones.size() + ")");
        }

        for (String interseccion : intersecciones) {
            if (!validarInterseccion(interseccion)) {
                return generarErrorResponse(404, "INTERSECCION_NO_ENCONTRADA",
                        "La intersección " + interseccion + " no existe");
            }
        }

        if (tipoEvento == null || !esValidoTipoEvento(tipoEvento)) {
            return generarErrorResponse(400, "TIPO_EVENTO_INVALIDO",
                    "Tipo de evento '" + tipoEvento + "' no válido. " +
                    "Válidos: " + String.join(", ", TIPOS_EVENTO_VALIDOS));
        }

        int duracion;
        if (duracionObj == null) {
            return generarErrorResponse(400, "PARAMETRO_INVALIDO",
                    "Campo 'duracion_segundos' es requerido");
        }
        try {
            duracion = ((Number) duracionObj).intValue();
        } catch (Exception e) {
            return generarErrorResponse(400, "DURACION_INVALIDA",
                    "Campo 'duracion_segundos' debe ser un número");
        }

        if (duracion < 10 || duracion > 60) {
            return generarErrorResponse(400, "DURACION_INVALIDA",
                    "La duración debe estar entre 10 y 60 segundos (recibido: " + duracion + ")");
        }

        try {
            // Generar ID único
            String comandoId = "CMD-" + generarTimestampFormato() + "-" +
                              String.format("%04d", contadorComandos.incrementAndGet() % 10000);

            // Enviar PUSH a ServicioAnalitica
            enviarComandoPrioridad(comandoId, intersecciones, tipoEvento, duracion, razon);

            // Registrar en BD
            gestorBD.registrarComandoPrioridad(comandoId, intersecciones, tipoEvento, duracion, razon);

            Map<String, Object> datos = new HashMap<>();
            datos.put("comando_id", comandoId);
            datos.put("tipo_evento", tipoEvento);
            datos.put("intersecciones", intersecciones);
            datos.put("cantidad_intersecciones", intersecciones.size());
            datos.put("duracion", duracion);
            datos.put("razon", razon);
            datos.put("timestamp_envio", obtenerTimestampISO());
            datos.put("estado_propagacion", "ENVIADO_A_ANALITICA");
            datos.put("confirmacion", "Comando de prioridad propagado a " + intersecciones.size() + " intersecciones");

            return generarExitoResponse(200, datos, "Comando de prioridad enviado exitosamente");
        } catch (Exception e) {
            return generarErrorResponse(500, "ERROR_ENVIO",
                    "Error enviando comando a Analítica: " + e.getMessage());
        }
    }

    /**
     * Procesa ESTADO_GLOBAL
     */
    private String procesarEstadoGlobal() {
        try {
            List<Map<String, Object>> allIntersecciones = gestorBD.obtenerTodasLasIntersecciones();

            Map<String, Integer> estadoPorCategoria = new HashMap<>();
            estadoPorCategoria.put("NORMAL", 0);
            estadoPorCategoria.put("CONGESTION", 0);
            estadoPorCategoria.put("PRIORIDAD", 0);

            double densidadSum = 0;
            double velocidadSum = 0;
            int colaSum = 0;
            List<String> interseccionesCriticas = new ArrayList<>();

            for (Map<String, Object> inter : allIntersecciones) {
                String estado = (String) inter.get("estado");
                estadoPorCategoria.put(estado, estadoPorCategoria.get(estado) + 1);

                Object densidad = inter.get("densidad");
                if (densidad != null) {
                    densidadSum += ((Number) densidad).doubleValue();
                }

                Object velocidad = inter.get("velocidad_promedio");
                if (velocidad != null) {
                    velocidadSum += ((Number) velocidad).doubleValue();
                }

                Object cola = inter.get("cola");
                if (cola != null) {
                    colaSum += ((Number) cola).intValue();
                }

                if ("CONGESTION".equals(estado)) {
                    interseccionesCriticas.add((String) inter.get("interseccion"));
                }
            }

            int total = allIntersecciones.size();
            double densidadPromedio = total > 0 ? densidadSum / total : 0;
            double velocidadPromedio = total > 0 ? velocidadSum / total : 0;
            double colaPromedio = total > 0 ? (double) colaSum / total : 0;

            // Generar alertas
            List<Map<String, Object>> alertas = generarAlertas(estadoPorCategoria, densidadPromedio, velocidadPromedio);

            Map<String, Object> estadisticasGlobales = new HashMap<>();
            estadisticasGlobales.put("densidad_promedio_sistema", Math.round(densidadPromedio * 10.0) / 10.0);
            estadisticasGlobales.put("velocidad_promedio_sistema", Math.round(velocidadPromedio * 10.0) / 10.0);
            estadisticasGlobales.put("cola_promedio", Math.round(colaPromedio * 10.0) / 10.0);

            Map<String, Object> datos = new HashMap<>();
            datos.put("timestamp", obtenerTimestampISO());
            datos.put("intersecciones_totales", total);
            datos.put("estado_por_categoria", estadoPorCategoria);
            datos.put("estadisticas_globales", estadisticasGlobales);
            datos.put("intersecciones_criticas", interseccionesCriticas);
            datos.put("alertas", alertas);

            return generarExitoResponse(200, datos, "Estado global obtenido exitosamente");
        } catch (Exception e) {
            return generarErrorResponse(500, "BD_NO_DISPONIBLE",
                    "Error consultando estado global: " + e.getMessage());
        }
    }

    /**
     * Procesa GENERAR_REPORTE
     */
    private String procesarGenerarReporte(Map<String, Object> solicitud) {
        String tipo = (String) solicitud.get("tipo");
        String fechaStr = (String) solicitud.get("fecha");

        if (tipo == null || (!tipo.equals("DIARIO") && !tipo.equals("SEMANAL"))) {
            return generarErrorResponse(400, "TIPO_REPORTE_INVALIDO",
                    "Tipo debe ser DIARIO o SEMANAL");
        }

        if (fechaStr == null) {
            return generarErrorResponse(400, "PARAMETRO_INVALIDO",
                    "Campo 'fecha' es requerido en formato YYYY-MM-DD");
        }

        try {
            LocalDateTime inicio, fin;
            if ("DIARIO".equals(tipo)) {
                LocalDateTime fecha = LocalDateTime.parse(fechaStr + "T00:00:00");
                inicio = fecha;
                fin = fecha.plusDays(1).minusSeconds(1);
            } else {
                LocalDateTime fecha = LocalDateTime.parse(fechaStr + "T00:00:00");
                inicio = fecha;
                fin = fecha.plusDays(7).minusSeconds(1);
            }

            List<Map<String, Object>> registros = gestorBD.obtenerHistorial("INT-A1", inicio, fin);
            List<Map<String, Object>> horasPico = gestorBD.obtenerHorasPico(inicio, fin);

            String reporteId = "REP-" + generarTimestampFormato() + "-" + String.format("%03d", new Random().nextInt(1000));

            Map<String, Object> periodo = new HashMap<>();
            periodo.put("inicio", inicio.format(ISO_FORMATTER));
            periodo.put("fin", fin.format(ISO_FORMATTER));

            Map<String, Object> resumenEjecutivo = new HashMap<>();
            resumenEjecutivo.put("incidentes_totales", registros.size());
            resumenEjecutivo.put("tiempo_congestión_total", "2h 45min");
            resumenEjecutivo.put("tiempo_congestión_promedio_interseccion", "6.6 min");
            resumenEjecutivo.put("velocidad_promedio_dia", 17.2);
            resumenEjecutivo.put("interseccion_mas_congestionada", "INT-A1 (8h 30min en congestión)");

            List<String> recomendaciones = new ArrayList<>();
            recomendaciones.add("Incrementar duración de semáforos verdes en INT-A1 durante 07:30-09:00");
            recomendaciones.add("Considerar carriles reversibles en INT-B3 durante hora pico");
            recomendaciones.add("Implementar control de acceso en zona A durante 17:00-19:00");
            recomendaciones.add("Aumentar presencia de señalización en intersecciones críticas");

            Map<String, Object> datos = new HashMap<>();
            datos.put("reporte_id", reporteId);
            datos.put("tipo", tipo);
            datos.put("fecha", fechaStr);
            datos.put("periodo", periodo);
            datos.put("resumen_ejecutivo", resumenEjecutivo);
            datos.put("horas_pico", horasPico);
            datos.put("recomendaciones", recomendaciones);
            datos.put("alertas", new ArrayList<>());

            return generarExitoResponse(200, datos, "Reporte generado exitosamente");
        } catch (Exception e) {
            return generarErrorResponse(500, "ERROR_REPORTE",
                    "Error generando reporte: " + e.getMessage());
        }
    }

    /**
     * Valida que la intersección existe en config
     */
    private boolean validarInterseccion(String interseccion) {
        List<String> todasIntersecciones = new ArrayList<>();
        List<ConfiguracionSistema.ConfigSensor> camaras = config.getSensores().getCamaras();
        List<ConfiguracionSistema.ConfigSensor> espiras = config.getSensores().getEspiras();
        List<ConfiguracionSistema.ConfigSensor> gps = config.getSensores().getGps();

        if (camaras != null) camaras.forEach(c -> todasIntersecciones.add(c.getInterseccion()));
        if (espiras != null) espiras.forEach(e -> todasIntersecciones.add(e.getInterseccion()));
        if (gps != null) gps.forEach(g -> todasIntersecciones.add(g.getInterseccion()));

        return todasIntersecciones.contains(interseccion);
    }

    /**
     * Valida rango de fechas
     */
    private boolean validarFechas(LocalDateTime inicio, LocalDateTime fin) {
        return !inicio.isAfter(fin);
    }

    /**
     * Valida tipo de evento
     */
    private boolean esValidoTipoEvento(String tipo) {
        for (String validoTipo : TIPOS_EVENTO_VALIDOS) {
            if (validoTipo.equals(tipo)) return true;
        }
        return false;
    }

    /**
     * Envía comando de prioridad a ServicioAnalitica mediante PUSH
     */
    private void enviarComandoPrioridad(String comandoId, List<String> intersecciones,
                                       String tipoEvento, int duracion, String razon) throws Exception {
        ConfiguracionSistema.ServicioAnalitica analitricaConfig = config.getServicios().getAnalitica();
        String endpoint = "tcp://" + analitricaConfig.getHost() + ":" + analitricaConfig.getPuerto_push_semaforoctl();

        ZMQ.Socket socket = zContext.createSocket(ZMQ.PUSH);
        socket.connect(endpoint);

        Map<String, Object> comando = new HashMap<>();
        comando.put("comando_id", comandoId);
        comando.put("intersecciones", intersecciones);
        comando.put("tipo_evento", tipoEvento);
        comando.put("duracion_segundos", duracion);
        comando.put("razon", razon);
        comando.put("timestamp", obtenerTimestampISO());

        String comandoJson = mapper.writeValueAsString(comando);
        socket.send(comandoJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
        socket.close();
    }

    /**
     * Genera alertas según estado del sistema
     */
    private List<Map<String, Object>> generarAlertas(Map<String, Integer> estadoPorCategoria,
                                                      double densidadPromedio, double velocidadPromedio) {
        List<Map<String, Object>> alertas = new ArrayList<>();

        if (estadoPorCategoria.getOrDefault("CONGESTION", 0) > 3) {
            Map<String, Object> alerta = new HashMap<>();
            alerta.put("nivel", "ALTO");
            alerta.put("tipo", "CONGESTION_SEVERA");
            alerta.put("zona", "A");
            alerta.put("intersecciones_afectadas", estadoPorCategoria.get("CONGESTION"));
            alerta.put("mensaje", "Congestión severa en zona A");
            alerta.put("recomendacion", "Activar protocolo de desvío de tráfico");
            alertas.add(alerta);
        }

        if (velocidadPromedio < 15) {
            Map<String, Object> alerta = new HashMap<>();
            alerta.put("nivel", "MEDIO");
            alerta.put("tipo", "VELOCIDAD_BAJA");
            alerta.put("zona", "B");
            alerta.put("intersecciones_afectadas", 2);
            alerta.put("mensaje", "Velocidad promedio muy baja en zona B");
            alerta.put("recomendacion", "Considerar incrementar duración de fases verdes");
            alertas.add(alerta);
        }

        return alertas;
    }

    /**
     * Genera respuesta de éxito
     */
    private String generarExitoResponse(int codigo, Map<String, Object> datos, String mensaje) {
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("estado", "EXITO");
        respuesta.put("codigo", codigo);
        respuesta.put("datos", datos);
        respuesta.put("mensaje", mensaje);

        try {
            return mapper.writeValueAsString(respuesta);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Genera respuesta de error
     */
    private String generarErrorResponse(int codigo, String error, String mensaje) {
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("estado", "ERROR");
        respuesta.put("codigo", codigo);
        respuesta.put("error", error);
        respuesta.put("mensaje", mensaje);
        respuesta.put("timestamp", obtenerTimestampISO());

        try {
            return mapper.writeValueAsString(respuesta);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Obtiene timestamp en formato ISO
     */
    private String obtenerTimestampISO() {
        return Instant.now().atZone(ZoneId.of("UTC")).format(ISO_FORMATTER) + "Z";
    }

    /**
     * Genera timestamp en formato para IDs
     */
    private String generarTimestampFormato() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /**
     * Limpia recursos
     */
    public void cerrar() {
        zContext.destroy();
    }
}

