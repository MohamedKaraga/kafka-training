package org.example;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Traffic Enrichment with Kafka Streams Joins
 * Enriches vehicle count data with route details for better analytics
 */
public class TrafficEnrichmentApp {

    private static final Logger log = LoggerFactory.getLogger(TrafficEnrichmentApp.class);

    private static final String APPLICATION_ID = "traffic-enrichment";
    private static final String BOOTSTRAP_SERVERS = "broker:9092";
    
    // Input topics
    private static final String VEHICLE_COUNT_TOPIC = "vehicle-count";
    private static final String ROUTE_DETAILS_TOPIC = "route-details";
    
    // Output topic  
    private static final String ENRICHED_TRAFFIC_TOPIC = "enriched-traffic";

    public static void main(String[] args) {
        new TrafficEnrichmentApp().start();
    }

    public void start() {
        Topology topology = buildTopology();
        KafkaStreams streams = new KafkaStreams(topology, getProperties());

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        log.info("Starting Traffic Enrichment Application...");
        streams.start();
    }

    /**
     * Build the topology with stream-table join
     */
    private Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        // 1. Create KTable for route details (reference data)
        KTable<String, String> routeDetailsTable = builder
            .table(ROUTE_DETAILS_TOPIC, 
                   Consumed.with(Serdes.String(), Serdes.String()))
            .mapValues(this::parseRouteDetails);

        // 2. Create KStream for vehicle counts (streaming data)
        KStream<String, String> vehicleCountStream = builder
            .stream(VEHICLE_COUNT_TOPIC, 
                    Consumed.with(Serdes.String(), Serdes.String()))
            .mapValues(this::parseVehicleCount)
            .peek((routeId, count) -> 
                log.debug("Vehicle count: {} = {}", routeId, count));

        // 3. Join stream with table to enrich data
        KStream<String, String> enrichedStream = vehicleCountStream
            .join(routeDetailsTable, this::enrichTrafficData)
            .peek((routeId, enrichedData) -> 
                log.info("Enriched traffic data: {} = {}", routeId, enrichedData));

        // 4. Detect congestion on enriched data
        enrichedStream
            .filter(this::isCongested)
            .peek((routeId, data) -> 
                log.warn("CONGESTION ALERT: {}", data))
            .to(ENRICHED_TRAFFIC_TOPIC, 
                Produced.with(Serdes.String(), Serdes.String()));

        return builder.build();
    }

    /**
     * Parse route details JSON
     */
    private String parseRouteDetails(String json) {
        // For lab, assume JSON like: {"name":"Autoroute A1","type":"highway","speedLimit":130,"capacity":2000}
        return json;
    }

    /**
     * Parse vehicle count
     */
    private String parseVehicleCount(String value) {
        return value; // Keep as string for lab
    }

    /**
     * Enrich traffic data by joining vehicle count with route details
     */
    private String enrichTrafficData(String vehicleCount, String routeDetails) {
        try {
            int count = Integer.parseInt(vehicleCount);
            
            // Extract route info (simplified parsing)
            String routeName = extractField(routeDetails, "name");
            String routeType = extractField(routeDetails, "type");
            int speedLimit = Integer.parseInt(extractField(routeDetails, "speedLimit"));
            int capacity = Integer.parseInt(extractField(routeDetails, "capacity"));
            
            // Calculate metrics
            double occupancyRate = (double) count / capacity * 100;
            String congestionLevel = getCongestionLevel(occupancyRate);
            String riskLevel = calculateRiskLevel(count, capacity, routeType);
            
            // Create enriched JSON
            return createEnrichedJson(routeName, routeType, speedLimit, capacity, 
                                    count, occupancyRate, congestionLevel, riskLevel);
                                    
        } catch (Exception e) {
            log.warn("Failed to enrich data: vehicleCount={}, routeDetails={}",
                    vehicleCount, routeDetails);
            return createErrorJson(vehicleCount, routeDetails);
        }
    }

    /**
     * Simple field extraction from JSON-like string
     */
    private String extractField(String json, String field) {
        // Simplified extraction - in real app use proper JSON parser
        String pattern = "\"" + field + "\":\"?([^,\"}]+)\"?";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "unknown";
    }

    /**
     * Determine congestion level based on occupancy rate
     */
    private String getCongestionLevel(double occupancyRate) {
        if (occupancyRate >= 90) return "CRITICAL";
        if (occupancyRate >= 70) return "HIGH";  
        if (occupancyRate >= 50) return "MEDIUM";
        return "LOW";
    }

    /**
     * Calculate risk level based on multiple factors
     */
    private String calculateRiskLevel(int vehicleCount, int capacity, String routeType) {
        double occupancy = (double) vehicleCount / capacity;
        
        return switch (routeType.toLowerCase()) {
            case "highway" -> occupancy > 0.8 ? "HIGH" : "MEDIUM";
            case "urban" -> occupancy > 0.6 ? "HIGH" : "LOW";
            case "residential" -> occupancy > 0.4 ? "MEDIUM" : "LOW";
            default -> "UNKNOWN";
        };
    }

    /**
     * Create enriched JSON output
     */
    private String createEnrichedJson(String routeName, String routeType, int speedLimit, 
                                    int capacity, int vehicleCount, double occupancyRate, 
                                    String congestionLevel, String riskLevel) {
        return String.format("""
            {
                "routeName": "%s",
                "routeType": "%s", 
                "speedLimit": %d,
                "capacity": %d,
                "currentVehicles": %d,
                "occupancyRate": %.2f,
                "congestionLevel": "%s",
                "riskLevel": "%s",
                "timestamp": "%s",
                "recommendations": %s
            }""", 
            routeName, routeType, speedLimit, capacity, vehicleCount, 
            occupancyRate, congestionLevel, riskLevel,
            java.time.Instant.now(),
            getRecommendations(congestionLevel, routeType)
        );
    }

    /**
     * Generate recommendations based on congestion and route type
     */
    private String getRecommendations(String congestionLevel, String routeType) {
        return switch (congestionLevel) {
            case "CRITICAL" -> switch (routeType.toLowerCase()) {
                case "highway" -> "[\"Consider alternative routes\", \"Reduce speed\", \"Increase following distance\"]";
                case "urban" -> "[\"Use public transport\", \"Avoid if possible\", \"Expect delays\"]";
                default -> "[\"Exercise caution\", \"Consider delays\"]";
            };
            case "HIGH" -> "[\"Monitor traffic conditions\", \"Allow extra travel time\"]";
            default -> "[\"Normal traffic conditions\"]";
        };
    }

    /**
     * Create error JSON for failed enrichment
     */
    private String createErrorJson(String vehicleCount, String routeDetails) {
        return String.format("""
            {
                "error": "enrichment_failed",
                "rawVehicleCount": "%s",
                "rawRouteDetails": "%s",
                "timestamp": "%s"
            }""", 
            vehicleCount, routeDetails, java.time.Instant.now()
        );
    }

    /**
     * Filter for congested routes
     */
    private boolean isCongested(String routeId, String enrichedData) {
        try {
            // Look for HIGH or CRITICAL congestion levels
            return enrichedData.contains("\"congestionLevel\": \"HIGH\"") || 
                   enrichedData.contains("\"congestionLevel\": \"CRITICAL\"");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Kafka Streams configuration
     */
    private Properties getProperties() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        
        // Join optimizations
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024);
        
        return props;
    }
}