package com.trafico.modelos;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Modelo de evento generado por un sensor tipo Cámara de tráfico.
 *
 * Mide: EVENTO_LONGITUD_COLA (Lq)
 * - volumen: número de vehículos en espera ante el semáforo
 * - velocidad_promedio: velocidad media en la intersección (máx. 50 km/h)
 */
public class EventoCamara {
    @JsonProperty("sensor_id")
    private String sensorId;

    @JsonProperty("tipo_sensor")
    private String tipoSensor = "camara";

    @JsonProperty("interseccion")
    private String interseccion;

    @JsonProperty("volumen")
    private int volumen; // Nº de vehículos en espera.

    @JsonProperty("velocidad_promedio")
    private double velocidadPromedio; // km/h, máximo 50.

    @JsonProperty("timestamp")
    private String timestamp;

    // Constructor por defecto requerido por Jackson.
    public EventoCamara() {}

    public EventoCamara(String sensorId, String interseccion, int volumen, double velocidadPromedio) {
        this.sensorId  = sensorId;
        this.interseccion = interseccion;
        this.volumen = volumen;
        this.velocidadPromedio = velocidadPromedio;
        this.timestamp = Instant.now().toString();
    }

    /** Serializa el evento a formato JSON */
    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }

    // Getters y Setters.
    public String getSensorId() { return sensorId; }
    public void setSensorId(String v) { this.sensorId = v; }

    public String getTipoSensor() { return tipoSensor; }
    public void setTipoSensor(String v) { this.tipoSensor = v; }

    public String getInterseccion() { return interseccion; }
    public void setInterseccion(String v) { this.interseccion = v; }

    public int getVolumen() { return volumen; }
    public void setVolumen(int v) { this.volumen = v; }

    public double getVelocidadPromedio() { return velocidadPromedio; }
    public void setVelocidadPromedio(double v) { this.velocidadPromedio = v; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String v) { this.timestamp = v; }

    @Override
    public String toString() {
        return String.format("[CAMARA] %s | %s | vol=%d | vel=%.1f km/h | %s",
                sensorId, interseccion, volumen, velocidadPromedio, timestamp);
    }
}

