# REX Kafka : Guide des bonnes pratiques et configurations

> **Note importante :** Ce guide distingue clairement les **valeurs officielles** (issues de la documentation Apache Kafka) des **recommandations empiriques** (à tester dans votre environnement). Les configurations de tuning doivent TOUJOURS être validées par des benchmarks spécifiques à votre cas d'usage.

## Légende des configurations

- **[OFFICIEL]** : Valeur par défaut ou configuration documentée officiellement par Apache Kafka
- **[RECOMMANDÉ]** : Best practice courante, mais à valider dans votre environnement
- **[CRITIQUE]** : Configuration essentielle pour le bon fonctionnement
- **[À TESTER]** : Valeur de départ, nécessite des benchmarks

---

## Table des matières

1. [Configurations critiques (OFFICIELLES)](#1-configurations-critiques-officielles)
2. [Configuration Producer](#2-configuration-producer)
3. [Configuration Consumer](#3-configuration-consumer)
4. [Configuration Broker](#4-configuration-broker)
5. [Méthodologie de tuning](#5-méthodologie-de-tuning)
6. [Gestion des offsets](#6-gestion-des-offsets)
7. [Partitioning](#7-partitioning)
8. [Résilience et haute disponibilité](#8-résilience-et-haute-disponibilité)
9. [Monitoring](#9-monitoring)
10. [Opérations et debug](#10-opérations-et-debug)
11. [Anti-patterns à éviter](#11-anti-patterns-à-éviter)
12. [Checklist pré-production](#12-checklist-pré-production)

---

## 1. Configurations critiques (OFFICIELLES)

### 1.1 Le problème du replication factor [CRITIQUE]

**Symptôme :** Le topic `__consumer_offsets` ne se crée pas automatiquement, les consumers ne démarrent pas.

**Cause :** Depuis KIP-115, Kafka refuse de créer les topics internes (`__consumer_offsets`, `__transaction_state`) si le nombre de brokers disponibles est inférieur au `replication.factor` configuré.

**Valeurs par défaut [OFFICIEL] :**
```properties
offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
```

**Configuration correcte [CRITIQUE] :**

```properties
# Développement/Test avec 1 broker
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1

# Production avec 3+ brokers
offsets.topic.replication.factor=3
offsets.topic.num.partitions=50
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2
```

**Impact :** C'est la cause numéro 1 des problèmes "mon consumer ne démarre pas" en environnement de développement.

**Source :** KIP-115 - Enforce offsets.topic.replication.factor upon __consumer_offsets auto topic creation

### 1.2 Valeurs par défaut officielles à connaître

```properties
# Producer [OFFICIEL - Source : Apache Kafka Documentation]
buffer.memory=33554432                    # 32MB
batch.size=16384                          # 16KB
linger.ms=5                               # 5ms (Kafka 4.0+, avant : 0)
acks=all                                  # avec enable.idempotence=true
max.block.ms=60000                        # 60 secondes
retries=2147483647                        # avec enable.idempotence=true
compression.type=none                     # Pas de compression par défaut

# Consumer [OFFICIEL - Source : Apache Kafka Documentation]
enable.auto.commit=true                   # Auto-commit activé
auto.commit.interval.ms=5000              # 5 secondes
session.timeout.ms=45000                  # 45 secondes
max.poll.interval.ms=300000               # 5 minutes
heartbeat.interval.ms=3000                # 3 secondes
auto.offset.reset=latest                  # Démarre à la fin
fetch.min.bytes=1                         # 1 byte minimum
max.poll.records=500                      # 500 records par poll

# Broker [OFFICIEL - Source : Apache Kafka Documentation]
num.partitions=1                          # 1 partition par défaut
default.replication.factor=1              # 1 replica par défaut
min.insync.replicas=1                     # 1 replica in-sync minimum
auto.create.topics.enable=true            # Création auto activée
log.retention.hours=168                   # 7 jours
```

---

## 2. Configuration Producer

### 2.1 Configuration minimale valide [OFFICIEL]

```properties
# Configuration minimale fonctionnelle
bootstrap.servers=localhost:9092
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=org.apache.kafka.common.serialization.StringSerializer
```

Cette configuration utilise tous les defaults Kafka. Elle fonctionne mais n'est pas optimisée.

### 2.2 Configuration production fiable [CRITIQUE]

```properties
# Connexion [CRITIQUE]
bootstrap.servers=kafka1:9092,kafka2:9092,kafka3:9092

# Sérialisation [CRITIQUE]
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=org.apache.kafka.common.serialization.StringSerializer

# Durabilité [RECOMMANDÉ - garantit aucune perte de données]
acks=all
enable.idempotence=true

# Note : enable.idempotence=true active automatiquement :
# - acks=all
# - retries=2147483647
# - max.in.flight.requests.per.connection=5
```

**Explication :**
- `acks=all` : Tous les replicas in-sync doivent confirmer la réception
- `enable.idempotence=true` : Élimine les duplicatas automatiquement, recommandé SYSTÉMATIQUEMENT

### 2.3 Ajout de compression [RECOMMANDÉ]

```properties
# Configuration de base +
compression.type=lz4
```

**Pourquoi lz4 ?**
- Très rapide en compression/décompression
- Bon ratio de compression
- Utilisé par défaut chez de nombreux utilisateurs Kafka en production

**Alternatives :**
- `snappy` : Légèrement moins de compression, très rapide
- `gzip` : Meilleure compression, plus lent (pour stockage critique)
- `zstd` : Bon équilibre (Kafka 2.1+)

**À faire :** Tester avec VOS données réelles et mesurer via JMX `compression-rate-avg`

### 2.4 Optimisation batching [À TESTER]

```properties
# Configuration de base + compression +
linger.ms=10          # [À TESTER] Point de départ : 10ms
batch.size=65536      # [À TESTER] Point de départ : 64KB
```

**Important :** Ces valeurs sont des **points de départ**, pas des recommandations universelles.

**Impact théorique :**
- `linger.ms` : Attend X ms avant d'envoyer pour accumuler plus de messages
- `batch.size` : Taille maximale d'un batch

**Comment trouver VOS valeurs optimales :**
1. Partir du default (`linger.ms=5`, `batch.size=16384`)
2. Augmenter progressivement
3. Mesurer via JMX : `batch-size-avg`, `request-latency-avg`
4. Trouver le compromis latence/throughput acceptable pour VOTRE application

**Valeurs à tester :**
- `linger.ms` : 0, 5, 10, 20, 50, 100
- `batch.size` : 16384 (16KB), 32768 (32KB), 65536 (64KB), 131072 (128KB)

### 2.5 Augmentation buffer [À TESTER si haute volumétrie]

```properties
# Si volumétrie très élevée
buffer.memory=67108864    # [À TESTER] 64MB au lieu de 32MB default
```

**Quand augmenter ?**
- Métrique JMX `buffer-available-bytes` < 10% du buffer
- Exceptions `BufferExhaustedException`
- Producer bloque régulièrement

**Valeurs à tester :** 33554432 (32MB default), 67108864 (64MB), 134217728 (128MB)

### 2.6 Configuration logs non-critiques [RECOMMANDÉ]

Pour logs applicatifs, métriques où une perte occasionnelle est acceptable :

```properties
bootstrap.servers=kafka1:9092,kafka2:9092,kafka3:9092
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=org.apache.kafka.common.serialization.StringSerializer

# Durabilité réduite pour performance maximale
acks=1                        # Leader seul (pas all)
enable.idempotence=false
retries=3
compression.type=snappy
```

### 2.7 Exemple de code avec callback

```java
ProducerRecord<String, String> record = new ProducerRecord<>("topic", key, value);

producer.send(record, (metadata, exception) -> {
    if (exception != null) {
        logger.error("Erreur envoi: {}", exception.getMessage());
        // Gérer l'erreur (retry, DLQ, alerte)
    } else {
        logger.info("Message envoyé: partition {}, offset {}", 
                    metadata.partition(), metadata.offset());
    }
});
```

---

## 3. Configuration Consumer

### 3.1 Configuration minimale valide [OFFICIEL]

```properties
# Configuration minimale fonctionnelle
bootstrap.servers=localhost:9092
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=org.apache.kafka.common.serialization.StringDeserializer
group.id=mon-consumer-group
```

Cette configuration utilise tous les defaults Kafka, notamment l'auto-commit.

### 3.2 Configuration production robuste [RECOMMANDÉ]

```properties
# Connexion [CRITIQUE]
bootstrap.servers=kafka1:9092,kafka2:9092,kafka3:9092

# Désérialisation [CRITIQUE]
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=org.apache.kafka.common.serialization.StringDeserializer

# Consumer group [CRITIQUE]
group.id=mon-application-group
client.id=mon-application-consumer-1

# Gestion des offsets [RECOMMANDÉ pour exactly-once]
enable.auto.commit=false
auto.offset.reset=earliest

# Timeouts [À TESTER - plus agressifs que defaults]
session.timeout.ms=10000      # Default: 45000
max.poll.interval.ms=30000    # Default: 300000
heartbeat.interval.ms=3000    # Default: 3000 (OK)

# Fetch [OFFICIEL - defaults OK]
fetch.min.bytes=1
fetch.max.wait.ms=500
max.poll.records=500
```

**Explication timeouts :**

**Valeurs par défaut [OFFICIEL] :**
- `session.timeout.ms=45000` (45 secondes)
- `max.poll.interval.ms=300000` (5 minutes)

**Valeurs réduites [À TESTER] :**
- `session.timeout.ms=10000` : Détecte consumer mort en 10s au lieu de 45s
- `max.poll.interval.ms=30000` : Plus strict sur le temps de traitement

**Avantage :** Détection rapide des pannes, redistribution plus rapide des partitions

**Inconvénient :** Peut causer des faux positifs (rebalancing intempestifs) si :
- Réseau lent
- Traitement long des messages
- GC pauses importantes

**Recommandation :** Tester avec VOS contraintes de traitement

### 3.3 Configuration auto-commit [OFFICIEL]

Pour applications simples où une perte occasionnelle est acceptable :

```properties
bootstrap.servers=kafka1:9092,kafka2:9092,kafka3:9092
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=org.apache.kafka.common.serialization.StringDeserializer
group.id=mon-application-group

# Auto-commit (defaults Kafka)
enable.auto.commit=true       # [OFFICIEL] Default
auto.commit.interval.ms=5000  # [OFFICIEL] Default - 5 secondes
auto.offset.reset=latest      # [OFFICIEL] Default

# Timeouts standards
session.timeout.ms=45000      # [OFFICIEL] Default
max.poll.interval.ms=300000   # [OFFICIEL] Default
```

**Attention :** Avec auto-commit, si crash après poll() mais avant traitement complet, les messages sont perdus.

### 3.4 Exemple de code avec commit manuel

```java
Properties props = new Properties();
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
// ... autres configs

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("mon-topic"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    
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
```

---

## 4. Configuration Broker

### 4.1 Configuration production (KRaft mode) [RECOMMANDÉ]

```properties
# Identité [CRITIQUE]
broker.id=1
node.id=1
listeners=PLAINTEXT://0.0.0.0:9092
advertised.listeners=PLAINTEXT://kafka1:9092

# Répertoires [CRITIQUE]
log.dirs=/var/lib/kafka/data
metadata.log.dir=/var/lib/kafka/meta

# Mode KRaft [CRITIQUE pour Kafka 3.3+]
process.roles=broker,controller
controller.quorum.voters=1@kafka1:9093,2@kafka2:9093,3@kafka3:9093
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT

# Topics par défaut [RECOMMANDÉ]
num.partitions=3                    # Au lieu de 1
default.replication.factor=3        # Au lieu de 1
min.insync.replicas=2               # Au lieu de 1
auto.create.topics.enable=false     # Désactiver en production

# Topics internes [CRITIQUE]
offsets.topic.replication.factor=3
offsets.topic.num.partitions=50     # [OFFICIEL] Default
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2

# Rétention [OFFICIEL mais à adapter]
log.retention.hours=168             # 7 jours par défaut
log.segment.bytes=1073741824        # 1GB
log.retention.check.interval.ms=300000

# Performance [OFFICIEL - defaults OK]
num.network.threads=8               # À adapter selon CPU
num.io.threads=8                    # À adapter selon disques
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600

# Compression [RECOMMANDÉ]
compression.type=producer           # Garder compression du producer
```

---

## 5. Méthodologie de tuning

### 5.1 Processus de validation

**Étape 1 : Baseline avec defaults**

```bash
# Benchmark avec configuration par défaut
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

# Mesurer amélioration du throughput
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

# Comparer avec baseline
```

**Étape 4 : Augmenter buffer si nécessaire**

```bash
# Tester avec volumétrie élevée
kafka-producer-perf-test \
  --topic test-perf \
  --num-records 1000000 \
  --record-size 1000 \
  --throughput -1 \
  --producer-props \
    bootstrap.servers=localhost:9092 \
    compression.type=lz4 \
    linger.ms=10 \
    batch.size=65536 \
    buffer.memory=67108864

# Observer : buffer-available-bytes en JMX
```

### 5.2 Métriques JMX à surveiller

**Producer :**
```
kafka.producer:type=producer-metrics,client-id=*
  - record-send-rate : messages/sec envoyés
  - batch-size-avg : taille moyenne des batchs
  - compression-rate-avg : ratio de compression
  - buffer-available-bytes : mémoire buffer disponible
  - request-latency-avg : latence réseau moyenne
```

**Consumer :**
```
kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*
  - records-lag-max : lag maximum
  - fetch-rate : fetch requests par seconde
  - records-consumed-rate : messages/sec consommés
```

### 5.3 Décision basée sur les résultats

**Exemple de tableau de décision :**

| Config | Throughput | Latence p99 | Buffer usage | Décision |
|--------|-----------|-------------|--------------|----------|
| Default | 10k/s | 50ms | 20% | Baseline |
| + compression | 12k/s | 55ms | 15% | ✅ Gain net |
| + linger.ms=10 | 18k/s | 65ms | 10% | ✅ Si latence OK |
| + batch.size=128KB | 20k/s | 70ms | 10% | ✅ Si latence OK |
| + buffer=128MB | 20k/s | 70ms | 5% | ❌ Pas d'amélioration |

**Règle :** Ne garder que les changements qui apportent un gain mesurable acceptable.

---

## 6. Gestion des offsets

### 6.1 Le mythe de auto.offset.reset [IMPORTANT]

**Erreur courante :** Penser que `auto.offset.reset=earliest` fait toujours recommencer depuis le début.

**Réalité [OFFICIEL] :**

```properties
auto.offset.reset=earliest
```

Cette configuration ne s'applique QUE dans ces cas :
1. Le consumer group n'existe pas encore (nouveau group)
2. Les offsets du consumer group ont expiré (après `offsets.retention.minutes`)
3. L'offset demandé n'existe plus dans le log

**Si le consumer group existe et a des offsets valides, cette config est IGNORÉE.**

### 6.2 Comment vraiment recommencer depuis le début

**Option 1 : Supprimer le consumer group**

```bash
# Arrêter TOUS les consumers du groupe d'abord
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --delete --group mon-groupe
```

**Option 2 : Reset des offsets**

```bash
# IMPORTANT : Arrêter tous les consumers du groupe d'abord

# Reset au début (rejouer tout)
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-earliest \
  --topic mon-topic \
  --execute

# Reset à la fin (skip tout le backlog)
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-latest \
  --topic mon-topic \
  --execute

# Reset à une date spécifique
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-datetime 2025-01-01T00:00:00.000 \
  --topic mon-topic \
  --execute

# Reset tous les topics du groupe
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-earliest \
  --all-topics \
  --execute
```

### 6.3 Auto-commit vs Commit manuel [IMPORTANT]

**Auto-commit [OFFICIEL] - Risque de perte :**

```java
// Default Kafka
enable.auto.commit=true
auto.commit.interval.ms=5000

// Scénario de perte :
// 1. poll() récupère 100 messages
// 2. Traitement des 50 premiers messages
// 3. Auto-commit se déclenche (offset = 100)
// 4. CRASH avant traitement des 50 derniers
// Résultat : 50 messages perdus définitivement
```

**Commit manuel [RECOMMANDÉ] - Exactly-once :**

```java
enable.auto.commit=false

ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
for (ConsumerRecord<String, String> record : records) {
    try {
        processRecord(record);
        consumer.commitSync(); // Commit APRÈS traitement
    } catch (Exception e) {
        logger.error("Erreur, offset non commité - message sera retraité");
    }
}
```

---

## 7. Partitioning

### 7.1 Le principe fondamental [CRITIQUE]

**Règle d'or :**
- **Même clé** → **Même partition** → **Ordre garanti**
- **Null key** → **Partition aléatoire** → **Pas d'ordre**

**Mauvaise pratique :**
```java
// Sans clé = pas d'ordre garanti
producer.send(new ProducerRecord<>("topic", null, message));
```

**Bonne pratique :**
```java
// Avec clé = ordre garanti par clé
producer.send(new ProducerRecord<>("topic", userId, message));
```

### 7.2 Dimensionnement du nombre de partitions

**Formule heuristique (point de départ) :**

```
Partitions = max(
    nombre_consumers_prévus,
    throughput_cible / throughput_par_partition
)
```

**Exemple de calcul :**
- Application prévoit 10 consumers maximum
- Throughput cible : 100 000 msg/s
- Throughput par partition mesuré : 20 000 msg/s
- Calcul : `max(10, 100000/20000) = max(10, 5) = 10 partitions`

**Règles pratiques [RECOMMANDÉ] :**

| Type d'application | Partitions suggérées | Justification |
|-------------------|---------------------|---------------|
| POC / Test | 3-6 | Permet de tester le parallélisme |
| Application standard | 10-20 | Balance scalabilité / overhead |
| Haute volumétrie | 50-100 | Selon calcul de throughput |

**Limites [IMPORTANT] :**
- Maximum recommandé : 4000 partitions par broker
- Plus de partitions = plus de fichiers, plus de mémoire
- Rebalancing plus long avec beaucoup de partitions

**Important :** 
- Nombre de consumers ≤ Nombre de partitions
- Si 10 partitions et 15 consumers → 5 consumers seront idle
- Si 10 partitions et 3 consumers → chaque consumer gère ~3-4 partitions

### 7.3 Hot partitions : problème et solutions

**Symptôme :** Une partition reçoit beaucoup plus de messages que les autres.

**Diagnostic :**
```bash
# Voir la distribution des messages par partition
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group mon-groupe
# Observer la colonne LAG par partition
```

**Causes fréquentes :**
1. Clés mal distribuées (ex: 80% des messages ont la même clé)
2. Custom partitioner déséquilibré
3. Nombre de partitions trop faible

**Solutions :**
1. Revoir la stratégie de clé (choisir une clé plus uniforme)
2. Augmenter le nombre de partitions
3. Utiliser un hash plus uniforme dans le custom partitioner

---

## 8. Résilience et haute disponibilité

### 8.1 Configuration acks [CRITIQUE]

**Matrice de décision :**

| Use Case | acks | min.insync.replicas | Perte possible | Performance |
|----------|------|---------------------|----------------|-------------|
| Logs applicatifs | 1 | 1 | Rare | Haute |
| Métriques | 1 | 1 | Acceptable | Haute |
| Événements métier | all | 2 | Non | Moyenne |
| Transactions financières | all | 2 | Non | Moyenne |

**acks=0 [DANGEREUX] - Fire and forget :**
```properties
acks=0
```
- Producer n'attend aucune confirmation
- Performance maximale
- Perte possible si leader crash
- Usage : logs non-critiques uniquement

**acks=1 [COMPROMIS] - Leader seul :**
```properties
acks=1
```
- Leader confirme l'écriture locale
- Perte possible si leader crash avant réplication
- Usage : la plupart des applications

**acks=all [SÛR] - Tous les replicas in-sync :**
```properties
acks=all
min.insync.replicas=2
```
- Tous les replicas in-sync doivent confirmer
- Aucune perte de données
- Usage : données critiques

**Exemple configuration production critique :**
```properties
# Topic avec 3 replicas
replication.factor=3
min.insync.replicas=2

# Producer
acks=all

# Garantie : message confirmé seulement si au moins 2 replicas l'ont reçu
# Si 1 broker tombe, pas de perte de données
```

### 8.2 Idempotence [RECOMMANDÉ SYSTÉMATIQUEMENT]

```properties
enable.idempotence=true
```

**Ce que ça active automatiquement [OFFICIEL] :**
- `acks=all`
- `retries=2147483647`
- `max.in.flight.requests.per.connection=5`

**Avantages :**
- Élimine les duplicatas automatiquement
- Pas d'impact performance notable
- Recommandé pour TOUTES les applications production

**Quand ne PAS l'utiliser :**
- Kafka très ancien (< 0.11)
- Besoin spécifique de `acks=1` ou `acks=0`

### 8.3 Replication factor [CRITIQUE]

**Recommandations par environnement :**

| Environnement | Replication Factor | min.insync.replicas | Justification |
|---------------|-------------------|---------------------|---------------|
| Dev/Test | 1 | 1 | Performance, pas de HA nécessaire |
| Staging | 2 | 1 | Test proche de prod |
| Production | 3 | 2 | Tolère la panne d'1 broker |
| Production critique | 3 | 2 | Standard industrie |

**Configuration topic production :**
```bash
kafka-topics --create \
  --topic evenements-critiques \
  --partitions 10 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --bootstrap-server localhost:9092
```

### 8.4 Circuit Breaker pattern [RECOMMANDÉ]

Pour applications qui ne peuvent pas être bloquées si Kafka est indisponible :

```java
public class ResilientProducer {
    private final KafkaProducer<String, String> producer;
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final int ERROR_THRESHOLD = 10;
    private volatile boolean circuitOpen = false;
    
    public void sendMessage(String key, String value) {
        // Vérifier l'état du circuit AVANT d'envoyer
        if (circuitOpen) {
            logger.warn("Circuit ouvert - Message rejeté ou sauvegardé localement");
            // Option 1 : Rejeter
            // Option 2 : Sauvegarder localement pour replay
            return;
        }
        
        ProducerRecord<String, String> record = 
            new ProducerRecord<>("topic", key, value);
        
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                int errors = errorCount.incrementAndGet();
                logger.error("Erreur envoi ({}/{}): {}", 
                    errors, ERROR_THRESHOLD, exception.getMessage());
                
                // Ouvrir le circuit après N erreurs consécutives
                if (errors >= ERROR_THRESHOLD) {
                    circuitOpen = true;
                    logger.error("CIRCUIT OUVERT - Kafka indisponible");
                    // Déclencher alerte monitoring
                }
            } else {
                // Succès : reset le compteur et fermer le circuit
                errorCount.set(0);
                if (circuitOpen) {
                    circuitOpen = false;
                    logger.info("CIRCUIT FERMÉ - Kafka disponible à nouveau");
                }
            }
        });
    }
}
```

**Avantages :**
- Application ne se bloque jamais
- Détection rapide des problèmes Kafka
- Possibilité de basculer sur stockage alternatif

---

## 9. Monitoring

### 9.1 Métriques Producer JMX [CRITIQUE]

**5 métriques essentielles à surveiller :**

1. **record-error-rate**
   - Doit être = 0
   - Si > 0 → problème de connexion ou de configuration
   - Alerter immédiatement

2. **buffer-available-bytes**
   - Mémoire disponible dans le buffer
   - Si < 10% du buffer.memory → risque de saturation
   - Augmenter buffer.memory ou réduire le débit

3. **batch-size-avg**
   - Taille moyenne des batchs envoyés
   - Comparer avec batch.size configuré
   - Optimiser linger.ms si trop faible

4. **compression-rate-avg**
   - Ratio de compression (1.0 = pas de compression)
   - Vérifier efficacité de compression.type
   - Changer de codec si ratio faible

5. **request-latency-avg**
   - Latence réseau moyenne vers les brokers
   - Si élevée → problème réseau ou brokers surchargés

**Commande pour exposer JMX :**
```bash
# Démarrer avec JMX activé
export KAFKA_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false"
```

### 9.2 Métriques Consumer JMX [CRITIQUE]

**5 métriques essentielles à surveiller :**

1. **records-lag-max**
   - Lag maximum sur toutes les partitions
   - Alerte si > 1000 pendant plus de 5 minutes
   - Indique que le consumer ne suit pas

2. **fetch-rate**
   - Nombre de fetch requests par seconde
   - Comparer avec la production rate

3. **records-consumed-rate**
   - Messages consommés par seconde
   - Doit être proche du taux de production

4. **commit-latency-avg**
   - Temps moyen pour commiter les offsets
   - Si élevé → problème de connexion au broker

5. **assigned-partitions**
   - Nombre de partitions assignées à ce consumer
   - Vérifier équilibrage dans le consumer group

### 9.3 Alertes Prometheus recommandées

```yaml
groups:
  - name: kafka
    rules:
      # Alerte critique : Erreurs producer
      - alert: KafkaProducerErrors
        expr: kafka_producer_record_error_rate > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Erreurs d'envoi détectées sur producer"
          description: "Le producer {{ $labels.client_id }} a un taux d'erreur de {{ $value }}"

      # Alerte warning : Consumer lag élevé
      - alert: KafkaConsumerLagHigh
        expr: kafka_consumer_records_lag_max > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Lag consumer élevé"
          description: "Le consumer {{ $labels.client_id }} a un lag de {{ $value }}"

      # Alerte warning : Buffer producer saturé
      - alert: KafkaProducerBufferLow
        expr: kafka_producer_buffer_available_bytes < 3355443  # 10% de 32MB
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Buffer producer presque saturé"
          description: "Seulement {{ $value }} bytes disponibles"

      # Alerte critical : Broker down
      - alert: KafkaBrokerDown
        expr: up{job="kafka-broker"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Broker Kafka down"
          description: "Le broker {{ $labels.instance }} est inaccessible"
```

### 9.4 Dashboard Grafana recommandé

**Panneaux essentiels :**

**Producer :**
- Throughput (messages/sec)
- Batch size moyenne
- Buffer disponible
- Request latency p95/p99
- Taux d'erreur

**Consumer :**
- Lag par partition
- Throughput consommation
- Rebalancing events
- Commit latency

**Broker :**
- CPU / Mémoire / Disque
- Network in/out
- Request rate
- Log size par topic

---

## 10. Opérations et debug

### 10.1 Commandes essentielles

**Vérifier un topic :**
```bash
kafka-topics --bootstrap-server localhost:9092 \
  --describe --topic mon-topic

# Informations affichées :
# - Leader : quel broker gère quelle partition
# - Replicas : où sont les copies
# - Isr : (In-Sync Replicas) qui est à jour
```

**Vérifier un consumer group :**
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group mon-groupe

# Colonnes importantes :
# - CURRENT-OFFSET : position actuelle
# - LOG-END-OFFSET : fin du log
# - LAG : retard (LOG-END - CURRENT)
# - CONSUMER-ID : qui consomme cette partition
# - HOST : sur quel serveur
```

**Lister tous les topics :**
```bash
# Topics utilisateur uniquement
kafka-topics --bootstrap-server localhost:9092 --list

# Inclure les topics internes
kafka-topics --bootstrap-server localhost:9092 \
  --list --exclude-internal=false
```

**Créer un topic avec configuration spécifique :**
```bash
kafka-topics --bootstrap-server localhost:9092 \
  --create \
  --topic mon-topic \
  --partitions 10 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --config compression.type=lz4 \
  --config min.insync.replicas=2
```

**Modifier la configuration d'un topic :**
```bash
kafka-configs --bootstrap-server localhost:9092 \
  --entity-type topics \
  --entity-name mon-topic \
  --alter \
  --add-config retention.ms=86400000
```

**Supprimer un topic :**
```bash
kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic mon-topic
```

### 10.2 Debug : Consumer group bloqué

**Symptômes :**
- LAG énorme et qui augmente
- Colonne CONSUMER-ID vide
- Messages ne sont plus consommés

**Diagnostic :**
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group mon-groupe

# Si CONSUMER-ID vide → tous les consumers du groupe sont morts
```

**Solutions selon le cas :**

**Cas 1 : Rejouer tout le backlog**
```bash
# Arrêter tous les consumers d'abord
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-earliest \
  --all-topics \
  --execute

# Redémarrer les consumers
```

**Cas 2 : Skip le backlog et reprendre en temps réel**
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-latest \
  --all-topics \
  --execute
```

**Cas 3 : Reprendre à une date précise**
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --to-datetime 2025-01-10T08:00:00.000 \
  --all-topics \
  --execute
```

**Cas 4 : Shift de N messages**
```bash
# Avancer de 1000 messages
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --shift-by 1000 \
  --all-topics \
  --execute

# Reculer de 1000 messages
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group mon-groupe \
  --reset-offsets --shift-by -1000 \
  --all-topics \
  --execute
```

### 10.3 Debug ultime : Lire __consumer_offsets

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic __consumer_offsets \
  --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter" \
  --from-beginning

# Format de sortie :
# [groupe,topic,partition] :: OffsetAndMetadata(offset=X, leaderEpoch=..., metadata=...)
```

### 10.4 Tests de performance

**Producer performance test :**
```bash
kafka-producer-perf-test \
  --topic test-perf \
  --num-records 1000000 \
  --record-size 1000 \
  --throughput -1 \
  --producer-props \
    bootstrap.servers=localhost:9092 \
    acks=all \
    compression.type=lz4

# Résultats affichés :
# - records sent
# - records/sec
# - MB/sec
# - avg latency
# - max latency
# - 50th, 95th, 99th, 99.9th percentile latency
```

**Consumer performance test :**
```bash
kafka-consumer-perf-test \
  --bootstrap-server localhost:9092 \
  --topic test-perf \
  --messages 1000000 \
  --threads 1

# Résultats affichés :
# - data.consumed.in.MB
# - MB.sec
# - data.consumed.in.nMsg
# - nMsg.sec
```

**End-to-end latency test :**
```bash
kafka-run-class kafka.tools.EndToEndLatency \
  localhost:9092 \
  test-latency \
  10000 \
  1 \
  1000

# Mesure la latence réelle bout-en-bout (production + consommation)
```

---

## 11. Anti-patterns à éviter

### 11.1 Le send() bloquant en boucle [CATASTROPHIQUE]

**MAUVAIS :**
```java
// Bloque à CHAQUE message - 10 msg/sec au lieu de 100k msg/sec
for (int i = 0; i < 1000000; i++) {
    producer.send(record).get();  // .get() BLOQUE ICI
}
```

**CORRECT :**
```java
// Envoie asynchrone - 100k+ msg/sec
for (int i = 0; i < 1000000; i++) {
    producer.send(record, callback);
}
producer.flush(); // Attendre la fin de tous les envois à la fin
```

### 11.2 Le Thread.sleep() dans le consumer [INUTILE]

**MAUVAIS :**
```java
while (true) {
    ConsumerRecords records = consumer.poll(Duration.ofMillis(100));
    processRecords(records);
    Thread.sleep(1000);  // POURQUOI ???
}
// Le poll() gère déjà l'attente intelligemment
```

**CORRECT :**
```java
while (true) {
    ConsumerRecords records = consumer.poll(Duration.ofMillis(100));
    processRecords(records);
    // Pas de sleep, le poll() attend si pas de messages
}
```

### 11.3 Le mythe "1 partition = 1 consumer" [FAUX]

**Malentendu fréquent :**
```
"Je veux 10 consumers donc je crée 10 partitions"
```

**Réalité :**
- **Partitions = capacité MAXIMALE de parallélisme**
- 3 consumers sur 10 partitions → OK (7 partitions disponibles pour scale)
- 10 consumers sur 3 partitions → 7 consumers IDLE (gaspillage)

**Règle :**
```
nombre_consumers ≤ nombre_partitions
```

**Exemple valide :**
```
Topic avec 10 partitions
- 1 consumer → gère 10 partitions
- 3 consumers → chacun gère ~3 partitions
- 10 consumers → chacun gère 1 partition
- 15 consumers → 10 actifs, 5 IDLE (inutiles)
```

### 11.4 Auto-création de topics en production [DANGEREUX]

**MAUVAIS :**
```properties
# Broker config
auto.create.topics.enable=true
```

**Risques :**
- Topics créés avec defaults (1 partition, RF=1)
- Erreurs typo créent des topics fantômes
- Pas de contrôle sur la configuration

**CORRECT :**
```properties
# Broker config
auto.create.topics.enable=false

# Créer explicitement avec la bonne config
kafka-topics --create --topic mon-topic \
  --partitions 10 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --bootstrap-server localhost:9092
```

### 11.5 Ignorer les métriques [ERREUR FATALE]

**MAUVAIS :**
- Déployer en production sans monitoring
- Découvrir les problèmes quand les clients appellent

**CORRECT :**
- JMX activé sur tous les composants
- Dashboards Grafana/Prometheus configurés
- Alertes sur consumer lag, errors, disk space
- Logs centralisés (ELK, Splunk, etc.)
- Astreinte avec runbook

### 11.6 Ne jamais tester les pannes [ERREUR COURANTE]

**MAUVAIS :**
- Déployer sans tester les scénarios de panne
- Première panne = panique et découverte des bugs

**CORRECT :**
```bash
# Tests de chaos réguliers
# 1. Arrêter un broker pendant 30 secondes
docker-compose pause kafka1
sleep 30
docker-compose unpause kafka1

# Observer : consumers rebalance, producers retry, metrics

# 2. Network partition
# 3. Disque plein simulé
# 4. OOM kill d'un consumer
```

---

## 12. Checklist pré-production

### 12.1 Configuration Broker

- [ ] `offsets.topic.replication.factor >= 3`
- [ ] `transaction.state.log.replication.factor >= 3`
- [ ] `min.insync.replicas = 2`
- [ ] `auto.create.topics.enable = false`
- [ ] `log.retention.hours` adapté au besoin métier
- [ ] `num.partitions` défini par défaut (3-10)
- [ ] `default.replication.factor = 3`
- [ ] Mode KRaft configuré (Kafka 3.3+)
- [ ] Listeners correctement configurés
- [ ] Advertised listeners accessibles depuis les clients

### 12.2 Configuration Producer

- [ ] `acks = all` pour données critiques
- [ ] `enable.idempotence = true`
- [ ] Compression activée (`lz4` ou `snappy`)
- [ ] `buffer.memory` dimensionné (testé en charge)
- [ ] `linger.ms` et `batch.size` optimisés (benchmarkés)
- [ ] Callbacks avec gestion d'erreurs implémentés
- [ ] Circuit breaker pour applications critiques
- [ ] Retry logic appropriée

### 12.3 Configuration Consumer

- [ ] `enable.auto.commit = false` (commit manuel) ou justifié
- [ ] `session.timeout.ms` adapté (testé)
- [ ] `max.poll.interval.ms` adapté au temps de traitement
- [ ] `auto.offset.reset` défini explicitement
- [ ] Gestion des erreurs implémentée (retry, DLQ)
- [ ] Idempotence du traitement vérifiée
- [ ] Logs de lag activés

### 12.4 Topics

- [ ] Tous les topics créés explicitement
- [ ] `replication.factor = 3`
- [ ] Nombre de partitions calculé et documenté
- [ ] `min.insync.replicas = 2` pour topics critiques
- [ ] Rétention définie par topic
- [ ] Compression configurée si nécessaire
- [ ] Naming convention respectée

### 12.5 Monitoring

- [ ] JMX activé sur tous les brokers
- [ ] JMX activé sur producers/consumers (ou metrics exportés)
- [ ] Dashboards Grafana configurés avec panneaux essentiels
- [ ] Alerte : consumer lag > seuil défini
- [ ] Alerte : disk space < 20%
- [ ] Alerte : producer errors > 0
- [ ] Alerte : broker down
- [ ] Logs centralisés (ELK, Splunk, etc.)
- [ ] Retention logs suffisante (7-30 jours)

### 12.6 Sécurité

- [ ] SSL/TLS activé
- [ ] SASL configuré (SCRAM, PLAIN, ou GSSAPI)
- [ ] ACLs définies par application/topic
- [ ] Principe du moindre privilège appliqué
- [ ] Audit logs activés
- [ ] Certificats avec expiration > 1 an
- [ ] Secrets gérés (Vault, K8s secrets, etc.)
- [ ] Network policies/firewall configurés

### 12.7 Haute disponibilité

- [ ] Minimum 3 brokers
- [ ] Brokers sur différents racks/AZ si cloud
- [ ] Procédures de backup documentées
- [ ] Plan de disaster recovery défini et testé
- [ ] Runbook opérationnel créé
- [ ] Astreinte définie avec escalation
- [ ] Tests de chaos effectués
- [ ] RTO/RPO définis et validés

### 12.8 Performance

- [ ] Tests de charge effectués (producer-perf-test)
- [ ] Benchmarks de throughput documentés
- [ ] Latence p95/p99 mesurée et acceptable
- [ ] Stratégie de scaling définie (vertical/horizontal)
- [ ] Capacité planifiée :
  - [ ] Nombre max de partitions
  - [ ] Stockage requis (retention * throughput)
  - [ ] Network bandwidth
  - [ ] CPU/RAM par broker
- [ ] Quota par client/application configurés si nécessaire

### 12.9 Documentation

- [ ] Architecture documentée (diagrammes)
- [ ] Configurations documentées et versionnées
- [ ] Procédures opérationnelles (démarrage, arrêt, upgrade)
- [ ] Procédures de debug (consumer bloqué, lag, etc.)
- [ ] Contacts et escalation définis
- [ ] Changelog des changements de config

### 12.10 Tests pré-go-live

- [ ] Tests de charge avec volumétrie production
- [ ] Tests de panne broker (1 broker down)
- [ ] Tests de panne consumer (rebalancing)
- [ ] Tests de recovery (restart cluster)
- [ ] Tests de backup/restore
- [ ] Tests de mise à jour (rolling upgrade)
- [ ] Tests de sécurité (ACLs, SSL)
- [ ] Tests de monitoring (alertes déclenchées correctement)

---

## Annexe A : Sources et références

### Documentation officielle (100% fiable)

- **Apache Kafka Documentation** : https://kafka.apache.org/documentation/
  - Configuration parameters officiels
  - Defaults documentés
  - KIPs (Kafka Improvement Proposals)

- **Confluent Documentation** : https://docs.confluent.io/platform/current/
  - Best practices de production
  - Guides de tuning

- **KIP-115** : https://cwiki.apache.org/confluence/display/KAFKA/KIP-115
  - Enforcement du offsets.topic.replication.factor

### Livres de référence

- **"Kafka: The Definitive Guide" (O'Reilly)**
  - Auteurs : Neha Narkhede, Gwen Shapira, Todd Palino
  - Créateurs originaux de Kafka chez LinkedIn

- **"Kafka in Action" (Manning)**
  - Auteur : Dylan Scott
  - Approche pratique avec exemples réels

- **"Kafka Streams in Action" (Manning)**
  - Auteur : Bill Bejeck
  - Pour le stream processing

### Blogs techniques recommandés

- **Confluent Blog** : https://www.confluent.io/blog/
  - Articles techniques approfondis
  - Cas d'usage production

- **LinkedIn Engineering Blog**
  - Retours d'expérience des créateurs de Kafka

- **Uber Engineering Blog**
  - Utilisation à très grande échelle

- **Netflix Tech Blog**
  - Patterns de résilience

### Outils recommandés

**Monitoring :**
- Prometheus + Grafana
- Confluent Control Center
- Burrow (consumer lag monitoring)

**CLI amélioré :**
- kcat (anciennement kafkacat) - Swiss army knife pour Kafka

**UI :**
- AKHQ (anciennement KafkaHQ)
- Kafdrop
- Kafka UI (provectus)

**Testing :**
- kafka-producer-perf-test (inclus)
- kafka-consumer-perf-test (inclus)
- Gatling Kafka plugin (load testing)

---

## Annexe B : Defaults officiels par version

### Kafka 4.0 (dernière version)

```properties
# Producer
linger.ms=5                    # Changé de 0 à 5 dans Kafka 4.0
batch.size=16384
buffer.memory=33554432
acks=1                         # (all si enable.idempotence=true)
compression.type=none
enable.idempotence=false
retries=2147483647
max.in.flight.requests.per.connection=5

# Consumer
enable.auto.commit=true
auto.commit.interval.ms=5000
session.timeout.ms=45000
max.poll.interval.ms=300000
heartbeat.interval.ms=3000
auto.offset.reset=latest
fetch.min.bytes=1
fetch.max.wait.ms=500
max.poll.records=500

# Broker
num.partitions=1
default.replication.factor=1
min.insync.replicas=1
offsets.topic.replication.factor=3
offsets.topic.num.partitions=50
transaction.state.log.replication.factor=3
auto.create.topics.enable=true
log.retention.hours=168
```

### Kafka 3.9 (stable)

```properties
# Producer
linger.ms=0                    # 0 dans Kafka < 4.0
# Autres identiques à 4.0

# Consumer et Broker : identiques à 4.0
```

---

## Annexe C : Formules de calcul

### Dimensionnement des partitions

```
Formule heuristique (point de départ) :
Partitions = max(
    nombre_consumers_max_prévu,
    throughput_cible_msgs_sec / throughput_partition_msgs_sec
)
```

**Exemple :**
```
Application e-commerce :
- Throughput cible : 50 000 commandes/sec en Black Friday
- Throughput par partition mesuré : 10 000 msgs/sec
- Nombre de consumers prévus : 20 (pour scaling)

Calcul : max(20, 50000/10000) = max(20, 5) = 20 partitions
```

### Dimensionnement du stockage

```
Stockage requis (GB) = 
    throughput_MB_sec * retention_secondes / 1024
```

**Exemple :**
```
Topic événements :
- Throughput : 10 MB/s
- Retention : 7 jours = 604 800 secondes
- Replication factor : 3

Calcul : (10 * 604800 / 1024) * 3 = 17 730 GB ≈ 17,3 TB
```

### Nombre de brokers minimum

```
Brokers minimum = max(
    replication_factor,
    ceil(stockage_total / stockage_par_broker)
)
```

**Exemple :**
```
- Stockage total requis : 20 TB
- Stockage par broker : 8 TB
- Replication factor : 3

Calcul : max(3, ceil(20/8)) = max(3, 3) = 3 brokers minimum
```

---

## Notes finales

### Philosophie de ce guide

Ce guide distingue clairement :

1. **Valeurs officielles [OFFICIEL]** : Issues de la documentation Apache Kafka, defaults du code source
2. **Best practices courantes [RECOMMANDÉ]** : Patterns largement utilisés en production
3. **Points de départ [À TESTER]** : Valeurs suggérées qui DOIVENT être validées

**Principe fondamental :** Il n'existe PAS de configuration universelle optimale. Les bonnes valeurs dépendent de :
- Volumétrie de vos messages
- Taille moyenne des messages
- Latence acceptable pour votre application
- Architecture réseau
- Ressources disponibles (CPU, RAM, disque, réseau)

### Méthodologie recommandée

1. **Partir des defaults officiels**
2. **Mesurer les performances actuelles**
3. **Identifier les goulots (via métriques JMX)**
4. **Changer UNE chose à la fois**
5. **Mesurer l'impact**
6. **Documenter les choix**

### Contribuer

Ce document est vivant. Les retours d'expérience et corrections sont bienvenus.

---

**Version :** 2.0  
**Dernière mise à jour :** Novembre 2025  
**Basé sur :** Apache Kafka 3.9 / 4.0 (KRaft mode)  
**Licence :** MIT - Usage libre pour formations et documentation interne
