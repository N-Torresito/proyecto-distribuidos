package com.trafico.utils;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.modelos.EventoCamara;
import com.trafico.modelos.EventoEspira;
import com.trafico.modelos.EventoGPS;

/**
 * Clase utilitaria que implementa las reglas de congestión.
 * Evalúa los eventos de sensores y determina el estado del tráfico.
 */
public class ReglasCongestion {
    private ReglasCongestion() {}

    /**
     * Detecta el nivel de congestión basado en los eventos de sensores.
     *
     * Reglas:
     * - PRIORIDAD: Si hay orden directa (no se evalúa aquí, viene de Monitoreo)
     * - CONGESTIÓN: Si cola >= umbral OR velocidad <= umbral OR densidad >= umbral
     * - NORMAL: Si cola < umbral AND velocidad > umbral AND densidad < umbral
     *
     * @param eventoCamara Evento de cámara (longitud de cola)
     * @param eventoEspira Evento de espira (conteo vehicular)
     * @param eventoGPS Evento de GPS (velocidad promedio)
     * @return "NORMAL", "CONGESTION" o "PRIORIDAD"
     */
    public static String detectarCongestion(EventoCamara eventoCamara, EventoEspira eventoEspira, EventoGPS eventoGPS) {
        ConfiguracionSistema config = ConfiguracionSistema.getInstancia();
        ConfiguracionSistema.Trafico traficoConfig = config.getTrafico();

        int umbralCola = traficoConfig.getUmbral_congestion_cola();
        int umbralVelocidad = traficoConfig.getUmbral_congestion_velocidad();
        int umbralDensidad = traficoConfig.getUmbral_congestion_densidad();

        int colaActual = eventoCamara.getVolumen();
        double velocidadActual = eventoGPS.getVelocidadPromedio();

        // Calcular densidad: relación entre vehículos contados e intervalo
        // densidad = vehículos / intervalo_segundos (aproximado)
        int densidadActual = (int) (eventoEspira.getVehiculosContados() * 100 / eventoEspira.getIntervaloSegundos());

        // Evaluar reglas de congestión
        boolean congestión = (colaActual >= umbralCola) ||
                           (velocidadActual <= umbralVelocidad) ||
                           (densidadActual >= umbralDensidad);

        if (congestión) {
            return "CONGESTION";
        } else {
            return "NORMAL";
        }
    }

    /**
     * Obtiene la duración de la fase de semáforo según el estado de tráfico.
     *
     * @param estado Estado del tráfico: "NORMAL", "CONGESTION", "PRIORIDAD"
     * @return Duración en segundos
     */
    public static int obtenerDuracionFase(String estado) {
        ConfiguracionSistema config = ConfiguracionSistema.getInstancia();
        ConfiguracionSistema.Semaforos semaforosConfig = config.getSemaforos();

        return switch (estado) {
            case "CONGESTION" -> semaforosConfig.getDuracion_congestion();
            case "PRIORIDAD" -> semaforosConfig.getDuracion_prioridad();
            default -> semaforosConfig.getDuracion_normal();
        };
    }
}


