package com.trafico;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.ConfiguracionSistema.ConfigSensor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lanza cada sensor como un proceso JVM independiente via ProcessBuilder.
 * Cada proceso es visible en `ps aux` con su clase y sensor_id en la línea de comando.
 *
 * Uso:
 *   java -cp Trafico-PC1.jar com.trafico.LanzadorSensores [config.json]
 *   Si no se pasa config, carga desde classpath (--resources).
 */
public class LanzadorSensores {

    public static void main(String[] args) throws Exception {
        String configArg = args.length > 0 ? args[0] : "--resources";

        ConfiguracionSistema cfg;
        try {
            cfg = "--resources".equals(configArg)
                ? ConfiguracionSistema.cargarDesdeRecursos()
                : ConfiguracionSistema.cargar(configArg);
        } catch (Exception e) {
            System.out.println("[SENSORES] config.json no encontrado en disco, cargando desde recursos internos...");
            cfg = ConfiguracionSistema.cargarDesdeRecursos();
            configArg = "--resources";
        }

        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║        SENSORES DE TRÁFICO - PC1           ║");
        System.out.println("║  Cada sensor = proceso independiente       ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println("[SENSORES] Ciudad  : "
                + cfg.getCiudad().getFilas().size() + "x"
                + cfg.getCiudad().getColumnas().size()
                + " = " + cfg.getCiudad().getTotal_intersecciones() + " intersecciones");
        System.out.println("[SENSORES] Broker  : localhost:" + cfg.getBroker().getPuerto_sub());
        System.out.println();

        // Obtener ejecutable java y classpath del proceso actual
        String javaExe = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");
        final String cfgFinal = configArg;

        List<Process> procesos = new ArrayList<>();

        // Lanzar sensores de CÁMARA
        for (ConfigSensor cs : cfg.getSensores().getCamaras()) {
            Process p = spawn(javaExe, classpath, "com.trafico.sensores.SensorCamara", cfgFinal, cs.getSensor_id());
            procesos.add(p);
            System.out.printf("[SENSORES] PID %-6d  SensorCamara  %s%n", p.pid(), cs.getSensor_id());
        }

        // Lanzar sensores de ESPIRA
        for (ConfigSensor cs : cfg.getSensores().getEspiras()) {
            Process p = spawn(javaExe, classpath, "com.trafico.sensores.SensorEspira", cfgFinal, cs.getSensor_id());
            procesos.add(p);
            System.out.printf("[SENSORES] PID %-6d  SensorEspira  %s%n", p.pid(), cs.getSensor_id());
        }

        // Lanzar sensores GPS
        for (ConfigSensor cs : cfg.getSensores().getGps()) {
            Process p = spawn(javaExe, classpath, "com.trafico.sensores.SensorGPS", cfgFinal, cs.getSensor_id());
            procesos.add(p);
            System.out.printf("[SENSORES] PID %-6d  SensorGPS     %s%n", p.pid(), cs.getSensor_id());
        }

        System.out.println();
        System.out.printf("[SENSORES] %d procesos de sensor iniciados. " +
                "Verifica con: ps aux | grep SensorCamara%n", procesos.size());
        System.out.println("[SENSORES] Ctrl+C para detener todos.");

        // Apagado limpio: matar todos los hijos
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SENSORES] Terminando procesos de sensores...");
            procesos.forEach(Process::destroyForcibly);
            System.out.println("[SENSORES] Todos los sensores detenidos.");
        }));

        // Bloquear hasta que todos los procesos terminen
        for (Process p : procesos) {
            try { p.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private static Process spawn(String javaExe, String classpath,
                                  String mainClass, String config, String sensorId) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            javaExe,
            "-cp", classpath,
            "-Dtrafico.sensor.id=" + sensorId,   // visible en `ps -f` como propiedad JVM
            mainClass,
            config,
            sensorId
        );
        pb.inheritIO();  // stdout/stderr del sensor va al mismo terminal
        return pb.start();
    }
}
