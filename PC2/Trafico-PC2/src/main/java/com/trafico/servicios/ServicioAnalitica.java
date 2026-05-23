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
import org.zeromq.ZPoller;

import java.time.Instant;
import java.util.*;

/**
 * Servicio de Analítica del PC2.
 *
 * Patron SUB: Recibe eventos de sensores del broker ZMQ en puerto 5556.
 * Procesa eventos usando reglas de congestion (flexible: cada interseccion tiene 1 tipo de sensor).
 * Patron REQ: Solicita cambios de semáforo a ServicioControlSemaforos (sincronico con timeout).
 * Patron PUSH: Envía datos procesados a GestorBaseDatosReplica en puerto 6000.
 * Patron PULL (BIND): Recibe comandos de prioridad del modulo de Monitoreo en PC3 (puerto 6002).
 */
public class ServicioAnalitica implements Runnable {
    private final ConfiguracionSistema config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean activo = true;

    private volatile Map<String, ComandoPrioridad> comandosPrioridad = new HashMap<>();

    private static class ComandoPrioridad {
        String comandoId;
        String tipoEvento;
        int duracionSegundos;
        long timestampInicio;
        String razon;

        ComandoPrioridad(String comandoId, String tipoEvento, int duracionSegundos, String razon) {
            this.comandoId = comandoId;
            this.tipoEvento = tipoEvento;
            this.duracionSegundos = duracionSegundos;
            this.timestampInicio = System.currentTimeMillis();
            this.razon = razon;
        }

        boolean esValido() {
            return (System.currentTimeMillis() - timestampInicio) < duracionSegundos * 1000L;
        }
    }

    public ServicioAnalitica() {
        this.config = ConfiguracionSistema.getInstancia();
    }

