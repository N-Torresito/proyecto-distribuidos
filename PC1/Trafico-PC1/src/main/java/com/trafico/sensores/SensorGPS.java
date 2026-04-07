package com.trafico.sensores;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.ConfiguracionSistema.ConfigSensor;
import com.trafico.config.Topicos;
import com.trafico.modelos.EventoGPS;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.Random;

/**
 * Sensor tipo GPS.
 *
 * Genera eventos EVENTO_DENSIDAD_DE_TRAFICO periódicamente y los publica
 * en el broker ZMQ usando el patrón PUB/SUB.
 *
 * Variables simuladas:
 *   - velocidad_promedio: [3, 50] km/h
 *   - nivel_congestion:   derivado de la velocidad según las reglas:
 *       ALTA   → vel < 10 km/h
 *       NORMAL → vel 11..39 km/h
 *       BAJA   → vel > 40 km/h
 *
 * Ejecución: java -cp Trafico-PC1.jar com.trafico.sensores.SensorGPS <config.json> [sensor_id]
 */
public class SensorGPS implements SensorTrafico {

    private final ConfigSensor config;
    private final String brokerAddress;
    private final Random random = new Random();
    private volatile boolean activo = true;

    // Estado de congestión simulado: permite simular horas pico realistas.
    private double velocidadActual = 35.0; // Arranca en tráfico normal.

    public SensorGPS(ConfigSensor config, String brokerAddress) {
        this.config        = config;
        this.brokerAddress = brokerAddress;
    }

    @Override
    public void run() {
        try (ZContext context = new ZContext()) {

            // Crear socket PUB y conectar al broker.
            ZMQ.Socket publisher = context.createSocket(SocketType.PUB);
            publisher.connect(brokerAddress);

            System.out.printf("[GPS] %s iniciado → publicando en %s%n",
                    config.getSensor_id(), brokerAddress);

            // Pausa inicial para que ZMQ establezca la conexión.
            Thread.sleep(500);

            while (activo && !Thread.currentThread().isInterrupted()) {

                // Simular evolución gradual de velocidad.
                // La velocidad cambia poco a poco, simulando tráfico dinámico.
                velocidadActual = simularVelocidadGradual(velocidadActual);

                EventoGPS evento = new EventoGPS(
                        config.getSensor_id(),
                        config.getInterseccion(),
                        Math.round(velocidadActual * 10.0) / 10.0
                );

                // Armar y enviar mensaje ZMQ: "TOPICO payload_json".
                String mensaje = Topicos.GPS + Topicos.SEPARADOR + evento.toJson();
                publisher.send(mensaje, 0);

                System.out.printf("[GPS] Publicado → %s%n", evento);

                // Esperar el intervalo configurado.
                Thread.sleep(config.getIntervalo_ms());
            }

            publisher.close();
            System.out.printf("[GPS] %s detenido.%n", config.getSensor_id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("[GPS] %s interrumpido.%n", config.getSensor_id());
        }
    }

    /**
     * Simula variación gradual de velocidad.
     * La velocidad no salta bruscamente sino que varía ±5 km/h por ciclo,
     * manteniéndose en el rango [3, 50] km/h.
     * Con probabilidad 15% ocurre un evento de congestión (bajada brusca).
     */
    private double simularVelocidadGradual(double velocidadAnterior) {
        double delta;

        // 15% de probabilidad de evento de congestión repentina.
        if (random.nextDouble() < 0.15) {
            delta = -(10 + random.nextDouble() * 15); // caída de 10..25 km/h.
        } else {
            // Variación normal: ±5 km/h.
            delta = (random.nextDouble() * 10) - 5;
        }

        double nueva = velocidadAnterior + delta;

        // Clamp al rango [3, 50].
        nueva = Math.max(3.0, Math.min(50.0, nueva));
        return nueva;
    }

    public void detener() {
        this.activo = false;
    }

    // Main: lanza UNO o TODOS los sensores GPS.
    public static void main(String[] args) throws Exception {
        String rutaConfig = args.length > 0 ? args[0] : "config.json";
        String sensorId = args.length > 1 ? args[1] : null;

        ConfiguracionSistema cfg = ConfiguracionSistema.cargar(rutaConfig);

        String brokerAddr = "tcp://" + "localhost" + ":" + cfg.getBroker().getPuerto_sub();

        cfg.getSensores().getGps().stream()
                .filter(s -> sensorId == null || s.getSensor_id().equals(sensorId))
                .forEach(s -> {
                    Thread t = new Thread(new SensorGPS(s, brokerAddr), s.getSensor_id());
                    t.setDaemon(true);
                    t.start();
                    System.out.printf("[GPS] Hilo iniciado: %s%n", s.getSensor_id());
                });

        Thread.currentThread().join();
    }
}
