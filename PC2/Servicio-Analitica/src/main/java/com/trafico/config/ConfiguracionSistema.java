package com.trafico.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Clase principal de configuración del sistema.
 * Carga y expone todos los parámetros desde config.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfiguracionSistema {
    private Ciudad ciudad;
    private Broker broker;
    private Semaforos semaforos;
    private Servicios servicios;
    private Sensores sensores;
    private Trafico trafico;

    // Instancia Singleton.
    private static ConfiguracionSistema instancia;

    public static ConfiguracionSistema cargar(String rutaArchivo) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        instancia = mapper.readValue(new File(rutaArchivo), ConfiguracionSistema.class);
        System.out.println("[CONFIG] Configuración cargada desde: " + rutaArchivo);
        return instancia;
    }

    /**
     * Carga desde el classpath (dentro del JAR).
     */
    public static ConfiguracionSistema cargarDesdeRecursos() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = ConfiguracionSistema.class.getClassLoader().getResourceAsStream("config.json");
        if (is == null) throw new IOException("No se encontró config.json en los recursos.");
        instancia = mapper.readValue(is, ConfiguracionSistema.class);
        System.out.println("[CONFIG] Configuración cargada desde recursos internos.");
        return instancia;
    }

    public static ConfiguracionSistema getInstancia() {
        if (instancia == null) throw new IllegalStateException("Configuración no inicializada.");
        return instancia;
    }

    // Getters y Setters.
    public Ciudad getCiudad() { return ciudad; }
    public void setCiudad(Ciudad ciudad) { this.ciudad = ciudad; }

    public Broker getBroker() { return broker; }
    public void setBroker(Broker broker) { this.broker = broker; }

    public Semaforos getSemaforos() { return semaforos; }
    public void setSemaforos(Semaforos semaforos) { this.semaforos = semaforos; }

    public Servicios getServicios() { return servicios; }
    public void setServicios(Servicios servicios) { this.servicios = servicios; }

    public Sensores getSensores() { return sensores; }
    public void setSensores(Sensores sensores) { this.sensores = sensores; }

    public Trafico getTrafico() { return trafico; }
    public void setTrafico(Trafico trafico) { this.trafico = trafico; }

    // Clases internas de configuración.

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ciudad {
        private List<String> filas;
        private List<Integer> columnas;
        private int total_intersecciones;

        public List<String> getFilas() { return filas; }
        public void setFilas(List<String> filas) { this.filas = filas; }
        public List<Integer> getColumnas() { return columnas; }
        public void setColumnas(List<Integer> columnas) { this.columnas = columnas; }
        public int getTotal_intersecciones() { return total_intersecciones; }
        public void setTotal_intersecciones(int v) { this.total_intersecciones = v; }

        @Override
        public String toString() {
            return "Ciudad{filas=" + filas + ", columnas=" + columnas + ", total=" + total_intersecciones + "}";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Broker {
        private int puerto_sub;
        private int puerto_pub;
        private String host_pc2;

        public int getPuerto_sub() { return puerto_sub; }
        public void setPuerto_sub(int puerto_sub) { this.puerto_sub = puerto_sub; }
        public int getPuerto_pub() { return puerto_pub; }
        public void setPuerto_pub(int puerto_pub) { this.puerto_pub = puerto_pub; }
        public String getHost_pc2() { return host_pc2; }
        public void setHost_pc2(String host_pc2) { this.host_pc2 = host_pc2; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sensores {
        private List<ConfigSensor> camaras;
        private List<ConfigSensor> espiras;
        private List<ConfigSensor> gps;

        public List<ConfigSensor> getCamaras() { return camaras; }
        public void setCamaras(List<ConfigSensor> camaras) { this.camaras = camaras; }
        public List<ConfigSensor> getEspiras() { return espiras; }
        public void setEspiras(List<ConfigSensor> espiras) { this.espiras = espiras; }
        public List<ConfigSensor> getGps() { return gps; }
        public void setGps(List<ConfigSensor> gps) { this.gps = gps; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigSensor {
        private String sensor_id;
        private String interseccion;
        private int intervalo_ms;

        public String getSensor_id() { return sensor_id; }
        public void setSensor_id(String sensor_id) { this.sensor_id = sensor_id; }
        public String getInterseccion() { return interseccion; }
        public void setInterseccion(String interseccion) { this.interseccion = interseccion; }
        public int getIntervalo_ms() { return intervalo_ms; }
        public void setIntervalo_ms(int intervalo_ms) { this.intervalo_ms = intervalo_ms; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trafico {
        private int velocidad_maxima_kmh;
        private int umbral_congestion_cola;
        private int umbral_congestion_velocidad;
        private int umbral_congestion_densidad;

        public int getVelocidad_maxima_kmh() { return velocidad_maxima_kmh; }
        public void setVelocidad_maxima_kmh(int v) { this.velocidad_maxima_kmh = v; }
        public int getUmbral_congestion_cola() { return umbral_congestion_cola; }
        public void setUmbral_congestion_cola(int v) { this.umbral_congestion_cola = v; }
        public int getUmbral_congestion_velocidad() { return umbral_congestion_velocidad; }
        public void setUmbral_congestion_velocidad(int v) { this.umbral_congestion_velocidad = v; }
        public int getUmbral_congestion_densidad() { return umbral_congestion_densidad; }
        public void setUmbral_congestion_densidad(int v) { this.umbral_congestion_densidad = v; }
    }

    // Semaforos, tiempos de fase por estado de tráfico.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Semaforos {
        private int duracion_normal; // segundos en fase verde — tráfico normal.
        private int duracion_congestion; // segundos en fase verde — congestión.
        private int duracion_prioridad; // segundos en fase verde — ola verde / ambulancia.

        public int getDuracion_normal() { return duracion_normal; }
        public void setDuracion_normal(int v) { this.duracion_normal = v; }
        public int getDuracion_congestion() { return duracion_congestion; }
        public void setDuracion_congestion(int v) { this.duracion_congestion = v; }
        public int getDuracion_prioridad() { return duracion_prioridad; }
        public void setDuracion_prioridad(int v) { this.duracion_prioridad = v; }
    }

    // Servicios, hosts y puertos de cada servicio del sistema.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Servicios {
        private ServicioAnalitica analitica;
        private ServicioBaseDatos base_datos;
        private ServicioMonitoreo monitoreo;

        public ServicioAnalitica getAnalitica() { return analitica; }
        public void setAnalitica(ServicioAnalitica v) { this.analitica = v; }
        public ServicioBaseDatos getBase_datos() { return base_datos; }
        public void setBase_datos(ServicioBaseDatos v) { this.base_datos = v; }
        public ServicioMonitoreo getMonitoreo() { return monitoreo; }
        public void setMonitoreo(ServicioMonitoreo v) { this.monitoreo = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServicioAnalitica {
        private String host;
        private int puerto_pull;
        private int puerto_push_semaforoctl;

        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPuerto_pull() { return puerto_pull; }
        public void setPuerto_pull(int v){ this.puerto_pull = v; }
        public int getPuerto_push_semaforoctl() { return puerto_push_semaforoctl; }
        public void setPuerto_push_semaforoctl(int v) { this.puerto_push_semaforoctl = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServicioBaseDatos {
        private String host;
        private int puerto;
        private String nombre;
        private String usuario;
        private String password;

        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPuerto() { return puerto; }
        public void setPuerto(int v) { this.puerto = v; }
        public String getNombre() { return nombre; }
        public void setNombre(String v) { this.nombre = v; }
        public String getUsuario() { return usuario; }
        public void setUsuario(String v) { this.usuario = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServicioMonitoreo {
        private int puerto_reqrep;

        public int getPuerto_reqrep() { return puerto_reqrep; }
        public void setPuerto_reqrep(int v) { this.puerto_reqrep = v; }

    }
}

