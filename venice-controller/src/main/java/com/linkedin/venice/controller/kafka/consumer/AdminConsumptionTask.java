package com.linkedin.venice.controller.kafka.consumer;

import com.linkedin.venice.controller.ExecutionIdAccessor;
import com.linkedin.venice.controller.VeniceHelixAdmin;
import com.linkedin.venice.controller.kafka.AdminTopicUtils;
import com.linkedin.venice.controller.kafka.protocol.admin.AdminOperation;
import com.linkedin.venice.controller.kafka.protocol.admin.KillOfflinePushJob;
import com.linkedin.venice.controller.kafka.protocol.enums.AdminMessageType;
import com.linkedin.venice.controller.kafka.protocol.serializer.AdminOperationSerializer;
import com.linkedin.venice.controller.stats.AdminConsumptionStats;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.validation.DataValidationException;
import com.linkedin.venice.exceptions.validation.DuplicateDataException;
import com.linkedin.venice.exceptions.validation.MissingDataException;
import com.linkedin.venice.kafka.consumer.KafkaConsumerWrapper;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.offsets.OffsetManager;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.utils.DaemonThreadFactory;
import com.linkedin.venice.utils.Pair;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.log4j.Logger;


/**
 * This class is used to create a task, which will consume the admin messages from the special admin topics.
 */
public class AdminConsumptionTask implements Runnable, Closeable {
  private static final String CONSUMER_TASK_ID_FORMAT = AdminConsumptionTask.class.getSimpleName() + " [Topic: %s] ";
  private static final long UNASSIGNED_VALUE = -1L;
  public static int READ_CYCLE_DELAY_MS = 1000;
  public static int IGNORED_CURRENT_VERSION = -1;

  private final Logger logger;

  private final String clusterName;
  private final String topic;
  private final String consumerTaskId;
  private final OffsetManager offsetManager;
  private final VeniceHelixAdmin admin;
  private final boolean isParentController;
  private final AtomicBoolean isRunning;
  private final AdminOperationSerializer deserializer;
  private final AdminConsumptionStats stats;
  private final int adminTopicReplicationFactor;

  private boolean isSubscribed;
  private KafkaConsumerWrapper consumer;
  private OffsetRecord lastOffset;
  private long lastPersistedOffset = UNASSIGNED_VALUE;
  private volatile long offsetToSkip = UNASSIGNED_VALUE;
  private volatile long offsetToSkipDIV = UNASSIGNED_VALUE;
  /**
   * The smallest or first failing offset.
   */
  private volatile long failingOffset = UNASSIGNED_VALUE;
  private boolean topicExists;
  /**
   * A {@link Map} of stores to admin operations belonging to each store. The corresponding kafka offset of each admin
   * operation is also attached as the first element of the {@link Pair}.
   */
  private Map<String, Queue<Pair<Long, AdminOperation>>> storeAdminOperationsMapWithOffset;
  private Map<String, Long> problematicStores;
  private Queue<ConsumerRecord<KafkaKey, KafkaMessageEnvelope>> undelegatedRecords;


  private final ExecutionIdAccessor executionIdAccessor;
  private final ExecutorService executorService;
  private final long processingCycleTimeoutInMs;
  /**
   * Once all admin messages in a cycle is processed successfully, the id would be updated together with the offset.
   * It represents a kind of comparable progress of admin topic consumption among all controllers.
   */
  private volatile long lastSucceededExecutionId = UNASSIGNED_VALUE;
  /**
   * The execution id of the last message that was delegated to a store's queue. Used for DIV check when fetching
   * messages from the admin topic.
   */
  private long lastDelegatedExecutionId = UNASSIGNED_VALUE;
  /**
   * The largest execution id for messages that were processed successfully in a cycle.
   */
  private long largestSucceededExecutionId = UNASSIGNED_VALUE;
  private Map<String, Long> lastSucceededExecutionIdMap;

