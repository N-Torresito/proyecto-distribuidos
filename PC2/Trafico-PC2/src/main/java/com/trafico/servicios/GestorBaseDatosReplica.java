package com.trafico.servicios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;

/**
 * Gestor de Base de Datos Réplica del PC2.
 *
 * Patrón PULL: Recibe datos de ServicioAnalitica en puerto 6000.
 * Actualiza BD PostgreSQL local (trafico_db) de forma asincrónica.
 * Implementa fallback/backup del PC2 (localhost) si la BD principal no está disponible.
 */
public class GestorBaseDatosReplica implements Runnable {
    private final ConfiguracionSistema config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile boolean activo = true;

    // Configuración de BD extraída de config.json
    private String DB_NAME;
    private String DB_USER;
    private String DB_PASSWORD;
    private String DB_PORT;
    private String DB_HOST_PRIMARY;
    private String DB_HOST_FALLBACK;

    private static final String SQL_INSERT =
        "INSERT INTO analisis_trafico (interseccion, estado, timestamp, velocidad_promedio, densidad, cola) " +
        "VALUES (?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT (interseccion) DO UPDATE SET " +
        "  estado = EXCLUDED.estado, " +
        "  timestamp = EXCLUDED.timestamp, " +
        "  velocidad_promedio = EXCLUDED.velocidad_promedio, " +
        "  densidad = EXCLUDED.densidad, " +
        "  cola = EXCLUDED.cola;";

    public GestorBaseDatosReplica() {
        this.config = ConfiguracionSistema.getInstancia();

        // Extraer configuración de BD desde config.json
        ConfiguracionSistema.ServicioBaseDatos bdConfig = config.getServicios().getBase_datos();
        this.DB_HOST_PRIMARY = bdConfig.getHost(); // BD principal en la misma máquina
        this.DB_HOST_FALLBACK = "localhost"; // Host de fallback (PC2)
        this.DB_PORT = String.valueOf(bdConfig.getPuerto());
        this.DB_NAME = bdConfig.getNombre();
        this.DB_USER = bdConfig.getUsuario();
        this.DB_PASSWORD = bdConfig.getPassword();
    }

    @Override
    public void run() {
        System.out.println("[BD_REPLICA] Iniciando GestorBaseDatosReplica...");

        // Intentar inicializar la base de datos
        inicializarBaseDatos();

        try (ZContext context = new ZContext()) {
            // Socket PULL para recibir datos de ServicioAnalitica
            ZMQ.Socket socketPull = context.createSocket(SocketType.PULL);
            String uriPull = "tcp://*:" + config.getServicios().getAnalitica().getPuerto_pull();
            socketPull.bind(uriPull);
            System.out.println("[BD_REPLICA] Socket PULL enlazado en: " + uriPull);

            while (activo) {
                String mensaje = socketPull.recvStr(ZMQ.DONTWAIT);

                if (mensaje != null) {
                    procesarYGuardarDato(mensaje);
                } else {
                    Thread.sleep(100); // Pequeña pausa para no consumir CPU
                }
            }

            System.out.println("[BD_REPLICA] Servicio finalizado.");

        } catch (Exception e) {
            System.err.println("[BD_REPLICA] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Inicializa la base de datos y la tabla si no existen.
     */
    private void inicializarBaseDatos() {
        try {
            // Cargar driver de PostgreSQL
            Class.forName("org.postgresql.Driver");

            // Intentar conectar a la BD principal (localhost)
            String urlPrincipal = "jdbc:postgresql://" + DB_HOST_PRIMARY + ":" + DB_PORT + "/" + DB_NAME;
            System.out.println("[BD_REPLICA] Intentando conectar a BD principal: " + DB_HOST_PRIMARY);
            Connection conn = DriverManager.getConnection(urlPrincipal, DB_USER, DB_PASSWORD);

            // Crear tabla si no existe
            String createTableSQL =
                "CREATE TABLE IF NOT EXISTS analisis_trafico (" +
                "  interseccion VARCHAR(50) PRIMARY KEY, " +
                "  estado VARCHAR(20) NOT NULL, " +
                "  timestamp VARCHAR(50) NOT NULL, " +
                "  velocidad_promedio DOUBLE PRECISION, " +
                "  densidad INTEGER, " +
                "  cola INTEGER, " +
                "  fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ");";

            try (PreparedStatement stmt = conn.prepareStatement(createTableSQL)) {
                stmt.execute();
            }

            conn.close();
            System.out.println("[BD_REPLICA] Base de datos inicializada correctamente.");

        } catch (ClassNotFoundException e) {
            System.err.println("[BD_REPLICA] Driver PostgreSQL no encontrado: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[BD_REPLICA] Advertencia: No se pudo conectar a BD principal en " + DB_HOST_PRIMARY +
                ". Configurado para intentar fallback a " + DB_HOST_FALLBACK + ": " + e.getMessage());
            // No interrumpir el flujo, el sistema puede continuar
        }
    }

    /**
     * Procesa y guarda un dato de análisis en la BD.
     */
    private void procesarYGuardarDato(String mensaje) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> datos = objectMapper.readValue(mensaje, Map.class);

            String interseccion = (String) datos.get("interseccion");
            String estado = (String) datos.get("estado");
            String timestamp = (String) datos.get("timestamp");
            double velocidadPromedio = ((Number) datos.get("velocidad_promedio")).doubleValue();
            int densidad = ((Number) datos.get("densidad")).intValue();
            int cola = ((Number) datos.get("cola")).intValue();

            guardarEnBD(interseccion, estado, timestamp, velocidadPromedio, densidad, cola);

        } catch (Exception e) {
            System.err.println("[BD_REPLICA] Error procesando dato: " + e.getMessage());
        }
    }

