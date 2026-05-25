package com.trafico.servicios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trafico.config.ConfiguracionSistema;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Visualizador HTML independiente del sistema de tráfico.
 *
 * - HTTP/SSE en puerto 8080 (GET / → página, GET /events → SSE stream)
 * - ZMQ SUB: se conecta al broker (puerto 5556) y escucha todos los tópicos
 * - Muestra cuadrícula 5×5 con semáforos en tiempo real y panel de alertas
 *
 * Uso: java -cp Trafico-PC2.jar com.trafico.servicios.ServidorVisualizador [config.json] [broker_host]
 */
public class ServidorVisualizador implements Runnable {

    private static final int HTTP_PORT = 8080;
    private static final int MAX_ALERTAS = 100;

    private final String brokerHost;
    private final int    brokerPubPort;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean activo = true;

    private final List<PrintWriter> sseClients = new CopyOnWriteArrayList<>();

    // Estado en memoria de semáforos y sensores
    private final Map<String, String>  estadoSemaforo   = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Integer> colaCamera       = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Integer> conteoEspira     = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Double>  densidadGPS      = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Deque<String>        alertas          = new ArrayDeque<>();

    // ── Constructor standalone ─────────────────────────────────────────────────

    public ServidorVisualizador(String brokerHost, int brokerPubPort) {
        this.brokerHost    = brokerHost;
        this.brokerPubPort = brokerPubPort;
    }

    // ── Runnable (embebido en LanzadorPC2) ────────────────────────────────────

    public ServidorVisualizador() {
        ConfiguracionSistema cfg = ConfiguracionSistema.getInstancia();
        this.brokerHost    = cfg.getBroker().getHost_pc2();
        this.brokerPubPort = cfg.getBroker().getPuerto_pub();
    }

