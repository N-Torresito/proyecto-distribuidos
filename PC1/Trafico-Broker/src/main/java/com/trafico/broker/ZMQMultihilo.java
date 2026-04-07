package com.trafico.broker;

import com.trafico.config.ConfiguracionSistema;
import com.trafico.config.Topicos;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker ZeroMQ MULTIHILO del PC1.
 *
 * Versión extendida para las pruebas de rendimiento (Tabla 1 del enunciado).
 * Usa un pool de hilos para procesar y reenviar mensajes en paralelo,
 * mejorando el throughput cuando hay múltiples sensores activos.
 *
 * Arquitectura:
 *   - 1 hilo receptor: lee mensajes del socket SUB y los pone en una cola
 *   - N hilos workers: toman mensajes de la cola y los reenvían por el socket PUB
 *
 * Nota: ZeroMQ requiere que un socket sea usado siempre desde el mismo hilo
 * (thread-safety no garantizada por socket). Por eso el PUB de reenvío usa
 * un socket exclusivo por worker mediante PUSH/PULL interno.
 *
 * Flujo interno:
 *   [SUB receptor] → [Cola interna] → [Workers] → [PUSH interno] → [PULL-PUB reenviador]
 *
 * Ejecución: java -cp Trafico-PC1.jar com.trafico.broker.ZMQMultihilo <config.json> [num_workers]
 */
public class ZMQMultihilo {
    private static final int WORKERS_DEFAULT = 4;
    private static final int PUERTO_INTERNO_WORKER = 5600; // PUSH/PULL interno.

    private final ConfiguracionSistema config;
    private final int numWorkers;
    private final AtomicLong mensajesRecibidos = new AtomicLong(0);
    private final AtomicLong mensajesEnviados  = new AtomicLong(0);
    private volatile boolean activo = true;

    public ZMQMultihilo(ConfiguracionSistema config, int numWorkers) {
        this.config = config;
        this.numWorkers = numWorkers;
    }

    public void iniciar() throws InterruptedException {
        try (ZContext context = new ZContext()) {

            // Socket SUB, recibe de sensores.
            ZMQ.Socket suscriptor = context.createSocket(SocketType.SUB);
            suscriptor.bind("tcp://*:" + config.getBroker().getPuerto_sub());
            suscriptor.subscribe(Topicos.CAMARA.getBytes(ZMQ.CHARSET));
            suscriptor.subscribe(Topicos.ESPIRA.getBytes(ZMQ.CHARSET));
            suscriptor.subscribe(Topicos.GPS.getBytes(ZMQ.CHARSET));
            System.out.printf("[BROKER-MT] SUB en puerto %d%n", config.getBroker().getPuerto_sub());

            // Socket PUSH interno: distribuye a workers.
            ZMQ.Socket pushInterno = context.createSocket(SocketType.PUSH);
            pushInterno.bind("tcp://127.0.0.1:" + PUERTO_INTERNO_WORKER);

            // Socket PUB final: reenvía al PC2.
            ZMQ.Socket publicador = context.createSocket(SocketType.PUB);
            publicador.bind("tcp://*:" + config.getBroker().getPuerto_pub());
            System.out.printf("[BROKER-MT] PUB en puerto %d → PC2%n", config.getBroker().getPuerto_pub());

            // Lanzar N workers.
            ExecutorService pool = Executors.newFixedThreadPool(numWorkers);
            for (int i = 0; i < numWorkers; i++) {
                final int workerId = i;
                pool.submit(() -> ejecutarWorker(context, workerId, publicador));
            }

            System.out.printf("[BROKER-MT] %d workers activos. Esperando eventos...%n", numWorkers);

            // Hilo de estadísticas.
            iniciarHiloEstadisticas();

            // Bucle receptor, recibe y distribuye a workers.
            while (activo && !Thread.currentThread().isInterrupted()) {
                String mensaje = suscriptor.recvStr(0);
                if (mensaje == null || mensaje.isBlank()) continue;

                mensajesRecibidos.incrementAndGet();
                pushInterno.send(mensaje, 0); // delegar al worker disponible.
            }

            pool.shutdownNow();
            suscriptor.close();
            pushInterno.close();
            publicador.close();
            System.out.println("[BROKER-MT] Broker multihilo detenido.");
        }
    }

    /**
     * Función de cada worker:
     * Lee mensajes del canal PULL interno y los publica al PC2 vía PUB compartido.
     * Sincronizado para garantizar thread-safety en el socket PUB compartido.
     */
    private void ejecutarWorker(ZContext context, int workerId, ZMQ.Socket publicador) {
        ZMQ.Socket pull = context.createSocket(SocketType.PULL);
        pull.connect("tcp://127.0.0.1:" + PUERTO_INTERNO_WORKER);

        System.out.printf("[BROKER-MT] Worker-%d iniciado%n", workerId);

        try {
            while (activo && !Thread.currentThread().isInterrupted()) {
                String mensaje = pull.recvStr(0);
                if (mensaje == null) continue;

                // Publicar al PC2 (sincronizado para thread-safety del socket PUB).
                synchronized (publicador) {
                    publicador.send(mensaje, 0);
                }

                mensajesEnviados.incrementAndGet();

                String topico = mensaje.split(Topicos.SEPARADOR, 2)[0];
                System.out.printf("[BROKER-MT] Worker-%d ► [%s] reenviado%n", workerId, topico);
            }
        } finally {
            pull.close();
            System.out.printf("[BROKER-MT] Worker-%d detenido%n", workerId);
        }
    }

    private void iniciarHiloEstadisticas() {
        Thread stats = new Thread(() -> {
            while (activo && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000);
                    System.out.printf(
                            "[BROKER-MT] ── Stats ── Recibidos: %d | Enviados: %d | Workers: %d%n",
                            mensajesRecibidos.get(), mensajesEnviados.get(), numWorkers);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "broker-mt-stats");
        stats.setDaemon(true);
        stats.start();
    }

    public void detener() {
        this.activo = false;
    }

    // Main
    public static void main(String[] args) throws Exception {
        String rutaConfig = args.length > 0 ? args[0] : "config.json";
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : WORKERS_DEFAULT;

        System.out.printf("[BROKER-MT] Iniciando con %d workers. Config: %s%n", workers, rutaConfig);

        ConfiguracionSistema cfg = ConfiguracionSistema.cargar(rutaConfig);
        ZMQMultihilo broker = new ZMQMultihilo(cfg, workers);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[BROKER-MT] Apagando...");
            broker.detener();
        }));

        broker.iniciar();
    }
}
