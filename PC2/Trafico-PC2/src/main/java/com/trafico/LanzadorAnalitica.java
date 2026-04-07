package com.trafico;

import com.trafico.servicios.ServicioAnalitica;
import com.trafico.servicios.GestorBaseDatosReplica;
import com.trafico.config.ConfiguracionSistema;

/**
 * Lanzador para ServicioAnalitica + GestorBaseDatosReplica (Programa 1 Independiente).
 *
 * Punto de entrada main que:
 * 1. Carga la configuración desde config.json
 * 2. Lanza ServicioAnalitica en un hilo (procesa eventos + REQ semáforo)
 * 3. Lanza GestorBaseDatosReplica en otro hilo (recibe datos + gestiona BD)
 * 4. Implementa hook de apagado limpio (Ctrl+C)
 *
 * Este programa se ejecuta en PC2 (192.168.1.2)
 * Recibe eventos del broker ZMQ en puerto 5556
 * Envía comandos a Control de Semáforos en puerto 6000 (REQ)
 * Envía datos a GestorBD en puerto 6000 (PUSH)
 * Se conecta a PostgreSQL en PC3 (192.168.1.3)
 */
public class LanzadorAnalitica {
    private static volatile boolean enEjecucion = true;

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║  Analítica + BD Réplica (PC2)             ║");
        System.out.println("║  Programa 1 Independiente                 ║");
        System.out.println("║  Gestión Inteligente de Tráfico Urbano    ║");
        System.out.println("║  2026-10                                   ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println();

        try {
            // Cargar configuración
            System.out.println("[LANZADOR_ANALITICA] Cargando configuración...");
            ConfiguracionSistema config = ConfiguracionSistema.cargarDesdeRecursos();
            System.out.println("[LANZADOR_ANALITICA] Configuración cargada:");
            System.out.println("  - Host PC2: " + config.getBroker().getHost_pc2());
            System.out.println("  - Puerto SUB (Broker): " + config.getBroker().getPuerto_sub());
            System.out.println("  - Puerto REQ/REP (Analítica↔Semáforo): " + config.getServicios().getAnalitica().getPuerto_pull());
            System.out.println("  - Puerto PUSH/PULL (Analítica→BD): " + config.getServicios().getAnalitica().getPuerto_pull());
            System.out.println("  - BD Host: " + config.getServicios().getBase_datos().getHost());
            System.out.println();

            // Crear instancias de servicios
            System.out.println("[LANZADOR_ANALITICA] Inicializando servicios...");
            ServicioAnalitica servicioAnalitica = new ServicioAnalitica();
            GestorBaseDatosReplica gestorBaseDatos = new GestorBaseDatosReplica();

            // Lanzar hilos de servicios
            Thread hiloAnalitica = new Thread(servicioAnalitica, "Hilo-ServicioAnalitica");
            Thread hiloGestorBD = new Thread(gestorBaseDatos, "Hilo-GestorBaseDatosReplica");

            hiloAnalitica.setDaemon(false);
            hiloGestorBD.setDaemon(false);

            System.out.println("[LANZADOR_ANALITICA] Lanzando servicios...");
            hiloAnalitica.start();
            hiloGestorBD.start();

            System.out.println("[LANZADOR_ANALITICA] Todos los servicios iniciados");
            System.out.println("[LANZADOR_ANALITICA] Presione Ctrl+C para detener...");
            System.out.println();

            // Hook de apagado limpio
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[LANZADOR_ANALITICA] Señal de apagado recibida (Ctrl+C)...");
                enEjecucion = false;

                // Detener servicios
                servicioAnalitica.detener();
                gestorBaseDatos.detener();

                // Esperar a que terminen los hilos
                try {
                    System.out.println("[LANZADOR_ANALITICA] Esperando a que los servicios finalicen...");
                    hiloAnalitica.join(5000);
                    hiloGestorBD.join(5000);
                    System.out.println("[LANZADOR_ANALITICA] ✓ Servicios detenidos correctamente");
                } catch (InterruptedException e) {
                    System.err.println("[LANZADOR_ANALITICA] Interrumpido durante el apagado: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }

                System.out.println("[LANZADOR_ANALITICA] Programa finalizado.");
            }, "Hilo-Apagado"));

            // Mantener el proceso principal activo
            while (enEjecucion) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    enEjecucion = false;
                    Thread.currentThread().interrupt();
                }
            }

        } catch (Exception e) {
            System.err.println("[LANZADOR_ANALITICA] Error crítico: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