    @Override
    public void run() {
        System.out.println("[VISUALIZADOR] Iniciando en puerto " + HTTP_PORT + " ...");
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
            http.createContext("/",       this::handleRoot);
            http.createContext("/events", this::handleSSE);
            http.setExecutor(Executors.newCachedThreadPool());
            http.start();
            System.out.println("[VISUALIZADOR] HTTP listo → http://localhost:" + HTTP_PORT);

            escucharZMQ(); // bloquea hasta que activo=false

            http.stop(0);
        } catch (Exception e) {
            System.err.println("[VISUALIZADOR] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── ZMQ SUB loop ──────────────────────────────────────────────────────────

    private void escucharZMQ() {
        String uri = "tcp://" + brokerHost + ":" + brokerPubPort;
        System.out.println("[VISUALIZADOR] SUB conectando a " + uri);
        try (ZContext ctx = new ZContext()) {
            ZMQ.Socket sub = ctx.createSocket(SocketType.SUB);
            sub.connect(uri);
            sub.subscribe(""); // todos los tópicos

            while (activo) {
                String msg = sub.recvStr(ZMQ.DONTWAIT);
                if (msg != null) {
                    procesarMensaje(msg);
                } else {
                    Thread.sleep(20);
                }
            }
        } catch (Exception e) {
            System.err.println("[VISUALIZADOR] ZMQ error: " + e.getMessage());
        }
    }

    // ── Procesado de mensajes ─────────────────────────────────────────────────

    private void procesarMensaje(String msg) {
        try {
            int sep = msg.indexOf(' ');
            if (sep < 0) return;
            String topico  = msg.substring(0, sep);
            String payload = msg.substring(sep + 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(payload, Map.class);

            switch (topico) {
                case "ESTADO_SEMAFORO"        -> procesarSemaforo(data);
                case "EVENTO_LONGITUD_COLA"   -> procesarCamara(data);
                case "EVENTO_CONTEO_VEHICULAR"-> procesarEspira(data);
                case "EVENTO_DENSIDAD_TRAFICO"-> procesarGPS(data);
            }
        } catch (Exception e) {
            System.err.println("[VISUALIZADOR] Parse error: " + e.getMessage());
        }
    }

    private void procesarSemaforo(Map<String, Object> d) {
        String inter  = str(d, "interseccion");
        String estado = str(d, "estado");
        String dir    = str(d, "direccion");
        String dirBloq= str(d, "direccion_bloqueada");
        int    dur    = num(d, "duracion");

        estadoSemaforo.put(inter, estado);

        String alerta = String.format("[%s] %s → %s (%s) | %s bloqueada | %ds",
            Instant.now().toString().substring(11, 19), inter, estado, dir, dirBloq, dur);
        registrarAlerta(alerta);

        broadcast("semaforo", mapper.createObjectNode()
            .put("interseccion", inter)
            .put("estado",       estado)
            .put("direccion",    dir)
            .put("bloqueada",    dirBloq)
            .put("duracion",     dur)
            .toString());
    }

    private void procesarCamara(Map<String, Object> d) {
        String inter = str(d, "interseccion");
        int    cola  = num(d, "longitud_cola");
        double vel   = dbl(d, "velocidad_promedio");
        colaCamera.put(inter, cola);

        String estadoTrafico = vel < 15 ? "CONGESTION" : "NORMAL";
        if ("CONGESTION".equals(estadoTrafico)) {
            registrarAlerta(String.format("[%s] ALERTA CAMARA %s: cola=%d vel=%.1f km/h",
                Instant.now().toString().substring(11, 19), inter, cola, vel));
        }
        broadcast("sensor", buildSensorJson(inter, "CAMARA", estadoTrafico, cola, (int)vel, 0));
    }

    private void procesarEspira(Map<String, Object> d) {
        String inter    = str(d, "interseccion");
        int    vehiculos= num(d, "vehiculos_por_minuto");
        conteoEspira.put(inter, vehiculos);

        String estadoTrafico = vehiculos > 20 ? "CONGESTION" : "NORMAL";
        if ("CONGESTION".equals(estadoTrafico)) {
            registrarAlerta(String.format("[%s] ALERTA ESPIRA %s: %d veh/min",
                Instant.now().toString().substring(11, 19), inter, vehiculos));
        }
        broadcast("sensor", buildSensorJson(inter, "ESPIRA", estadoTrafico, 0, 0, vehiculos));
    }

    private void procesarGPS(Map<String, Object> d) {
        String inter  = str(d, "interseccion");
        String nivel  = str(d, "nivel_congestion");
        double dens   = dbl(d, "densidad_vehicular");
        densidadGPS.put(inter, dens);

        String estadoTrafico = "ALTA".equals(nivel) ? "CONGESTION" : "NORMAL";
        if ("CONGESTION".equals(estadoTrafico)) {
            registrarAlerta(String.format("[%s] ALERTA GPS %s: densidad=%.1f nivel=%s",
                Instant.now().toString().substring(11, 19), inter, dens, nivel));
        }
        broadcast("sensor", buildSensorJson(inter, "GPS", estadoTrafico, 0, (int)dens, 0));
    }

    private String buildSensorJson(String inter, String tipo, String estado,
                                   int cola, int vel, int vehiculos) {
        try {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("interseccion", inter);
            m.put("tipo",         tipo);
            m.put("estado",       estado);
            m.put("cola",         cola);
            m.put("velocidad",    vel);
            m.put("vehiculos",    vehiculos);
            return mapper.writeValueAsString(m);
        } catch (Exception e) { return "{}"; }
    }

    private void registrarAlerta(String alerta) {
        synchronized (alertas) {
            alertas.addFirst(alerta);
            while (alertas.size() > MAX_ALERTAS) alertas.removeLast();
        }
        broadcast("alerta", "\"" + alerta.replace("\"","\\\"") + "\"");
    }

    // ── SSE broadcasting ─────────────────────────────────────────────────────

    private void broadcast(String tipo, String jsonData) {
        String line = "data: {\"tipo\":\"" + tipo + "\",\"payload\":" + jsonData + "}\n\n";
        sseClients.removeIf(pw -> {
            pw.write(line);
            pw.flush();
            return pw.checkError();
        });
    }

    // ── HTTP handlers ────────────────────────────────────────────────────────

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = HTML.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private void handleSSE(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        ex.getResponseHeaders().add("Content-Type",  "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection",    "keep-alive");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);

        PrintWriter pw = new PrintWriter(
            new BufferedWriter(new OutputStreamWriter(ex.getResponseBody(), StandardCharsets.UTF_8)));
        sseClients.add(pw);
        System.out.println("[VISUALIZADOR] Cliente SSE conectado. Total: " + sseClients.size());

        // Enviar estado inicial
        synchronized (estadoSemaforo) {
            for (Map.Entry<String, String> e : estadoSemaforo.entrySet()) {
                pw.write("data: {\"tipo\":\"semaforo\",\"payload\":{\"interseccion\":\"" + e.getKey() +
                         "\",\"estado\":\"" + e.getValue() + "\"}}\n\n");
            }
        }
        synchronized (alertas) {
            for (String a : alertas) {
                pw.write("data: {\"tipo\":\"alerta\",\"payload\":\"" +
                         a.replace("\"","\\\"") + "\"}\n\n");
            }
        }
        pw.flush();

        // Mantener conexión abierta — el hilo de ZMQ hará broadcast
        while (!pw.checkError() && activo) {
            try { Thread.sleep(5000); pw.write(": ping\n\n"); pw.flush(); }
            catch (InterruptedException ignored) { break; }
        }
        sseClients.remove(pw);
        System.out.println("[VISUALIZADOR] Cliente SSE desconectado. Total: " + sseClients.size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(Map<String,Object> m, String k) {
        Object v = m.get(k); return v != null ? v.toString() : "";
    }
    private static int num(Map<String,Object> m, String k) {
        Object v = m.get(k); return v instanceof Number ? ((Number)v).intValue() : 0;
    }
    private static double dbl(Map<String,Object> m, String k) {
        Object v = m.get(k); return v instanceof Number ? ((Number)v).doubleValue() : 0.0;
    }

    public void detener() { activo = false; }

    // ── main (standalone) ─────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String brokerHost = args.length > 1 ? args[1] : "localhost";
        int    port       = 5556;

        if (args.length > 0 && !args[0].equals("--resources")) {
            ConfiguracionSistema cfg = ConfiguracionSistema.cargar(args[0]);
            brokerHost = cfg.getBroker().getHost_pc2();
            port       = cfg.getBroker().getPuerto_pub();
        } else {
            ConfiguracionSistema.cargarDesdeRecursos();
        }

        ServidorVisualizador sv = new ServidorVisualizador(brokerHost, port);
        Runtime.getRuntime().addShutdownHook(new Thread(sv::detener));
        sv.run();
    }

    // ── HTML embebido ─────────────────────────────────────────────────────────

    private static final String HTML = """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8"/>
<title>Monitoreo Tráfico — Sistema Distribuido</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:monospace;background:#111;color:#eee;padding:12px}
  h1{text-align:center;color:#0f0;margin-bottom:10px;font-size:1.1em;letter-spacing:2px}
  #conn{text-align:center;font-size:.75em;margin-bottom:12px}
  .ok{color:#0f0}.ko{color:#f44}
  .layout{display:grid;grid-template-columns:1fr 340px;gap:12px}
  /* -- Cuadrícula ciudad -- */
  #ciudad{display:grid;grid-template-columns:repeat(5,1fr);gap:6px}
  .celda{
    background:#222;border:1px solid #333;border-radius:6px;
    padding:6px 4px;text-align:center;position:relative;min-height:100px;
    transition:background .4s
  }
  .celda.congestion{background:#3a1010;border-color:#a00}
  .celda.normal{background:#102010;border-color:#060}
  .celda-id{font-size:.65em;color:#888;margin-bottom:4px}
  .sem-badge{
    display:inline-block;width:14px;height:14px;border-radius:50%;
    margin:2px;vertical-align:middle;border:1px solid #555;
    background:#333;transition:background .4s
  }
  .sem-verde{background:#00e000;box-shadow:0 0 6px #0f0}
  .sem-rojo {background:#dd0000;box-shadow:0 0 4px #f00}
  .sem-label{font-size:.6em;color:#aaa;display:block;margin-top:2px}
  .stats{font-size:.62em;color:#aaa;margin-top:4px;line-height:1.5}
  /* -- Panel derecho -- */
  .right-panel{display:flex;flex-direction:column;gap:10px}
  #alertas{
    flex:1;overflow-y:auto;max-height:420px;
    background:#0d0d0d;border:1px solid #333;border-radius:6px;padding:8px
  }
  .alerta-item{font-size:.68em;padding:3px 0;border-bottom:1px solid #1a1a1a;color:#ccc}
  .alerta-item.crit{color:#f88}
  #resumen{
    background:#0d0d0d;border:1px solid #333;border-radius:6px;padding:8px;font-size:.7em
  }
  #resumen h3{color:#0af;margin-bottom:6px;font-size:.8em}
  .stat-row{display:flex;justify-content:space-between;padding:2px 0;color:#aaa}
  .stat-val{color:#fff;font-weight:bold}
  #legend{font-size:.65em;color:#555;text-align:center;margin-top:8px}
  @media(max-width:900px){.layout{grid-template-columns:1fr}}
</style>
</head>
<body>
<h1>SISTEMA MONITOREO TRÁFICO DISTRIBUIDO</h1>
<div id="conn"><span class="ko">⬤ Sin conexión</span></div>

<div class="layout">
  <!-- Cuadrícula 5x5 -->
  <div id="ciudad"></div>

  <!-- Panel derecho -->
  <div class="right-panel">
    <div id="alertas">
      <div style="color:#555;font-size:.75em;padding:4px">Esperando eventos...</div>
    </div>
    <div id="resumen">
      <h3>RESUMEN SISTEMA</h3>
      <div class="stat-row"><span>Semáforos VERDE</span><span class="stat-val" id="cnt-verde">0</span></div>
      <div class="stat-row"><span>Semáforos ROJO</span><span class="stat-val" id="cnt-rojo">0</span></div>
      <div class="stat-row"><span>Intersecciones con semáforo</span><span class="stat-val">15</span></div>
      <div class="stat-row"><span>Alertas recibidas</span><span class="stat-val" id="cnt-alertas">0</span></div>
      <div class="stat-row"><span>Eventos totales</span><span class="stat-val" id="cnt-eventos">0</span></div>
      <div class="stat-row"><span>Última actualización</span><span class="stat-val" id="ultima-ts">—</span></div>
    </div>
  </div>
</div>
<div id="legend">
  NS = NORTE-SUR &nbsp;|&nbsp; EO = ESTE-OESTE &nbsp;|&nbsp;
  <span style="color:#0f0">●</span> VERDE &nbsp;
  <span style="color:#f00">●</span> ROJO &nbsp;
  <span style="color:#333">●</span> Sin dato
</div>

<script>
// ── Configuración de la cuadrícula ──────────────────────────────────────────
const FILAS = ['A','B','C','D','E'];
const COLS  = [1, 2, 3, 4, 5];

// Direcciones por intersección (NS=NORTE-SUR, EO=ESTE-OESTE)
const SEMAFOROS = {
  'INT-A1':'NS','INT-A2':'EO','INT-A3':'NS',
  'INT-B1':'NS','INT-B3':'NS','INT-B4':'EO',
  'INT-C1':'NS','INT-C4':'EO','INT-C5':'NS',
  'INT-D2':'EO','INT-D3':'NS','INT-D5':'NS',
  'INT-E2':'EO','INT-E4':'EO','INT-E5':'NS'
};

let cntAlertas = 0, cntEventos = 0;
const semState = {};     // interseccion → 'VERDE'|'ROJO'
const sensorState = {};  // interseccion → 'CONGESTION'|'NORMAL'

// ── Construir cuadrícula ─────────────────────────────────────────────────────
const grid = document.getElementById('ciudad');
FILAS.forEach(f => {
  COLS.forEach(c => {
    const id = `INT-${f}${c}`;
    const hasSem = id in SEMAFOROS;
    const div = document.createElement('div');
    div.className = 'celda';
    div.id = `cell-${id}`;
    div.innerHTML = `
      <div class="celda-id">${id}</div>
      ${hasSem ? `
        <span class="sem-badge" id="sem-${id}" title="${SEMAFOROS[id]}"></span>
        <span class="sem-label">${SEMAFOROS[id]}</span>
      ` : '<span style="font-size:.6em;color:#444">sin semáforo</span>'}
      <div class="stats" id="stats-${id}">—</div>
    `;
    grid.appendChild(div);
  });
});

// ── SSE ──────────────────────────────────────────────────────────────────────
const connDiv = document.getElementById('conn');
let es;

function conectar() {
  es = new EventSource('/events');
  es.onopen = () => {
    connDiv.innerHTML = '<span class="ok">⬤ Conectado</span>';
  };
  es.onmessage = (e) => {
    try {
      const ev = JSON.parse(e.data);
      cntEventos++;
      document.getElementById('cnt-eventos').textContent = cntEventos;
      document.getElementById('ultima-ts').textContent = new Date().toLocaleTimeString();

      if (ev.tipo === 'semaforo')  handleSemaforo(ev.payload);
      else if (ev.tipo === 'sensor')  handleSensor(ev.payload);
      else if (ev.tipo === 'alerta')  handleAlerta(ev.payload);
    } catch(err) { console.error(err); }
  };
  es.onerror = () => {
    connDiv.innerHTML = '<span class="ko">⬤ Reconectando...</span>';
    es.close();
    setTimeout(conectar, 3000);
  };
}
conectar();

// ── Handlers ──────────────────────────────────────────────────────────────────
function handleSemaforo(p) {
  const inter = p.interseccion;
  const estado = p.estado;
  semState[inter] = estado;

  const badge = document.getElementById(`sem-${inter}`);
  if (badge) {
    badge.className = 'sem-badge ' + (estado === 'VERDE' ? 'sem-verde' : 'sem-rojo');
    badge.title = (p.direccion || '') + ' → ' + estado +
                  (p.bloqueada ? ` | ${p.bloqueada} bloqueada` : '');
  }
  actualizarCelda(inter);
  actualizarContadores();
}

function handleSensor(p) {
  const inter = p.interseccion;
  sensorState[inter] = p.estado;

  const statsEl = document.getElementById(`stats-${inter}`);
  if (statsEl) {
    let txt = '';
    if (p.tipo === 'CAMARA')  txt = `cola:${p.cola} vel:${p.velocidad}km/h`;
    if (p.tipo === 'ESPIRA')  txt = `veh:${p.vehiculos}/min`;
    if (p.tipo === 'GPS')     txt = `dens:${p.velocidad}`;
    statsEl.textContent = `[${p.tipo}] ${txt}`;
  }
  actualizarCelda(inter);
}

function handleAlerta(texto) {
  cntAlertas++;
  document.getElementById('cnt-alertas').textContent = cntAlertas;

  const alertasDiv = document.getElementById('alertas');
  if (alertasDiv.querySelector('[data-placeholder]')) alertasDiv.innerHTML = '';
  const item = document.createElement('div');
  item.className = 'alerta-item' + (texto.includes('ALERTA') ? ' crit' : '');
  item.textContent = texto;
  alertasDiv.prepend(item);
  // Limitar a 80 items en DOM
  while (alertasDiv.children.length > 80) alertasDiv.removeChild(alertasDiv.lastChild);
}

function actualizarCelda(inter) {
  const cell = document.getElementById(`cell-${inter}`);
  if (!cell) return;
  const congestion = sensorState[inter] === 'CONGESTION';
  cell.className = 'celda ' + (congestion ? 'congestion' : 'normal');
}

function actualizarContadores() {
  let verde = 0, rojo = 0;
  for (const v of Object.values(semState)) {
    if (v === 'VERDE') verde++; else rojo++;
  }
  document.getElementById('cnt-verde').textContent = verde;
  document.getElementById('cnt-rojo').textContent  = rojo;
}
</script>
</body>
</html>
""";
}
