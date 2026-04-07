package com.trafico.servicios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;

import java.util.*;
import java.util.concurrent.*;

/**
 * Servicio de Control de Semáforos del PC2.
 *
 * Patrón REP: Responde a solicitudes de ServicioAnalitica mediante ZeroMQ en puerto 6001.
 * Mantiene un mapa de semáforos {intersección → estado (VERDE/ROJO)}.
 * Simula cambios de semáforo respetando duraciones de fase.
 */
public class ServicioControlSemaforos implements Runnable {
    private final ConfiguracionSistema config;
    private final Map<String, EstadoSemaforoInterseccion> semaforosPorInterseccion = Collections.synchronizedMap(new HashMap<>());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean activo = true;

    // ExecutorService para cambios de semáforo programados
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(4);

    public ServicioControlSemaforos() {
        this.config = ConfiguracionSistema.getInstancia();
    }

    @Override
    public void run() {
        System.out.println("[SEMAFOROCTL] Iniciando ServicioControlSemaForos...");

        // Inicializar semáforos en estado ROJO
        List<ConfiguracionSistema.ConfigSensor> sensores = config.getSensores().getCamaras();
        for (ConfiguracionSistema.ConfigSensor sensor : sensores) {
            String interseccion = sensor.getInterseccion();
            semaforosPorInterseccion.put(interseccion,
                new EstadoSemaforoInterseccion(interseccion, "ROJO", 0));
        }

        System.out.println("[SEMAFOROCTL] Semáforos inicializados.");

        try (ZContext context = new ZContext()) {
            // Socket REP para responder a solicitudes de ServicioAnalitica
            ZMQ.Socket socketRep = context.createSocket(SocketType.REP);
            String uriRep = "tcp://*:" + config.getServicios().getAnalitica().getPuerto_push_semaforoctl();
            socketRep.bind(uriRep);
            System.out.println("[SEMAFOROCTL] Socket REP enlazado en: " + uriRep);

            while (activo) {
                // Recibir solicitud sin bloquear
                String solicitud = socketRep.recvStr(ZMQ.DONTWAIT);

                if (solicitud != null) {
                    procesarComando(solicitud);

                    // Enviar respuesta
                    String respuesta = objectMapper.writeValueAsString(Map.of(
                        "status", "OK",
                        "mensaje", "Semáforo procesado",
                        "timestamp", System.currentTimeMillis()
                    ));
                    socketRep.send(respuesta.getBytes(), 0);
                } else {
                    Thread.sleep(100); // Pequeña pausa para no consumir CPU
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

    /**
     * Procesa un comando para cambiar el estado de un semáforo.
     */
    private void procesarComando(String comando) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> datosComando = objectMapper.readValue(comando, Map.class);

            String interseccion = (String) datosComando.get("interseccion");
            String estado = (String) datosComando.get("estado");
            int duracion = ((Number) datosComando.get("duracion")).intValue();

            EstadoSemaforoInterseccion semaforoActual = semaforosPorInterseccion.get(interseccion);
            if (semaforoActual == null) {
                System.err.println("[SEMAFOROCTL] Intersección desconocida: " + interseccion);
                return;
            }

            // Si ya estaba en VERDE, mantener estado pero actualizar duración
            // Si estaba en ROJO, cambiar a VERDE
            if (semaforoActual.estadoActual.equals("ROJO")) {
                cambiarSemaforoAVerde(interseccion, duracion);
            } else {
                // Ya está en VERDE, solo actualizar la duración remanente
                semaforoActual.duracionRemanente = duracion;
                System.out.println(String.format("[SEMAFOROCTL] %s | VERDE (actualizado) | duracion=%ds",
                    interseccion, duracion));
            }

        } catch (Exception e) {
            System.err.println("[SEMAFOROCTL] Error procesando comando: " + e.getMessage());
        }
    }

    /**
     * Cambia el semáforo a VERDE y programa su regreso a ROJO.
     */
    private void cambiarSemaforoAVerde(String interseccion, int duracion) {
        EstadoSemaforoInterseccion semaforoActual = semaforosPorInterseccion.get(interseccion);
        semaforoActual.estadoActual = "VERDE";
        semaforoActual.duracionRemanente = duracion;

        System.out.println(String.format("[SEMAFOROCTL] %s | VERDE | duracion=%ds",
            interseccion, duracion));

        // Programar cambio a ROJO después de la duración especificada
        scheduledExecutor.schedule(() -> cambiarSemaforoARojo(interseccion),
            duracion, TimeUnit.SECONDS);
    }

    /**
     * Cambia el semáforo a ROJO.
     */
    private void cambiarSemaforoARojo(String interseccion) {
        EstadoSemaforoInterseccion semaforoActual = semaforosPorInterseccion.get(interseccion);
        semaforoActual.estadoActual = "ROJO";
        semaforoActual.duracionRemanente = 0;

        System.out.println(String.format("[SEMAFOROCTL] %s | ROJO | esperando comando",
            interseccion));
    }

    /**
     * Obtiene el estado actual de un semáforo.
     */
    public String obtenerEstadoSemaforoInterseccion(String interseccion) {
        EstadoSemaforoInterseccion estado = semaforosPorInterseccion.get(interseccion);
        return estado != null ? estado.estadoActual : "DESCONOCIDO";
    }

    /**
     * Obtiene la duración remanente de la fase actual.
     */
    public int obtenerDuracionRemanente(String interseccion) {
        EstadoSemaforoInterseccion estado = semaforosPorInterseccion.get(interseccion);
        return estado != null ? estado.duracionRemanente : 0;
    }

    public void detener() {
        activo = false;
    }

    /**
     * Clase auxiliar para rastrear el estado de un semáforo.
     */
    private static class EstadoSemaforoInterseccion {
        String interseccion;
        String estadoActual; // "VERDE" o "ROJO"
        int duracionRemanente;

        EstadoSemaforoInterseccion(String interseccion, String estado, int duracion) {
            this.interseccion = interseccion;
            this.estadoActual = estado;
            this.duracionRemanente = duracion;
        }
    }
}

