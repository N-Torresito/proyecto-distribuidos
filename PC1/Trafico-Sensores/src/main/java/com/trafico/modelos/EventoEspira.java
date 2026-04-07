package com.trafico.modelos;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Modelo de evento generado por un sensor tipo Espira Inductiva.
 *
 * Mide: EVENTO_CONTEO_VEHICULAR (Cv)
 * - vehiculos_contados: número de vehículos que han pasado sobre la espira
 * - intervalo_segundos: duración del intervalo de medición (30s por defecto,
 *   coincidiendo con los cambios de semáforo)
 */
public class EventoEspira {

    @JsonProperty("sensor_id")
    private String sensorId;

    @JsonProperty("tipo_sensor")
    private String tipoSensor = "espira_inductiva";

    @JsonProperty("interseccion")
    private String interseccion;

    @JsonProperty("vehiculos_contados")
    private int vehiculosContados; // Nº de vehículos que cruzaron la espira.

    @JsonProperty("intervalo_segundos")
    private int intervaloSegundos; // Duración del intervalo (30s por defecto).

    @JsonProperty("timestamp_inicio")
    private String timestampInicio;

    @JsonProperty("timestamp_fin")
    private String timestampFin;

    // Constructor por defecto requerido por Jackson.
    public EventoEspira() {}

    public EventoEspira(String sensorId, String interseccion, int vehiculosContados, int intervaloSegundos) {
        this.sensorId = sensorId;
        this.interseccion = interseccion;
        this.vehiculosContados = vehiculosContados;
        this.intervaloSegundos = intervaloSegundos;
        Instant fin = Instant.now();
        this.timestampFin = fin.toString();
        this.timestampInicio = fin.minusSeconds(intervaloSegundos).toString();
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

    public int getVehiculosContados() { return vehiculosContados; }
    public void setVehiculosContados(int v) { this.vehiculosContados = v; }

    public int getIntervaloSegundos() { return intervaloSegundos; }
    public void setIntervaloSegundos(int v) { this.intervaloSegundos = v; }

    public String getTimestampInicio() { return timestampInicio; }
    public void setTimestampInicio(String v) { this.timestampInicio = v; }

    public String getTimestampFin() { return timestampFin; }
    public void setTimestampFin(String v) { this.timestampFin = v; }

    @Override
    public String toString() {
        return String.format("[ESPIRA] %s | %s | veh=%d | intervalo=%ds | %s → %s",
                sensorId, interseccion, vehiculosContados, intervaloSegundos, timestampInicio, timestampFin);
    }
}