  public AdminConsumptionTask(String clusterName,
      KafkaConsumerWrapper consumer,
      VeniceHelixAdmin admin,
      OffsetManager offsetManager,
      ExecutionIdAccessor executionIdAccessor,
      boolean isParentController,
      AdminConsumptionStats stats,
      int adminTopicReplicationFactor,
      long processingCycleTimeoutInMs,
      int maxWorkerThreadPoolSize) {
    this.clusterName = clusterName;
    this.topic = AdminTopicUtils.getTopicNameFromClusterName(clusterName);
    this.consumerTaskId = String.format(CONSUMER_TASK_ID_FORMAT, this.topic);
    this.logger = Logger.getLogger(consumerTaskId);
    this.admin = admin;
    this.isParentController = isParentController;
    this.deserializer = new AdminOperationSerializer();
    this.isRunning = new AtomicBoolean(true);
    this.isSubscribed = false;
    this.lastOffset = new OffsetRecord();
    this.topicExists = false;
    this.stats = stats;
    this.adminTopicReplicationFactor = adminTopicReplicationFactor;
    this.consumer = consumer;
    this.offsetManager = offsetManager;
    this.executionIdAccessor = executionIdAccessor;
    this.processingCycleTimeoutInMs = processingCycleTimeoutInMs;

    this.storeAdminOperationsMapWithOffset = new HashMap<>();
    this.problematicStores = new HashMap<>();
    this.executorService = new ThreadPoolExecutor(1, maxWorkerThreadPoolSize, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), new DaemonThreadFactory("Venice-Admin-Execution-Task"));
    this.undelegatedRecords = new LinkedList<>();
    this.stats.setAdminConsumptionFailedOffset(failingOffset);
  }

  @Override
  public synchronized void close() throws IOException {
    isRunning.getAndSet(false);
  }

  @Override
  public void run() {
    logger.info("Running " + this.getClass().getSimpleName());
    long lastLogTime = 0;
    while (isRunning.get()) {
      try {
        Utils.sleep(READ_CYCLE_DELAY_MS);
        if (!admin.isMasterController(clusterName)) {
          unSubscribe();
          continue;
        }
        if (!isSubscribed) {
          if (whetherTopicExists(topic)) {
            // Topic was not created by this process, so we make sure it has the right retention.
            makeSureAdminTopicUsingInfiniteRetentionPolicy(topic);
          } else {
            String logMessage = "Admin topic: " + topic + " hasn't been created yet. ";
            if (!isParentController) {
              // To reduce log bloat, only log once per minute
              if (System.currentTimeMillis() - lastLogTime > 60 * Time.MS_PER_SECOND) {
                logger.info(logMessage + "Since this is a child controller, it will not be created by this process.");
                lastLogTime = System.currentTimeMillis();
              }
              continue;
            }
            logger.info(logMessage + "Since this is the parent controller, it will be created it now.");
            admin.getTopicManager().createTopic(topic, 1, adminTopicReplicationFactor, true,
                false, Optional.of(adminTopicReplicationFactor - 1));
          }
          subscribe();
        }
        Iterator<ConsumerRecord<KafkaKey, KafkaMessageEnvelope>> recordsIterator;
        // Only poll the kafka channel if there are no more undelegated records due to exceptions.
        if (undelegatedRecords.isEmpty()) {
          ConsumerRecords records = consumer.poll(READ_CYCLE_DELAY_MS);
          if (null == records || records.isEmpty()) {
            logger.debug("Received null or no records");
          } else {
            logger.debug("Received record num: " + records.count());
            recordsIterator = records.iterator();
            while (recordsIterator.hasNext()) {
              undelegatedRecords.add(recordsIterator.next());
            }
          }
        } else {
          logger.debug("Consuming from undelegated records first before polling from the admin topic");
        }

        while (!undelegatedRecords.isEmpty()) {
          try {
            delegateMessage(undelegatedRecords.peek());
          } catch (DataValidationException e) {
            // Very unlikely but DataValidationException could be thrown here.
            logger.error("Admin consumption task is blocked due to DataValidationException with offset "
                + undelegatedRecords.peek().offset(), e);
            failingOffset = undelegatedRecords.peek().offset();
            stats.recordFailedAdminConsumption();
            stats.recordAdminTopicDIVErrorReportCount();
            break;
          }
          updateLastOffset(undelegatedRecords.poll().offset());
        }
        executeMessagesAndCollectResults();
        stats.setAdminConsumptionFailedOffset(failingOffset);
      } catch (Exception e) {
        logger.error("Exception thrown while running admin consumption task", e);
        // Unsubscribe and resubscribe in the next cycle to start over and avoid missing messages from poll.
        unSubscribe();
      }
    }
    // Release resources
    internalClose();
  }

  private void subscribe() {
    lastOffset = offsetManager.getLastOffset(topic, AdminTopicUtils.ADMIN_TOPIC_PARTITION_ID);
    lastPersistedOffset = lastOffset.getOffset();
    lastDelegatedExecutionId = executionIdAccessor.getLastSucceededExecutionId(clusterName);
    // Subscribe the admin topic
    consumer.subscribe(topic, AdminTopicUtils.ADMIN_TOPIC_PARTITION_ID, lastOffset);
    isSubscribed = true;
    logger.info("Subscribe to topic name: " + topic + ", with offset: " + lastOffset.getOffset()
        + " and execution id: " + lastDelegatedExecutionId);
  }

  private void unSubscribe() {
    if (isSubscribed) {
      consumer.unSubscribe(topic, AdminTopicUtils.ADMIN_TOPIC_PARTITION_ID);
      storeAdminOperationsMapWithOffset.clear();
      problematicStores.clear();
      undelegatedRecords.clear();
      failingOffset = UNASSIGNED_VALUE;
      offsetToSkip = UNASSIGNED_VALUE;
      offsetToSkipDIV = UNASSIGNED_VALUE;
      largestSucceededExecutionId = UNASSIGNED_VALUE;
      lastDelegatedExecutionId = UNASSIGNED_VALUE;
      lastSucceededExecutionId = UNASSIGNED_VALUE;
      stats.recordPendingAdminMessagesCount(UNASSIGNED_VALUE);
      stats.recordStoresWithPendingAdminMessagesCount(UNASSIGNED_VALUE);
      isSubscribed = false;
      logger.info("Unsubscribe from topic name: " + topic);
    }
  }

  /**
   * Delegate work from the {@code storeAdminOperationsMapWithOffset} to the worker threads. Wait for the worker threads
   * to complete or when timeout {@code processingCycleTimeoutInMs} is reached. Collect the result of each thread.
   * The result can either be success: all given {@link AdminOperation}s were processed successfully or made progress
   * but couldn't finish processing all of it within the time limit for each cycle. Failure is when either an exception
   * was thrown or the thread got stuck while processing the problematic {@link AdminOperation}. Based on the results we
   * set appropriate values for the cluster wide {@code lastSucceededExecutionId} and {@code lastOffset}.
   * @throws InterruptedException
   */
  private void executeMessagesAndCollectResults() throws InterruptedException {
    lastSucceededExecutionId = executionIdAccessor.getLastSucceededExecutionId(clusterName);
    lastSucceededExecutionIdMap = executionIdAccessor.getLastSucceededExecutionIdMap(clusterName);
    List<Callable<Void>> tasks = new ArrayList<>();
    List<String> stores = new ArrayList<>();
    // Create a task for each store that has admin messages pending to be processed.
    for (Map.Entry<String, Queue<Pair<Long, AdminOperation>>> entry : storeAdminOperationsMapWithOffset.entrySet()) {
      if (!entry.getValue().isEmpty()) {
        if (checkOffsetToSkip(entry.getValue().peek().getFirst())) {
          entry.getValue().poll();
        }
        tasks.add(new AdminExecutionTask(logger, clusterName, entry.getKey(),
            lastSucceededExecutionIdMap.getOrDefault(entry.getKey(), lastSucceededExecutionId), entry.getValue(), admin,
            executionIdAccessor, isParentController, stats));
        stores.add(entry.getKey());
      }
    }
    if (isRunning.get()) {
      if (!tasks.isEmpty()) {
        int pendingAdminMessagesCount = 0;
        int storesWithPendingAdminMessagesCount = 0;
        long adminExecutionTasksInvokeTime = System.currentTimeMillis();
        // Wait for the worker threads to finish processing the internal admin topics.
        List<Future<Void>> results = executorService.invokeAll(tasks, processingCycleTimeoutInMs, TimeUnit.MILLISECONDS);
        stats.recordAdminConsumptionCycleDurationMs(System.currentTimeMillis() - adminExecutionTasksInvokeTime);
        Map<String, Long> newLastSucceededExecutionIdMap = executionIdAccessor.getLastSucceededExecutionIdMap(clusterName);
        for (int i = 0; i < results.size(); i++) {
          String storeName = stores.get(i);
          Future<Void> result = results.get(i);
          long newlySucceededExecutionId =
              newLastSucceededExecutionIdMap.getOrDefault(storeName, lastSucceededExecutionId);
          if (newlySucceededExecutionId > largestSucceededExecutionId) {
            largestSucceededExecutionId = newlySucceededExecutionId;
          }
          if (result.isCancelled()) {
            if (lastSucceededExecutionIdMap.get(storeName).equals(newLastSucceededExecutionIdMap.get(storeName))) {
              // only mark the store problematic if it didn't make any progress.
              problematicStores.put(storeName, storeAdminOperationsMapWithOffset.get(storeName).peek().getFirst());
              logger.warn("Could not finish processing admin operations for store " + storeName + " in time");
              pendingAdminMessagesCount += storeAdminOperationsMapWithOffset.get(storeName).size();
              storesWithPendingAdminMessagesCount++;
            }
          } else {
            try {
              result.get();
              if (problematicStores.containsKey(storeName)) {
                problematicStores.remove(storeName);
              }
            } catch (ExecutionException e) {
              problematicStores.put(storeName, storeAdminOperationsMapWithOffset.get(storeName).peek().getFirst());
              pendingAdminMessagesCount += storeAdminOperationsMapWithOffset.get(storeName).size();
              storesWithPendingAdminMessagesCount++;
            }
          }
        }
        if (problematicStores.isEmpty()) {
          // All admin operations were successfully executed or skipped.
          // 1. Clear the failing offset.
          // 2. Set the cluster wide lastSucceededExecutionId to the largest (latest) execution id.
          // 3. Persist the latest execution id and offset (cluster wide) to ZK.
          if (largestSucceededExecutionId > lastSucceededExecutionId) {
            lastSucceededExecutionId = largestSucceededExecutionId;
          }
          // Ensure failingOffset from the delegateMessage is not overwritten.
          if (failingOffset <= lastOffset.getOffset()) {
            failingOffset = UNASSIGNED_VALUE;
          }
          persistLastExecutionIdAndLastOffset();
        } else {
          // One or more stores encountered problems while executing their admin operations.
          // 1. Do not persist the latest offset (cluster wide) to ZK.
          // 2. Find and set the smallest failing offset amongst the problematic stores.
          long smallestOffset = UNASSIGNED_VALUE;

          for (Map.Entry<String, Long> problematicStore : problematicStores.entrySet()) {
            if (smallestOffset == UNASSIGNED_VALUE || problematicStore.getValue() < smallestOffset) {
              smallestOffset = problematicStore.getValue();
            }
          }
          // Ensure failingOffset from the delegateMessage is not overwritten.
          if (failingOffset <= lastOffset.getOffset()) {
            failingOffset = smallestOffset;
          }
        }
        stats.recordPendingAdminMessagesCount(pendingAdminMessagesCount);
        stats.recordStoresWithPendingAdminMessagesCount(storesWithPendingAdminMessagesCount);
      } else {
        // in situations when we skipped a blocking message (while delegating) and no other messages are queued up.
        persistLastExecutionIdAndLastOffset();
      }
    }
  }

  private void internalClose() {
    unSubscribe();
    executorService.shutdownNow();
    try {
      if (!executorService.awaitTermination(processingCycleTimeoutInMs, TimeUnit.MILLISECONDS)) {
        logger.warn(
            "Unable to shutdown worker executor thread pool in admin consumption task in time " + consumerTaskId);
      }
    } catch (InterruptedException e) {
      logger.warn("Interrupted while waiting for worker executor thread pool to shutdown " + consumerTaskId);
    }
    logger.info("Closed consumer for admin topic: " + topic);
    consumer.close();
  }

  private boolean whetherTopicExists(String topicName) {
    if (topicExists) {
      return true;
    }
    // Check it again if it is false
    topicExists = admin.getTopicManager().containsTopic(topicName);
    return topicExists;
  }

  private void makeSureAdminTopicUsingInfiniteRetentionPolicy(String topicName) {
    admin.getTopicManager().updateTopicRetention(topicName, Long.MAX_VALUE);
    logger.info("Admin topic: " + topic + " has been updated to use infinite retention policy");
  }

  /**
   * This method groups {@link AdminOperation}s by their corresponding store.
   * @param record The {@link ConsumerRecord} containing the {@link AdminOperation}.
   */
  private void delegateMessage(ConsumerRecord<KafkaKey, KafkaMessageEnvelope> record) {
    if (checkOffsetToSkip(record.offset()) || !shouldProcessRecord(record)) {
      return;
    }
    KafkaKey kafkaKey = record.key();
    KafkaMessageEnvelope kafkaValue = record.value();
    if (kafkaKey.isControlMessage()) {
      logger.debug("Received control message: " + kafkaValue);
      return;
    }
    // check message type
    MessageType messageType = MessageType.valueOf(kafkaValue);
    if (MessageType.PUT != messageType) {
      throw new VeniceException("Received unexpected message type: " + messageType);
    }
    Put put = (Put) kafkaValue.payloadUnion;
    AdminOperation adminOperation = deserializer.deserialize(put.putValue.array(), put.schemaId);
    logger.debug("Received admin message: " + adminOperation);
    try {
      checkAndValidateMessage(adminOperation, record.offset());
    } catch (DuplicateDataException e) {
      // Previous processed message, safe to skip
      logger.info(e.getMessage());
      return;
    }
    String storeName = extractStoreName(adminOperation);
    storeAdminOperationsMapWithOffset.putIfAbsent(storeName, new LinkedList<>());
    storeAdminOperationsMapWithOffset.get(storeName).add(new Pair<>(record.offset(), adminOperation));
  }

  private void checkAndValidateMessage(AdminOperation message, long offset) {
    long incomingExecutionId = message.executionId;
    if (checkOffsetToSkipDIV(offset) || lastDelegatedExecutionId == UNASSIGNED_VALUE) {
      lastDelegatedExecutionId = incomingExecutionId;
      return;
    }
    if (incomingExecutionId == lastDelegatedExecutionId + 1) {
      // Expected behavior
      lastDelegatedExecutionId++;
    } else if (incomingExecutionId <= lastDelegatedExecutionId) {
      throw new DuplicateDataException("Skipping message with execution id: " + incomingExecutionId
          + " because last delegated execution id was: " + lastDelegatedExecutionId);
    } else {
      throw new MissingDataException("Last delegated execution id was: " + lastDelegatedExecutionId
          + " ,but incoming execution id is: " + incomingExecutionId);
    }
  }

  /**
   * This method extracts the corresponding store name from the {@link AdminOperation} based on the
   * {@link AdminMessageType}.
   * @param adminOperation the {@link AdminOperation} to have its store name extracted.
   * @return the corresponding store name.
   */
  private String extractStoreName(AdminOperation adminOperation) {
    switch (AdminMessageType.valueOf(adminOperation)) {
      case KILL_OFFLINE_PUSH_JOB:
        KillOfflinePushJob message = (KillOfflinePushJob) adminOperation.payloadUnion;
        return Version.parseStoreFromKafkaTopicName(message.kafkaTopic.toString());
      default:
        try {
          GenericRecord payload = (GenericRecord) adminOperation.payloadUnion;
          return payload.get("storeName").toString();
        } catch (Exception e) {
          throw new VeniceException("Failed to handle operation type: " + adminOperation.operationType
              + " because it does not contain a storeName field");
        }
    }
  }

  private void updateLastOffset(long offset) {
    if (offset > lastOffset.getOffset()) {
      lastOffset.setOffset(offset);
    }
  }

  private void persistLastExecutionIdAndLastOffset() {
    executionIdAccessor.updateLastSucceededExecutionId(clusterName, lastSucceededExecutionId);
    if (lastPersistedOffset == lastOffset.getOffset()) {
      // offset did not change from previously persisted value
      return;
    }
    offsetManager.put(topic, AdminTopicUtils.ADMIN_TOPIC_PARTITION_ID, lastOffset);
    lastPersistedOffset = lastOffset.getOffset();
  }

  public void skipMessageWithOffset(long offset) {
    if (offset == failingOffset) {
      offsetToSkip = offset;
    } else {
      throw new VeniceException(
          "Cannot skip an offset that isn't the first one failing.  Last failed offset is: " + failingOffset);
    }
  }

  void skipMessageDIVWithOffset(long offset) {
    if (offset == failingOffset) {
      offsetToSkipDIV = offset;
    } else {
      throw new VeniceException(
          "Cannot skip an offset that isn't the first one failing.  Last failed offset is: " + failingOffset);
    }
  }

  private boolean checkOffsetToSkip(long offset) {
    boolean skip = false;
    if (offset == offsetToSkip) {
      logger.warn("Skipping admin message with offset " + offset + " as instructed");
      offsetToSkip = UNASSIGNED_VALUE;
      skip = true;
    }
    return skip;
  }

  private boolean checkOffsetToSkipDIV(long offset) {
    boolean skip = false;
    if (offset == offsetToSkipDIV) {
      logger.warn("Skipping DIV for admin message with offset " + offset + " as instructed");
      offsetToSkipDIV = UNASSIGNED_VALUE;
      skip = true;
    }
    return skip;
  }

  long getLastSucceededExecutionId() {
    return lastSucceededExecutionId;
  }

  long getFailingOffset() {
    return failingOffset;
  }

  private boolean shouldProcessRecord(ConsumerRecord<KafkaKey, KafkaMessageEnvelope> record) {
    // check topic
    String recordTopic = record.topic();
    if (!topic.equals(recordTopic)) {
      throw new VeniceException(
          consumerTaskId + " received message from different topic: " + recordTopic + ", expected: " + topic);
    }
    // check partition
    int recordPartition = record.partition();
    if (AdminTopicUtils.ADMIN_TOPIC_PARTITION_ID != recordPartition) {
      throw new VeniceException(
          consumerTaskId + " received message from different partition: " + recordPartition + ", expected: "
              + AdminTopicUtils.ADMIN_TOPIC_PARTITION_ID);
    }
    long recordOffset = record.offset();
    // check offset
    if (lastOffset.getOffset() >= recordOffset) {
      logger.error(
          "Current record has been processed, last known offset: " + lastOffset.getOffset() +
              ", current offset: " + recordOffset);
      return false;
    }

    return true;
  }
}
