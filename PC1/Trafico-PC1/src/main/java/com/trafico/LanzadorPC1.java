package com.trafico;

import com.trafico.broker.ZeroMQ;
import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.ConfiguracionSistema.ConfigSensor;
import com.trafico.sensores.SensorCamara;
import com.trafico.sensores.SensorEspira;
import com.trafico.sensores.SensorGPS;
import com.trafico.sensores.SensorTrafico;

import java.util.ArrayList;
import java.util.List;

/**
 * Punto de entrada principal del PC1.
 *
 * Este lanzador permite dos modos de ejecución:
 *
 * 1. MODO INTEGRADO (modo legacy): Lanza el Broker ZMQ y todos los sensores en el mismo proceso.
 *    Uso: java -cp Trafico-PC1.jar com.trafico.LanzadorPC1 [ruta/config.json]
 *
 * 2. MODO SEPARADO: Los componentes pueden ejecutarse en procesos independientes.
 *    - Broker:   java -cp Trafico-PC1.jar com.trafico.LanzadorBroker [ruta/config.json]
 *    - Sensores: java -cp Trafico-PC1.jar com.trafico.LanzadorSensores [ruta/config.json]
 *
 * En modo separado, asegúrate de iniciar primero el Broker, luego los Sensores.
 *
 * Si no se especifica ruta, busca config.json en el directorio actual.
 */
public class LanzadorPC1 {

    public static void main(String[] args) throws Exception {

        // Cargar configuración.
        String rutaConfig = args.length > 0 ? args[0] : "config.json";
        ConfiguracionSistema cfg;

        try {
            cfg = ConfiguracionSistema.cargar(rutaConfig);
        } catch (Exception e) {
            System.out.println("[PC1] config.json no encontrado en disco, "
                    + "cargando desde recursos internos...");
            cfg = ConfiguracionSistema.cargarDesdeRecursos();
        }

        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║   GESTIÓN INTELIGENTE DE TRÁFICO URBANO    ║");
        System.out.println("║        PC1 - Sensores y Broker ZMQ         ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println("[PC1] Ciudad       : "
                + cfg.getCiudad().getFilas().size() + "x"
                + cfg.getCiudad().getColumnas().size()
                + " = " + cfg.getCiudad().getTotal_intersecciones()
                + " intersecciones");
        System.out.println("[PC1] Broker SUB   : puerto " + cfg.getBroker().getPuerto_sub());
        System.out.println("[PC1] Broker PUB   : puerto " + cfg.getBroker().getPuerto_pub());
        System.out.println("[PC1] PC2 Analítica: "
                + cfg.getServicios().getAnalitica().getHost()
                + ":" + cfg.getServicios().getAnalitica().getPuerto_pull());
        System.out.println("[PC1] PC3 BD       : "
                + cfg.getServicios().getBase_datos().getHost()
                + ":" + cfg.getServicios().getBase_datos().getPuerto());
        System.out.println("[PC1] Semáforo normal=" + cfg.getSemaforos().getDuracion_normal()
                + "s | congestión=" + cfg.getSemaforos().getDuracion_congestion()
                + "s | prioridad=" + cfg.getSemaforos().getDuracion_prioridad() + "s");
        System.out.println();

        String brokerAddr = "tcp://localhost:" + cfg.getBroker().getPuerto_sub();

        List<Thread> hilos = new ArrayList<>();
        List<SensorTrafico> sensores = new ArrayList<>();

        // Lanzar Broker ZMQ en hilo propio.
        ZeroMQ broker = new ZeroMQ(cfg);
        Thread hiloBroker = new Thread(broker::iniciar, "BrokerZMQ");
        hiloBroker.setDaemon(false);
        hiloBroker.start();
        hilos.add(hiloBroker);

        // Esperar a que el broker levante antes de que los sensores publiquen.
        System.out.println("[PC1] Esperando al broker...");
        Thread.sleep(1000);

        // Lanzar sensores de CÁMARA.
        for (ConfigSensor cs : cfg.getSensores().getCamaras()) {
            SensorTrafico sensor = new SensorCamara(cs, brokerAddr);
            sensores.add(sensor);
            Thread t = new Thread(sensor, cs.getSensor_id());
            t.setDaemon(true);
            t.start();
            hilos.add(t);
        }
        System.out.printf("[PC1] %d sensor(es) de cámara iniciados.%n", cfg.getSensores().getCamaras().size());

        // Lanzar sensores de ESPIRA.
        for (ConfigSensor cs : cfg.getSensores().getEspiras()) {
            SensorTrafico sensor = new SensorEspira(cs, brokerAddr);
            sensores.add(sensor);
            Thread t = new Thread(sensor, cs.getSensor_id());
            t.setDaemon(true);
            t.start();
            hilos.add(t);
        }
        System.out.printf("[PC1] %d sensor(es) de espira iniciados.%n", cfg.getSensores().getEspiras().size());

        // Lanzar sensores GPS.
        for (ConfigSensor cs : cfg.getSensores().getGps()) {
            SensorTrafico sensor = new SensorGPS(cs, brokerAddr);
            sensores.add(sensor);
            Thread t = new Thread(sensor, cs.getSensor_id());
            t.setDaemon(true);
            t.start();
            hilos.add(t);
        }
        System.out.printf("[PC1] %d sensor(es) GPS iniciados.%n", cfg.getSensores().getGps().size());
        System.out.printf("[PC1] %d sensor(es) GPS iniciados.%n", cfg.getSensores().getGps().size());

        System.out.println();
        System.out.println("[PC1] Sistema PC1 en ejecución. Ctrl+C para detener.");

        // Hook de apagado limpio.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[PC1] Apagando todos los componentes...");
            broker.detener();
            sensores.forEach(SensorTrafico::detener);
            hilos.forEach(Thread::interrupt);
            System.out.println("[PC1] PC1 detenido correctamente.");
        }));

        // Mantener proceso vivo mientras el broker corra.
        hiloBroker.join();
    }
}
