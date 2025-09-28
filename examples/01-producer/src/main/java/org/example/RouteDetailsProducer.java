package org.example;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Producer for route details reference data
 * Sends route information to be used for stream enrichment
 */
public class RouteDetailsProducer {

    private static final Logger log = LoggerFactory.getLogger(RouteDetailsProducer.class);
    private static final String TOPIC_NAME = "route-details";

    // Route definitions with detailed information
    private static final RouteInfo[] ROUTES = {
        new RouteInfo("route-A1", "Autoroute A1", "highway", 130, 2000, "Paris-Lille", "Major highway connecting Paris to Lille"),
        new RouteInfo("route-A6", "Autoroute A6", "highway", 130, 1800, "Paris-Lyon", "Highway connecting Paris to Lyon via Burgundy"),
        new RouteInfo("route-N7", "Route Nationale 7", "national", 90, 800, "Paris-Marseille", "Historic national road, scenic route"),
        new RouteInfo("route-BP", "Boulevard Périphérique", "urban", 70, 1200, "Paris Ring", "Paris ring road with heavy urban traffic"),
        new RouteInfo("route-M25", "Rocade", "urban", 80, 1000, "City Ring", "Urban ring road around metropolitan area")
    };

    public static void main(String[] args) {
        log.info("Starting Route Details Producer");
        
        var producer = new RouteDetailsProducer();
        producer.sendRouteDetails();
        
        log.info("Route details sent successfully");
    }

    public void sendRouteDetails() {
        Properties props = createProducerProperties();

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            
            log.info("Sending route details for {} routes...", ROUTES.length);
            
            for (RouteInfo route : ROUTES) {
                sendRouteInfo(producer, route);
            }
            
            producer.flush();
            log.info("All route details sent and acknowledged");
            
        } catch (Exception e) {
            log.error("Producer error", e);
        }
    }

    private Properties createProducerProperties() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // Ensure reference data is properly replicated
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        
        return props;
    }

    private void sendRouteInfo(KafkaProducer<String, String> producer, RouteInfo route) {
        String routeJson = route.toJson();
        
        ProducerRecord<String, String> record = new ProducerRecord<>(
            TOPIC_NAME, 
            route.id(), 
            routeJson
        );
        
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to send route details for {}: {}", route.id(), exception.getMessage());
            } else {
                log.info("Route details sent: {} to partition {}, offset {}",
                    route.id(), metadata.partition(), metadata.offset());
            }
        });
        
        log.debug("Route: {} = {}", route.id(), routeJson);
    }

    /**
     * Route information record
     */
    private record RouteInfo(
        String id,
        String name, 
        String type,
        int speedLimit,
        int capacity,
        String description,
        String notes
    ) {
        public String toJson() {
            return String.format("""
                {
                    "id": "%s",
                    "name": "%s",
                    "type": "%s",
                    "speedLimit": %d,
                    "capacity": %d,
                    "description": "%s",
                    "notes": "%s",
                    "lastUpdated": "%s"
                }""",
                id, name, type, speedLimit, capacity, 
                description, notes, java.time.Instant.now()
            );
        }
    }
}