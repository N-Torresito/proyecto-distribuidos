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
 * Procesa eventos usando reglas de congestion.
 * Patron REQ: Solicita cambios de semáforo a ServicioControlSemaForos (sincronico con timeout).
 * Patron PUSH: Envía datos procesados a GestorBaseDatosReplica en puerto 6000.
 * Patron REP: Recibe indicaciones directas del modulo de Monitoreo en PC3 (comandos de usuario).
 */
public class ServicioAnalitica implements Runnable {
    private final ConfiguracionSistema config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean activo = true;

    // Para almacenar comandos de prioridad temporal del usuario (por interseccion)
    private volatile Map<String, ComandoPrioridad> comandosPrioridad = new HashMap<>();
    private static final long DURACION_COMANDO_PRIORIDAD = 60000; // 60 segundos

    /**
     * Clase interna para almacenar informacion de comando de prioridad
     */
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
            // Socket SUB para recibir eventos del broker
            ZMQ.Socket socketSub = context.createSocket(SocketType.SUB);
            String uriSub = "tcp://" + config.getBroker().getHost_pc2() + ":" + config.getBroker().getPuerto_pub();
            socketSub.connect(uriSub);

            // Suscribirse a los tres topicos
            socketSub.subscribe(Topicos.CAMARA.getBytes(ZMQ.CHARSET));
            socketSub.subscribe(Topicos.ESPIRA.getBytes(ZMQ.CHARSET));
            socketSub.subscribe(Topicos.GPS.getBytes(ZMQ.CHARSET));

            System.out.println("[ANALITICA] Conectado al broker en: " + uriSub);

            // Socket REQ para comunicarse con Control de Semáforos (sincronico)
            ZMQ.Socket socketReqSemaforoCtl = context.createSocket(SocketType.REQ);
            String uriReqSemaforoCtl = "tcp://localhost:" + config.getServicios().getAnalitica().getPuerto_push_semaforoctl();
            socketReqSemaforoCtl.connect(uriReqSemaforoCtl);
            socketReqSemaforoCtl.setReceiveTimeOut(5000); // Timeout de 5 segundos para respuesta
            System.out.println("[ANALITICA] Socket REQ (SemaforoCtl) conectado en: " + uriReqSemaforoCtl);

            // Socket PUSH para enviar datos a GestorBaseDatos
            ZMQ.Socket socketPushBD = context.createSocket(SocketType.PUSH);
            String uriPushBD = "tcp://localhost:" + config.getServicios().getAnalitica().getPuerto_pull();
            socketPushBD.connect(uriPushBD);
            System.out.println("[ANALITICA] Socket PUSH (BD) conectado en: " + uriPushBD);

            // Socket PULL para recibir comandos de prioridad del modulo de Monitoreo (PC3)
            ZMQ.Socket socketPullMonitoreo = context.createSocket(SocketType.PULL);
            String uriPullMonitoreo = "tcp://" + config.getServicios().getAnalitica().getHost() +
                                      ":" + config.getServicios().getMonitoreo().getPuerto_reqrep();
            socketPullMonitoreo.connect(uriPullMonitoreo);
            System.out.println("[ANALITICA] Socket PULL (Monitoreo) conectado en: " + uriPullMonitoreo);

            // Mapa para agrupar eventos por interseccion (buffer corto)
            Map<String, EventosInterseccion> bufferEventos = new HashMap<>();

            // Configurar poller para monitorear múltiples sockets
            ZPoller poller = new ZPoller(context);
            poller.register(socketSub, ZPoller.IN);
            poller.register(socketPullMonitoreo, ZPoller.IN);

            System.out.println("[ANALITICA] ✓ Todos los sockets inicializados correctamente");
            System.out.println("[ANALITICA] Esperando eventos de sensores y comandos de monitoreo...");
            System.out.println("[ANALITICA] Nota: Las intersecciones tienen sensores DIFERENTES");System.out.println("[ANALITICA] Procesando eventos según sensores disponibles (timeout 5s)\n");

