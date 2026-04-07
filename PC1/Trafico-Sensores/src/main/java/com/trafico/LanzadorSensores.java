package com.trafico;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.ConfiguracionSistema.ConfigSensor;
import com.trafico.sensores.SensorCamara;
import com.trafico.sensores.SensorEspira;
import com.trafico.sensores.SensorGPS;
import com.trafico.sensores.SensorTrafico;

import java.util.ArrayList;
import java.util.List;

/**
 * Punto de entrada para ejecutar SOLO los sensores en un programa separado.
 *
 * Este lanzador permite ejecutar los sensores como un proceso independiente,
 * publicando eventos hacia el broker ZMQ (que debe estar en ejecución en
 * otra máquina/proceso).
 *
 * Los sensores se conectarán al broker especificado en config.json.
 *
 * Uso:
 *   java -cp Trafico-PC1.jar com.trafico.LanzadorSensores [ruta/config.json]
 *
 * Si no se especifica ruta, busca config.json en el directorio actual.
 */
public class LanzadorSensores {

    public static void main(String[] args) throws Exception {

        // Cargar configuración.
        String rutaConfig = args.length > 0 ? args[0] : "config.json";
        ConfiguracionSistema cfg;

        try {
            cfg = ConfiguracionSistema.cargar(rutaConfig);
        } catch (Exception e) {
            System.out.println("[SENSORES] config.json no encontrado en disco, "
                    + "cargando desde recursos internos...");
            cfg = ConfiguracionSistema.cargarDesdeRecursos();
        }

        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║        SENSORES DE TRÁFICO - PC1           ║");
        System.out.println("║   Generadores de Eventos (CÁMARA, ESPIRA, GPS)  ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println("[SENSORES] Ciudad       : "
                + cfg.getCiudad().getFilas().size() + "x"
                + cfg.getCiudad().getColumnas().size()
                + " = " + cfg.getCiudad().getTotal_intersecciones()
                + " intersecciones");
        System.out.println("[SENSORES] Broker       : localhost:"
                + cfg.getBroker().getPuerto_sub());
        System.out.println();

        String brokerAddr = "tcp://localhost:" + cfg.getBroker().getPuerto_sub();

        List<Thread> hilos = new ArrayList<>();
        List<SensorTrafico> sensores = new ArrayList<>();

        // Lanzar sensores de CÁMARA.
        for (ConfigSensor cs : cfg.getSensores().getCamaras()) {
            SensorTrafico sensor = new SensorCamara(cs, brokerAddr);
            sensores.add(sensor);
            Thread t = new Thread(sensor, cs.getSensor_id());
            t.setDaemon(false);
            t.start();
            hilos.add(t);
        }
        System.out.printf("[SENSORES] %d sensor(es) de cámara iniciados.%n",
                cfg.getSensores().getCamaras().size());

        // Lanzar sensores de ESPIRA.
        for (ConfigSensor cs : cfg.getSensores().getEspiras()) {
            SensorTrafico sensor = new SensorEspira(cs, brokerAddr);
            sensores.add(sensor);
            Thread t = new Thread(sensor, cs.getSensor_id());
            t.setDaemon(false);
            t.start();
            hilos.add(t);
        }
        System.out.printf("[SENSORES] %d sensor(es) de espira iniciados.%n",
                cfg.getSensores().getEspiras().size());

        // Lanzar sensores GPS.
        for (ConfigSensor cs : cfg.getSensores().getGps()) {
            SensorTrafico sensor = new SensorGPS(cs, brokerAddr);
            sensores.add(sensor);
            Thread t = new Thread(sensor, cs.getSensor_id());
            t.setDaemon(false);
            t.start();
            hilos.add(t);
        }
        System.out.printf("[SENSORES] %d sensor(es) GPS iniciados.%n",
                cfg.getSensores().getGps().size());

        System.out.println();
        System.out.printf("[SENSORES] Total de sensores activos: %d%n", sensores.size());
        System.out.println("[SENSORES] Sistema de sensores en ejecución. Ctrl+C para detener.");

        // Hook de apagado limpio.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SENSORES] Apagando todos los sensores...");
            sensores.forEach(SensorTrafico::detener);
            hilos.forEach(Thread::interrupt);
            System.out.println("[SENSORES] Sistema de sensores detenido correctamente.");
        }));

        // Mantener proceso vivo mientras haya sensores corriendo.
        for (Thread hilo : hilos) {
            hilo.join();
        }
    }
}

