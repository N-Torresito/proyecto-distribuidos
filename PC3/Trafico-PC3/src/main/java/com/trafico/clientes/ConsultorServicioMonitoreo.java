package com.trafico.clientes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Consultor del Servicio de Monitoreo - Cliente CLI interactivo.
 * Se conecta a ServicioMonitoreo mediante REQ/REP en puerto 7000.
 */
public class ConsultorServicioMonitoreo {
    private static final String HOST = "192.168.1.3";
    private static final int PUERTO = 7000;
    private static final String PREFIJO_LOG = "[CONSULTOR]";
    private static final ObjectMapper mapper = new ObjectMapper();

    private ZContext zContext;
    private ZMQ.Socket socket;
    private BufferedReader reader;
    private List<String> historialSolicitudes;

    public ConsultorServicioMonitoreo() {
        this.zContext = new ZContext();
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.historialSolicitudes = new ArrayList<>();
    }

    /**
     * Conecta al servicio de monitoreo
     */
    public boolean conectar() {
        System.out.println(PREFIJO_LOG + " Conectando a ServicioMonitoreo (" + HOST + ":" + PUERTO + ")...");

        try {
            socket = zContext.createSocket(ZMQ.REQ);
            socket.connect("tcp://" + HOST + ":" + PUERTO);
            socket.setReceiveTimeOut(5000); // 5 segundos timeout
            System.out.println(PREFIJO_LOG + " ✓ Conectado\n");
            return true;
        } catch (Exception e) {
            System.err.println(PREFIJO_LOG + " ✗ Error conectando: " + e.getMessage());
            return false;
        }
    }

    /**
     * Menú principal interactivo
     */
    public void mostrarMenu() {
        System.out.println("╔═════════════════════════════════════════════════╗");
        System.out.println("║  CONSULTOR DE TRÁFICO                           ║");
        System.out.println("║  Gestión Inteligente de Tráfico Urbano         ║");
        System.out.println("║  Conexión: " + HOST + ":" + PUERTO + " (REQ/REP)              ║");
        System.out.println("╚═════════════════════════════════════════════════╝\n");

        boolean salir = false;
        while (!salir) {
            mostrarOpciones();
            System.out.print("Seleccione opción: ");

            try {
                String opcion = reader.readLine().trim();

                switch (opcion) {
                    case "1":
                        consultarEstadoActual();
                        break;
                    case "2":
                        consultarHistorial();
                        break;
                    case "3":
                        enviarPrioridad();
                        break;
                    case "4":
                        consultarEstadoGlobal();
                        break;
                    case "5":
                        generarReporte();
                        break;
                    case "6":
                        mostrarHistorial();
                        break;
                    case "7":
                        salir = true;
                        System.out.println("\n✓ Conexión cerrada. ¡Hasta pronto!");
                        break;
                    default:
                        System.out.println("Opción no válida. Intente de nuevo.\n");
                }
            } catch (IOException e) {
                System.err.println("Error leyendo entrada: " + e.getMessage());
            }
        }
    }

    /**
     * Muestra opciones del menú
     */
    private void mostrarOpciones() {
        System.out.println("1. Consultar estado actual de intersección");
        System.out.println("2. Ver histórico de período específico");
        System.out.println("3. Enviar indicación de prioridad (ambulancia, etc)");
        System.out.println("4. Ver estado global del sistema");
        System.out.println("5. Generar reporte");
        System.out.println("6. Ver historial de consultas");
        System.out.println("7. Salir\n");
    }

    /**
     * Opción 1: Consultar estado actual
     */
    private void consultarEstadoActual() throws IOException {
        System.out.print("Ingrese intersección (ej: INT-A1): ");
        String interseccion = reader.readLine().trim();

        if (interseccion.isEmpty()) {
            System.out.println("Intersección no puede estar vacía.\n");
            return;
        }

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "ESTADO_ACTUAL");
        solicitud.put("interseccion", interseccion);

