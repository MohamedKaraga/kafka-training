package org.example;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Properties;

/**
 * Simple Kafka Streams example : Real-time traffic congestion detection
 */
public class TrafficCongestionDetector {

    private static final Logger log = LoggerFactory.getLogger(TrafficCongestionDetector.class);

    private static final String APPLICATION_ID = "traffic-detector";
    private static final String BOOTSTRAP_SERVERS = "broker:9092";
    private static final String INPUT_TOPIC = "vehicle-count";
    private static final String OUTPUT_TOPIC = "traffic-alerts";
    private static final int THRESHOLD = 1000;

    public static void main(String[] args) {
        new TrafficCongestionDetector().start();
    }

    public void start() {
        Topology topology = buildTopology();
        Properties props = getProperties();
        KafkaStreams streams = new KafkaStreams(topology, props);

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        log.info("Starting traffic detection application...");
        streams.start();
    }

    private Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        builder.<String, String>stream(INPUT_TOPIC)
                .mapValues(this::parseVehicleCount)
                .groupByKey(Grouped.with(Serdes.String(), Serdes.Integer()))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
                .reduce(Integer::sum)
                .toStream()
                .filter((windowedKey, count) -> count > THRESHOLD)
                .peek((windowedKey, count) ->
                        log.info("TRAFFIC CONGESTION! Route: {}, Vehicles: {}",
                                windowedKey.key(), count))
                .map((windowedKey, count) ->
                        KeyValue.pair(windowedKey.key(), createAlert(windowedKey.key(), count)))
                .to(OUTPUT_TOPIC, Produced.with(Serdes.String(), Serdes.String()));

        return builder.build();
    }

    private Integer parseVehicleCount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid value: {}", value);
            return 0;
        }
    }

    private String createAlert(String route, int count) {
        String severity = getSeverity(count);
        return String.format(
                "{\"route\":\"%s\", \"vehicles\":%d, \"severity\":\"%s\"}",
                route, count, severity
        );
    }

    private String getSeverity(int count) {
        if (count > 2000) return "CRITICAL";
        if (count > 1500) return "HIGH";
        return "MEDIUM";
    }

    private Properties getProperties() {
        Properties props = new Properties();

        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                Serdes.String().getClass().getName());
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
                StreamsConfig.EXACTLY_ONCE_V2);

        return props;
    }
}