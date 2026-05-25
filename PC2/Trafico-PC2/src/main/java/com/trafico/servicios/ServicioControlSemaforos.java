package com.trafico.servicios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Servicio de Control de Semáforos — PC2.
 *
 * Patrón REP (puerto 6001): recibe comandos de ServicioAnalitica.
 * Patrón PUB (conecta al broker en 5555): publica cambios de estado como
 *   "ESTADO_SEMAFORO {...}" para que el ServidorVisualizador los reciba.
 *
 * Seguridad vial: cada intersección tiene UNA dirección configurada
 * (NORTE-SUR o ESTE-OESTE). Cuando esa dirección pasa a VERDE,
 * la dirección opuesta queda explícitamente en ROJO.
 */
public class ServicioControlSemaforos implements Runnable {

    private final ConfiguracionSistema config;
    private final Map<String, EstadoSemaforoInterseccion> semaforos =
            Collections.synchronizedMap(new HashMap<>());
    private final ObjectMapper mapper = new ObjectMapper();
    private final Queue<String> publishQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private volatile boolean activo = true;

    public ServicioControlSemaforos() {
        this.config = ConfiguracionSistema.getInstancia();
    }

    @Override
    public void run() {
        System.out.println("[SEMAFOROCTL] Iniciando...");

        // Inicializar semáforos desde lista explícita del config (con dirección)
        for (ConfiguracionSistema.ConfigSemaforo cs : config.getSemaforos().getLista()) {
            semaforos.put(cs.getInterseccion(),
                new EstadoSemaforoInterseccion(
                    cs.getInterseccion(), cs.getSemaforo_id(), cs.getDireccion(), "ROJO", 0));
        }
        System.out.printf("[SEMAFOROCTL] %d semáforos inicializados en ROJO.%n", semaforos.size());

        try (ZContext ctx = new ZContext()) {

            // PUB → broker: publica cambios de estado al visualizador
            ZMQ.Socket socketPub = ctx.createSocket(SocketType.PUB);
            socketPub.connect("tcp://localhost:" + config.getBroker().getPuerto_sub());
            Thread.sleep(300); // ZMQ connection warm-up

            // REP → analytics: recibe comandos de cambio de fase
            ZMQ.Socket socketRep = ctx.createSocket(SocketType.REP);
            String uriBind = "tcp://*:" + config.getServicios().getAnalitica().getPuerto_push_semaforoctl();
            socketRep.bind(uriBind);
            System.out.println("[SEMAFOROCTL] REP enlazado en " + uriBind);

            while (activo) {
                // Drenar cola de publicaciones (llenada desde hilos del scheduler)
                String pub;
                while ((pub = publishQueue.poll()) != null) {
                    socketPub.send(pub, 0);
                }

                // Recibir comandos de analytics
                String solicitud = socketRep.recvStr(ZMQ.DONTWAIT);
                if (solicitud != null) {
                    procesarComando(solicitud);
                    socketRep.send(mapper.writeValueAsString(
                        Map.of("status", "OK", "ts", System.currentTimeMillis())), 0);
                } else {
                    Thread.sleep(10);
                }
            }

        } catch (Exception e) {
            System.err.println("[SEMAFOROCTL] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scheduler.shutdownNow();
            System.out.println("[SEMAFOROCTL] Finalizado.");
        }
    }

    private void procesarComando(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cmd = mapper.readValue(json, Map.class);
            String interseccion = (String) cmd.get("interseccion");
            int duracion = ((Number) cmd.get("duracion")).intValue();

            EstadoSemaforoInterseccion sem = semaforos.get(interseccion);
            if (sem == null) {
                System.err.println("[SEMAFOROCTL] Intersección desconocida: " + interseccion);
                return;
            }

            if ("ROJO".equals(sem.estadoActual)) {
                cambiarAVerde(sem, duracion);
            } else {
                sem.duracionRemanente = duracion;
                System.out.printf("[SEMAFOROCTL] %s | %s=VERDE actualizado | %ds%n",
                    interseccion, sem.direccion, duracion);
            }
        } catch (Exception e) {
            System.err.println("[SEMAFOROCTL] Error en comando: " + e.getMessage());
        }
    }

    private void cambiarAVerde(EstadoSemaforoInterseccion sem, int duracion) {
        sem.estadoActual = "VERDE";
        sem.duracionRemanente = duracion;
        System.out.printf("[SEMAFOROCTL] %s | %s | %s → VERDE | %s → ROJO (seg. vial) | %ds%n",
            sem.interseccion, sem.semaforoId, sem.direccion, sem.direccionOpuesta(), duracion);
        publicarEstado(sem);
        scheduler.schedule(() -> cambiarARojo(sem), duracion, TimeUnit.SECONDS);
    }

    private void cambiarARojo(EstadoSemaforoInterseccion sem) {
        sem.estadoActual = "ROJO";
        sem.duracionRemanente = 0;
        System.out.printf("[SEMAFOROCTL] %s | %s | ROJO — esperando comando%n",
            sem.interseccion, sem.semaforoId);
        publicarEstado(sem);
    }

    private void publicarEstado(EstadoSemaforoInterseccion sem) {
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("semaforo_id",        sem.semaforoId);
            ev.put("interseccion",        sem.interseccion);
            ev.put("estado",              sem.estadoActual);
            ev.put("direccion",           sem.direccion);
            ev.put("direccion_bloqueada", sem.direccionOpuesta());
            ev.put("duracion",            sem.duracionRemanente);
            ev.put("timestamp",           Instant.now().toString());
            publishQueue.add("ESTADO_SEMAFORO " + mapper.writeValueAsString(ev));
        } catch (Exception e) {
            System.err.println("[SEMAFOROCTL] Error publicando: " + e.getMessage());
        }
    }

    public String obtenerEstadoSemaforoInterseccion(String interseccion) {
        EstadoSemaforoInterseccion s = semaforos.get(interseccion);
        return s != null ? s.estadoActual : "DESCONOCIDO";
    }

    public int obtenerDuracionRemanente(String interseccion) {
        EstadoSemaforoInterseccion s = semaforos.get(interseccion);
        return s != null ? s.duracionRemanente : 0;
    }

    public void detener() { activo = false; }

    // ── Clase interna ──────────────────────────────────────────────────────────

    private static class EstadoSemaforoInterseccion {
        String interseccion;
        String semaforoId;
        String direccion;       // NORTE-SUR o ESTE-OESTE (del config.json)
        String estadoActual;    // VERDE o ROJO
        int    duracionRemanente;

        EstadoSemaforoInterseccion(String interseccion, String semaforoId,
                                   String direccion, String estado, int duracion) {
            this.interseccion     = interseccion;
            this.semaforoId       = semaforoId;
            this.direccion        = direccion;
            this.estadoActual     = estado;
            this.duracionRemanente = duracion;
        }

        /** La dirección perpendicular siempre está en ROJO cuando esta está en VERDE. */
        String direccionOpuesta() {
            return "NORTE-SUR".equals(direccion) ? "ESTE-OESTE" : "NORTE-SUR";
        }
    }
}