    /**
     * Guarda los datos en la base de datos PostgreSQL.
     */
    private void guardarEnBD(String interseccion, String estado, String timestamp,
                           double velocidadPromedio, int densidad, int cola) {
        Connection conn = null;
        try {
            // Intentar conectar a BD principal
            String urlBD = "jdbc:postgresql://" + DB_HOST_PRIMARY + ":" + DB_PORT + "/" + DB_NAME;
            conn = DriverManager.getConnection(urlBD, DB_USER, DB_PASSWORD);

            // Ejecutar INSERT
            try (PreparedStatement stmt = conn.prepareStatement(SQL_INSERT)) {
                stmt.setString(1, interseccion);
                stmt.setString(2, estado);
                stmt.setString(3, timestamp);
                stmt.setDouble(4, velocidadPromedio);
                stmt.setInt(5, densidad);
                stmt.setInt(6, cola);
                stmt.executeUpdate();
            }

            System.out.println(String.format("[BD_REPLICA] INSERT | %s | %s",
                interseccion, timestamp));

        } catch (Exception e) {
            System.err.println("[BD_REPLICA] Error guardando en BD principal (" + DB_HOST_PRIMARY +
                "): " + e.getMessage());
            // Intento fallback (PC3)
            intentarFallbackPC3(interseccion, estado, timestamp, velocidadPromedio, densidad, cola);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception e) {
                    System.err.println("[BD_REPLICA] Error cerrando conexión: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Intenta guardar en BD de respaldo (PC3 con host desde config).
     */
    private void intentarFallbackPC3(String interseccion, String estado, String timestamp,
                                     double velocidadPromedio, int densidad, int cola) {
        Connection conn = null;
        try {
            System.out.println("[BD_REPLICA] Intentando fallback a PC3 (" + DB_HOST_FALLBACK +
                ":" + DB_PORT + ")...");

            String urlFallback = "jdbc:postgresql://" + DB_HOST_FALLBACK + ":" + DB_PORT + "/" + DB_NAME;
            conn = DriverManager.getConnection(urlFallback, DB_USER, DB_PASSWORD);

            try (PreparedStatement stmt = conn.prepareStatement(SQL_INSERT)) {
                stmt.setString(1, interseccion);
                stmt.setString(2, estado);
                stmt.setString(3, timestamp);
                stmt.setDouble(4, velocidadPromedio);
                stmt.setInt(5, densidad);
                stmt.setInt(6, cola);
                stmt.executeUpdate();
            }

            System.out.println(String.format("[BD_REPLICA] FALLBACK PC3 (%s) | INSERT | %s",
                DB_HOST_FALLBACK, interseccion));

        } catch (Exception e) {
            System.err.println("[BD_REPLICA] Fallback PC3 (" + DB_HOST_FALLBACK +
                ") también falló: " + e.getMessage());
            // Almacenar en caché local o simplemente registrar
            System.out.println("[BD_REPLICA] Dato no persistido (caché perdida): " + interseccion);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception e) {
                    System.err.println("[BD_REPLICA] Error cerrando conexión fallback: " + e.getMessage());
                }
            }
        }
    }

    public void detener() {
        activo = false;
    }
}


