package com.trafico.sensores;

/**
 * Interfaz base para todos los sensores de tráfico del sistema.
 *
 * Todos los sensores (Cámara, Espira, GPS) implementan esta interfaz,
 * lo que garantiza que pueden ejecutarse como hilos mediante Thread(Runnable).
 */
public interface SensorTrafico extends Runnable {
    /** Detiene el ciclo de generación de eventos del sensor */
    void detener();
}