    @Override
    public void run() {
        System.out.println("[ANALITICA] Iniciando ServicioAnalitica...");

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socketSub = context.createSocket(SocketType.SUB);
            String uriSub = "tcp://" + config.getBroker().getHost_pc2() + ":" + config.getBroker().getPuerto_pub();
            socketSub.connect(uriSub);
            socketSub.subscribe(Topicos.CAMARA.getBytes(ZMQ.CHARSET));
            socketSub.subscribe(Topicos.ESPIRA.getBytes(ZMQ.CHARSET));
            socketSub.subscribe(Topicos.GPS.getBytes(ZMQ.CHARSET));
            System.out.println("[ANALITICA] Conectado al broker en: " + uriSub);

            ZMQ.Socket socketReqSemaforoCtl = context.createSocket(SocketType.REQ);
            String uriReqSemaforoCtl = "tcp://localhost:" + config.getServicios().getAnalitica().getPuerto_push_semaforoctl();
            socketReqSemaforoCtl.connect(uriReqSemaforoCtl);
            socketReqSemaforoCtl.setReceiveTimeOut(5000);
            System.out.println("[ANALITICA] Socket REQ (SemaforoCtl) conectado en: " + uriReqSemaforoCtl);

            ZMQ.Socket socketPushBD = context.createSocket(SocketType.PUSH);
            String uriPushBD = "tcp://localhost:" + config.getServicios().getAnalitica().getPuerto_pull();
            socketPushBD.connect(uriPushBD);
            System.out.println("[ANALITICA] Socket PUSH (BD) conectado en: " + uriPushBD);

            // PULL enlazado: PC3 conecta PUSH hacia aquí para enviar comandos de prioridad
            ZMQ.Socket socketPullMonitoreo = context.createSocket(SocketType.PULL);
            String uriPullMonitoreo = "tcp://*:" + config.getServicios().getAnalitica().getPuerto_pull_monitoreo();
            socketPullMonitoreo.bind(uriPullMonitoreo);
            System.out.println("[ANALITICA] Socket PULL (Monitoreo) enlazado en: " + uriPullMonitoreo);

            Map<String, EventosInterseccion> bufferEventos = new HashMap<>();

            ZPoller poller = new ZPoller(context);
            poller.register(socketSub, ZPoller.IN);
            poller.register(socketPullMonitoreo, ZPoller.IN);

            System.out.println("[ANALITICA] Todos los sockets inicializados. Esperando eventos...");

            while (activo) {
                int eventId = poller.poll(100);

                if (eventId == -1) {
                    Thread.sleep(10);
                    continue;
                }

                if (poller.isReadable(socketSub)) {
                    String mensaje = socketSub.recvStr(ZMQ.DONTWAIT);
                    if (mensaje != null) {
                        procesarMensaje(mensaje, bufferEventos, socketReqSemaforoCtl, socketPushBD);
                    }
                }

                if (poller.isReadable(socketPullMonitoreo)) {
                    String comando = socketPullMonitoreo.recvStr(ZMQ.DONTWAIT);
                    if (comando != null) {
                        procesarComandoMonitoreo(comando);
                    }
                }

                verificarYProcesarTimeouts(bufferEventos, socketReqSemaforoCtl, socketPushBD);
            }

            poller.destroy();
            System.out.println("[ANALITICA] Servicio finalizado.");

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void procesarMensaje(String mensaje, Map<String, EventosInterseccion> bufferEventos,
                                  ZMQ.Socket socketReqSemaforoCtl, ZMQ.Socket socketPushBD) {
        try {
            String[] partes = mensaje.split(Topicos.SEPARADOR, 2);
            if (partes.length != 2) return;

            String topico = partes[0];
            String jsonPayload = partes[1];

            System.out.println("[ANALITICA] Recibido [" + topico + "]: " + jsonPayload);

            String interseccion = null;
            switch (topico) {
                case Topicos.CAMARA -> {
                    EventoCamara evento = objectMapper.readValue(jsonPayload, EventoCamara.class);
                    interseccion = evento.getInterseccion();
                    bufferEventos.computeIfAbsent(interseccion, k -> new EventosInterseccion()).setEventoCamara(evento);
                }
                case Topicos.ESPIRA -> {
                    EventoEspira evento = objectMapper.readValue(jsonPayload, EventoEspira.class);
                    interseccion = evento.getInterseccion();
                    bufferEventos.computeIfAbsent(interseccion, k -> new EventosInterseccion()).setEventoEspira(evento);
                }
                case Topicos.GPS -> {
                    EventoGPS evento = objectMapper.readValue(jsonPayload, EventoGPS.class);
                    interseccion = evento.getInterseccion();
                    bufferEventos.computeIfAbsent(interseccion, k -> new EventosInterseccion()).setEventoGPS(evento);
                }
            }

            if (interseccion != null) {
                EventosInterseccion eventos = bufferEventos.get(interseccion);
                if (eventos.tieneEventos()) {
                    procesarInterseccion(eventos, socketReqSemaforoCtl, socketPushBD);
                }
            }

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error procesando mensaje: " + e.getMessage());
        }
    }

    private void procesarInterseccion(EventosInterseccion eventos, ZMQ.Socket socketReqSemaforoCtl,
                                       ZMQ.Socket socketPushBD) {
        try {
            EventoCamara eventoCamara = eventos.getEventoCamara();
            EventoEspira eventoEspira = eventos.getEventoEspira();
            EventoGPS eventoGPS = eventos.getEventoGPS();

            String interseccion = null;
            if (eventoCamara != null) interseccion = eventoCamara.getInterseccion();
            else if (eventoEspira != null) interseccion = eventoEspira.getInterseccion();
            else if (eventoGPS != null) interseccion = eventoGPS.getInterseccion();

            if (interseccion == null) return;

            String estado;
            int duracionFase;
            String accionTomada;

            if (verificarPrioridad(interseccion)) {
                estado = "PRIORIDAD";
                duracionFase = ReglasCongestion.obtenerDuracionFase("PRIORIDAD");
                ComandoPrioridad cmd = comandosPrioridad.get(interseccion);
                accionTomada = "PRIORIDAD (" + cmd.tipoEvento + ") - " + cmd.razon;
                System.out.println("\n[ANALITICA] EVENTO PRIORITARIO: " + cmd.tipoEvento + " en " + interseccion);
            } else {
                estado = detectarCongestionFlexible(eventoCamara, eventoEspira, eventoGPS);
                duracionFase = ReglasCongestion.obtenerDuracionFase(estado);
                accionTomada = "CONGESTION".equals(estado) ? "EXTENDER fase verde" : "Fase verde normal";
            }

            System.out.println("[ANALITICA] " + interseccion + " | Estado: " + estado +
                " | Duracion: " + duracionFase + "s | Sensores: " + eventos.obtenerSensoresDisponibles());

            // Enviar comando a Control de Semáforos via REQ
            String comandoSemaforoJson = objectMapper.writeValueAsString(Map.of(
                "interseccion", interseccion,
                "estado", estado,
                "duracion", duracionFase,
                "timestamp", Instant.now().toString()
            ));
            try {
                socketReqSemaforoCtl.send(comandoSemaforoJson.getBytes(), 0);
                byte[] respuesta = socketReqSemaforoCtl.recv(0);
                if (respuesta != null) {
                    System.out.println("[ANALITICA] REQ-REP OK: " + new String(respuesta));
                }
            } catch (Exception e) {
                System.err.println("[ANALITICA] Timeout REQ-REP (" + interseccion + "): " + e.getMessage());
            }

            // Enviar a BD via PUSH (siempre, independiente del estado)
            try {
                Map<String, Object> datosProcesados = new HashMap<>();
                datosProcesados.put("interseccion", interseccion);
                datosProcesados.put("estado", estado);
                datosProcesados.put("timestamp", Instant.now().toString());
                if (eventoGPS != null) datosProcesados.put("velocidad_promedio", eventoGPS.getVelocidadPromedio());
                if (eventoEspira != null) datosProcesados.put("densidad",
                    eventoEspira.getVehiculosContados() * 100 / eventoEspira.getIntervaloSegundos());
                if (eventoCamara != null) datosProcesados.put("cola", eventoCamara.getVolumen());

                socketPushBD.send(objectMapper.writeValueAsString(datosProcesados).getBytes(), 0);
                System.out.println("[ANALITICA] PUSH BD: " + interseccion + " | " + estado);
            } catch (Exception e) {
                System.err.println("[ANALITICA] Error PUSH BD: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error procesando interseccion: " + e.getMessage());
        }
    }

    private String detectarCongestionFlexible(EventoCamara camara, EventoEspira espira, EventoGPS gps) {
        ConfiguracionSistema.Trafico traficoConfig = config.getTrafico();
        int umbralCola = traficoConfig.getUmbral_congestion_cola();
        int umbralVelocidad = traficoConfig.getUmbral_congestion_velocidad();
        int umbralDensidad = traficoConfig.getUmbral_congestion_densidad();

        int contadoresCongestion = 0;
        int contadoresTotales = 0;

        if (camara != null) {
            contadoresTotales++;
            if (camara.getVolumen() >= umbralCola || camara.getVelocidadPromedio() <= umbralVelocidad) {
                contadoresCongestion++;
            }
        }
        if (espira != null) {
            contadoresTotales++;
            int densidad = espira.getVehiculosContados() * 100 / espira.getIntervaloSegundos();
            if (densidad >= umbralDensidad) contadoresCongestion++;
        }
        if (gps != null) {
            contadoresTotales++;
            if (gps.getVelocidadPromedio() <= umbralVelocidad) contadoresCongestion++;
        }

        if (contadoresTotales > 0 && contadoresCongestion > 0) {
            if (contadoresTotales == 1 || contadoresCongestion * 2 >= contadoresTotales) {
                return "CONGESTION";
            }
        }
        return "NORMAL";
    }

    private void procesarComandoMonitoreo(String comando) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> datos = objectMapper.readValue(comando, Map.class);

            String comandoId = (String) datos.get("comando_id");
            @SuppressWarnings("unchecked")
            List<String> intersecciones = (List<String>) datos.get("intersecciones");
            String tipoEvento = (String) datos.get("tipo_evento");
            int duracion = ((Number) datos.get("duracion_segundos")).intValue();
            String razon = (String) datos.get("razon");

            System.out.println("\n[ANALITICA] INDICACION DIRECTA PC3: " + tipoEvento + " | " + comandoId);

            ComandoPrioridad cmd = new ComandoPrioridad(comandoId, tipoEvento, duracion, razon);
            for (String interseccion : intersecciones) {
                comandosPrioridad.put(interseccion, cmd);
                System.out.println("  -> PRIORIDAD activada: " + interseccion + " por " + duracion + "s");
            }

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error procesando comando monitoreo: " + e.getMessage());
        }
    }

