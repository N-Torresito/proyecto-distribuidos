package com.trafico.clientes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Consultor del Servicio de Monitoreo - Cliente CLI interactivo.
 * Conecta a ServicioMonitoreo (PC3) via REQ/REP en puerto 7000.
 * Si PC3 no responde (timeout), hace failover automático a ServicioMonitoreoReplica (PC2) en puerto 7001.
 */
public class ConsultorServicioMonitoreo {
    private static final String PREFIJO_LOG = "[CONSULTOR]";
    private static final ObjectMapper mapper = new ObjectMapper();

    private String hostActual;
    private int puertoActual;
    private final String hostPrimario;
    private final int puertoPrimario;
    private final String hostReplica;
    private final int puertoReplica;
    private boolean usandoReplica = false;

    private ZContext zContext;
    private ZMQ.Socket socket;
    private final BufferedReader reader;
    private final List<String> historialSolicitudes;

    public ConsultorServicioMonitoreo() {
        ConfiguracionSistema config = ConfiguracionSistema.getInstancia();
        this.hostPrimario = config.getServicios().getMonitoreo().getHost();
        this.puertoPrimario = config.getServicios().getMonitoreo().getPuerto_reqrep();
        this.hostReplica = config.getServicios().getMonitoreo().getHost_replica();
        this.puertoReplica = config.getServicios().getMonitoreo().getPuerto_replica();

        this.hostActual = hostPrimario;
        this.puertoActual = puertoPrimario;

        this.zContext = new ZContext();
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.historialSolicitudes = new ArrayList<>();
    }

    public boolean conectar() {
        System.out.println(PREFIJO_LOG + " Conectando a ServicioMonitoreo (" + hostActual + ":" + puertoActual + ")...");
        try {
            socket = zContext.createSocket(SocketType.REQ);
            socket.connect("tcp://" + hostActual + ":" + puertoActual);
            socket.setReceiveTimeOut(4000);
            System.out.println(PREFIJO_LOG + " Conectado a " + hostActual + ":" + puertoActual + "\n");
            return true;
        } catch (Exception e) {
            System.err.println(PREFIJO_LOG + " Error conectando: " + e.getMessage());
            return false;
        }
    }

    /**
     * Intenta failover a la réplica en PC2 cuando PC3 no responde.
     */
    private boolean conectarReplica() {
        System.out.println("\n[FAILOVER] PC3 (" + hostPrimario + ":" + puertoPrimario + ") no disponible.");
        System.out.println("[FAILOVER] Conectando a réplica en PC2 (" + hostReplica + ":" + puertoReplica + ")...");
        try {
            if (socket != null) socket.close();
            socket = zContext.createSocket(SocketType.REQ);
            socket.connect("tcp://" + hostReplica + ":" + puertoReplica);
            socket.setReceiveTimeOut(4000);
            hostActual = hostReplica;
            puertoActual = puertoReplica;
            usandoReplica = true;
            System.out.println("[FAILOVER] Conectado a réplica. El sistema continúa operando.\n");
            return true;
        } catch (Exception e) {
            System.err.println("[FAILOVER] Error conectando a réplica: " + e.getMessage());
            return false;
        }
    }

    public void mostrarMenu() {
        System.out.println("╔═════════════════════════════════════════════════╗");
        System.out.println("║  CONSULTOR DE TRÁFICO                           ║");
        System.out.println("║  Gestión Inteligente de Tráfico Urbano         ║");
        if (usandoReplica) {
            System.out.println("║  [MODO REPLICA] Servidor: " + hostActual + ":" + puertoActual + " ║");
        } else {
            System.out.println("║  Servidor: " + hostActual + ":" + puertoActual + "                     ║");
        }
        System.out.println("╚═════════════════════════════════════════════════╝\n");

        boolean salir = false;
        while (!salir) {
            mostrarOpciones();
            System.out.print("Seleccione opción: ");

            try {
                String opcion = reader.readLine().trim();

                switch (opcion) {
                    case "1": consultarEstadoActual(); break;
                    case "2": consultarHistorial(); break;
                    case "3": enviarPrioridad(); break;
                    case "4": consultarEstadoGlobal(); break;
                    case "5": generarReporte(); break;
                    case "6": mostrarHistorial(); break;
                    case "7":
                        salir = true;
                        System.out.println("\nConexión cerrada.");
                        break;
                    default:
                        System.out.println("Opción no válida.\n");
                }
            } catch (IOException e) {
                System.err.println("Error leyendo entrada: " + e.getMessage());
            }
        }
    }

