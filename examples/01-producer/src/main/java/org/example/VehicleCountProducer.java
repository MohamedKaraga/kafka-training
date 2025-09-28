package org.example;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.Random;

/**
 * Simple Kafka Producer for vehicle count simulation
 **/
public class VehicleCountProducer {

    private static final Logger log = LoggerFactory.getLogger(VehicleCountProducer.class);
    
    private static final String TOPIC_NAME = "vehicle-count";
    private static final int NUMBER_OF_RECORDS = 1000;
    private static final int MIN_VEHICLES = 1;
    private static final int MAX_VEHICLES = 500;
    
    private static final String[] ROUTES = {
        "route-A1",    // Autoroute A1
        "route-A6",    // Autoroute A6  
        "route-N7",    // Nationale 7
        "route-BP",    // Boulevard Périphérique
        "route-M25"    // Rocade
    };

    public static void main(String[] args) {
        log.info("Starting Vehicle Count Producer");
        
        VehicleCountProducer producer = new VehicleCountProducer();
        producer.run();
        
        log.info("Producer finished successfully");
    }

    public void run() {
        Properties props = createProducerProperties();
        Random random = new Random();

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            
            log.info("Sending {} vehicle count messages...", NUMBER_OF_RECORDS);
            
            for (int i = 0; i < NUMBER_OF_RECORDS; i++) {
                String route = ROUTES[random.nextInt(ROUTES.length)];
                int vehicleCount = random.nextInt(MAX_VEHICLES - MIN_VEHICLES) + MIN_VEHICLES;
                
                sendVehicleCount(producer, route, vehicleCount, i);
                
                // Small delay to see the flow
                sleep(50);
            }
            
            // Ensure all messages are sent
            producer.flush();
            log.info("All messages sent and acknowledged");
            
        } catch (Exception e) {
            log.error("Producer error", e);
        }
    }

    private Properties createProducerProperties() {
        Properties props = new Properties();
        
        // Basic configuration
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // Performance and reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");           // Wait for all replicas
        props.put(ProducerConfig.RETRIES_CONFIG, 3);            // Retry on failure
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);     // 16KB batches
        props.put(ProducerConfig.LINGER_MS_CONFIG, 100);        // Wait 100ms for batching
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB buffer
        
        return props;
    }

    private void sendVehicleCount(KafkaProducer<String, String> producer, String route, int count, int messageId) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
            TOPIC_NAME, 
            route, 
            String.valueOf(count)
        );
        
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                log.error("Failed to send message {}: {}", messageId, exception.getMessage());
            } else {
                log.debug("Sent: {} = {} to partition {}, offset {}",
                    route, count, metadata.partition(), metadata.offset());
            }
        });
        
        // Log progress every 100 messages
        if (messageId % 100 == 0) {
            log.info("Progress: {}/{} messages sent", messageId, NUMBER_OF_RECORDS);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Producer interrupted");
        }
    }
}