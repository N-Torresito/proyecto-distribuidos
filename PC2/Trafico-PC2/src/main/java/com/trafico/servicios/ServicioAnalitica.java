package com.trafico.servicios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafico.utils.ReglasCongestion;
import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.Topicos;
import com.trafico.modelos.EventoCamara;
import com.trafico.modelos.EventoEspira;
import com.trafico.modelos.EventoGPS;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;

import java.time.Instant;
import java.util.*;

/**
 * Servicio de Analítica del PC2.
 *
 * Patrón SUB: Recibe eventos de sensores del broker ZMQ en puerto 5556.
 * Procesa eventos usando reglas de congestión.
 * Patrón REQ: Solicita cambios de semáforo a ServicioControlSemaForos (sincrónico con timeout).
 * Patrón PUSH: Envía datos procesados a GestorBaseDatosReplica en puerto 6000.
 */
public class ServicioAnalitica implements Runnable {
    private final ConfiguracionSistema config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean activo = true;

    public ServicioAnalitica() {
        this.config = ConfiguracionSistema.getInstancia();
    }

    @Override
    public void run() {
        System.out.println("[ANALITICA] Iniciando ServicioAnalitica...");

        try (ZContext context = new ZContext()) {
            // Socket SUB para recibir eventos del broker
            ZMQ.Socket socketSub = context.createSocket(SocketType.SUB);
            String uriSub = "tcp://" + config.getBroker().getHost_pc2() + ":" + config.getBroker().getPuerto_sub();
            socketSub.connect(uriSub);

            // Suscribirse a los tres tópicos
            socketSub.subscribe(Topicos.CAMARA.getBytes(ZMQ.CHARSET));
            socketSub.subscribe(Topicos.ESPIRA.getBytes(ZMQ.CHARSET));
            socketSub.subscribe(Topicos.GPS.getBytes(ZMQ.CHARSET));

            System.out.println("[ANALITICA] Conectado al broker en: " + uriSub);

            // Socket REQ para comunicarse con Control de Semáforos (sincrónico)
            ZMQ.Socket socketReqSemaforoCtl = context.createSocket(SocketType.REQ);
            String uriReqSemaforoCtl = "tcp://localhost:" + config.getServicios().getAnalitica().getPuerto_pull();
            socketReqSemaforoCtl.connect(uriReqSemaforoCtl);
            socketReqSemaforoCtl.setReceiveTimeOut(5000); // Timeout de 5 segundos para respuesta
            System.out.println("[ANALITICA] Socket REQ (SemaforoCtl) conectado en: " + uriReqSemaforoCtl);

            // Socket PUSH para enviar datos a GestorBaseDatosReplica
            ZMQ.Socket socketPushBD = context.createSocket(SocketType.PUSH);
            String uriPushBD = "tcp://localhost:" + config.getServicios().getAnalitica().getPuerto_pull();
            socketPushBD.connect(uriPushBD);
            System.out.println("[ANALITICA] Socket PUSH (BD) conectado en: " + uriPushBD);

            // Mapa para agrupar eventos por intersección (buffer corto)
            Map<String, EventosInterseccion> bufferEventos = new HashMap<>();

            while (activo) {
                String mensaje = socketSub.recvStr(ZMQ.DONTWAIT);

                if (mensaje != null) {
                    procesarMensaje(mensaje, bufferEventos, socketReqSemaforoCtl, socketPushBD);
                } else {
                    Thread.sleep(100); // Pequeña pausa para no consumir CPU
                }
            }

            System.out.println("[ANALITICA] Servicio finalizado.");

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Procesa un mensaje del broker.
     */
    private void procesarMensaje(String mensaje, Map<String, EventosInterseccion> bufferEventos,
                                  ZMQ.Socket socketReqSemaforoCtl, ZMQ.Socket socketPushBD) {
        try {
            String[] partes = mensaje.split(Topicos.SEPARADOR, 2);
            if (partes.length != 2) {
                System.err.println("[ANALITICA] Mensaje mal formado: " + mensaje);
                return;
            }

            String topico = partes[0];
            String jsonPayload = partes[1];

            // Parsear el evento según el tópico
            String interseccion = null;
              switch (topico) {
                case Topicos.CAMARA -> {
                  EventoCamara evento = objectMapper.readValue(jsonPayload, EventoCamara.class);
                  interseccion = evento.getInterseccion();
                  bufferEventos.computeIfAbsent(interseccion, k -> new EventosInterseccion())
                    .setEventoCamara(evento);

                }
                case Topicos.ESPIRA -> {
                  EventoEspira evento = objectMapper.readValue(jsonPayload, EventoEspira.class);
                  interseccion = evento.getInterseccion();
                  bufferEventos.computeIfAbsent(interseccion, k -> new EventosInterseccion())
                    .setEventoEspira(evento);

                }
                case Topicos.GPS -> {
                  EventoGPS evento = objectMapper.readValue(jsonPayload, EventoGPS.class);
                  interseccion = evento.getInterseccion();
                  bufferEventos.computeIfAbsent(interseccion, k -> new EventosInterseccion())
                    .setEventoGPS(evento);
                }
              }

            // Intentar procesar si tenemos los 3 eventos para la intersección
            if (interseccion != null) {
                EventosInterseccion eventos = bufferEventos.get(interseccion);
                if (eventos.estanCompletos()) {
                    procesarInterseccion(eventos, socketReqSemaforoCtl, socketPushBD);
                    bufferEventos.remove(interseccion);
                }
            }

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error procesando mensaje: " + e.getMessage());
        }
    }

    /**
     * Procesa una intersección cuando tenemos todos sus eventos.
     */
    private void procesarInterseccion(EventosInterseccion eventos, ZMQ.Socket socketReqSemaforoCtl, ZMQ.Socket socketPushBD) {
        try {
            EventoCamara eventoCamara = eventos.getEventoCamara();
            EventoEspira eventoEspira = eventos.getEventoEspira();
            EventoGPS eventoGPS = eventos.getEventoGPS();
            String interseccion = eventoCamara.getInterseccion();

            // Detectar estado de congestión
            String estado = ReglasCongestion.detectarCongestion(eventoCamara, eventoEspira, eventoGPS);
            int duracionFase = ReglasCongestion.obtenerDuracionFase(estado);

            // Crear comando para cambiar semáforo (JSON)
            String comandoSemaforoJson = objectMapper.writeValueAsString(Map.of(
                "interseccion", interseccion,
                "estado", estado,
                "duracion", duracionFase,
                "timestamp", Instant.now().toString()
            ));

            // Enviar solicitud a Control de Semáforos mediante REQ (sincrónico con timeout)
            try {
                socketReqSemaforoCtl.send(comandoSemaforoJson.getBytes(), 0);
                byte[] respuesta = socketReqSemaforoCtl.recv(0);
                if (respuesta != null) {
                    String respuestaStr = new String(respuesta);
                    System.out.println(String.format("[ANALITICA] REQ-REP OK | %s | respuesta: %s",
                        interseccion, respuestaStr));
                }
            } catch (Exception e) {
                System.err.println("[ANALITICA] Timeout en REQ-REP para " + interseccion + ": " + e.getMessage());
            }

            // Enviar datos procesados a GestorBaseDatosReplica mediante PUSH
            try {
                String datosProcesadosJson = objectMapper.writeValueAsString(Map.of(
                    "interseccion", interseccion,
                    "estado", estado,
                    "timestamp", Instant.now().toString(),
                    "velocidad_promedio", eventoGPS.getVelocidadPromedio(),
                    "densidad", (int)(eventoEspira.getVehiculosContados() * 100 / eventoEspira.getIntervaloSegundos()),
                    "cola", eventoCamara.getVolumen()
                ));

                socketPushBD.send(datosProcesadosJson.getBytes(), 0);
                System.out.println(String.format("[ANALITICA] PUSH BD | %s | %s",
                    interseccion, Instant.now().toString()));
            } catch (Exception e) {
                System.err.println("[ANALITICA] Error enviando datos a BD: " + e.getMessage());
            }

            // Imprimir por pantalla
            System.out.println(String.format("[ANALITICA] %s | %s | Duración: %ds | %s",
                interseccion, estado, duracionFase, Instant.now().toString()));

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error procesando intersección: " + e.getMessage());
        }
    }

    public void detener() {
        activo = false;
    }

    /**
     * Clase auxiliar para agrupar eventos de una intersección.
     */
    private static class EventosInterseccion {
        private EventoCamara eventoCamara;
        private EventoEspira eventoEspira;
        private EventoGPS eventoGPS;

        public void setEventoCamara(EventoCamara evento) { this.eventoCamara = evento; }
        public void setEventoEspira(EventoEspira evento) { this.eventoEspira = evento; }
        public void setEventoGPS(EventoGPS evento) { this.eventoGPS = evento; }

        public EventoCamara getEventoCamara() { return eventoCamara; }
        public EventoEspira getEventoEspira() { return eventoEspira; }
        public EventoGPS getEventoGPS() { return eventoGPS; }

        public boolean estanCompletos() {
            return eventoCamara != null && eventoEspira != null && eventoGPS != null;
        }
    }
}







