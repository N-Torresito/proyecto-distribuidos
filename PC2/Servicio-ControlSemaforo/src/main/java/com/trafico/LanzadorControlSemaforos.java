package com.trafico;

import com.trafico.servicios.ServicioControlSemaforos;
import com.trafico.config.ConfiguracionSistema;

/**
 * Lanzador para ServicioControlSemaforos (Independiente).
 *
 * Punto de entrada main que:
 * 1. Carga la configuración desde config.json
 * 2. Lanza ServicioControlSemaforos en un hilo
 * 3. Implementa hook de apagado limpio (Ctrl+C)
 *
 * Este programa se ejecuta en PC2 (192.168.1.2)
 * Responde a solicitudes REQ de ServicioAnalitica en puerto 6001
 * Gestiona estados de semáforos (VERDE/ROJO)
 */
public class LanzadorControlSemaforos {
    private static volatile boolean enEjecucion = true;

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║  Control de Semáforos (PC2)               ║");
        System.out.println("║  Programa Independiente                    ║");
        System.out.println("║  Gestión Inteligente de Tráfico Urbano    ║");
        System.out.println("║  2026-10                                   ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println();

        try {
            // Cargar configuración
            System.out.println("[LANZADOR_SEMAFOROCTL] Cargando configuración...");
            ConfiguracionSistema config = ConfiguracionSistema.cargarDesdeRecursos();
            System.out.println("[LANZADOR_SEMAFOROCTL] Configuración cargada:");
            System.out.println("  - Puerto REP (Responde a Analítica): " + config.getServicios().getAnalitica().getPuerto_push_semaforoctl());
            System.out.println();

            // Crear instancia de servicio
            System.out.println("[LANZADOR_SEMAFOROCTL] Inicializando ServicioControlSemaforos...");
            ServicioControlSemaforos servicioControlSemaForos = new ServicioControlSemaforos();

            // Lanzar hilo del servicio
            Thread hiloControlSemaForos = new Thread(servicioControlSemaForos, "Hilo-ServicioControlSemaForos");
            hiloControlSemaForos.setDaemon(false);

            System.out.println("[LANZADOR_SEMAFOROCTL] Lanzando servicio...");
            hiloControlSemaForos.start();

            System.out.println("[LANZADOR_SEMAFOROCTL] Servicio iniciado");
            System.out.println("[LANZADOR_SEMAFOROCTL] Presione Ctrl+C para detener...");
            System.out.println();

            // Hook de apagado limpio
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[LANZADOR_SEMAFOROCTL] Señal de apagado recibida (Ctrl+C)...");
                enEjecucion = false;

                // Detener servicio
                servicioControlSemaForos.detener();

                // Esperar a que termine el hilo
                try {
                    System.out.println("[LANZADOR_SEMAFOROCTL] Esperando a que el servicio finalice...");
                    hiloControlSemaForos.join(5000);
                    System.out.println("[LANZADOR_SEMAFOROCTL] ✓ Servicio detenido correctamente");
                } catch (InterruptedException e) {
                    System.err.println("[LANZADOR_SEMAFOROCTL] Interrumpido durante el apagado: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }

                System.out.println("[LANZADOR_SEMAFOROCTL] Programa finalizado.");
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
            System.err.println("[LANZADOR_SEMAFOROCTL] Error crítico: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
