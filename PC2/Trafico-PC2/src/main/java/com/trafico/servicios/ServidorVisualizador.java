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
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Monitoreo Tráfico — PC2</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Courier New',monospace;background:#0d0d12;color:#ccc;padding:8px;min-height:100vh}

/* ── HEADER ── */
.header{display:flex;align-items:center;justify-content:space-between;
        border-bottom:1px solid #1a2a3a;padding-bottom:6px;margin-bottom:8px}
.title{color:#4fc3f7;font-size:.85em;letter-spacing:3px;text-transform:uppercase;font-weight:bold}
.conn-badge{font-size:.65em;padding:3px 8px;border-radius:12px;border:1px solid #333}
.conn-ok{color:#4caf50;border-color:#1a4a1a;background:#0a1a0a}
.conn-ko{color:#f44336;border-color:#4a1a1a;background:#1a0a0a}

/* ── LAYOUT ── */
.layout{display:grid;grid-template-columns:1fr 280px;gap:8px;height:calc(100vh - 50px)}

/* ══════════════════════════════════════════
   MAPA CIUDAD
══════════════════════════════════════════ */
.map-section{display:flex;flex-direction:column;gap:4px}
.map-label{font-size:.6em;color:#3a5a7a;letter-spacing:2px;text-align:center}

/* Etiquetas de columna */
.col-labels{display:grid;grid-template-columns:20px repeat(5,1fr);gap:3px;padding:0 2px}
.col-lbl{text-align:center;font-size:.65em;color:#3a5a7a;font-weight:bold;line-height:1.8}

/* Fila del mapa = etiqueta de fila + 5 nodos */
.map-row{display:grid;grid-template-columns:20px repeat(5,1fr);gap:3px;align-items:stretch}
.row-lbl{display:flex;align-items:center;justify-content:center;
         font-size:.65em;color:#3a5a7a;font-weight:bold}

/* ── Nodo / Intersección ── */
.nodo{
  background:#111318;
  border:1px solid #1c2030;
  border-radius:5px;
  padding:5px 4px;
  min-height:90px;
  display:flex;flex-direction:column;gap:2px;
  transition:background .5s,border-color .5s;
  position:relative;overflow:hidden;
}
/* Congestion state */
.nodo.cong{background:#1a0a0a;border-color:#5a1515}
.nodo.cong::after{
  content:'';position:absolute;top:0;left:0;right:0;height:2px;
  background:linear-gradient(90deg,transparent,#f44,transparent);
  animation:pulse-bar 1.2s ease-in-out infinite;
}
/* Normal (with sensor data) */
.nodo.norm{background:#0a110a;border-color:#1a3a1a}
/* No data yet */
.nodo.empty{background:#0e0e14;border-color:#1a1a28}

@keyframes pulse-bar{0%,100%{opacity:.3}50%{opacity:1}}

/* Nodo header: ID + badges sensor */
.nodo-head{display:flex;justify-content:space-between;align-items:flex-start}
.nodo-id{font-size:.58em;color:#3a4a6a;line-height:1}
.sensor-row{display:flex;gap:2px}
.sbadge{font-size:.5em;padding:1px 3px;border-radius:3px;font-weight:bold;line-height:1.4}
.s-cam{background:#0d2a4a;color:#4fc3f7}
.s-esp{background:#1a0d3a;color:#ce93d8}
.s-gps{background:#0d2a0d;color:#81c784}

/* ── Semáforo visual ── */
.sem-area{display:flex;align-items:center;gap:4px;margin:1px 0}

/* Caja del semáforo físico */
.tl-box{
  background:#0a0a0a;border:1px solid #222;border-radius:3px;
  padding:2px 3px;display:flex;gap:2px;align-items:center;
}
.tl-box.ns{flex-direction:column}     /* vertical = NORTE-SUR */
.tl-box.eo{flex-direction:row}        /* horizontal = ESTE-OESTE */

/* Bombillas del semáforo */
.bulb{width:9px;height:9px;border-radius:50%;background:#1a1a1a;
      border:1px solid #2a2a2a;transition:background .4s,box-shadow .4s}
.bulb.verde{background:#00dd00;box-shadow:0 0 6px #00ee00,0 0 2px #00ff00}
.bulb.rojo {background:#cc0000;box-shadow:0 0 5px #dd0000,0 0 2px #ff0000}

/* Etiqueta del semáforo */
.sem-info{display:flex;flex-direction:column}
.sem-dir{font-size:.5em;color:#3a5a3a}
.sem-val{font-size:.58em;font-weight:bold;line-height:1}
.sem-val.verde{color:#4caf50}
.sem-val.rojo {color:#f44336}
.sem-val.init {color:#555}

.no-sem{font-size:.5em;color:#1e1e2e;margin:2px 0}

/* ── Datos sensor ── */
.sensor-data{font-size:.55em;color:#2a3a5a;line-height:1.5;margin-top:auto}
.sensor-data b{color:#5a7a9a}

/* ══════════════════════════════════════════
   PANEL DERECHO
══════════════════════════════════════════ */
.right-panel{display:flex;flex-direction:column;gap:6px;overflow:hidden}

.panel{background:#0e0e14;border:1px solid #1a1a28;border-radius:6px;padding:8px}
.panel-hdr{font-size:.62em;color:#3a7aaa;letter-spacing:2px;text-transform:uppercase;
           margin-bottom:6px;border-bottom:1px solid #1a1a28;padding-bottom:3px}

/* Stats grid */
.stats-grid{display:grid;grid-template-columns:1fr 1fr;gap:4px;margin-bottom:6px}
.stat-box{background:#08080f;border-radius:4px;padding:6px;text-align:center}
.stat-n{font-size:1.5em;font-weight:bold;line-height:1}
.stat-n.c-verde{color:#4caf50}
.stat-n.c-rojo {color:#f44336}
.stat-n.c-ev   {color:#4fc3f7}
.stat-n.c-al   {color:#ff9800}
.stat-lbl{font-size:.55em;color:#3a4a5a;margin-top:2px}

.info-row{display:flex;justify-content:space-between;font-size:.6em;
          padding:3px 0;border-bottom:1px solid #12121e;color:#3a4a5a}
.info-row:last-child{border:none}
.info-val{color:#9ab}

/* Alertas */
.alerts-panel{flex:1;min-height:0;display:flex;flex-direction:column}
.alerts-list{flex:1;overflow-y:auto;max-height:320px}
.alert-item{font-size:.6em;padding:3px 4px;border-bottom:1px solid #10101a;
            color:#556;line-height:1.4;transition:color .3s}
.alert-item:first-child{color:#8ab;border-left:2px solid #3a5a7a;padding-left:6px}
.a-warn{color:#b87333!important}
.a-warn:first-child{border-left-color:#b87333!important}
.a-crit{color:#c44!important}
.a-crit:first-child{border-left-color:#c44!important}

/* Leyenda */
.legend{display:flex;flex-wrap:wrap;gap:6px;font-size:.55em;
        color:#2a3a4a;justify-content:center;margin-top:4px}
.leg{display:flex;align-items:center;gap:3px}
.leg-sq{width:8px;height:8px;border-radius:2px}

@media(max-width:860px){.layout{grid-template-columns:1fr}}
</style>
</head>
<body>

<div class="header">
  <div class="title">&#9632; Gestión Inteligente de Tráfico Urbano — PC2</div>
  <div id="badge" class="conn-badge conn-ko">Sin conexión</div>
</div>

<div class="layout">

  <!-- ════ MAPA ════ -->
  <div class="map-section">
    <div class="map-label">CUADRÍCULA CIUDAD 5×5 — TIEMPO REAL</div>
    <div class="col-labels">
      <div></div>
      <div class="col-lbl">1</div><div class="col-lbl">2</div><div class="col-lbl">3</div>
      <div class="col-lbl">4</div><div class="col-lbl">5</div>
    </div>
    <div id="map-grid"></div>
    <div class="legend">
      <div class="leg"><div class="leg-sq" style="background:#1a0a0a;border:1px solid #5a1515"></div>Congestión</div>
      <div class="leg"><div class="leg-sq" style="background:#0a110a;border:1px solid #1a3a1a"></div>Normal</div>
      <div class="leg"><div class="bulb verde" style="width:8px;height:8px;display:inline-block"></div>VERDE</div>
      <div class="leg"><div class="bulb rojo"  style="width:8px;height:8px;display:inline-block"></div>ROJO</div>
      <div class="leg"><span class="sbadge s-cam">CAM</span>Cámara</div>
      <div class="leg"><span class="sbadge s-esp">ESP</span>Espira</div>
      <div class="leg"><span class="sbadge s-gps">GPS</span>GPS</div>
    </div>
  </div>

  <!-- ════ PANEL ════ -->
  <div class="right-panel">
    <div class="panel">
      <div class="panel-hdr">Resumen Sistema</div>
      <div class="stats-grid">
        <div class="stat-box"><div class="stat-n c-verde" id="cv">0</div><div class="stat-lbl">VERDE</div></div>
        <div class="stat-box"><div class="stat-n c-rojo"  id="cr">0</div><div class="stat-lbl">ROJO</div></div>
        <div class="stat-box"><div class="stat-n c-ev"    id="ce">0</div><div class="stat-lbl">Eventos</div></div>
        <div class="stat-box"><div class="stat-n c-al"    id="ca">0</div><div class="stat-lbl">Alertas</div></div>
      </div>
      <div class="info-row"><span>Semáforos totales</span><span class="info-val">15 / 25</span></div>
      <div class="info-row"><span>Intersecciones activas</span><span class="info-val" id="ci">0 / 25</span></div>
      <div class="info-row"><span>Última actualización</span><span class="info-val" id="lu">—</span></div>
    </div>

    <div class="panel alerts-panel">
      <div class="panel-hdr">Alertas y Eventos</div>
      <div class="alerts-list" id="al-list">
        <div class="alert-item" data-ph="1">Esperando eventos del sistema...</div>
      </div>
    </div>
  </div>

</div>

<script>
// ─── Mapa de intersecciones ───────────────────────────────────────────────────
const ROWS = ['A','B','C','D','E'];
const COLS = [1,2,3,4,5];

// Semáforos: intersección → dirección
const SEMS = {
  'INT-A1':'NS','INT-A2':'EO','INT-A3':'NS',
  'INT-B1':'NS','INT-B3':'NS','INT-B4':'EO',
  'INT-C1':'NS','INT-C4':'EO','INT-C5':'NS',
  'INT-D2':'EO','INT-D3':'NS','INT-D5':'NS',
  'INT-E2':'EO','INT-E4':'EO','INT-E5':'NS'
};

// Sensores: intersección → tipo
const SENS = {
  'INT-A1':'CAM','INT-A2':'ESP','INT-A3':'GPS',
  'INT-B1':'GPS','INT-B3':'CAM','INT-B4':'ESP',
  'INT-C1':'ESP','INT-C4':'GPS','INT-C5':'CAM',
  'INT-D2':'CAM','INT-D3':'ESP','INT-D5':'GPS',
  'INT-E2':'GPS','INT-E4':'CAM','INT-E5':'ESP'
};

// Estado en memoria
const semState = {}, trafico = {};
let nEv=0, nAl=0, nActivas=new Set();

// ─── Construir cuadrícula ─────────────────────────────────────────────────────
function buildGrid() {
  const grid = document.getElementById('map-grid');
  ROWS.forEach(r => {
    const row = document.createElement('div');
    row.className = 'map-row';
    row.innerHTML = `<div class="row-lbl">${r}</div>`;
    COLS.forEach(c => {
      const id = `INT-${r}${c}`;
      const dir = SEMS[id];
      const sen = SENS[id];
      const scls = sen==='CAM'?'s-cam':sen==='ESP'?'s-esp':'s-gps';

      const semHtml = dir ? `
        <div class="sem-area">
          <div class="tl-box ${dir.toLowerCase()}" id="tl-${id}">
            <div class="bulb" id="b0-${id}"></div>
            <div class="bulb" id="b1-${id}"></div>
          </div>
          <div class="sem-info">
            <span class="sem-dir">${dir}</span>
            <span class="sem-val init" id="sv-${id}">—</span>
          </div>
        </div>` : `<div class="no-sem">sin semáforo</div>`;

      const sensHtml = sen ? `<span class="sbadge ${scls}">${sen}</span>` : '';

      row.innerHTML += `
        <div class="nodo empty" id="n-${id}">
          <div class="nodo-head">
            <span class="nodo-id">${id}</span>
            <div class="sensor-row">${sensHtml}</div>
          </div>
          ${semHtml}
          <div class="sensor-data" id="sd-${id}">—</div>
        </div>`;
    });
    grid.appendChild(row);
  });
}

// ─── Actualizar semáforo ───────────────────────────────────────────────────────
function setSem(id, estado) {
  semState[id] = estado;
  const verde = estado === 'VERDE';
  const b0 = document.getElementById(`b0-${id}`);
  const b1 = document.getElementById(`b1-${id}`);
  const sv = document.getElementById(`sv-${id}`);
  if (!b0) return;
  // b0 = bombilla superior/izquierda (verde), b1 = inferior/derecha (rojo)
  b0.className = 'bulb' + (verde ? ' verde' : '');
  b1.className = 'bulb' + (verde ? '' : ' rojo');
  sv.textContent = estado;
  sv.className   = 'sem-val ' + (verde ? 'verde' : 'rojo');
  updateCounts();
}

// ─── Actualizar nodo (estado tráfico) ────────────────────────────────────────
function setNodo(id, estado) {
  trafico[id] = estado;
  nActivas.add(id);
  const n = document.getElementById(`n-${id}`);
  if (n) n.className = 'nodo ' + (estado==='CONGESTION' ? 'cong' : 'norm');
  document.getElementById('ci').textContent = nActivas.size + ' / 25';
}

function setSensorData(id, html) {
  const el = document.getElementById(`sd-${id}`);
  if (el) el.innerHTML = html;
}

// ─── Contadores ───────────────────────────────────────────────────────────────
function updateCounts() {
  let v=0,r=0;
  for (const [k,s] of Object.entries(semState)) {
    if (k in SEMS) (s==='VERDE'?v++:r++);
  }
  document.getElementById('cv').textContent = v;
  document.getElementById('cr').textContent = r;
}

// ─── Alertas ─────────────────────────────────────────────────────────────────
function addAlert(txt, lvl='') {
  nAl++;
  document.getElementById('ca').textContent = nAl;
  const list = document.getElementById('al-list');
  if (list.querySelector('[data-ph]')) list.innerHTML = '';
  const d = document.createElement('div');
  d.className = 'alert-item ' + (lvl==='crit'?'a-crit':lvl==='warn'?'a-warn':'');
  d.textContent = hhmm() + ' ' + txt;
  list.prepend(d);
  while (list.children.length > 100) list.lastChild.remove();
}

function hhmm() {
  return new Date().toLocaleTimeString('es',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'});
}

// ─── SSE ──────────────────────────────────────────────────────────────────────
function connect() {
  const es = new EventSource('/events');
  const badge = document.getElementById('badge');

  es.onopen = () => {
    badge.className = 'conn-badge conn-ok';
    badge.textContent = 'Conectado';
  };

  es.onmessage = e => {
    try {
      const ev = JSON.parse(e.data);
      nEv++;
      document.getElementById('ce').textContent = nEv;
      document.getElementById('lu').textContent = hhmm();
      const p = ev.payload;

      if (ev.tipo === 'semaforo') {
        setSem(p.interseccion, p.estado);
        const dur = p.duracion ? ` ${p.duracion}s` : '';
        addAlert(`[SEM] ${p.interseccion} → ${p.estado} (${p.direccion||''})${dur}`);

      } else if (ev.tipo === 'sensor') {
        setNodo(p.interseccion, p.estado);
        const cong = p.estado==='CONGESTION';
        let html='';
        if (p.tipo==='CAMARA') {
          html = `<b>Cola:</b>${p.cola} veh &nbsp;<b>Vel:</b>${p.velocidad} km/h`;
          if (cong) addAlert(`[CAM] ${p.interseccion}: cola=${p.cola} vel=${p.velocidad}km/h`, 'warn');
        } else if (p.tipo==='ESPIRA') {
          html = `<b>Veh:</b>${p.vehiculos}/min`;
          if (cong) addAlert(`[ESP] ${p.interseccion}: ${p.vehiculos}veh/min`, 'warn');
        } else if (p.tipo==='GPS') {
          html = `<b>Dens:</b>${p.velocidad} veh/km`;
          if (cong) addAlert(`[GPS] ${p.interseccion}: densidad alta`, 'warn');
        }
        setSensorData(p.interseccion, html);

      } else if (ev.tipo === 'alerta') {
        addAlert(p, 'crit');
      }
    } catch(err) { console.error(err); }
  };

  es.onerror = () => {
    badge.className = 'conn-badge conn-ko';
    badge.textContent = 'Reconectando...';
    es.close();
    setTimeout(connect, 3000);
  };
}

buildGrid();
connect();
</script>
</body>
</html>
""";
}
