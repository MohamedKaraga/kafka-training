# REX Kafka : Guide des bonnes pratiques pour développeurs

> **Note importante :** Ce guide se concentre sur la configuration **côté client** (Producer et Consumer). Il distingue clairement les **valeurs officielles** (issues de la documentation Apache Kafka) des **recommandations empiriques** (à tester dans votre environnement).

## Légende des configurations

- **[OFFICIEL]** : Valeur par défaut ou configuration documentée officiellement par Apache Kafka
- **[RECOMMANDÉ]** : Best practice courante, mais à valider dans votre environnement
- **[CRITIQUE]** : Configuration essentielle pour le bon fonctionnement
- **[À TESTER]** : Valeur de départ, nécessite des benchmarks

---

## Table des matières

1. [Comprendre les defaults Kafka](#1-comprendre-les-defaults-kafka)
2. [Configuration Producer](#2-configuration-producer)
3. [Configuration Consumer](#3-configuration-consumer)
4. [Méthodologie de tuning](#4-méthodologie-de-tuning)
5. [Gestion des offsets](#5-gestion-des-offsets)
6. [Partitioning](#6-partitioning)
7. [Résilience et gestion d'erreurs](#7-résilience-et-gestion-derreurs)
8. [Monitoring côté client](#8-monitoring-côté-client)
9. [Debug et troubleshooting](#9-debug-et-troubleshooting)
10. [Anti-patterns à éviter](#10-anti-patterns-à-éviter)
11. [Checklist développeur](#11-checklist-développeur)

---

## 1. Comprendre les defaults Kafka

### 1.1 Defaults Producer [OFFICIEL]

```properties
# Configuration par défaut d'un Producer Kafka
# Source : Apache Kafka Documentation

# Batching et performance
linger.ms=5                               # 5ms (Kafka 4.0+, avant : 0)
batch.size=16384                          # 16KB
buffer.memory=33554432                    # 32MB

# Fiabilité
acks=1                                    # Leader seul (all si idempotence activée)
enable.idempotence=false
retries=2147483647                        # Retry infini
max.in.flight.requests.per.connection=5

# Compression
compression.type=none                     # Pas de compression par défaut

# Timeouts
max.block.ms=60000                        # 60 secondes
request.timeout.ms=30000                  # 30 secondes
delivery.timeout.ms=120000                # 2 minutes
```

### 1.2 Defaults Consumer [OFFICIEL]

```properties
# Configuration par défaut d'un Consumer Kafka
# Source : Apache Kafka Documentation

# Gestion des offsets
enable.auto.commit=true                   # Auto-commit activé
auto.commit.interval.ms=5000              # 5 secondes
auto.offset.reset=latest                  # Démarre à la fin

# Timeouts et heartbeat
session.timeout.ms=45000                  # 45 secondes
max.poll.interval.ms=300000               # 5 minutes
heartbeat.interval.ms=3000                # 3 secondes

# Fetch
fetch.min.bytes=1                         # 1 byte minimum
fetch.max.wait.ms=500                     # 500ms d'attente max
max.poll.records=500                      # 500 records par poll
```

**Important :** Ces valeurs fonctionnent mais ne sont pas optimisées. Le reste du guide explique comment les adapter à votre cas d'usage.

---

## 2. Configuration Producer

### 2.1 Configuration minimale [CRITIQUE]

```java
Properties props = new Properties();

// Configuration obligatoire
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
    StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
    StringSerializer.class.getName());

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
```

Cette configuration utilise tous les defaults Kafka. Elle fonctionne mais n'est pas optimisée.

### 2.2 Configuration production recommandée [RECOMMANDÉ]

```java
Properties props = new Properties();

// Connexion [CRITIQUE]
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, 
    "kafka1:9092,kafka2:9092,kafka3:9092");

// Sérialisation [CRITIQUE]
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
    StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
    StringSerializer.class.getName());

// Fiabilité [RECOMMANDÉ - garantit aucune perte]
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

// Note : enable.idempotence=true active automatiquement :
// - acks=all
// - retries=2147483647
// - max.in.flight.requests.per.connection=5

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
```

**Explication :**
- `acks=all` : Tous les replicas in-sync confirment la réception
- `enable.idempotence=true` : Élimine les duplicatas automatiquement
- **Recommandé SYSTÉMATIQUEMENT en production**

### 2.3 Ajout de compression [RECOMMANDÉ]

```java
// Configuration de base +
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
```

**Comparatif des codecs de compression :**

| Codec | Vitesse | Ratio | Usage recommandé |
|-------|---------|-------|------------------|
| none | N/A | 1:1 | Faible volumétrie |
| snappy | Très rapide | Moyen | Logs, métriques |
| lz4 | Très rapide | Bon | **Production générale (RECOMMANDÉ)** |
| gzip | Lent | Excellent | Stockage critique |
| zstd | Moyen | Très bon | Bon équilibre (Kafka 2.1+) |

**À faire :** Tester avec VOS données et mesurer via JMX `compression-rate-avg`

### 2.4 Optimisation batching [À TESTER]

```java
// Configuration de base + compression +
props.put(ProducerConfig.LINGER_MS_CONFIG, 10);      // [À TESTER] 10ms
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);  // [À TESTER] 64KB
```

**Impact :**
- `linger.ms` : Attend X ms avant d'envoyer pour accumuler plus de messages
- `batch.size` : Taille maximale d'un batch en bytes

**Valeurs à tester progressivement :**
- `linger.ms` : 0, 5, 10, 20, 50, 100
- `batch.size` : 16384 (16KB), 32768 (32KB), 65536 (64KB), 131072 (128KB)

**Comment choisir :**
1. Partir du default
2. Augmenter progressivement
3. Mesurer : throughput, latence, `batch-size-avg` (JMX)
4. Trouver le compromis acceptable pour votre application

### 2.5 Augmentation buffer [À TESTER si haute volumétrie]

```java
// Si volumétrie très élevée
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864);  // 64MB
```

**Quand augmenter ?**
- Métrique JMX `buffer-available-bytes` < 10%
- Exceptions `BufferExhaustedException`
- Logs indiquant que le producer bloque

**Valeurs à tester :**
- 33554432 (32MB - default)
- 67108864 (64MB)
- 134217728 (128MB)

### 2.6 Configuration haute performance (logs non-critiques)

Pour logs applicatifs, métriques où une perte occasionnelle est acceptable :

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
    StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
    StringSerializer.class.getName());

// Performance maximale (durabilité réduite)
props.put(ProducerConfig.ACKS_CONFIG, "1");           // Leader seul
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
props.put(ProducerConfig.RETRIES_CONFIG, 3);
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
```

### 2.7 Utilisation avec callback [RECOMMANDÉ]

```java
ProducerRecord<String, String> record = 
    new ProducerRecord<>("mon-topic", key, value);

producer.send(record, new Callback() {
    @Override
    public void onCompletion(RecordMetadata metadata, Exception exception) {
        if (exception != null) {
            // Erreur d'envoi
            logger.error("Erreur envoi vers {}: {}", 
                metadata.topic(), exception.getMessage());
            // Gérer l'erreur : retry, DLQ, alerte
        } else {
            // Succès
            logger.debug("Message envoyé: topic={}, partition={}, offset={}", 
                metadata.topic(), metadata.partition(), metadata.offset());
        }
    }
});
```

**Important :** Le callback est exécuté dans un thread du producer. Ne pas faire de traitement bloquant.

### 2.8 Configuration complète exemple

```java
public class ProductionProducerConfig {
    
    public static KafkaProducer<String, String> createProducer() {
        Properties props = new Properties();
        
        // Connexion
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, 
            "kafka1:9092,kafka2:9092,kafka3:9092");
        
        // Sérialisation
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class.getName());
        
        // Fiabilité
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        // Performance
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        
        // Timeouts (defaults OK, mais peuvent être ajustés)
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 60000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        
        return new KafkaProducer<>(props);
    }
}
```

---

## 3. Configuration Consumer

### 3.1 Configuration minimale [CRITIQUE]

```java
Properties props = new Properties();

// Configuration obligatoire
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
    StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
    StringDeserializer.class.getName());
props.put(ConsumerConfig.GROUP_ID_CONFIG, "mon-consumer-group");

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("mon-topic"));
```

Cette configuration utilise tous les defaults Kafka (auto-commit activé).

### 3.2 Configuration production robuste [RECOMMANDÉ]

```java
Properties props = new Properties();

// Connexion [CRITIQUE]
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
    "kafka1:9092,kafka2:9092,kafka3:9092");

// Désérialisation [CRITIQUE]
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
    StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
    StringDeserializer.class.getName());

// Consumer group [CRITIQUE]
props.put(ConsumerConfig.GROUP_ID_CONFIG, "mon-application-group");
props.put(ConsumerConfig.CLIENT_ID_CONFIG, "mon-app-consumer-1");

// Gestion des offsets [RECOMMANDÉ pour exactly-once]
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
```

**Explication :**
- `enable.auto.commit=false` : Commit manuel après traitement réussi
- `auto.offset.reset=earliest` : Si nouveau groupe, démarre au début
- Garantit qu'aucun message n'est perdu en cas de crash

### 3.3 Optimisation des timeouts [À TESTER]

```java
// Timeouts plus agressifs que defaults (détection rapide des pannes)
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);    // 10s au lieu de 45s
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30000);  // 30s au lieu de 5min
props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);  // 3s (default OK)
```

**Defaults officiels [OFFICIEL] :**
- `session.timeout.ms=45000` (45 secondes)
- `max.poll.interval.ms=300000` (5 minutes)

**Valeurs réduites [À TESTER] :**
- Détecte consumer mort en 10s au lieu de 45s
- Plus strict sur le temps de traitement

**Avantage :** Détection rapide des pannes, redistribution plus rapide

**Inconvénient :** Peut causer des rebalancing intempestifs si :
- Réseau lent
- Traitement long des messages
- GC pauses importantes

**Recommandation :** 
- Commencer avec les defaults
- Réduire progressivement si besoin
- Adapter `max.poll.interval.ms` à votre temps de traitement réel

### 3.4 Configuration auto-commit [OFFICIEL]

Pour applications simples où une perte occasionnelle est acceptable :

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
    StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
    StringDeserializer.class.getName());
props.put(ConsumerConfig.GROUP_ID_CONFIG, "mon-groupe");

// Auto-commit (defaults Kafka)
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);        // Default
props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);   // 5s
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");     // Default
```

**Attention :** Avec auto-commit, si crash après `poll()` mais avant traitement, les messages sont perdus.

### 3.5 Pattern de consommation avec commit manuel [RECOMMANDÉ]

```java
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("mon-topic"));

try {
    while (true) {
        ConsumerRecords<String, String> records = 
            consumer.poll(Duration.ofMillis(100));
        
        for (ConsumerRecord<String, String> record : records) {
            try {
                // Traiter le message
                processRecord(record);
                
                // Commit APRÈS traitement réussi
                consumer.commitSync();
                
            } catch (Exception e) {
                logger.error("Erreur traitement, offset non commité", e);
                // Le message sera retraité au prochain poll
            }
        }
    }
} finally {
    consumer.close();
}
```

### 3.6 Pattern avec commit par batch [PLUS PERFORMANT]

```java
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("mon-topic"));

try {
    while (true) {
        ConsumerRecords<String, String> records = 
            consumer.poll(Duration.ofMillis(100));
        
        int count = 0;
        for (ConsumerRecord<String, String> record : records) {
            processRecord(record);
            count++;
        }
        
        // Commit après traitement de tout le batch
        consumer.commitSync();
        logger.info("Traité et commité {} messages", count);
    }
} finally {
    consumer.close();
}
```

**Avantage :** Meilleure performance (moins de commits)

**Inconvénient :** En cas d'erreur, tout le batch est retraité

### 3.7 Configuration complète exemple

```java
public class ProductionConsumerConfig {
    
    public static KafkaConsumer<String, String> createConsumer() {
        Properties props = new Properties();
        
        // Connexion
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
            "kafka1:9092,kafka2:9092,kafka3:9092");
        
        // Désérialisation
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            StringDeserializer.class.getName());
        
        // Consumer group
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "mon-application");
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, 
            "mon-app-" + InetAddress.getLocalHost().getHostName());
        
        // Offsets
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Timeouts (à adapter selon votre traitement)
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30000);
        
        // Fetch (defaults OK pour la plupart des cas)
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        return new KafkaConsumer<>(props);
    }
}
```

---

## 4. Méthodologie de tuning

### 4.1 Processus de validation Producer

**Étape 1 : Baseline avec defaults**

```bash
kafka-producer-perf-test \
  --topic test-perf \
  --num-records 100000 \
  --record-size 1000 \
  --throughput -1 \
  --producer-props bootstrap.servers=localhost:9092

# Noter les métriques :
# - Throughput (records/sec)
# - Latence moyenne
# - Latence p99
```

**Étape 2 : Ajouter compression**

```bash
kafka-producer-perf-test \
  --topic test-perf \
  --num-records 100000 \
  --record-size 1000 \
  --throughput -1 \
  --producer-props \
    bootstrap.servers=localhost:9092 \
    compression.type=lz4

# Comparer avec baseline
```

**Étape 3 : Optimiser batching**

```bash
kafka-producer-perf-test \
  --topic test-perf \
  --num-records 100000 \
  --record-size 1000 \
  --throughput -1 \
  --producer-props \
    bootstrap.servers=localhost:9092 \
    compression.type=lz4 \
    linger.ms=10 \
    batch.size=65536
```

**Étape 4 : Analyser les résultats**

| Config | Throughput | Latence p99 | Décision |
|--------|-----------|-------------|----------|
| Default | 10k/s | 50ms | Baseline |
| + compression | 12k/s | 55ms | ✅ Gain net |
| + batching | 18k/s | 65ms | ✅ Si latence OK |

### 4.2 Métriques JMX à surveiller

**Producer :**
```java
// Accéder aux métriques
Map<MetricName, ? extends Metric> metrics = producer.metrics();

// Métriques clés :
// - record-send-rate : messages/sec envoyés
// - batch-size-avg : taille moyenne des batchs
// - compression-rate-avg : ratio de compression
// - buffer-available-bytes : mémoire buffer disponible
// - request-latency-avg : latence réseau
```

**Consumer :**
```java
// Accéder aux métriques
Map<MetricName, ? extends Metric> metrics = consumer.metrics();

// Métriques clés :
// - records-lag-max : lag maximum
// - fetch-rate : fetch par seconde
// - records-consumed-rate : messages/sec consommés
```

### 4.3 Logging des métriques

```java
public class MetricsLogger {
    
    public static void logProducerMetrics(KafkaProducer<?, ?> producer) {
        Map<MetricName, ? extends Metric> metrics = producer.metrics();
        
        metrics.forEach((name, metric) -> {
            if (name.name().equals("record-send-rate") ||
                name.name().equals("batch-size-avg") ||
                name.name().equals("compression-rate-avg")) {
                logger.info("Métrique {}: {}", name.name(), metric.metricValue());
            }
        });
    }
    
    public static void logConsumerMetrics(KafkaConsumer<?, ?> consumer) {
        Map<MetricName, ? extends Metric> metrics = consumer.metrics();
        
        metrics.forEach((name, metric) -> {
            if (name.name().equals("records-lag-max") ||
                name.name().equals("records-consumed-rate")) {
                logger.info("Métrique {}: {}", name.name(), metric.metricValue());
            }
        });
    }
}
```

---

## 5. Gestion des offsets

### 5.1 Le mythe de auto.offset.reset [IMPORTANT]

**Erreur courante :** Penser que `auto.offset.reset=earliest` fait toujours recommencer depuis le début.

**Réalité [OFFICIEL] :**

```java
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
```

Cette configuration ne s'applique QUE dans ces cas :
1. Le consumer group n'existe pas encore (nouveau groupe)
2. Les offsets du consumer group ont expiré
3. L'offset demandé n'existe plus dans le log

**Si le consumer group existe et a des offsets valides, cette config est IGNORÉE.**

### 5.2 Comment vraiment recommencer depuis le début

**Option 1 : Changer de group.id**

```java
// Simple : utiliser un nouveau groupe
props.put(ConsumerConfig.GROUP_ID_CONFIG, "mon-groupe-v2");
```

**Option 2 : Utiliser seek() [RECOMMANDÉ]**

```java
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("mon-topic"));

// Premier poll pour obtenir l'assignation des partitions
consumer.poll(Duration.ofMillis(0));

// Revenir au début de chaque partition assignée
consumer.seekToBeginning(consumer.assignment());

// Ou à la fin
consumer.seekToEnd(consumer.assignment());
```

**Option 3 : Seek à un timestamp**

```java
// Consommer depuis une date précise
long timestamp = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();

Map<TopicPartition, Long> timestampsToSearch = new HashMap<>();
for (TopicPartition partition : consumer.assignment()) {
    timestampsToSearch.put(partition, timestamp);
}

Map<TopicPartition, OffsetAndTimestamp> offsets = 
    consumer.offsetsForTimes(timestampsToSearch);

offsets.forEach((partition, offsetAndTimestamp) -> {
    if (offsetAndTimestamp != null) {
        consumer.seek(partition, offsetAndTimestamp.offset());
    }
});
```

### 5.3 Auto-commit vs Commit manuel [CRITIQUE]

**Auto-commit - Risque de perte :**

```java
// Scénario de perte avec auto-commit=true
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);

// 1. poll() récupère 100 messages
ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

// 2. Traitement des 50 premiers messages
// 3. Auto-commit se déclenche (offset avancé à 100)
// 4. CRASH avant traitement des 50 derniers

// Résultat : 50 messages perdus définitivement
```

**Commit manuel - Exactly-once [RECOMMANDÉ] :**

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

for (ConsumerRecord<String, String> record : records) {
    try {
        processRecord(record);
        // Commit APRÈS traitement réussi
        consumer.commitSync();
    } catch (Exception e) {
        logger.error("Erreur, offset non commité - message sera retraité");
        break; // Sortir de la boucle, ne pas commiter
    }
}
```

### 5.4 Commit asynchrone pour performance

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

for (ConsumerRecord<String, String> record : records) {
    processRecord(record);
}

// Commit asynchrone (non-bloquant)
consumer.commitAsync((offsets, exception) -> {
    if (exception != null) {
        logger.error("Erreur commit: {}", exception.getMessage());
    } else {
        logger.debug("Commit réussi: {}", offsets);
    }
});
```

---

## 6. Partitioning

### 6.1 Le principe fondamental [CRITIQUE]

**Règle d'or :**
- **Même clé** → **Même partition** → **Ordre garanti**
- **Null key** → **Partition aléatoire** → **Pas d'ordre**

**Mauvaise pratique :**
```java
// Sans clé = pas d'ordre garanti
ProducerRecord<String, String> record = 
    new ProducerRecord<>("topic", null, message);
producer.send(record);
```

**Bonne pratique :**
```java
// Avec clé = ordre garanti par clé
String userId = "user123";
ProducerRecord<String, String> record = 
    new ProducerRecord<>("topic", userId, message);
producer.send(record);

// Tous les messages avec userId="user123" iront dans la même partition
// Donc ordre garanti pour cet utilisateur
```

### 6.2 Custom Partitioner [AVANCÉ]

```java
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;

public class CustomPartitioner implements Partitioner {
    
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                        Object value, byte[] valueBytes, Cluster cluster) {
        
        int numPartitions = cluster.partitionCountForTopic(topic);
        
        if (key == null) {
            // Si pas de clé, distribution aléatoire
            return new Random().nextInt(numPartitions);
        }
        
        String keyStr = (String) key;
        
        // Exemple : router par région géographique
        if (keyStr.startsWith("EU-")) {
            return 0; // Partition 0 pour Europe
        } else if (keyStr.startsWith("US-")) {
            return 1; // Partition 1 pour USA
        } else if (keyStr.startsWith("ASIA-")) {
            return 2; // Partition 2 pour Asie
        }
        
        // Fallback : hash standard
        return Math.abs(keyStr.hashCode()) % numPartitions;
    }
    
    @Override
    public void close() {
        // Cleanup si nécessaire
    }
    
    @Override
    public void configure(Map<String, ?> configs) {
        // Configuration du partitioner si nécessaire
    }
}
```

**Utilisation :**
```java
props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, 
    CustomPartitioner.class.getName());
```

### 6.3 Comprendre la distribution des partitions

**Règle importante :**
```
Nombre de consumers ≤ Nombre de partitions
```

**Exemples :**

| Partitions | Consumers | Distribution |
|-----------|-----------|--------------|
| 10 | 1 | 1 consumer gère 10 partitions |
| 10 | 3 | Chaque consumer gère ~3 partitions |
| 10 | 10 | Chaque consumer gère 1 partition |
| 10 | 15 | 10 actifs, 5 IDLE (inutiles) |

**En code :**
```java
// Voir les partitions assignées à ce consumer
Set<TopicPartition> assignment = consumer.assignment();
logger.info("Partitions assignées: {}", assignment);
```

---

## 7. Résilience et gestion d'erreurs

### 7.1 Configuration acks [CRITIQUE]

**Matrice de décision :**

| Use Case | acks | Perte possible | Performance |
|----------|------|----------------|-------------|
| Logs applicatifs | 1 | Rare | Haute |
| Métriques | 1 | Acceptable | Haute |
| Événements métier | all | Non | Moyenne |
| Transactions | all | Non | Moyenne |

**acks=0 - Fire and forget :**
```java
props.put(ProducerConfig.ACKS_CONFIG, "0");
// Performance max, mais perte possible
// Usage : logs non-critiques uniquement
```

**acks=1 - Leader seul [DEFAULT] :**
```java
props.put(ProducerConfig.ACKS_CONFIG, "1");
// Compromis performance/fiabilité
// Usage : la plupart des applications
```

**acks=all - Tous les replicas [RECOMMANDÉ] :**
```java
props.put(ProducerConfig.ACKS_CONFIG, "all");
// Aucune perte de données
// Usage : données critiques
```

### 7.2 Idempotence [RECOMMANDÉ SYSTÉMATIQUEMENT]

```java
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
```

**Ce que ça active automatiquement [OFFICIEL] :**
- `acks=all`
- `retries=2147483647`
- `max.in.flight.requests.per.connection=5`

**Avantages :**
- Élimine les duplicatas automatiquement
- Pas d'impact performance notable
- **Recommandé pour TOUTES les applications**

### 7.3 Pattern Circuit Breaker [AVANCÉ]

Pour applications qui ne peuvent pas être bloquées si Kafka est indisponible :

```java
public class ResilientKafkaProducer {
    
    private final KafkaProducer<String, String> producer;
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final int ERROR_THRESHOLD = 10;
    private volatile boolean circuitOpen = false;
    
    public ResilientKafkaProducer(Properties config) {
        this.producer = new KafkaProducer<>(config);
    }
    
    public void send(String topic, String key, String value) {
        // Vérifier l'état du circuit AVANT d'envoyer
        if (circuitOpen) {
            logger.warn("Circuit ouvert - Message rejeté : {}", key);
            // Option 1 : Rejeter
            // Option 2 : Sauvegarder localement pour replay
            return;
        }
        
        ProducerRecord<String, String> record = 
            new ProducerRecord<>(topic, key, value);
        
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                int errors = consecutiveErrors.incrementAndGet();
                logger.error("Erreur envoi ({}/{}): {}", 
                    errors, ERROR_THRESHOLD, exception.getMessage());
                
                // Ouvrir le circuit après N erreurs consécutives
                if (errors >= ERROR_THRESHOLD) {
                    circuitOpen = true;
                    logger.error("CIRCUIT OUVERT - Kafka indisponible");
                }
            } else {
                // Succès : reset et fermer le circuit
                consecutiveErrors.set(0);
                if (circuitOpen) {
                    circuitOpen = false;
                    logger.info("CIRCUIT FERMÉ - Kafka disponible");
                }
            }
        });
    }
    
    public void close() {
        producer.close();
    }
}
```

### 7.4 Gestion des erreurs Consumer

```java
public class ResilientConsumer {
    
    private final KafkaConsumer<String, String> consumer;
    private final int MAX_RETRIES = 3;
    
    public void consume() {
        consumer.subscribe(Arrays.asList("mon-topic"));
        
        while (true) {
            try {
                ConsumerRecords<String, String> records = 
                    consumer.poll(Duration.ofMillis(100));
                
                for (ConsumerRecord<String, String> record : records) {
                    processWithRetry(record);
                }
                
                consumer.commitSync();
                
            } catch (Exception e) {
                logger.error("Erreur critique dans la boucle consumer", e);
                // Gérer selon criticité : continue ou break
            }
        }
    }
    
    private void processWithRetry(ConsumerRecord<String, String> record) {
        int attempts = 0;
        Exception lastException = null;
        
        while (attempts < MAX_RETRIES) {
            try {
                processRecord(record);
                return; // Succès
            } catch (Exception e) {
                attempts++;
                lastException = e;
                logger.warn("Tentative {}/{} échouée pour offset {}: {}", 
                    attempts, MAX_RETRIES, record.offset(), e.getMessage());
                
                try {
                    Thread.sleep(1000 * attempts); // Backoff exponentiel
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        
        // Échec après MAX_RETRIES
        logger.error("Échec définitif après {} tentatives, offset {}", 
            MAX_RETRIES, record.offset());
        sendToDLQ(record, lastException);
    }
    
    private void sendToDLQ(ConsumerRecord<String, String> record, Exception e) {
        // Envoyer vers Dead Letter Queue
        logger.error("Message envoyé au DLQ: {}", record.key());
    }
}
```

---

## 8. Monitoring côté client

### 8.1 Métriques Producer essentielles

```java
public class ProducerMonitoring {
    
    public static void logMetrics(KafkaProducer<?, ?> producer) {
        Map<MetricName, ? extends Metric> metrics = producer.metrics();
        
        // 1. Taux d'erreur (doit être 0)
        findAndLog(metrics, "record-error-rate");
        
        // 2. Buffer disponible (surveiller si < 10%)
        findAndLog(metrics, "buffer-available-bytes");
        
        // 3. Taille moyenne des batchs
        findAndLog(metrics, "batch-size-avg");
        
        // 4. Ratio de compression
        findAndLog(metrics, "compression-rate-avg");
        
        // 5. Latence réseau
        findAndLog(metrics, "request-latency-avg");
    }
    
    private static void findAndLog(Map<MetricName, ? extends Metric> metrics, 
                                   String name) {
        metrics.entrySet().stream()
            .filter(e -> e.getKey().name().equals(name))
            .forEach(e -> logger.info("Métrique {}: {}", 
                name, e.getValue().metricValue()));
    }
}
```

### 8.2 Métriques Consumer essentielles

```java
public class ConsumerMonitoring {
    
    public static void logMetrics(KafkaConsumer<?, ?> consumer) {
        Map<MetricName, ? extends Metric> metrics = consumer.metrics();
        
        // 1. Lag maximum (alerter si > 1000)
        findAndLog(metrics, "records-lag-max");
        
        // 2. Taux de consommation
        findAndLog(metrics, "records-consumed-rate");
        
        // 3. Taux de fetch
        findAndLog(metrics, "fetch-rate");
        
        // 4. Partitions assignées
        logger.info("Partitions assignées: {}", consumer.assignment().size());
    }
}
```

### 8.3 Monitoring périodique avec ScheduledExecutor

```java
public class KafkaMetricsScheduler {
    
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(1);
    
    public void startMonitoring(KafkaProducer<?, ?> producer) {
        scheduler.scheduleAtFixedRate(
            () -> ProducerMonitoring.logMetrics(producer),
            0, 30, TimeUnit.SECONDS
        );
    }
    
    public void startMonitoring(KafkaConsumer<?, ?> consumer) {
        scheduler.scheduleAtFixedRate(
            () -> ConsumerMonitoring.logMetrics(consumer),
            0, 30, TimeUnit.SECONDS
        );
    }
    
    public void shutdown() {
        scheduler.shutdown();
    }
}
```

---

## 9. Debug et troubleshooting

### 9.1 Vérifier un consumer group (CLI)

```bash
# Voir l'état du consumer group
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group mon-groupe

# Colonnes importantes :
# - CURRENT-OFFSET : position actuelle
# - LOG-END-OFFSET : fin du log
# - LAG : retard (LOG-END - CURRENT)
# - CONSUMER-ID : qui consomme
# - HOST : sur quel serveur
```

### 9.2 Reset des offsets (CLI)

**IMPORTANT : Arrêter tous les consumers du groupe d'abord**

```bash
# Reset au début (rejouer tout)
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-earliest \
  --topic mon-topic \
  --execute

# Reset à la fin (skip tout)
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-latest \
  --topic mon-topic \
  --execute

# Reset à une date précise
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-datetime 2025-01-10T08:00:00.000 \
  --topic mon-topic \
  --execute
```

### 9.3 Debug en code : afficher les records

```java
public class DebugConsumer {
    
    public static void debugPoll(KafkaConsumer<String, String> consumer) {
        ConsumerRecords<String, String> records = 
            consumer.poll(Duration.ofMillis(100));
        
        logger.info("Récupéré {} records", records.count());
        
        for (ConsumerRecord<String, String> record : records) {
            logger.debug(
                "Topic: {}, Partition: {}, Offset: {}, Key: {}, Value: {}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value()
            );
        }
    }
}
```

### 9.4 Tester la performance

```bash
# Producer performance test
kafka-producer-perf-test \
  --topic test-perf \
  --num-records 100000 \
  --record-size 1000 \
  --throughput -1 \
  --producer-props \
    bootstrap.servers=localhost:9092 \
    acks=all \
    compression.type=lz4

# Consumer performance test
kafka-consumer-perf-test \
  --bootstrap-server localhost:9092 \
  --topic test-perf \
  --messages 100000 \
  --threads 1
```

---

## 10. Anti-patterns à éviter

### 10.1 Le send() bloquant en boucle [CATASTROPHIQUE]

**MAUVAIS - 10 msg/sec :**
```java
for (int i = 0; i < 1000000; i++) {
    // .get() BLOQUE à chaque message
    producer.send(record).get();
}
```

**CORRECT - 100k+ msg/sec :**
```java
for (int i = 0; i < 1000000; i++) {
    // Envoi asynchrone
    producer.send(record, callback);
}
producer.flush(); // Attendre la fin à la fin
```

### 10.2 Le Thread.sleep() dans le consumer [INUTILE]

**MAUVAIS :**
```java
while (true) {
    ConsumerRecords records = consumer.poll(Duration.ofMillis(100));
    processRecords(records);
    Thread.sleep(1000);  // POURQUOI ???
}
```

**CORRECT :**
```java
while (true) {
    // poll() gère déjà l'attente intelligemment
    ConsumerRecords records = consumer.poll(Duration.ofMillis(100));
    processRecords(records);
}
```

### 10.3 Fermeture des ressources sans try-with-resources

**MAUVAIS :**
```java
KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.send(record);
producer.close(); // Peut ne jamais être appelé si exception
```

**CORRECT :**
```java
try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
    producer.send(record);
} // close() appelé automatiquement
```

### 10.4 Ignorer les exceptions dans les callbacks

**MAUVAIS :**
```java
producer.send(record, (metadata, exception) -> {
    // Ne rien faire avec l'exception
});
```

**CORRECT :**
```java
producer.send(record, (metadata, exception) -> {
    if (exception != null) {
        logger.error("Erreur envoi: {}", exception.getMessage());
        // Gérer : retry, alerte, DLQ
    }
});
```

### 10.5 Ne pas configurer client.id

**MAUVAIS :**
```java
// client.id non défini = difficile à tracer
props.put(ConsumerConfig.GROUP_ID_CONFIG, "mon-groupe");
```

**CORRECT :**
```java
props.put(ConsumerConfig.GROUP_ID_CONFIG, "mon-groupe");
props.put(ConsumerConfig.CLIENT_ID_CONFIG, 
    "mon-app-" + InetAddress.getLocalHost().getHostName());
// Facilite le debug et le monitoring
```

---

## 11. Checklist développeur

### 11.1 Configuration Producer

- [ ] `bootstrap.servers` avec plusieurs brokers
- [ ] `key.serializer` et `value.serializer` définis
- [ ] `acks=all` pour données critiques
- [ ] `enable.idempotence=true` (recommandé systématiquement)
- [ ] Compression activée (`lz4` par défaut)
- [ ] Callbacks avec gestion d'erreurs
- [ ] `client.id` défini pour traçabilité
- [ ] Try-with-resources ou close() dans finally

### 11.2 Configuration Consumer

- [ ] `bootstrap.servers` avec plusieurs brokers
- [ ] `key.deserializer` et `value.deserializer` définis
- [ ] `group.id` défini
- [ ] `client.id` défini
- [ ] `enable.auto.commit=false` (commit manuel recommandé)
- [ ] `auto.offset.reset` défini explicitement
- [ ] Gestion des erreurs de traitement
- [ ] Try-with-resources ou close() dans finally

### 11.3 Code

- [ ] Pas de `.get()` après chaque `send()`
- [ ] Callbacks implémentés sur les `send()`
- [ ] Commit après traitement réussi (consumer)
- [ ] Gestion des exceptions
- [ ] Idempotence du traitement vérifiée
- [ ] Logs suffisants pour debug
- [ ] Métriques exposées ou loggées

### 11.4 Tests

- [ ] Tests unitaires avec Kafka embarqué
- [ ] Tests de charge effectués
- [ ] Tests avec Kafka indisponible (gestion erreurs)
- [ ] Tests de rebalancing (consumer)
- [ ] Performance mesurée (throughput, latence)

### 11.5 Documentation

- [ ] Configuration documentée
- [ ] Schéma des topics (clés, valeurs)
- [ ] Politique de retry documentée
- [ ] Gestion des erreurs documentée

---

## Annexe A : Exemples complets

### Producer complet avec bonnes pratiques

```java
public class ProductionKafkaProducer {
    
    private final KafkaProducer<String, String> producer;
    private final String topic;
    
    public ProductionKafkaProducer(String bootstrapServers, String topic) {
        this.topic = topic;
        
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "my-producer");
        
        // Fiabilité
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        
        // Performance
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        
        this.producer = new KafkaProducer<>(props);
    }
    
    public void send(String key, String value) {
        ProducerRecord<String, String> record = 
            new ProducerRecord<>(topic, key, value);
        
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                logger.error("Erreur envoi key={}: {}", key, exception.getMessage());
            } else {
                logger.debug("Envoyé: partition={}, offset={}", 
                    metadata.partition(), metadata.offset());
            }
        });
    }
    
    public void close() {
        producer.close(Duration.ofSeconds(30));
    }
}
```

### Consumer complet avec bonnes pratiques

```java
public class ProductionKafkaConsumer {
    
    private final KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;
    
    public ProductionKafkaConsumer(String bootstrapServers, 
                                   String groupId, 
                                   String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "my-consumer");
        
        // Offsets
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Timeouts
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30000);
        
        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList(topic));
    }
    
    public void start() {
        try {
            while (running) {
                ConsumerRecords<String, String> records = 
                    consumer.poll(Duration.ofMillis(100));
                
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        processRecord(record);
                    } catch (Exception e) {
                        logger.error("Erreur traitement offset {}: {}", 
                            record.offset(), e.getMessage());
                    }
                }
                
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            logger.info("Consumer wakeup");
        } finally {
            consumer.close();
        }
    }
    
    public void stop() {
        running = false;
        consumer.wakeup();
    }
    
    private void processRecord(ConsumerRecord<String, String> record) {
        logger.info("Traitement: key={}, value={}", record.key(), record.value());
        // Votre logique métier
    }
}
```

---

## Annexe B : Ressources

### Documentation officielle

- **Apache Kafka Documentation** : https://kafka.apache.org/documentation/
- **Confluent Documentation** : https://docs.confluent.io/platform/current/

### Livres recommandés

- "Kafka: The Definitive Guide" (O'Reilly)
- "Kafka in Action" (Manning)

### Outils

- **kcat** (CLI avancé) : https://github.com/edenhill/kcat
- **Kafka Tool** (GUI) : http://www.kafkatool.com/

---

**Version :** 2.0 - Développeurs  
**Dernière mise à jour :** Novembre 2025  
**Basé sur :** Apache Kafka 3.9 / 4.0
