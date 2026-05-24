package com.trafico.sensores;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.ConfiguracionSistema.ConfigSensor;
import com.trafico.config.Topicos;
import com.trafico.modelos.EventoEspira;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.Random;

/**
 * Sensor tipo Espira Inductiva.
 *
 * Genera eventos EVENTO_CONTEO_VEHICULAR cada 30 segundos (por defecto),
 * coincidiendo con los ciclos de cambio de semáforo, y los publica
 * en el broker ZMQ usando el patrón PUB/SUB.
 *
 * Variables simuladas:
 *   - vehiculos_contados: [0, 30] vehículos en el intervalo
 *   - intervalo_segundos: 30 (fijo, configurable en config.json)
 *
 * Ejecución: java -cp trafico-pc1.jar com.trafico.sensores.SensorEspira <config.json> [sensor_id]
 */
public class SensorEspira implements SensorTrafico {

    private final ConfigSensor config;
    private final String brokerAddress;
    private final Random random = new Random();
    private volatile boolean activo = true;

    public SensorEspira(ConfigSensor config, String brokerAddress) {
        this.config        = config;
        this.brokerAddress = brokerAddress;
    }

    @Override
    public void run() {
        try (ZContext context = new ZContext()) {

            // Crear socket PUB y conectar al broker.
            ZMQ.Socket publisher = context.createSocket(SocketType.PUB);
            publisher.connect(brokerAddress);

            System.out.printf("[ESPIRA] %s iniciada → publicando en %s%n",
                    config.getSensor_id(), brokerAddress);

            // Pausa inicial para que ZMQ establezca la conexión.
            Thread.sleep(500);

            while (activo && !Thread.currentThread().isInterrupted()) {

                // Generar datos aleatorios simulados.
                // Los vehículos contados se distribuyen con más probabilidad de
                // valores medios (simulando distribución realista de tráfico).
                int vehiculos = simularConteoVehicular();
                int intervalo = config.getIntervalo_ms() / 1000; // ms → segundos.

                EventoEspira evento = new EventoEspira(
                        config.getSensor_id(),
                        config.getInterseccion(),
                        vehiculos,
                        intervalo
                );

                // Armar y enviar mensaje ZMQ: "TOPICO payload_json".
                String mensaje = Topicos.ESPIRA + Topicos.SEPARADOR + evento.toJson();
                publisher.send(mensaje, 0);

                System.out.printf("[ESPIRA] Publicado → %s%n", evento);

                // Esperar el intervalo configurado (30s por defecto).
                Thread.sleep(config.getIntervalo_ms());
            }

            publisher.close();
            System.out.printf("[ESPIRA] %s detenida.%n", config.getSensor_id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("[ESPIRA] %s interrumpida.%n", config.getSensor_id());
        }
    }

    /**
     * Simula un conteo vehicular con distribución aproximadamente normal:
     * genera la media de varios uniformes para obtener distribución tipo campana.
     * Rango resultante: [0, 30] vehículos aprox.
     */
    private int simularConteoVehicular() {
        int suma = 0;
        for (int i = 0; i < 3; i++) suma += random.nextInt(11); // 3 × [0,10].
        return suma; // resultado en [0, 30].
    }

    public void detener() {
        this.activo = false;
    }

    /**
     * Punto de entrada para un proceso sensor individual.
     * args[0] = ruta config.json o "--resources"
     * args[1] = sensor_id (obligatorio)
     * Corre en el thread principal — este proceso ES el sensor.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Uso: SensorEspira <config|--resources> <sensor_id>");
            System.exit(1);
        }
        String configArg = args[0];
        String sensorId  = args[1];

        ConfiguracionSistema cfg;
        try {
            cfg = "--resources".equals(configArg)
                ? ConfiguracionSistema.cargarDesdeRecursos()
                : ConfiguracionSistema.cargar(configArg);
        } catch (Exception e) {
            try { cfg = ConfiguracionSistema.cargarDesdeRecursos(); }
            catch (Exception ex) { System.err.println("[ESPIRA] Error cargando config: " + ex.getMessage()); System.exit(1); return; }
        }

        ConfigSensor cs = cfg.getSensores().getEspiras().stream()
                .filter(s -> s.getSensor_id().equals(sensorId))
                .findFirst()
                .orElse(null);
        if (cs == null) { System.err.println("[ESPIRA] Sensor no encontrado: " + sensorId); System.exit(1); return; }

        String brokerAddr = "tcp://localhost:" + cfg.getBroker().getPuerto_sub();
        // Corre en el thread principal — visible como proceso separado en `ps`
        new SensorEspira(cs, brokerAddr).run();
    }
}
