package com.trafico.sensores;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.ConfiguracionSistema.ConfigSensor;
import com.trafico.config.Topicos;
import com.trafico.modelos.EventoCamara;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.Random;

/**
 * Sensor tipo Cámara de Tráfico.
 *
 * Genera eventos EVENTO_LONGITUD_COLA periódicamente y los publica
 * en el broker ZMQ usando el patrón PUB/SUB.
 *
 * Variables simuladas:
 *   - volumen: [0, 20] vehículos en espera
 *   - velocidad_promedio:[5, 50] km/h
 *
 * Ejecución: java -cp Trafico-PC1.jar com.trafico.sensores.SensorCamara <config.json> <sensor_id>
 */
public class SensorCamara implements SensorTrafico {

    private final ConfigSensor config;
    private final String brokerAddress;
    private final Random random = new Random();
    private volatile boolean activo = true;

    public SensorCamara(ConfigSensor config, String brokerAddress) {
        this.config = config;
        this.brokerAddress = brokerAddress;
    }

    @Override
    public void run() {
        try (ZContext context = new ZContext()) {

            // Crear socket PUB y conectar al broker.
            ZMQ.Socket publisher = context.createSocket(SocketType.PUB);
            publisher.connect(brokerAddress);

            System.out.printf("[CAMARA] %s iniciado → publicando en %s%n",
                    config.getSensor_id(), brokerAddress);

            // Pequeña pausa para que ZMQ establezca la conexión antes de publicar.
            Thread.sleep(500);

            while (activo && !Thread.currentThread().isInterrupted()) {

                // Generar datos aleatorios simulados.
                int    volumen   = random.nextInt(21); // 0..20 vehículos.
                double velocidad = 5 + random.nextDouble() * 45; // 5..50 km/h.

                EventoCamara evento = new EventoCamara(
                        config.getSensor_id(),
                        config.getInterseccion(),
                        volumen,
                        Math.round(velocidad * 10.0) / 10.0
                );

                // Armar y enviar mensaje ZMQ: "TOPICO payload_json".
                String mensaje = Topicos.CAMARA + Topicos.SEPARADOR + evento.toJson();
                publisher.send(mensaje, 0);

                System.out.printf("[CAMARA] Publicado → %s%n", evento);

                // Esperar el intervalo configurado.
                Thread.sleep(config.getIntervalo_ms());
            }

            publisher.close();
            System.out.printf("[CAMARA] %s detenido.%n", config.getSensor_id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("[CAMARA] %s interrumpido.%n", config.getSensor_id());
        }
    }

    public void detener() {
        this.activo = false;
    }

    // Main, lanza UN sensor de cámara por argumento.
    public static void main(String[] args) throws Exception {
        String rutaConfig = args.length > 0 ? args[0] : "config.json";
        String sensorId   = args.length > 1 ? args[1] : null;

        ConfiguracionSistema cfg = ConfiguracionSistema.cargar(rutaConfig);

        String brokerAddr = "tcp://" + "localhost" + ":" + cfg.getBroker().getPuerto_sub();

        // Filtrar el sensor por ID si se especificó, o lanzar todos.
        cfg.getSensores().getCamaras().stream()
                .filter(s -> sensorId == null || s.getSensor_id().equals(sensorId))
                .forEach(s -> {
                    Thread t = new Thread(new SensorCamara(s, brokerAddr), s.getSensor_id());
                    t.setDaemon(true);
                    t.start();
                    System.out.printf("[CAMARA] Hilo iniciado: %s%n", s.getSensor_id());
                });

        // Mantener el proceso vivo.
        Thread.currentThread().join();
    }
}