        System.out.println(PREFIJO_LOG + " Enviando ESTADO_ACTUAL | " + interseccion + "...");
        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("ESTADO_ACTUAL | " + interseccion);

        presentarRespuesta(respuesta);
    }

    /**
     * Opción 2: Ver histórico
     */
    private void consultarHistorial() throws IOException {
        System.out.print("Ingrese intersección (ej: INT-A1): ");
        String interseccion = reader.readLine().trim();

        System.out.print("Ingrese fecha inicio (ej: 2026-04-06T08:00:00Z): ");
        String fechaInicio = reader.readLine().trim();

        System.out.print("Ingrese fecha fin (ej: 2026-04-06T10:00:00Z): ");
        String fechaFin = reader.readLine().trim();

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "HISTORIAL_RANGO");
        solicitud.put("interseccion", interseccion);
        solicitud.put("fecha_inicio", fechaInicio);
        solicitud.put("fecha_fin", fechaFin);

        System.out.println(PREFIJO_LOG + " Enviando HISTORIAL_RANGO...");
        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("HISTORIAL_RANGO | " + interseccion);

        presentarRespuesta(respuesta);
    }

    /**
     * Opción 3: Enviar prioridad
     */
    private void enviarPrioridad() throws IOException {
        System.out.print("Ingrese intersecciones (separadas por coma, ej: INT-A1,INT-A2): ");
        String interseccionesStr = reader.readLine().trim();
        List<String> intersecciones = Arrays.asList(interseccionesStr.split(","));
        intersecciones = new ArrayList<>(intersecciones);
        for (int i = 0; i < intersecciones.size(); i++) {
            intersecciones.set(i, intersecciones.get(i).trim());
        }

        System.out.println("\nTipo de evento:");
        System.out.println("  (1) AMBULANCIA");
        System.out.println("  (2) BOMBEROS");
        System.out.println("  (3) POLICIA");
        System.out.println("  (4) EVENTO_ESPECIAL");
        System.out.print("Seleccione tipo: ");

        String tipoOpcion = reader.readLine().trim();
        String tipoEvento;
        switch (tipoOpcion) {
            case "1": tipoEvento = "AMBULANCIA"; break;
            case "2": tipoEvento = "BOMBEROS"; break;
            case "3": tipoEvento = "POLICIA"; break;
            case "4": tipoEvento = "EVENTO_ESPECIAL"; break;
            default:
                System.out.println("Opción no válida.\n");
                return;
        }

        System.out.print("Ingrese duración (10-60 segundos): ");
        int duracion;
        try {
            duracion = Integer.parseInt(reader.readLine().trim());
        } catch (NumberFormatException e) {
            System.out.println("Duración debe ser un número.\n");
            return;
        }

        System.out.print("Ingrese razón (opcional): ");
        String razon = reader.readLine().trim();

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "ENVIAR_PRIORIDAD");
        solicitud.put("intersecciones", intersecciones);
        solicitud.put("tipo_evento", tipoEvento);
        solicitud.put("duracion_segundos", duracion);
        solicitud.put("razon", razon);

        System.out.println(PREFIJO_LOG + " Enviando comando de prioridad...");
        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("ENVIAR_PRIORIDAD | " + tipoEvento);

        presentarRespuesta(respuesta);
    }

    /**
     * Opción 4: Estado global
     */
    private void consultarEstadoGlobal() {
        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "ESTADO_GLOBAL");

        System.out.println(PREFIJO_LOG + " Consultando estado global del sistema...");
        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("ESTADO_GLOBAL");

        presentarRespuesta(respuesta);
    }

    /**
     * Opción 5: Generar reporte
     */
    private void generarReporte() throws IOException {
        System.out.println("Tipo de reporte:");
        System.out.println("  (1) DIARIO");
        System.out.println("  (2) SEMANAL");
        System.out.print("Seleccione tipo: ");

        String tipoOpcion = reader.readLine().trim();
        String tipo;
        switch (tipoOpcion) {
            case "1": tipo = "DIARIO"; break;
            case "2": tipo = "SEMANAL"; break;
            default:
                System.out.println("Opción no válida.\n");
                return;
        }

        System.out.print("Ingrese fecha (formato YYYY-MM-DD): ");
        String fecha = reader.readLine().trim();

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "GENERAR_REPORTE");
        solicitud.put("tipo", tipo);
        solicitud.put("fecha", fecha);

        System.out.println(PREFIJO_LOG + " Generando reporte " + tipo + "...");
        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("GENERAR_REPORTE | " + tipo);

        presentarRespuesta(respuesta);
    }

    /**
     * Opción 6: Mostrar historial de consultas
     */
    private void mostrarHistorial() {
        System.out.println("\n╔═════════════════════════════════════════╗");
        System.out.println("║ HISTORIAL DE CONSULTAS                  ║");
        System.out.println("╚═════════════════════════════════════════╝\n");

        if (historialSolicitudes.isEmpty()) {
            System.out.println("No hay consultas en el historial.\n");
            return;
        }

        for (int i = 0; i < historialSolicitudes.size(); i++) {
            System.out.println((i + 1) + ". " + historialSolicitudes.get(i));
        }
        System.out.println();
    }

    /**
     * Envía una solicitud al servidor y retorna respuesta
     */
    private String enviarSolicitud(Map<String, Object> solicitud) {
        try {
            long inicio = System.currentTimeMillis();
            String solicitudJson = mapper.writeValueAsString(solicitud);

            socket.send(solicitudJson.getBytes("UTF-8"), 0);
            byte[] respuestaBytes = socket.recv(0);

            long tiempoTranscurrido = System.currentTimeMillis() - inicio;

            if (respuestaBytes == null) {
                System.out.println(PREFIJO_LOG + " ✗ Timeout esperando respuesta");
                return null;
            }

            String respuesta = new String(respuestaBytes, "UTF-8");
            System.out.println(PREFIJO_LOG + " Respuesta recibida en " + tiempoTranscurrido + "ms\n");
            return respuesta;
        } catch (Exception e) {
            System.err.println(PREFIJO_LOG + " Error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Presenta la respuesta de forma formateada
     */
    private void presentarRespuesta(String respuestaJson) {
        if (respuestaJson == null) {
            System.out.println("No se recibió respuesta.\n");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> respuesta = mapper.readValue(respuestaJson, Map.class);
            String estado = (String) respuesta.get("estado");

            if ("EXITO".equals(estado)) {
                presentarExito(respuesta);
            } else {
                presentarError(respuesta);
            }

            System.out.print("\nPresione Enter para continuar...");
            reader.readLine();
            System.out.println();
        } catch (Exception e) {
            System.err.println("Error procesando respuesta: " + e.getMessage() + "\n");
        }
    }

    /**
     * Presenta una respuesta exitosa
     */
    private void presentarExito(Map<String, Object> respuesta) {
        @SuppressWarnings("unchecked")
        Map<String, Object> datos = (Map<String, Object>) respuesta.get("datos");

        if (datos.containsKey("estado_semaforo")) {
            presentarEstadoActual(datos);
        } else if (datos.containsKey("periodo")) {
            presentarHistorial(datos);
        } else if (datos.containsKey("comando_id")) {
            presentarPrioridad(datos);
        } else if (datos.containsKey("intersecciones_totales")) {
            presentarEstadoGlobal(datos);
        } else if (datos.containsKey("reporte_id")) {
            presentarReporte(datos);
        }
    }

    /**
     * Presenta estado actual
     */
    private void presentarEstadoActual(Map<String, Object> datos) {
        String interseccion = (String) datos.get("interseccion");
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║ ESTADO ACTUAL - " + padRight(interseccion, 24) + " ║");
        System.out.println("╠════════════════════════════════════════╣");
        System.out.println("║ Semáforo:          " + padRight((String) datos.get("estado_semaforo"), 18) + " ║");
        System.out.println("║ Duración Fase:     " + padRight(datos.get("duracion_fase") + " segundos", 18) + " ║");
        System.out.println("║ Densidad:          " + padRight(datos.get("densidad") + "%", 18) + " ║");
        System.out.println("║ Velocidad:         " + padRight(datos.get("velocidad_promedio") + " km/h", 18) + " ║");
        System.out.println("║ Cola:              " + padRight(datos.get("cola") + " vehículos", 18) + " ║");
        System.out.println("║ Timestamp:         " + padRight(formatearTimestamp((String) datos.get("timestamp")), 18) + " ║");
        System.out.println("╚════════════════════════════════════════╝");
    }

    /**
     * Presenta histórico
     */
    @SuppressWarnings("unchecked")
    private void presentarHistorial(Map<String, Object> datos) {
        String interseccion = (String) datos.get("interseccion");
        Map<String, Object> periodo = (Map<String, Object>) datos.get("periodo");
        Map<String, Object> estadisticas = (Map<String, Object>) datos.get("estadisticas");

        System.out.println("╔════════════════════════════════════════════════════╗");
        System.out.println("║ HISTÓRICO - " + padRight(interseccion, 37) + " ║");
        System.out.println("╠════════════════════════════════════════════════════╣");
        System.out.println("║ Período: " + periodo.get("inicio") + " a " + periodo.get("fin") + " ║");
        System.out.println("║ Duración: " + padRight(periodo.get("duracion_minutos") + " minutos", 43) + " ║");
        System.out.println("║ Total de registros: " + padRight(datos.get("total_registros") + "", 30) + " ║");
        System.out.println("║                                                    ║");
        System.out.println("║ ESTADÍSTICAS:                                      ║");
        System.out.println("║   Densidad promedio: " + padRight(estadisticas.get("densidad_promedio") + "%", 27) + " ║");
        System.out.println("║   Velocidad promedio: " + padRight(estadisticas.get("velocidad_promedio") + " km/h", 26) + " ║");
        System.out.println("║   Cola máxima: " + padRight(estadisticas.get("cola_maxima") + " vehículos", 34) + " ║");
        System.out.println("╚════════════════════════════════════════════════════╝");
    }

    /**
     * Presenta comando de prioridad
     */
    private void presentarPrioridad(Map<String, Object> datos) {
        @SuppressWarnings("unchecked")
        List<String> intersecciones = (List<String>) datos.get("intersecciones");
        String interseccionesStr = String.join(", ", intersecciones);

        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║ COMANDO DE PRIORIDAD ENVIADO                ║");
        System.out.println("╠═════════════════════════════════════════════╣");
        System.out.println("║ ID Comando:         " + padRight((String) datos.get("comando_id"), 23) + " ║");
        System.out.println("║ Tipo de Evento:     " + padRight((String) datos.get("tipo_evento"), 23) + " ║");
        System.out.println("║ Intersecciones:     " + padRight(interseccionesStr, 23) + " ║");
        System.out.println("║ Duración:           " + padRight(datos.get("duracion") + " segundos", 23) + " ║");
        System.out.println("║ Estado:             " + padRight((String) datos.get("estado_propagacion"), 23) + " ║");
        System.out.println("║ Timestamp:          " + padRight(formatearTimestamp((String) datos.get("timestamp_envio")), 23) + " ║");
        System.out.println("║ Confirmación:       " + padRight((String) datos.get("confirmacion"), 23) + " ║");
        System.out.println("╚═════════════════════════════════════════════╝");
    }

    /**
     * Presenta estado global
     */
    @SuppressWarnings("unchecked")
    private void presentarEstadoGlobal(Map<String, Object> datos) {
        Map<String, Object> estadoPorCategoria = (Map<String, Object>) datos.get("estado_por_categoria");
        Map<String, Object> estadisticas = (Map<String, Object>) datos.get("estadisticas_globales");
        List<String> criticas = (List<String>) datos.get("intersecciones_criticas");

        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║ ESTADO GLOBAL DEL SISTEMA                   ║");
        System.out.println("╠═════════════════════════════════════════════╣");
        System.out.println("║ RESUMEN POR ESTADO:                         ║");
        System.out.println("║   ✓ NORMAL:         " + padRight(estadoPorCategoria.get("NORMAL") + " intersecciones", 20) + " ║");
        System.out.println("║   ⚠  CONGESTION:    " + padRight(estadoPorCategoria.get("CONGESTION") + " intersecciones", 20) + " ║");
        System.out.println("║   🚨 PRIORIDAD:     " + padRight(estadoPorCategoria.get("PRIORIDAD") + " intersecciones", 20) + " ║");
        System.out.println("║                                             ║");
        System.out.println("║ ESTADÍSTICAS GLOBALES:                      ║");
        System.out.println("║   Densidad: " + padRight(estadisticas.get("densidad_promedio_sistema") + "%", 29) + " ║");
        System.out.println("║   Velocidad: " + padRight(estadisticas.get("velocidad_promedio_sistema") + " km/h", 27) + " ║");
        System.out.println("║   Cola promedio: " + padRight(estadisticas.get("cola_promedio") + " vehículos", 24) + " ║");
        System.out.println("╚═════════════════════════════════════════════╝");
    }

    /**
     * Presenta reporte
     */
    @SuppressWarnings("unchecked")
    private void presentarReporte(Map<String, Object> datos) {
        Map<String, Object> resumen = (Map<String, Object>) datos.get("resumen_ejecutivo");

        System.out.println("╔═════════════════════════════════════════════╗");
        System.out.println("║ REPORTE - " + datos.get("tipo") + " (" + datos.get("fecha") + ")    ║");
        System.out.println("╠═════════════════════════════════════════════╣");
        System.out.println("║ ID: " + padRight((String) datos.get("reporte_id"), 40) + " ║");
        System.out.println("║ Incidentes: " + padRight(resumen.get("incidentes_totales") + "", 33) + " ║");
        System.out.println("║ Velocidad promedio: " + padRight(resumen.get("velocidad_promedio_dia") + " km/h", 24) + " ║");
        System.out.println("║ Intersección más congestionada:           ║");
        System.out.println("║   " + padRight((String) resumen.get("interseccion_mas_congestionada"), 38) + " ║");
        System.out.println("╚═════════════════════════════════════════════╝");
    }

    /**
     * Presenta un error
     */
    private void presentarError(Map<String, Object> respuesta) {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║ ERROR                                  ║");
        System.out.println("╠════════════════════════════════════════╣");
        System.out.println("║ Código: " + padRight(respuesta.get("codigo") + "", 29) + " ║");
        System.out.println("║ Error: " + padRight((String) respuesta.get("error"), 30) + " ║");
        System.out.println("║ Mensaje: " + padRight((String) respuesta.get("mensaje"), 28) + " ║");
        System.out.println("╚════════════════════════════════════════╝");
    }

    /**
     * Formatea timestamp ISO a formato legible
     */
    private String formatearTimestamp(String timestamp) {
        try {
            if (timestamp.contains("T")) {
                String[] partes = timestamp.split("T");
                String[] hora = partes[1].split(":");
                return partes[0] + " " + hora[0] + ":" + hora[1];
            }
        } catch (Exception e) {
            // Ignorar
        }
        return timestamp;
    }

    /**
     * Rellena con espacios a la derecha
     */
    private String padRight(String s, int n) {
        return String.format("%-" + n + "s", s).substring(0, Math.min(n, s.length() + (n - s.length())));
    }

    /**
     * Limpia recursos
     */
    public void cerrar() {
        if (socket != null) socket.close();
        if (zContext != null) zContext.destroy();
    }

    /**
     * Punto de entrada
     */
    public static void main(String[] args) {
        ConsultorServicioMonitoreo consultor = new ConsultorServicioMonitoreo();

        try {
            if (consultor.conectar()) {
                consultor.mostrarMenu();
            }
        } finally {
            consultor.cerrar();
        }
    }
}