    private void mostrarOpciones() {
        if (usandoReplica) System.out.println("  [USANDO REPLICA PC2 - PC3 no disponible]");
        System.out.println("1. Consultar estado actual de intersección");
        System.out.println("2. Ver histórico de período específico");
        System.out.println("3. Enviar indicación de prioridad (ambulancia, etc)");
        System.out.println("4. Ver estado global del sistema");
        System.out.println("5. Generar reporte");
        System.out.println("6. Ver historial de consultas");
        System.out.println("7. Salir\n");
    }

    private void consultarEstadoActual() throws IOException {
        System.out.print("Ingrese intersección (ej: INT-A1): ");
        String interseccion = reader.readLine().trim();
        if (interseccion.isEmpty()) { System.out.println("Intersección requerida.\n"); return; }

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "ESTADO_ACTUAL");
        solicitud.put("interseccion", interseccion);

        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("ESTADO_ACTUAL | " + interseccion);
        presentarRespuesta(respuesta);
    }

    private void consultarHistorial() throws IOException {
        System.out.print("Ingrese intersección (ej: INT-A1): ");
        String interseccion = reader.readLine().trim();
        System.out.print("Fecha inicio (ej: 2026-04-06T08:00:00Z): ");
        String fechaInicio = reader.readLine().trim();
        System.out.print("Fecha fin (ej: 2026-04-06T10:00:00Z): ");
        String fechaFin = reader.readLine().trim();

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "HISTORIAL_RANGO");
        solicitud.put("interseccion", interseccion);
        solicitud.put("fecha_inicio", fechaInicio);
        solicitud.put("fecha_fin", fechaFin);

        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("HISTORIAL_RANGO | " + interseccion);
        presentarRespuesta(respuesta);
    }

    private void enviarPrioridad() throws IOException {
        System.out.print("Intersecciones (separadas por coma, ej: INT-A1,INT-A2): ");
        String interseccionesStr = reader.readLine().trim();
        List<String> intersecciones = new ArrayList<>();
        for (String s : interseccionesStr.split(",")) intersecciones.add(s.trim());

        System.out.println("Tipo de evento: (1) AMBULANCIA  (2) BOMBEROS  (3) POLICIA  (4) EVENTO_ESPECIAL");
        System.out.print("Seleccione tipo: ");
        String tipoEvento;
        switch (reader.readLine().trim()) {
            case "1": tipoEvento = "AMBULANCIA"; break;
            case "2": tipoEvento = "BOMBEROS"; break;
            case "3": tipoEvento = "POLICIA"; break;
            case "4": tipoEvento = "EVENTO_ESPECIAL"; break;
            default: System.out.println("Opción no válida.\n"); return;
        }

        System.out.print("Duración (10-60 segundos): ");
        int duracion;
        try { duracion = Integer.parseInt(reader.readLine().trim()); }
        catch (NumberFormatException e) { System.out.println("Duración debe ser número.\n"); return; }

        System.out.print("Razón (opcional): ");
        String razon = reader.readLine().trim();

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "ENVIAR_PRIORIDAD");
        solicitud.put("intersecciones", intersecciones);
        solicitud.put("tipo_evento", tipoEvento);
        solicitud.put("duracion_segundos", duracion);
        solicitud.put("razon", razon);

        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("ENVIAR_PRIORIDAD | " + tipoEvento);
        presentarRespuesta(respuesta);
    }

    private void consultarEstadoGlobal() {
        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "ESTADO_GLOBAL");
        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("ESTADO_GLOBAL");
        presentarRespuesta(respuesta);
    }

    private void generarReporte() throws IOException {
        System.out.println("Tipo: (1) DIARIO  (2) SEMANAL");
        System.out.print("Seleccione tipo: ");
        String tipo;
        switch (reader.readLine().trim()) {
            case "1": tipo = "DIARIO"; break;
            case "2": tipo = "SEMANAL"; break;
            default: System.out.println("Opción no válida.\n"); return;
        }
        System.out.print("Fecha (YYYY-MM-DD): ");
        String fecha = reader.readLine().trim();

        Map<String, Object> solicitud = new HashMap<>();
        solicitud.put("tipo_solicitud", "GENERAR_REPORTE");
        solicitud.put("tipo", tipo);
        solicitud.put("fecha", fecha);

        String respuesta = enviarSolicitud(solicitud);
        historialSolicitudes.add("GENERAR_REPORTE | " + tipo);
        presentarRespuesta(respuesta);
    }

    private void mostrarHistorial() {
        System.out.println("\n=== HISTORIAL DE CONSULTAS ===");
        if (historialSolicitudes.isEmpty()) { System.out.println("Sin consultas.\n"); return; }
        for (int i = 0; i < historialSolicitudes.size(); i++) {
            System.out.println((i + 1) + ". " + historialSolicitudes.get(i));
        }
        System.out.println();
    }

    /**
     * Envía solicitud. Si hay timeout y no estamos en réplica, intenta failover automático.
     */
    private String enviarSolicitud(Map<String, Object> solicitud) {
        try {
            long inicio = System.currentTimeMillis();
            String solicitudJson = mapper.writeValueAsString(solicitud);
            socket.send(solicitudJson.getBytes("UTF-8"), 0);
            byte[] respuestaBytes = socket.recv(0);
            long elapsed = System.currentTimeMillis() - inicio;

            if (respuestaBytes == null) {
                System.out.println(PREFIJO_LOG + " Timeout esperando respuesta de " + hostActual);
                // Intentar failover si no estamos ya en réplica
                if (!usandoReplica && conectarReplica()) {
                    System.out.println(PREFIJO_LOG + " Reintentando con réplica...");
                    socket.send(solicitudJson.getBytes("UTF-8"), 0);
                    respuestaBytes = socket.recv(0);
                    if (respuestaBytes == null) {
                        System.out.println(PREFIJO_LOG + " Réplica también sin respuesta.");
                        return null;
                    }
                } else {
                    return null;
                }
            }

            System.out.println(PREFIJO_LOG + " Respuesta en " + elapsed + "ms" +
                (usandoReplica ? " [REPLICA]" : "") + "\n");
            return new String(respuestaBytes, "UTF-8");

        } catch (Exception e) {
            System.err.println(PREFIJO_LOG + " Error: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void presentarRespuesta(String respuestaJson) {
        if (respuestaJson == null) { System.out.println("Sin respuesta del servidor.\n"); return; }
        try {
            Map<String, Object> respuesta = mapper.readValue(respuestaJson, Map.class);
            String estado = (String) respuesta.get("estado");

            if ("EXITO".equals(estado)) {
                Map<String, Object> datos = (Map<String, Object>) respuesta.get("datos");
                System.out.println("╔══════════════════════════════════════════╗");
                System.out.println("║  RESPUESTA                               ║");
                System.out.println("╠══════════════════════════════════════════╣");
                imprimirDatos(datos, "║  ");
                System.out.println("╚══════════════════════════════════════════╝");
            } else {
                System.out.println("ERROR [" + respuesta.get("codigo") + "]: " + respuesta.get("error"));
                System.out.println("  " + respuesta.get("mensaje"));
            }

            System.out.print("\nPresione Enter para continuar...");
            try { reader.readLine(); } catch (IOException ignored) {}
            System.out.println();

        } catch (Exception e) {
            System.err.println("Error procesando respuesta: " + e.getMessage() + "\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void imprimirDatos(Map<String, Object> datos, String prefijo) {
        for (Map.Entry<String, Object> entry : datos.entrySet()) {
            Object valor = entry.getValue();
            if (valor instanceof Map) {
                System.out.println(prefijo + entry.getKey() + ":");
                imprimirDatos((Map<String, Object>) valor, prefijo + "  ");
            } else if (valor instanceof List) {
                System.out.println(prefijo + entry.getKey() + ": " + valor);
            } else {
                System.out.println(prefijo + entry.getKey() + ": " + valor);
            }
        }
    }

    public void cerrar() {
        if (socket != null) socket.close();
        if (zContext != null) zContext.destroy();
    }

    public static void main(String[] args) {
        try {
            ConfiguracionSistema.cargarDesdeRecursos();
        } catch (Exception e) {
            System.err.println("Error cargando configuración: " + e.getMessage());
            System.exit(1);
        }

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
