package com.trafico;

import com.trafico.broker.ZeroMQ;
import com.trafico.config.ConfiguracionSistema;

/**
 * Punto de entrada para ejecutar SOLO el Broker ZMQ en un programa separado.
 *
 * Este lanzador permite ejecutar el broker como un proceso independiente,
 * desacoplado de los sensores. Los sensores se pueden lanzar desde otro
 * programa (LanzadorSensores) o manualmente.
 *
 * Uso:
 *   java -cp Trafico-PC1.jar com.trafico.LanzadorBroker [ruta/config.json]
 *
 * Si no se especifica ruta, busca config.json en el directorio actual.
 */
public class LanzadorBroker {

    public static void main(String[] args) throws Exception {

        // Cargar configuración.
        String rutaConfig = args.length > 0 ? args[0] : "config.json";
        ConfiguracionSistema cfg;

        try {
            cfg = ConfiguracionSistema.cargar(rutaConfig);
        } catch (Exception e) {
            System.out.println("[BROKER] config.json no encontrado en disco, "
                    + "cargando desde recursos internos...");
            cfg = ConfiguracionSistema.cargarDesdeRecursos();
        }

        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║          BROKER ZMQ - PC1                 ║");
        System.out.println("║   Gestor de Mensajes de Sensores          ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println("[BROKER] Broker SUB   : puerto " + cfg.getBroker().getPuerto_sub());
        System.out.println("[BROKER] Broker PUB   : puerto " + cfg.getBroker().getPuerto_pub());
        System.out.println("[BROKER] Destino PC2  : "
                + cfg.getBroker().getHost_pc2() + ":" + cfg.getBroker().getPuerto_pub());
        System.out.println();

        // Lanzar Broker ZMQ.
        ZeroMQ broker = new ZeroMQ(cfg);

        // Hook de apagado limpio con Ctrl+C.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[BROKER] Señal de apagado recibida. Cerrando...");
            broker.detener();
        }));

        System.out.println("[BROKER] Iniciando broker en modo independiente...");
        broker.iniciar();
    }
}

