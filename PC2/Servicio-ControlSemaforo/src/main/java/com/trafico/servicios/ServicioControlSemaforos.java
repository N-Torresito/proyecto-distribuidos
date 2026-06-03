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
 * Servicio de Control de Semáforos del PC2 (módulo independiente).
 *
 * REP (puerto 6001): recibe comandos de ServicioAnalitica.
 * PUB → broker (puerto 5555): publica ESTADO_SEMAFORO al visualizador vía broker.
 * PUB directo (puerto 5557): publica ESTADO_SEMAFORO directamente al visualizador.
 */
public class ServicioControlSemaforos implements Runnable {

    private final ConfiguracionSistema config;
    private final Map<String, EstadoSemaforoInterseccion> semaforosPorInterseccion =
            Collections.synchronizedMap(new HashMap<>());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Queue<String> publishQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(4);
    private volatile boolean activo = true;

    public ServicioControlSemaforos() {
        this.config = ConfiguracionSistema.getInstancia();
    }

    @Override
    public void run() {
        System.out.println("[SEMAFOROCTL] Iniciando ServicioControlSemaforos...");

        for (ConfiguracionSistema.ConfigSemaforo sem : config.getSemaforos().getLista()) {
            semaforosPorInterseccion.put(sem.getInterseccion(),
                new EstadoSemaforoInterseccion(
                    sem.getInterseccion(), sem.getSemaforo_id(), sem.getDireccion(), "ROJO", 0));
        }
        System.out.printf("[SEMAFOROCTL] %d semáforos inicializados en ROJO.%n",
            semaforosPorInterseccion.size());

        try (ZContext context = new ZContext()) {

            // PUB → broker
            ZMQ.Socket socketPub = context.createSocket(SocketType.PUB);
            socketPub.connect("tcp://" + config.getBroker().getHost_pc2() + ":" + config.getBroker().getPuerto_sub());

            // PUB directo → visualizador (sin broker)
            ZMQ.Socket socketPubLocal = context.createSocket(SocketType.PUB);
            socketPubLocal.bind("tcp://*:5557");
            Thread.sleep(300);

            // REP → analytics
            ZMQ.Socket socketRep = context.createSocket(SocketType.REP);
            String uriRep = "tcp://*:" + config.getServicios().getAnalitica().getPuerto_push_semaforoctl();
            socketRep.bind(uriRep);
            System.out.println("[SEMAFOROCTL] REP enlazado en: " + uriRep);
            System.out.println("[SEMAFOROCTL] PUB directo enlazado en tcp://*:5557");

            while (activo) {
                // Drenar cola de publicaciones
                String pub;
                while ((pub = publishQueue.poll()) != null) {
                    socketPub.send(pub, 0);
                    socketPubLocal.send(pub, 0);
                }

                String solicitud = socketRep.recvStr(ZMQ.DONTWAIT);
                if (solicitud != null) {
                    procesarComando(solicitud);
                    String respuesta = objectMapper.writeValueAsString(Map.of(
                        "status", "OK",
                        "mensaje", "Semáforo procesado",
                        "timestamp", System.currentTimeMillis()
                    ));
                    socketRep.send(respuesta.getBytes(), 0);
                } else {
                    Thread.sleep(10);
                }
            }

        } catch (Exception e) {
            System.err.println("[SEMAFOROCTL] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scheduledExecutor.shutdownNow();
            System.out.println("[SEMAFOROCTL] Servicio finalizado.");
        }
    }

    private void procesarComando(String comando) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> datosComando = objectMapper.readValue(comando, Map.class);

            String interseccion = (String) datosComando.get("interseccion");
            int duracion = ((Number) datosComando.get("duracion")).intValue();

            EstadoSemaforoInterseccion sem = semaforosPorInterseccion.get(interseccion);
            if (sem == null) {
                System.err.println("[SEMAFOROCTL] Intersección desconocida: " + interseccion);
                return;
            }

            if ("ROJO".equals(sem.estadoActual)) {
                cambiarSemaforoAVerde(sem, duracion);
            } else {
                sem.duracionRemanente = duracion;
                System.out.printf("[SEMAFOROCTL] %s | VERDE (actualizado) | %ds%n",
                    interseccion, duracion);
            }

        } catch (Exception e) {
            System.err.println("[SEMAFOROCTL] Error procesando comando: " + e.getMessage());
        }
    }

    private void cambiarSemaforoAVerde(EstadoSemaforoInterseccion sem, int duracion) {
        sem.estadoActual = "VERDE";
        sem.duracionRemanente = duracion;
        System.out.printf("[SEMAFOROCTL] %s | %s | VERDE | %ds%n",
            sem.interseccion, sem.semaforoId, duracion);
        publicarEstado(sem);
        scheduledExecutor.schedule(() -> cambiarSemaforoARojo(sem), duracion, TimeUnit.SECONDS);
    }

    private void cambiarSemaforoARojo(EstadoSemaforoInterseccion sem) {
        sem.estadoActual = "ROJO";
        sem.duracionRemanente = 0;
        System.out.printf("[SEMAFOROCTL] %s | %s | ROJO%n", sem.interseccion, sem.semaforoId);
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
            publishQueue.add("ESTADO_SEMAFORO " + objectMapper.writeValueAsString(ev));
        } catch (Exception e) {
            System.err.println("[SEMAFOROCTL] Error publicando: " + e.getMessage());
        }
    }

    public String obtenerEstadoSemaforoInterseccion(String interseccion) {
        EstadoSemaforoInterseccion s = semaforosPorInterseccion.get(interseccion);
        return s != null ? s.estadoActual : "DESCONOCIDO";
    }

    public int obtenerDuracionRemanente(String interseccion) {
        EstadoSemaforoInterseccion s = semaforosPorInterseccion.get(interseccion);
        return s != null ? s.duracionRemanente : 0;
    }

    public void detener() { activo = false; }

    private static class EstadoSemaforoInterseccion {
        String interseccion;
        String semaforoId;
        String direccion;
        String estadoActual;
        int    duracionRemanente;

        EstadoSemaforoInterseccion(String interseccion, String semaforoId,
                                   String direccion, String estado, int duracion) {
            this.interseccion      = interseccion;
            this.semaforoId        = semaforoId;
            this.direccion         = direccion;
            this.estadoActual      = estado;
            this.duracionRemanente = duracion;
        }

        String direccionOpuesta() {
            return "NORTE-SUR".equals(direccion) ? "ESTE-OESTE" : "NORTE-SUR";
        }
    }
}
