package com.trafico.config;

/**
 * Constantes de tópicos ZeroMQ usados en el patrón PUB/SUB.
 *
 * Cada tipo de sensor publica en un tópico distinto.
 * El Broker se suscribe a los tres y los reenvía al PC2.
 */

public final class Topicos {
    private Topicos() {}

    /** Tópico para eventos de cámaras de tráfico (longitud de cola) */
        public static final String CAMARA = "EVENTO_LONGITUD_COLA";

    /** Tópico para eventos de espiras inductivas (conteo vehicular) */
        public static final String ESPIRA = "EVENTO_CONTEO_VEHICULAR";

    /** Tópico para eventos de sensores GPS (densidad de tráfico) */
        public static final String GPS = "EVENTO_DENSIDAD_TRAFICO";

    /** Separador entre el tópico y el payload JSON en el mensaje ZMQ */
        public static final String SEPARADOR = " ";
}
