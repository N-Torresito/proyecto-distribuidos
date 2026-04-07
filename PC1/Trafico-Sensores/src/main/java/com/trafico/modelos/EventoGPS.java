package com.trafico.modelos;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Modelo de evento generado por un sensor tipo GPS.
 *
 * Mide: EVENTO_DENSIDAD_DE_TRAFICO (Dt)
 * - nivel_congestion: ALTA / NORMAL / BAJA según velocidad promedio
 *     ALTA   → velocidad < 10 km/h
 *     NORMAL → velocidad entre 11 y 39 km/h
 *     BAJA   → velocidad > 40 km/h
 * - velocidad_promedio: velocidad media detectada por GPS
 */
public class EventoGPS {
    @JsonProperty("sensor_id")
    private String sensorId;

    @JsonProperty("tipo_sensor")
    private String tipoSensor = "gps";

    @JsonProperty("interseccion")
    private String interseccion;

    @JsonProperty("nivel_congestion")
    private String nivelCongestion;   // "ALTA", "NORMAL", "BAJA".

    @JsonProperty("velocidad_promedio")
    private double velocidadPromedio; // km/h.

    @JsonProperty("timestamp")
    private String timestamp;

    // Constructor por defecto requerido por Jackson.
    public EventoGPS() {}

    public EventoGPS(String sensorId, String interseccion, double velocidadPromedio) {
        this.sensorId = sensorId;
        this.interseccion = interseccion;
        this.velocidadPromedio = velocidadPromedio;
        this.nivelCongestion = calcularNivelCongestion(velocidadPromedio);
        this.timestamp = Instant.now().toString();
    }

    /**
     * Calcula el nivel de congestión basado en la velocidad promedio.
     * Reglas del enunciado:
     *   ALTA   → vel < 10 km/h.
     *   NORMAL → vel entre 11 y 39 km/h.
     *   BAJA   → vel > 40 km/h  (tráfico fluido).
     */
    public static String calcularNivelCongestion(double velocidad) {
        if (velocidad < 10) {
            return "ALTA";
        } else if (velocidad <= 39) {
            return "NORMAL";
        } else {
            return "BAJA";
        }
    }

    /** Serializa el evento a formato JSON */
    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }

    // Getters y Setters
    public String getSensorId() { return sensorId; }
    public void setSensorId(String v) { this.sensorId = v; }

    public String getTipoSensor() { return tipoSensor; }
    public void setTipoSensor(String v) { this.tipoSensor = v; }

    public String getInterseccion() { return interseccion; }
    public void setInterseccion(String v) { this.interseccion = v; }

    public String getNivelCongestion() { return nivelCongestion; }
    public void setNivelCongestion(String v) { this.nivelCongestion = v; }

    public double getVelocidadPromedio() { return velocidadPromedio; }
    public void setVelocidadPromedio(double v) { this.velocidadPromedio = v; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String v) { this.timestamp = v; }

    @Override
    public String toString() {
        return String.format("[GPS] %s | %s | congestion=%s | vel=%.1f km/h | %s",
                sensorId, interseccion, nivelCongestion, velocidadPromedio, timestamp);
    }
}
