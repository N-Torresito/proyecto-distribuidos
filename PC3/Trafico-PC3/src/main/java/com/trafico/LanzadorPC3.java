package com.trafico;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.servicios.ServicioMonitoreo;

/**
 * Lanzador de PC3 - Punto de entrada de la aplicación.
 * Inicializa la configuración y lanza el servicio de monitoreo.
 */
public class LanzadorPC3 {
    private static final String PREFIJO_LOG = "[LANZADOR]";
    private static ServicioMonitoreo servicio;

    public static void main(String[] args) {
        try {
            mostrarBanner();

            System.out.println(PREFIJO_LOG + " Cargando configuración...");
            ConfiguracionSistema config = ConfiguracionSistema.cargarDesdeRecursos();

            mostrarConfiguracion(config);

            System.out.println(PREFIJO_LOG + " Inicializando servicios...");
            servicio = new ServicioMonitoreo(config);

            // Crear hilo para el servicio
            Thread hilo = new Thread(servicio, "Hilo-ServicioMonitoreo");
            hilo.setDaemon(false);
            hilo.start();

            System.out.println(PREFIJO_LOG + " ✓ Servicio iniciado");
            System.out.println(PREFIJO_LOG + " Presione Ctrl+C para detener...\n");

            // Instalar hook de apagado limpio
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n" + PREFIJO_LOG + " Señal de apagado recibida (Ctrl+C)...");
                if (servicio != null) {
                    servicio.detener();
                }
                System.out.println(PREFIJO_LOG + " ✓ Servicio detenido correctamente");
                System.out.println(PREFIJO_LOG + " PC3 finalizado.");
            }));

            // Mantener la aplicación viva
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            System.err.println(PREFIJO_LOG + " Error fatal: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Muestra el banner ASCII
     */
    private static void mostrarBanner() {
        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║  PC3: Servicio de Monitoreo y Consulta     ║");
        System.out.println("║  Gestión Inteligente de Tráfico Urbano     ║");
        System.out.println("║  2026-10                                   ║");
        System.out.println("╚════════════════════════════════════════════╝\n");
    }

    /**
     * Muestra la configuración cargada
     */
    private static void mostrarConfiguracion(ConfiguracionSistema config) {
        System.out.println(PREFIJO_LOG + " Configuración cargada:");
        int puertoMonitoreo = config.getServicios().getMonitoreo().getPuerto_reqrep();
        String hostAnalitica = config.getServicios().getAnalitica().getHost();
        int puertoAnalitica = config.getServicios().getMonitoreo().getPuerto_reqrep();
        String hostBD = config.getServicios().getBase_datos().getHost();
        int puertoBD = config.getServicios().getBase_datos().getPuerto();
        String nombreBD = config.getServicios().getBase_datos().getNombre();

        System.out.println("  - Puerto Monitoreo (REQ/REP): " + puertoMonitoreo);
        System.out.println("  - Host Analítica: " + hostAnalitica);
        System.out.println("  - Puerto Analítica (Prioridad): " + puertoAnalitica);
        System.out.println("  - BD: " + nombreBD + " en " + hostBD + ":" + puertoBD);
        System.out.println();
    }
}

