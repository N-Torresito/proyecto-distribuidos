package com.trafico.broker;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.Topicos;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker ZeroMQ del PC1.
 *
 * Patrón de comunicación:
 *   Sensores → [PUB] → Broker [SUB] (puerto_sub)
 *   Broker [PUB] → [SUB] → Servicio de Analítica en PC2 (puerto_pub)
 *
 * Responsabilidades:
 *   1. Recibir todos los eventos de los tres tipos de sensores (PUB/SUB).
 *   2. Reenviar cada evento al nodo de procesamiento en PC2, actuando como
 *      intermediario desacoplado (los sensores no conocen la IP del PC2).
 *   3. Registrar estadísticas básicas de mensajes procesados.
 *
 * Diseño Single-Thread (diseño base del proyecto).
 * Ver diseño multihilo en ZMQMultihilo.java para las pruebas de rendimiento.
 *
 * Ejecución: java -cp trafico-pc1.jar com.trafico.broker.BrokerZMQ <config.json>
 */

public class ZeroMQ {
    private final ConfiguracionSistema config;
    private final AtomicLong mensajesRecibidos = new AtomicLong(0);
    private final AtomicLong mensajesEnviados  = new AtomicLong(0);
    private volatile boolean activo = true;

    public ZeroMQ(ConfiguracionSistema config) {
        this.config = config;
    }

    public void iniciar() {
        try (ZContext context = new ZContext()) {

            // Socket SUB: recibe eventos de los sensores (PC1 interno).
            ZMQ.Socket suscriptor = context.createSocket(SocketType.SUB);
            String puertoSub = "tcp://*:" + config.getBroker().getPuerto_sub();
            suscriptor.bind(puertoSub);

            // Suscribir a los tres tópicos de sensores.
            suscriptor.subscribe(Topicos.CAMARA.getBytes(ZMQ.CHARSET));
            suscriptor.subscribe(Topicos.ESPIRA.getBytes(ZMQ.CHARSET));
            suscriptor.subscribe(Topicos.GPS.getBytes(ZMQ.CHARSET));

            System.out.println("[BROKER] SUB escuchando en: " + puertoSub);
            System.out.println("[BROKER] Suscrito a tópicos: " + Topicos.CAMARA + ", " + Topicos.ESPIRA + ", " + Topicos.GPS);

            // Socket PUB, reenvía eventos al Servicio de Analítica en PC2.
            ZMQ.Socket publicador = context.createSocket(SocketType.PUB);
            publicador.bind("tcp://*:" + config.getBroker().getPuerto_pub());

            String puertoPC2 = "tcp://" + config.getBroker().getHost_pc2()+ ":" + config.getBroker().getPuerto_pub();
            System.out.println("[BROKER] PUB publicando en puerto: " + config.getBroker().getPuerto_pub());
            System.out.println("[BROKER] Destino PC2: " + puertoPC2);
            System.out.println("[BROKER] ─── Broker activo. Esperando eventos... ───");

            // Hilo de estadísticas: imprime contadores cada 30 segundos.
            iniciarHiloEstadisticas();

            // Bucle principal: recibir y reenviar.
            while (activo && !Thread.currentThread().isInterrupted()) {

                // Recibir mensaje completo: "TOPICO {json_payload}".
                String mensaje = suscriptor.recvStr(0);

                if (mensaje == null || mensaje.isBlank()) continue;

                mensajesRecibidos.incrementAndGet();

                // Extraer tópico (primera palabra) para log.
                String topico = mensaje.split(Topicos.SEPARADOR, 2)[0];

                // Reenviar el mensaje íntegro al PC2 (mismo formato).
                publicador.send(mensaje, 0);
                mensajesEnviados.incrementAndGet();

                System.out.printf("[BROKER] ► [%s] recibido y reenviado al PC2%n", topico);
            }

            suscriptor.close();
            publicador.close();
            System.out.println("[BROKER] Broker detenido.");
        }
    }

    /** Hilo secundario que imprime estadísticas cada 30 segundos */
    private void iniciarHiloEstadisticas() {
        Thread stats = new Thread(() -> {
            while (activo && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000);
                    System.out.printf(
                            "[BROKER] ── Estadísticas ── Recibidos: %d | Enviados: %d%n",
                            mensajesRecibidos.get(), mensajesEnviados.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "broker-stats");
        stats.setDaemon(true);
        stats.start();
    }

    public void detener() {
        this.activo = false;
    }

    // Main
    public static void main(String[] args) throws Exception {
        String rutaConfig = args.length > 0 ? args[0] : "config.json";

        System.out.println("[BROKER] Cargando configuración desde: " + rutaConfig);
        ConfiguracionSistema cfg = ConfiguracionSistema.cargar(rutaConfig);

        ZeroMQ broker = new ZeroMQ(cfg);

        // Hook de apagado limpio con Ctrl+C.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[BROKER] Señal de apagado recibida. Cerrando...");
            broker.detener();
        }));

        broker.iniciar();
    }
}
