package com.trafico;

import com.trafico.servicios.ServicioAnalitica;
import com.trafico.servicios.ServicioControlSemaforos;
import com.trafico.servicios.GestorBaseDatosReplica;
import com.trafico.config.ConfiguracionSistema;

/**
 * Lanzador Principal de PC2 (Servicio de Analítica, Control de Semáforos y BD Réplica).
 *
 * Punto de entrada main que:
 * 1. Carga la configuración desde config.json
 * 2. Lanza 3 hilos independientes:
 *    - Hilo 1: ServicioAnalitica (SUB + procesa + PUSH)
 *    - Hilo 2: ServicioControlSemaForos (gestiona semáforos)
 *    - Hilo 3: GestorBaseDatosReplica (PULL + DB)
 * 3. Implementa hook de apagado limpio (Ctrl+C)
 */
public class LanzadorPC2 {
    private static volatile boolean enEjecucion = true;

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║  PC2: Servicio de Analítica de Tráfico    ║");
        System.out.println("║  Gestión Inteligente de Tráfico Urbano    ║");
        System.out.println("║  2026-10                                   ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println();

        try {
            // Cargar configuración
            System.out.println("[LANZADOR] Cargando configuración...");
            ConfiguracionSistema config = ConfiguracionSistema.cargarDesdeRecursos();
            System.out.println("[LANZADOR] Configuración cargada:");
            System.out.println("  - Host PC2: " + config.getBroker().getHost_pc2());
            System.out.println("  - Puerto SUB (Broker): " + config.getBroker().getPuerto_sub());
            System.out.println("  - Puerto PUSH/PULL (Analítica→BD): " + config.getServicios().getAnalitica().getPuerto_pull());
            System.out.println("  - Puerto PUSH/PULL (Analítica→SemaforoCtl): " + config.getServicios().getAnalitica().getPuerto_push_semaforoctl());
            System.out.println();

            // Crear instancias de servicios
            System.out.println("[LANZADOR] Inicializando servicios...");
            ServicioAnalitica servicioAnalitica = new ServicioAnalitica();
            ServicioControlSemaforos servicioControlSemaForos = new ServicioControlSemaforos();
            GestorBaseDatosReplica gestorBaseDatos = new GestorBaseDatosReplica();

            // Lanzar hilos de servicios
            Thread hiloAnalitica = new Thread(servicioAnalitica, "Hilo-ServicioAnalitica");
            Thread hiloControlSemaForos = new Thread(servicioControlSemaForos, "Hilo-ServicioControlSemaForos");
            Thread hiloGestorBD = new Thread(gestorBaseDatos, "Hilo-GestorBaseDatosReplica");

            hiloAnalitica.setDaemon(false);
            hiloControlSemaForos.setDaemon(false);
            hiloGestorBD.setDaemon(false);

            System.out.println("[LANZADOR] Lanzando servicios...");
            hiloAnalitica.start();
            hiloControlSemaForos.start();
            hiloGestorBD.start();

            System.out.println("[LANZADOR] Todos los servicios iniciados");
            System.out.println("[LANZADOR] Presione Ctrl+C para detener...");
            System.out.println();

            // Hook de apagado limpio
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[LANZADOR] Señal de apagado recibida (Ctrl+C)...");
                enEjecucion = false;

                // Detener servicios
                servicioAnalitica.detener();
                servicioControlSemaForos.detener();
                gestorBaseDatos.detener();

                // Esperar a que terminen los hilos
                try {
                    System.out.println("[LANZADOR] Esperando a que los servicios finalicen...");
                    hiloAnalitica.join(5000);
                    hiloControlSemaForos.join(5000);
                    hiloGestorBD.join(5000);
                    System.out.println("[LANZADOR] ✓ Servicios detenidos correctamente");
                } catch (InterruptedException e) {
                    System.err.println("[LANZADOR] Interrumpido durante el apagado: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }

                System.out.println("[LANZADOR] PC2 finalizado.");
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
            System.err.println("[LANZADOR] Error crítico: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}