    private boolean verificarPrioridad(String interseccion) {
        ComandoPrioridad cmd = comandosPrioridad.get(interseccion);
        if (cmd == null) return false;
        if (cmd.esValido()) return true;
        comandosPrioridad.remove(interseccion);
        return false;
    }

    private void verificarYProcesarTimeouts(Map<String, EventosInterseccion> bufferEventos,
                                             ZMQ.Socket socketReqSemaforoCtl, ZMQ.Socket socketPushBD) {
        List<String> aEliminar = new ArrayList<>();
        for (Map.Entry<String, EventosInterseccion> entry : bufferEventos.entrySet()) {
            EventosInterseccion eventos = entry.getValue();
            if (eventos.tiempoExpirado() && eventos.tieneEventos()) {
                procesarInterseccion(eventos, socketReqSemaforoCtl, socketPushBD);
                aEliminar.add(entry.getKey());
            }
        }
        aEliminar.forEach(bufferEventos::remove);
    }

    public void detener() {
        activo = false;
    }

    private static class EventosInterseccion {
        private EventoCamara eventoCamara;
        private EventoEspira eventoEspira;
        private EventoGPS eventoGPS;
        private long ultimaActualizacion;

        public EventosInterseccion() { this.ultimaActualizacion = System.currentTimeMillis(); }

        public void setEventoCamara(EventoCamara e) { eventoCamara = e; ultimaActualizacion = System.currentTimeMillis(); }
        public void setEventoEspira(EventoEspira e) { eventoEspira = e; ultimaActualizacion = System.currentTimeMillis(); }
        public void setEventoGPS(EventoGPS e) { eventoGPS = e; ultimaActualizacion = System.currentTimeMillis(); }

        public EventoCamara getEventoCamara() { return eventoCamara; }
        public EventoEspira getEventoEspira() { return eventoEspira; }
        public EventoGPS getEventoGPS() { return eventoGPS; }

        public boolean tieneEventos() { return eventoCamara != null || eventoEspira != null || eventoGPS != null; }
        public boolean tiempoExpirado() { return (System.currentTimeMillis() - ultimaActualizacion) > 5000; }

        public String obtenerSensoresDisponibles() {
            StringBuilder sb = new StringBuilder();
            if (eventoCamara != null) sb.append("CAMARA ");
            if (eventoEspira != null) sb.append("ESPIRA ");
            if (eventoGPS != null) sb.append("GPS ");
            return sb.toString().trim();
        }
    }
}
