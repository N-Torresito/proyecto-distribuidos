package com.trafico;

import com.trafico.broker.ZeroMQ;
import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.ConfiguracionSistema.ConfigSensor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Punto de entrada principal del PC1.
 *
 * Lanza el Broker ZMQ como hilo y cada sensor como proceso JVM independiente.
 * Los sensores son visibles en `ps aux` como procesos separados.
 *
 * Uso: mvn exec:java  (usa com.trafico.LanzadorPC1 como mainClass)
 *
 * Si no se especifica ruta, carga config.json desde classpath (recursos internos).
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

        // Lanzar Broker ZMQ en hilo propio (el broker vive en este proceso).
        ZeroMQ broker = new ZeroMQ(cfg);
        Thread hiloBroker = new Thread(broker::iniciar, "BrokerZMQ");
        hiloBroker.setDaemon(false);
        hiloBroker.start();

        // Esperar a que el broker levante antes de que los sensores publiquen.
        System.out.println("[PC1] Esperando al broker...");
        Thread.sleep(1000);

        // Obtener ejecutable java y classpath del proceso actual para spawnar hijos.
        String javaExe   = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");
        // Si se pasó un archivo de config, los hijos lo usarán; si no, usan recursos internos.
        String cfgArg = (args.length > 0) ? args[0] : "--resources";

        List<Process> procesos = new ArrayList<>();

        // Lanzar sensores de CÁMARA como procesos independientes.
        for (ConfigSensor cs : cfg.getSensores().getCamaras()) {
            Process p = spawn(javaExe, classpath, "com.trafico.sensores.SensorCamara", cfgArg, cs.getSensor_id());
            procesos.add(p);
            System.out.printf("[PC1] PID %-6d  SensorCamara  %s%n", p.pid(), cs.getSensor_id());
        }

        // Lanzar sensores de ESPIRA como procesos independientes.
        for (ConfigSensor cs : cfg.getSensores().getEspiras()) {
            Process p = spawn(javaExe, classpath, "com.trafico.sensores.SensorEspira", cfgArg, cs.getSensor_id());
            procesos.add(p);
            System.out.printf("[PC1] PID %-6d  SensorEspira  %s%n", p.pid(), cs.getSensor_id());
        }

        // Lanzar sensores GPS como procesos independientes.
        for (ConfigSensor cs : cfg.getSensores().getGps()) {
            Process p = spawn(javaExe, classpath, "com.trafico.sensores.SensorGPS", cfgArg, cs.getSensor_id());
            procesos.add(p);
            System.out.printf("[PC1] PID %-6d  SensorGPS     %s%n", p.pid(), cs.getSensor_id());
        }

        System.out.println();
        System.out.printf("[PC1] Broker + %d procesos sensor iniciados. " +
                "Verifica: ps aux | grep SensorCamara%n", procesos.size());
        System.out.println("[PC1] Ctrl+C para detener todo.");

        // Hook de apagado limpio.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[PC1] Apagando todos los componentes...");
            broker.detener();
            procesos.forEach(Process::destroyForcibly);
            System.out.println("[PC1] PC1 detenido correctamente.");
        }));

        // Mantener proceso vivo mientras el broker corra.
        hiloBroker.join();
    }

    private static Process spawn(String javaExe, String classpath,
                                  String mainClass, String config, String sensorId) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            javaExe,
            "-cp", classpath,
            "-Dtrafico.sensor.id=" + sensorId,
            mainClass,
            config,
            sensorId
        );
        pb.inheritIO();
        return pb.start();
    }
}