            while (activo) {
                int eventId = poller.poll(100); // Poll con timeout de 100ms

                if (eventId == -1) {
                    Thread.sleep(10); // Error en poll, pequeña pausa
                    continue;
                }

                // Procesar eventos del broker (SUB)
                if (poller.isReadable(socketSub)) {
                    String mensaje = socketSub.recvStr(ZMQ.DONTWAIT);
                    if (mensaje != null) {
                        procesarMensaje(mensaje, bufferEventos, socketReqSemaforoCtl, socketPushBD);
                    }
                }

                // Procesar comandos del Monitoreo (PULL)
                if (poller.isReadable(socketPullMonitoreo)) {
                    String comando = socketPullMonitoreo.recvStr(ZMQ.DONTWAIT);
                    if (comando != null) {
                        procesarComandoMonitoreo(comando);
                    }
                }

                // Verificar timeouts: procesar intersecciones que hayan estado inactivas 5 segundos
                verificarYProcesarTimeouts(bufferEventos, socketReqSemaforoCtl, socketPushBD);
            }

            poller.destroy();
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

            System.out.println("[ANALITICA] Mensaje recibido:" + jsonPayload + " Topico:" + topico);

            // Parsear el evento según el topico
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

            // Procesar inmediatamente: NO esperamos a que lleguen todos los sensores
            // Cada interseccion tiene solo algunos sensores (según config.json)
            if (interseccion != null) {
                EventosInterseccion eventos = bufferEventos.get(interseccion);
                if (eventos.tieneEventos()) {
                    // Procesar con los sensores disponibles
                    procesarInterseccion(eventos, socketReqSemaforoCtl, socketPushBD);
                    // Mantener en buffer por si llegan más datos en los proximos 5s
                    // Se limpiará automáticamente por timeout
                }
            }

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error procesando mensaje: " + e.getMessage());
        }
    }

    /**
     * Procesa una interseccion con los sensores que se tengan disponibles.
     * Nota: Las intersecciones tienen diferentes sensores según config.json
     */
    private void procesarInterseccion(EventosInterseccion eventos, ZMQ.Socket socketReqSemaforoCtl, ZMQ.Socket socketPushBD) {
        try {
            EventoCamara eventoCamara = eventos.getEventoCamara();
            EventoEspira eventoEspira = eventos.getEventoEspira();
            EventoGPS eventoGPS = eventos.getEventoGPS();

            // Obtener interseccion de cualquier evento disponible
            String interseccion = null;
            if (eventoCamara != null) interseccion = eventoCamara.getInterseccion();
            else if (eventoEspira != null) interseccion = eventoEspira.getInterseccion();
            else if (eventoGPS != null) interseccion = eventoGPS.getInterseccion();

            if (interseccion == null) {
                System.err.println("[ANALITICA] Error: No se pudo determinar la interseccion");
                return;
            }

            // Verificar si hay comando de prioridad del Monitoreo
            String estado;
            int duracionFase;
            String accionTomada;

            if (verificarPrioridad(interseccion)) {
                estado = "PRIORIDAD";
                duracionFase = ReglasCongestion.obtenerDuracionFase("PRIORIDAD");
                ComandoPrioridad cmd = comandosPrioridad.get(interseccion);
                accionTomada = "PRIORIDAD (" + cmd.tipoEvento + ") - " + cmd.razon;
                System.out.println("\n┌────────────────────────────────────────────┐");
                System.out.println("║    EVENTO PRIORITARIO DETECTADO             ║");
                System.out.println("║ Tipo: " + cmd.tipoEvento + " - " + cmd.comandoId + "    ");
                System.out.println("└────────────────────────────────────────────┘");
            } else {
                // Detectar estado de congestion basado en sensores disponibles
                estado = detectarCongestionFlexible(eventoCamara, eventoEspira, eventoGPS);
                duracionFase = ReglasCongestion.obtenerDuracionFase(estado);

                if ("CONGESTION".equals(estado)) {
                    accionTomada = "EXTENDER fase verde (tráfico congestionado)";
                    System.out.println("\n┌────────────────────────────────────────────┐");
                    System.out.println("│    CONGESTIoN DETECTADA                    │");
                    System.out.println("└────────────────────────────────────────────┘");
                } else {
                    accionTomada = "Fase verde normal";
                    System.out.println("\n┌────────────────────────────────────────────┐");
                    System.out.println("│ ✓ Tráfico NORMAL                            │");
                    System.out.println("└────────────────────────────────────────────┘");
                }
            }

            // Informacion detallada de sensores disponibles
            System.out.println("[ANALITICA] Análisis de Interseccion: " + interseccion);
            System.out.println("[ANALITICA] Sensores disponibles: " + eventos.obtenerSensoresDisponibles());

            if (eventoCamara != null) {
                System.out.println(String.format(" CÁMARA    - Cola: %d vehículos | Vel: %.1f km/h",
                    eventoCamara.getVolumen(), eventoCamara.getVelocidadPromedio()));
            }
            if (eventoEspira != null) {
                System.out.println(String.format(" ESPIRA    - Conteo: %d vehículos en %ds",
                    eventoEspira.getVehiculosContados(), eventoEspira.getIntervaloSegundos()));
            }
            if (eventoGPS != null) {
                System.out.println(String.format(" GPS       - Nivel congestion: %s | Velocidad: %.1f km/h",
                    eventoGPS.getNivelCongestion(), eventoGPS.getVelocidadPromedio()));
            }

            System.out.println("\n[ANALITICA] Decision de Analítica:");
            System.out.println("  Estado: " + estado);
            System.out.println("  Accion: " + accionTomada);
            System.out.println("  Duracion de fase: " + duracionFase + " segundos");

            // Crear comando para cambiar semáforo (JSON)
            String comandoSemaforoJson = objectMapper.writeValueAsString(Map.of(
                "interseccion", interseccion,
                "estado", estado,
                "duracion", duracionFase,
                "timestamp", Instant.now().toString()
            ));

            // Enviar solicitud a Control de Semáforos mediante REQ (sincronico con timeout)
            System.out.println("\n[ANALITICA] Enviando comando a Control de Semáforos...");
            try {
                socketReqSemaforoCtl.send(comandoSemaforoJson.getBytes(), 0);
                byte[] respuesta = socketReqSemaforoCtl.recv(0);
                if (respuesta != null) {
                    String respuestaStr = new String(respuesta);
                    System.out.println("[ANALITICA] ✓ REQ-REP exitoso | Respuesta: " + respuestaStr);
                } else {
                    System.out.println("[ANALITICA] ✓ Comando enviado a Control de Semáforos");
                }
            } catch (Exception e) {
                System.err.println("[ANALITICA]  Timeout en comunicacion con Control de Semáforos (" +
                    interseccion + "): " + e.getMessage());
            }

            // Enviar datos procesados a GestorBaseDatosReplica mediante PUSH
            System.out.println("[ANALITICA] Enviando datos procesados a Base de Datos...");
            try {
                Map<String, Object> datosProcesados = new HashMap<>();
                datosProcesados.put("interseccion", interseccion);
                datosProcesados.put("estado", estado);
                datosProcesados.put("timestamp", Instant.now().toString());

                // Incluir solo los datos disponibles
                if (eventoGPS != null) {
                    datosProcesados.put("velocidad_promedio", eventoGPS.getVelocidadPromedio());
                }
                if (eventoEspira != null) {
                    datosProcesados.put("densidad", eventoEspira.getVehiculosContados() * 100 / eventoEspira.getIntervaloSegundos());
                }
                if (eventoCamara != null) {
                    datosProcesados.put("cola", eventoCamara.getVolumen());
                }

                if (datosProcesados.get("estado") != "CONGESTION") {
                    String datosProcesadosJson = objectMapper.writeValueAsString(datosProcesados);
                    socketPushBD.send(datosProcesadosJson.getBytes(), 0);
                    System.out.println("[ANALITICA]   PUSH BD exitoso | " + interseccion + " | " +
                            Instant.now().toString());
                }
            } catch (Exception e) {
                System.err.println("[ANALITICA] Error enviando datos a BD: " + e.getMessage());
            }

            System.out.println("[ANALITICA] ==================================\n");

        } catch (Exception e) {
            System.err.println("[ANALITICA] Error procesando interseccion: " + e.getMessage());
        }
    }

    /**
     * Detecta congestion basada en los sensores disponibles (flexible).
     * - Si solo tiene CAMARA: analiza cola y velocidad
     * - Si solo tiene ESPIRA: analiza densidad
     * - Si solo tiene GPS: analiza velocidad
     * - Si tiene varios: combina informacion
     */
    private String detectarCongestionFlexible(EventoCamara camara, EventoEspira espira, EventoGPS gps) {
        ConfiguracionSistema config = ConfiguracionSistema.getInstancia();
        ConfiguracionSistema.Trafico traficoConfig = config.getTrafico();

        int umbralCola = traficoConfig.getUmbral_congestion_cola();
        int umbralVelocidad = traficoConfig.getUmbral_congestion_velocidad();
        int umbralDensidad = traficoConfig.getUmbral_congestion_densidad();

        int contadoresCongestion = 0;
        int contadoresTotales = 0;

        // Analizar CAMARA (cola y velocidad)
        if (camara != null) {
            contadoresTotales++;
            if (camara.getVolumen() >= umbralCola) {
                contadoresCongestion++;
                System.out.println("[ANALITICA]  CAMARA detecta congestion: cola=" + camara.getVolumen() + " >= " + umbralCola);
            }
            if (camara.getVelocidadPromedio() <= umbralVelocidad) {
                contadoresCongestion++;
                System.out.println("[ANALITICA]  CAMARA detecta congestion: velocidad=" + camara.getVelocidadPromedio() + " <= " + umbralVelocidad);
            }
        }

        // Analizar ESPIRA (densidad)
        if (espira != null) {
            contadoresTotales++;
            int densidad = espira.getVehiculosContados() * 100 / espira.getIntervaloSegundos();
            if (densidad >= umbralDensidad) {
                contadoresCongestion++;
                System.out.println("[ANALITICA]  ESPIRA detecta congestion: densidad=" + densidad + " >= " + umbralDensidad);
            }
        }

        // Analizar GPS (velocidad)
        if (gps != null) {
            contadoresTotales++;
            if (gps.getVelocidadPromedio() <= umbralVelocidad) {
                contadoresCongestion++;
                System.out.println("[ANALITICA]  GPS detecta congestion: velocidad=" + gps.getVelocidadPromedio() + " <= " + umbralVelocidad);
            }
        }

        // Si al menos el 50% de los sensores detecta congestion, es CONGESTION
        if (contadoresTotales > 0 && contadoresCongestion > 0) {
            // Si tenemos múltiples sensores, requiere mayoría
            if (contadoresTotales > 1) {
                if (contadoresCongestion * 2 >= contadoresTotales) {
                    return "CONGESTION";
                }
            } else {
                // Si solo hay un sensor, tomar su decision
                return "CONGESTION";
            }
        }

        return "NORMAL";
    }

    /**
     * Procesa un comando de prioridad del modulo de Monitoreo (PC3).
     * Estructura esperada del comando JSON:
     * {
     *   "comando_id": "CMD-20260406-0001",
     *   "intersecciones": ["INT-A1", "INT-B2"],
     *   "tipo_evento": "AMBULANCIA|BOMBEROS|POLICIA|EVENTO_ESPECIAL",
     *   "duracion_segundos": 40,
     *   "razon": "Ambulancia de emergencia",
     *   "timestamp": "2026-04-06T14:35:22.123Z"
     * }
     */
    private void procesarComandoMonitoreo(String comando) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> datos = objectMapper.readValue(comando, Map.class);

            String comandoId = (String) datos.get("comando_id");
            @SuppressWarnings("unchecked")
            List<String> intersecciones = (List<String>) datos.get("intersecciones");
            String tipoEvento = (String) datos.get("tipo_evento");
            Object duracionObj = datos.get("duracion_segundos");
            String razon = (String) datos.get("razon");
            String timestamp = (String) datos.get("timestamp");

            System.out.println("\n╔════════════════════════════════════════════╗");
            System.out.println("║  INDICACIoN DIRECTA DE MONITOREO (PC3)    ║");
            System.out.println("╚════════════════════════════════════════════╝");
            System.out.println("[MONITOREO] Comando recibido:");
            System.out.println("  - ID Comando: " + comandoId);
            System.out.println("  - Tipo Evento: " + tipoEvento);
            System.out.println("  - Intersecciones: " + intersecciones);
            System.out.println("  - Duracion: " + duracionObj + " segundos");
            System.out.println("  - Razon: " + razon);
            System.out.println("  - Timestamp PC3: " + timestamp);

            // Validar campos requeridos
            if (comandoId == null || comandoId.isEmpty()) {
                System.err.println("[MONITOREO] Error: Campo 'comando_id' requerido");
                return;
            }

            if (intersecciones == null || intersecciones.isEmpty()) {
                System.err.println("[MONITOREO] Error: Campo 'intersecciones' requerido y no vacío");
                return;
            }

            if (tipoEvento == null || tipoEvento.isEmpty()) {
                System.err.println("[MONITOREO] Error: Campo 'tipo_evento' requerido");
                return;
            }

            int duracion = 0;
            if (duracionObj != null) {
                try {
                    duracion = ((Number) duracionObj).intValue();
                } catch (Exception e) {
                    System.err.println("[MONITOREO] Error: 'duracion_segundos' debe ser un número");
                    return;
                }
            }

            // Registrar comando de prioridad para cada interseccion
            ComandoPrioridad cmd = new ComandoPrioridad(comandoId, tipoEvento, duracion, razon);

            System.out.println("[MONITOREO] Activando PRIORIDAD en " + intersecciones.size() + " intersecciones:");
            for (String interseccion : intersecciones) {
                comandosPrioridad.put(interseccion, cmd);
                System.out.println("  ✓ " + interseccion + " -> PRIORIDAD por " + duracion + "s (" + tipoEvento + ")");
            }

            System.out.println("[MONITOREO] ✓ Comando procesado exitosamente\n");

        } catch (Exception e) {
            System.err.println("[MONITOREO] Error procesando comando: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Verifica si hay un comando de prioridad activo y vigente para una interseccion.
     */
    private boolean verificarPrioridad(String interseccion) {
        ComandoPrioridad cmd = comandosPrioridad.get(interseccion);

        if (cmd == null) {
            return false;
        }

        // Verificar si el comando sigue siendo válido
        if (cmd.esValido()) {
            return true;
        } else {
            // Si expiro, eliminarlo
            comandosPrioridad.remove(interseccion);
            System.out.println("[ANALITICA] Comando de prioridad expirado para " + interseccion);
            return false;
        }
    }

    public void detener() {
        activo = false;
    }

    /**
     * Verifica y procesa intersecciones que hayan expirado por timeout (5 segundos sin nuevos eventos).
     * Esto garantiza que los datos se procesen aunque no lleguen todos los sensores.
     */
    private void verificarYProcesarTimeouts(Map<String, EventosInterseccion> bufferEventos,
                                            ZMQ.Socket socketReqSemaforoCtl, ZMQ.Socket socketPushBD) {
        List<String> interseccionesAEliminar = new ArrayList<>();

        for (Map.Entry<String, EventosInterseccion> entry : bufferEventos.entrySet()) {
            String interseccion = entry.getKey();
            EventosInterseccion eventos = entry.getValue();

            if (eventos.tiempoExpirado() && eventos.tieneEventos()) {
                System.out.println("[ANALITICA]   Timeout alcanzado para " + interseccion +
                    " - Procesando con sensores disponibles: " + eventos.obtenerSensoresDisponibles());
                procesarInterseccion(eventos, socketReqSemaforoCtl, socketPushBD);
                interseccionesAEliminar.add(interseccion);
            }
        }

        // Eliminar las que ya fueron procesadas
        for (String interseccion : interseccionesAEliminar) {
            bufferEventos.remove(interseccion);
        }
    }

    /**
     * Clase auxiliar para agrupar eventos de una interseccion.
     * Nota: Las intersecciones no tienen todos los sensores. Cada una tiene solo algunos.
     * Se procesan los eventos cuando se reciben (con timeout de 5 segundos de inactividad).
     */
    private static class EventosInterseccion {
        private EventoCamara eventoCamara;
        private EventoEspira eventoEspira;
        private EventoGPS eventoGPS;
        private long ultimaActualizacion;

        public EventosInterseccion() {
            this.ultimaActualizacion = System.currentTimeMillis();
        }

        public void setEventoCamara(EventoCamara evento) {
            this.eventoCamara = evento;
            this.ultimaActualizacion = System.currentTimeMillis();
        }

        public void setEventoEspira(EventoEspira evento) {
            this.eventoEspira = evento;
            this.ultimaActualizacion = System.currentTimeMillis();
        }

        public void setEventoGPS(EventoGPS evento) {
            this.eventoGPS = evento;
            this.ultimaActualizacion = System.currentTimeMillis();
        }

        public EventoCamara getEventoCamara() { return eventoCamara; }
        public EventoEspira getEventoEspira() { return eventoEspira; }
        public EventoGPS getEventoGPS() { return eventoGPS; }

        /**
         * Verifica si hay al menos UN evento disponible
         */
        public boolean tieneEventos() {
            return eventoCamara != null || eventoEspira != null || eventoGPS != null;
        }

        /**
         * Verifica si paso el timeout sin nuevos eventos (5 segundos)
         */
        public boolean tiempoExpirado() {
            return (System.currentTimeMillis() - ultimaActualizacion) > 5000;
        }

        /**
         * Retorna los sensores disponibles en esta interseccion
         */
        public String obtenerSensoresDisponibles() {
            StringBuilder sb = new StringBuilder();
            if (eventoCamara != null) sb.append("CAMARA ");
            if (eventoEspira != null) sb.append("ESPIRA ");
            if (eventoGPS != null) sb.append("GPS ");
            return sb.toString().trim();
        }
    }
}







