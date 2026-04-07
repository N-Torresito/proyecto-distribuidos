package com.trafico.servicios;

import com.trafico.bd.GestorBaseDatosConsultas;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * Servicio de Monitoreo - Servidor REQ/REP en puerto 7000.
 * Recibe solicitudes JSON de clientes y genera respuestas procesadas.
 */
public class ServicioMonitoreo implements Runnable {
    private ConfiguracionSistema config;
    private ControladorConsultas controlador;
    private ZContext zContext;
    private ZMQ.Socket socket;
    private volatile boolean activo = true;
    private static final String PREFIJO_LOG = "[MONITOREO]";

    public ServicioMonitoreo(ConfiguracionSistema config) {
        this.config = config;
        this.zContext = new ZContext();
        GestorBaseDatosConsultas gestorBD = new GestorBaseDatosConsultas(config);
        this.controlador = new ControladorConsultas(gestorBD, config);
    }

    @Override
    public void run() {
        try {
            iniciarServicio();
            loopAceptacion();
        } catch (Exception e) {
            System.err.println(PREFIJO_LOG + " Error fatal: " + e.getMessage());
            e.printStackTrace();
        } finally {
            detener();
        }
    }

    /**
     * Inicializa el socket REQ/REP
     */
    private void iniciarServicio() {
        socket = zContext.createSocket(ZMQ.REP);
        int puerto = config.getServicios().getMonitoreo().getPuerto_reqrep();

        try {
            socket.bind("tcp://*:" + puerto);
            System.out.println(PREFIJO_LOG + " Iniciando ServicioMonitoreo...");
            System.out.println(PREFIJO_LOG + " Socket REQ/REP enlazado en: tcp://*:" + puerto);
        } catch (Exception e) {
            throw new RuntimeException("No se pudo enlazar socket en puerto " + puerto, e);
        }
    }

    /**
     * Loop principal de aceptación de solicitudes
     */
    private void loopAceptacion() {
        int contadorSolicitudes = 0;

        while (activo) {
            try {
                // Recibir solicitud (bloqueante)
                byte[] request = socket.recv(0);
                if (request == null) continue;

                String solicitudJson = new String(request, "UTF-8");
                contadorSolicitudes++;

                long tiempoInicio = System.currentTimeMillis();

                // Extraer tipo de solicitud para logging
                String tipoSolicitud = extraerTipoSolicitud(solicitudJson);
                System.out.println(PREFIJO_LOG + " Solicitud recibida #" + contadorSolicitudes + ": " + tipoSolicitud);

                // Procesar solicitud
                System.out.println(PREFIJO_LOG + " Consultando BD...");
                String respuestaJson = controlador.procesarSolicitud(solicitudJson);

                long tiempoTranscurrido = System.currentTimeMillis() - tiempoInicio;
                int bytesRespuesta = respuestaJson.length();

                System.out.println(PREFIJO_LOG + " Respuesta generada (" + tiempoTranscurrido + "ms): " + bytesRespuesta + " bytes");

                // Enviar respuesta
                socket.send(respuestaJson.getBytes("UTF-8"), 0);
                System.out.println(PREFIJO_LOG + " Respuesta enviada");
                System.out.println();

            } catch (Exception e) {
                System.err.println(PREFIJO_LOG + " Error procesando solicitud: " + e.getMessage());
                try {
                    // Enviar error al cliente
                    String errorResponse = "{\"estado\":\"ERROR\",\"codigo\":500,\"error\":\"ERROR_SERVIDOR\"," +
                                         "\"mensaje\":\"Error interno del servidor\"}";
                    socket.send(errorResponse.getBytes("UTF-8"), 0);
                } catch (Exception ex) {
                    System.err.println(PREFIJO_LOG + " Error enviando respuesta de error");
                }
            }
        }
    }

    /**
     * Extrae el tipo de solicitud del JSON para logging
     */
    private String extraerTipoSolicitud(String json) {
        try {
            int inicio = json.indexOf("\"tipo_solicitud\"");
            if (inicio == -1) return "DESCONOCIDO";

            inicio = json.indexOf("\"", inicio + 16) + 1;
            int fin = json.indexOf("\"", inicio);
            return json.substring(inicio, fin);
        } catch (Exception e) {
            return "DESCONOCIDO";
        }
    }

    /**
     * Detiene el servicio y limpia recursos
     */
    public void detener() {
        System.out.println(PREFIJO_LOG + " Detener solicitado");
        activo = false;

        if (socket != null) {
            try {
                socket.close();
                System.out.println(PREFIJO_LOG + " Socket cerrado");
            } catch (Exception e) {
                System.err.println(PREFIJO_LOG + " Error cerrando socket: " + e.getMessage());
            }
        }

        if (controlador != null) {
            controlador.cerrar();
        }

        if (zContext != null) {
            try {
                zContext.destroy();
            } catch (Exception e) {
                System.err.println(PREFIJO_LOG + " Error destruyendo contexto ZMQ: " + e.getMessage());
            }
        }
    }

    /**
     * Verifica si el servicio está activo
     */
    public boolean estaActivo() {
        return activo;
    }
}

