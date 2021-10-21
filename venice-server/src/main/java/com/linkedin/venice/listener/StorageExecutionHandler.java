package com.linkedin.venice.listener;

import com.linkedin.davinci.compression.StorageEngineBackedCompressorFactory;
import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.listener.response.AdminResponse;
import com.linkedin.davinci.listener.response.ReadResponse;
import com.linkedin.davinci.storage.DiskHealthCheckService;
import com.linkedin.davinci.storage.MetadataRetriever;
import com.linkedin.davinci.storage.StorageEngineRepository;
import com.linkedin.davinci.storage.chunking.BatchGetChunkingAdapter;
import com.linkedin.davinci.storage.chunking.GenericRecordChunkingAdapter;
import com.linkedin.davinci.storage.chunking.SingleGetChunkingAdapter;
import com.linkedin.davinci.store.AbstractStorageEngine;
import com.linkedin.davinci.store.record.ValueRecord;
import com.linkedin.davinci.store.rocksdb.RocksDBComputeAccessMode;
import com.linkedin.venice.VeniceConstants;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compute.ComputeOperationUtils;
import com.linkedin.venice.compute.ComputeRequestWrapper;
import com.linkedin.venice.compute.ReadComputeOperator;
import com.linkedin.venice.compute.protocol.request.ComputeOperation;
import com.linkedin.venice.compute.protocol.request.enums.ComputeOperationType;
import com.linkedin.venice.compute.protocol.request.router.ComputeRouterRequestKeyV1;
import com.linkedin.venice.compute.protocol.response.ComputeResponseRecordV1;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.listener.request.AdminRequest;
import com.linkedin.venice.listener.request.ComputeRouterRequestWrapper;
import com.linkedin.venice.listener.request.DictionaryFetchRequest;
import com.linkedin.venice.listener.request.GetRouterRequest;
import com.linkedin.venice.listener.request.HealthCheckRequest;
import com.linkedin.venice.listener.request.MultiGetRouterRequestWrapper;
import com.linkedin.venice.listener.request.RouterRequest;
import com.linkedin.venice.listener.response.BinaryResponse;
import com.linkedin.venice.listener.response.ComputeResponseWrapper;
import com.linkedin.venice.listener.response.HttpShortcutResponse;
import com.linkedin.venice.listener.response.MultiGetResponseWrapper;
import com.linkedin.venice.listener.response.MultiKeyResponseWrapper;
import com.linkedin.venice.listener.response.StorageResponseObject;
import com.linkedin.venice.meta.PartitionerConfig;
import com.linkedin.venice.meta.PartitionerConfigImpl;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.partitioner.VenicePartitioner;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.read.protocol.request.router.MultiGetRouterRequestKeyV1;
import com.linkedin.venice.read.protocol.response.MultiGetResponseRecordV1;
import com.linkedin.venice.serializer.FastSerializerDeserializerFactory;
import com.linkedin.venice.serializer.RecordSerializer;
import com.linkedin.venice.serializer.SerializerDeserializerFactory;
import com.linkedin.venice.streaming.StreamingConstants;
import com.linkedin.venice.streaming.StreamingUtils;
import com.linkedin.venice.utils.ByteUtils;
import com.linkedin.venice.utils.ComplementSet;
import com.linkedin.venice.utils.ComputeUtils;
import com.linkedin.venice.utils.LatencyUtils;
import com.linkedin.venice.utils.PartitionUtils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import com.linkedin.venice.utils.queues.LabeledRunnable;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.util.Utf8;
import org.apache.log4j.Logger;


/***
 * {@link StorageExecutionHandler} will take the incoming {@link RouterRequest}, and delegate the lookup request to
 * a thread pool {@link #executor}, which is being shared by all the requests.
 * Especially, this handler will execute parallel lookups for {@link MultiGetRouterRequestWrapper}.
 */
@ChannelHandler.Sharable
public class StorageExecutionHandler extends ChannelInboundHandlerAdapter {
  private static final Logger logger = Logger.getLogger(StorageExecutionHandler.class);

  /**
   * When constructing a {@link BinaryDecoder}, we pass in this 16 bytes array because if we pass anything
   * less than that, it would end up getting discarded by the ByteArrayByteSource's constructor, a new byte
   * array created, and the content of the one passed in would be copied into the newly constructed one.
   * Therefore, it seems more efficient, in terms of GC, to statically allocate a 16 bytes array and keep
   * re-using it to construct decoders. Since we always end up re-configuring the decoder and not actually
   * using its initial value, it shouldn't cause any issue to share it.
   */
  private static final byte[] BINARY_DECODER_PARAM = new byte[16];

  private final DiskHealthCheckService diskHealthCheckService;
  private final ThreadPoolExecutor executor;
  private final ThreadPoolExecutor computeExecutor;
  private final StorageEngineRepository storageEngineRepository;
  private final ReadOnlyStoreRepository metadataRepository;
  private final ReadOnlySchemaRepository schemaRepo;
  private final MetadataRetriever metadataRetriever;
  private final Map<Utf8, Schema> computeResultSchemaCache;
  private final boolean fastAvroEnabled;
  private final boolean parallelBatchGetEnabled;
  private final int parallelBatchGetChunkSize;
  private final boolean keyValueProfilingEnabled;
  private final RocksDBComputeAccessMode rocksDBComputeAccessMode;
  private final VeniceServerConfig serverConfig;
  private final Map<String, VenicePartitioner> venicePartitioners = new VeniceConcurrentHashMap<>();
  private final StorageEngineBackedCompressorFactory compressorFactory;

  private static class ReusableObjects {
    // reuse buffer for rocksDB value object
    final ByteBuffer reusedByteBuffer = ByteBuffer.allocate(1024 * 1024);

    // LRU cache for storing schema->record map for object reuse of value and result record
    final LinkedHashMap<Schema, GenericRecord> reuseValueRecordMap = new LinkedHashMap<Schema, GenericRecord>(100, 0.75f, true){
      protected boolean removeEldestEntry(Map.Entry <Schema, GenericRecord> eldest) {
        return size() > 100;
      }
    };

    final LinkedHashMap<Schema, GenericRecord> reuseResultRecordMap = new LinkedHashMap<Schema, GenericRecord>(100, 0.75f, true){
      protected boolean removeEldestEntry(Map.Entry <Schema, GenericRecord> eldest) {
        return size() > 100;
      }
    };
  }
  private final ThreadLocal<ReusableObjects> threadLocalReusableObjects = ThreadLocal.withInitial(ReusableObjects::new);

  public StorageExecutionHandler(ThreadPoolExecutor executor, ThreadPoolExecutor computeExecutor,
                                  StorageEngineRepository storageEngineRepository,
                                  ReadOnlyStoreRepository metadataStoreRepository,
                                  ReadOnlySchemaRepository schemaRepository,
                                  MetadataRetriever metadataRetriever, DiskHealthCheckService healthCheckService,
                                  boolean fastAvroEnabled, boolean parallelBatchGetEnabled, int parallelBatchGetChunkSize,
                                  VeniceServerConfig serverConfig, StorageEngineBackedCompressorFactory compressorFactory) {
    this.executor = executor;
    this.computeExecutor = computeExecutor;
    this.storageEngineRepository = storageEngineRepository;
    this.metadataRepository = metadataStoreRepository;
    this.schemaRepo = schemaRepository;
    this.metadataRetriever = metadataRetriever;
    this.diskHealthCheckService = healthCheckService;
    this.fastAvroEnabled = fastAvroEnabled;
    this.computeResultSchemaCache = new VeniceConcurrentHashMap<>();
    this.parallelBatchGetEnabled = parallelBatchGetEnabled;
    this.parallelBatchGetChunkSize = parallelBatchGetChunkSize;
    this.keyValueProfilingEnabled = serverConfig.isKeyValueProfilingEnabled();
    this.rocksDBComputeAccessMode = serverConfig.getRocksDBServerConfig().getServerStorageOperation();
    this.serverConfig = serverConfig;
    this.compressorFactory = compressorFactory;
  }

  @Override
  public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
    final long preSubmissionTimeNs = System.nanoTime();

    /**
     * N.B.: This is the only place in the entire class where we submit things into the {@link executor}.
     *
     * The reason for this is two-fold:
     *
     * 1. We want to make the {@link StorageExecutionHandler} fully non-blocking as far as Netty (which
     *    is the one calling this function) is concerned. Therefore, it is beneficial to fork off the
     *    work into the executor from the very beginning.
     * 2. By making the execution asynchronous from the beginning, we can simplify the rest of the class
     *    by making every other function a blocking one. If there is a desire to introduce additional
     *    concurrency in the rest of the class (i.e.: to make batch gets or large value re-assembly
     *    parallel), then it would be good to carefully consider whether this is a premature optimization,
     *    and if not, whether these additional operations should be performed in the same executor or in
     *    a secondary one, so as to not starve the primary requests. Furthermore, it should be considered
     *    whether it might be more beneficial to do streaming of these large response use cases, rather
     *    than parallel operations gated behind a synchronization barrier before any of the response can
     *    be sent out.
     */

    if (message instanceof RouterRequest) {
      RouterRequest request = (RouterRequest) message;
      // Check before putting the request to the intermediate queue
      if (request.shouldRequestBeTerminatedEarly()) {
        // Try to make the response short
        VeniceRequestEarlyTerminationException earlyTerminationException = new VeniceRequestEarlyTerminationException(request.getStoreName());
        context.writeAndFlush(new HttpShortcutResponse(earlyTerminationException.getMessage(), earlyTerminationException.getHttpResponseStatus()));
        return;
      }
      /**
       * For now, we are evaluating whether parallel lookup is good overall or not.
       * Eventually, we either pick up the new parallel implementation or keep the original one, so it is fine
       * to have some duplicate code for the time-being.
       */
      if (parallelBatchGetEnabled && request.getRequestType().equals(RequestType.MULTI_GET)) {
        handleMultiGetRequestInParallel((MultiGetRouterRequestWrapper) request, parallelBatchGetChunkSize)
            .whenComplete( (v, e) -> {
              if (e != null) {
                if (e instanceof VeniceRequestEarlyTerminationException) {
                  VeniceRequestEarlyTerminationException earlyTerminationException = (VeniceRequestEarlyTerminationException)e;
                  context.writeAndFlush(new HttpShortcutResponse(earlyTerminationException.getMessage(), earlyTerminationException.getHttpResponseStatus()));
                } else {
                  logger.error("Exception thrown in parallel batch get for " + request.getResourceName(), e);
                  context.writeAndFlush(new HttpShortcutResponse(e.getMessage(), HttpResponseStatus.INTERNAL_SERVER_ERROR));
                }
              } else {
                context.writeAndFlush(v);
              }
            });
        return;
      }

      final ThreadPoolExecutor executor = getExecutor(request.getRequestType());
      executor.submit(new LabeledRunnable(request.getStoreName(), () -> {
        try {
          if (request.shouldRequestBeTerminatedEarly()) {
            throw new VeniceRequestEarlyTerminationException(request.getStoreName());
          }
          double submissionWaitTime = LatencyUtils.getLatencyInMS(preSubmissionTimeNs);
          int queueLen = executor.getQueue().size();
          ReadResponse response;
          switch (request.getRequestType()) {
            case SINGLE_GET:
              response = handleSingleGetRequest((GetRouterRequest) request);
              break;
            case MULTI_GET:
              response = handleMultiGetRequest((MultiGetRouterRequestWrapper) request);
              break;
            case COMPUTE:
              response = handleComputeRequest((ComputeRouterRequestWrapper) message);
              break;
            default:
              throw new VeniceException("Unknown request type: " + request.getRequestType());
          }
          response.setStorageExecutionSubmissionWaitTime(submissionWaitTime);
          response.setStorageExecutionQueueLen(queueLen);
          if (request.isStreamingRequest()) {
            response.setStreamingResponse();
          }
          context.writeAndFlush(response);
        } catch (VeniceRequestEarlyTerminationException e) {
          context.writeAndFlush(new HttpShortcutResponse(e.getMessage(), e.getHttpResponseStatus()));
        } catch (Exception e) {
          logger.error("Exception thrown for " + request.getResourceName(), e);
          context.writeAndFlush(new HttpShortcutResponse(e.getMessage(), HttpResponseStatus.INTERNAL_SERVER_ERROR));
        }
      }));

    } else if (message instanceof HealthCheckRequest) {
      if (diskHealthCheckService.isDiskHealthy()) {
        context.writeAndFlush(new HttpShortcutResponse("OK", HttpResponseStatus.OK));
      } else {
        context.writeAndFlush(new HttpShortcutResponse("Venice storage node hardware is not healthy!", HttpResponseStatus.INTERNAL_SERVER_ERROR));
        logger.error("Disk is not healthy according to the disk health check service: " + diskHealthCheckService.getErrorMessage());
      }
    } else if (message instanceof DictionaryFetchRequest) {
      BinaryResponse response = handleDictionaryFetchRequest((DictionaryFetchRequest) message);
      context.writeAndFlush(response);
    } else if (message instanceof AdminRequest) {
      AdminResponse response = handleServerAdminRequest((AdminRequest) message);
      context.writeAndFlush(response);
    } else {
      context.writeAndFlush(new HttpShortcutResponse("Unrecognized object in StorageExecutionHandler",
          HttpResponseStatus.INTERNAL_SERVER_ERROR));
    }
  }

  private ThreadPoolExecutor getExecutor(RequestType requestType) {
    switch (requestType) {
      case SINGLE_GET:
      case MULTI_GET:
        return executor;
      case COMPUTE:
        return computeExecutor;
      default:
        throw new VeniceException("Request type " + requestType + " is not supported.");
    }
  }

  private int getSubPartitionId(int userPartition, String resourceName, PartitionerConfig partitionerConfig, byte[] keyBytes) {
    if (partitionerConfig == null || partitionerConfig.getAmplificationFactor() == 1) {
      return userPartition;
    }
    VenicePartitioner venicePartitioner = venicePartitioners.computeIfAbsent(resourceName, k -> {
      Properties partitionerParams = new Properties();
      if (partitionerConfig.getPartitionerParams() != null) {
        partitionerParams.putAll(partitionerConfig.getPartitionerParams());
      }
      // specify amplificationFactor as 1 to avoid using UserPartitionAwarePartitioner
      return PartitionUtils.getVenicePartitioner(partitionerConfig.getPartitionerClass(),
          1, new VeniceProperties(partitionerParams));
    });
    int subPartitionOffset = venicePartitioner.getPartitionId(keyBytes, partitionerConfig.getAmplificationFactor());
    int subPartition = userPartition * partitionerConfig.getAmplificationFactor() + subPartitionOffset;
    return subPartition;
  }

  private PartitionerConfig getPartitionerConfig(String resourceName) {
    try {
      PartitionerConfig partitionerConfig = null;
      String storeName = Version.parseStoreFromKafkaTopicName(resourceName);
      int versionNumber = Version.parseVersionFromKafkaTopicName(resourceName);
      Store store = metadataRepository.getStoreOrThrow(storeName);
      Optional<Version> version = store.getVersion(versionNumber);
      if (version.isPresent()) {
        partitionerConfig = version.get().getPartitionerConfig();
      }
      return partitionerConfig;
    } catch (Exception e) {
      logger.error("Can not acquire partitionerConfig. ", e);
      return new PartitionerConfigImpl();
    }
  }

  private ReadResponse handleSingleGetRequest(GetRouterRequest request) {
    PartitionerConfig partitionerConfig = getPartitionerConfig(request.getResourceName());
    int subPartition = getSubPartitionId(request.getPartition(), request.getResourceName(),
        partitionerConfig, request.getKeyBytes());
    String topic = request.getResourceName();
    byte[] key = request.getKeyBytes();
    boolean isChunked = metadataRetriever.isStoreVersionChunked(topic);

    AbstractStorageEngine storageEngine = storageEngineRepository.getLocalStorageEngine(topic);
    Optional<Long> offsetObj = metadataRetriever.getOffset(topic, subPartition);
    long offset = offsetObj.isPresent() ? offsetObj.get() : OffsetRecord.LOWEST_OFFSET;
    StorageResponseObject response = new StorageResponseObject();
    response.setCompressionStrategy(metadataRetriever.getStoreVersionCompressionStrategy(topic));
    response.setDatabaseLookupLatency(0);

    ValueRecord valueRecord = SingleGetChunkingAdapter.get(storageEngine, subPartition, key, isChunked, response);
    response.setValueRecord(valueRecord);
    response.setOffset(offset);

    if (keyValueProfilingEnabled) {
      response.setOptionalKeySizeList(Optional.of(Arrays.asList(key.length)));
      response.setOptionalValueSizeList(Optional.of(Arrays.asList(response.isFound() ? valueRecord.getDataSize() : 0)));
    }

    return response;
  }

  private CompletableFuture<ReadResponse> handleMultiGetRequestInParallel(MultiGetRouterRequestWrapper request, int parallelChunkSize) {
    String topic = request.getResourceName();
    Iterable<MultiGetRouterRequestKeyV1> keys = request.getKeys();
    AbstractStorageEngine store = storageEngineRepository.getLocalStorageEngine(topic);

    MultiGetResponseWrapper responseWrapper = new MultiGetResponseWrapper();
    responseWrapper.setCompressionStrategy(metadataRetriever.getStoreVersionCompressionStrategy(topic));
    responseWrapper.setDatabaseLookupLatency(0);
    boolean isChunked = metadataRetriever.isStoreVersionChunked(topic);

    ExecutorService executorService = getExecutor(RequestType.MULTI_GET);
    if (!(keys instanceof ArrayList)) {
      throw new VeniceException("'keys' in MultiGetResponseWrapper should be an ArrayList");
    }
    final ArrayList<MultiGetRouterRequestKeyV1> keyList = (ArrayList)keys;
    int totalKeyNum = keyList.size();
    int splitSize = (int)Math.ceil((double)totalKeyNum / parallelChunkSize);

    ReentrantLock requestLock = new ReentrantLock();
    CompletableFuture[] chunkFutures = new CompletableFuture[splitSize];
    PartitionerConfig partitionerConfig = getPartitionerConfig(request.getResourceName());
    Set<Integer> subPartitionIds = VeniceConcurrentHashMap.newKeySet();

    Optional<List<Integer>> optionalKeyList =  keyValueProfilingEnabled
        ? Optional.of(new ArrayList<>(totalKeyNum)) : Optional.empty();
    Optional<List<Integer>> optionalValueList = keyValueProfilingEnabled
        ? Optional.of(new ArrayList<>(totalKeyNum)) : Optional.empty();

    for (int cur = 0; cur < splitSize; ++cur) {
      final int finalCur = cur;
      chunkFutures[cur] = CompletableFuture.runAsync(() -> {
        if (request.shouldRequestBeTerminatedEarly()) {
          throw new VeniceRequestEarlyTerminationException(request.getStoreName());
        }
        int startPos = finalCur * parallelChunkSize;
        int endPos = Math.min((finalCur + 1) * parallelChunkSize, totalKeyNum);
        for (int subChunkCur = startPos; subChunkCur < endPos; ++subChunkCur) {
          final MultiGetRouterRequestKeyV1 key = keyList.get(subChunkCur);
          optionalKeyList.ifPresent(list -> list.add(key.keyBytes.remaining()));
          int subPartitionId = getSubPartitionId(key.partitionId, request.getResourceName(),
              partitionerConfig, key.keyBytes.array());
          subPartitionIds.add(subPartitionId);
          MultiGetResponseRecordV1 record =
              BatchGetChunkingAdapter.get(store, subPartitionId, key.keyBytes, isChunked, responseWrapper);
          if (null == record) {
            if (request.isStreamingRequest()) {
              // For streaming, we would like to send back non-existing keys since the end-user won't know the status of
              // non-existing keys in the response if the response is partial.
              record = new MultiGetResponseRecordV1();
              // Negative key index to indicate the non-existing keys
              record.keyIndex = Math.negateExact(key.keyIndex);
              record.schemaId = StreamingConstants.NON_EXISTING_KEY_SCHEMA_ID;
              record.value = StreamingUtils.EMPTY_BYTE_BUFFER;
            }
          } else {
            record.keyIndex = key.keyIndex;
          }

          if (null != record) {
            // TODO: streaming support in storage node
            requestLock.lock();
            try {
              responseWrapper.addRecord(record);

              if (optionalValueList.isPresent()) {
                optionalValueList.get().add(record.value.remaining());
              }

            } finally {
              requestLock.unlock();
            }
          }
        }
      }, executorService);
    }

    // Offset data
    for (int subPartitionId : subPartitionIds) {
      addPartitionOffsetMapping(topic, subPartitionId, responseWrapper);
    }

    return CompletableFuture.allOf(chunkFutures).handle((v, e) -> {
      if (e != null) {
        throw new VeniceException(e);
      }
      responseWrapper.setOptionalKeySizeList(optionalKeyList);
      responseWrapper.setOptionalValueSizeList(optionalValueList);
      return responseWrapper;
    });
  }

  private ReadResponse handleMultiGetRequest(MultiGetRouterRequestWrapper request) {
    String topic = request.getResourceName();
    Iterable<MultiGetRouterRequestKeyV1> keys = request.getKeys();
    PartitionerConfig partitionerConfig = getPartitionerConfig(request.getResourceName());
    Set<Integer> subPartitionIds = new HashSet<>();
    AbstractStorageEngine store = storageEngineRepository.getLocalStorageEngine(topic);

    MultiGetResponseWrapper responseWrapper = new MultiGetResponseWrapper();
    responseWrapper.setCompressionStrategy(metadataRetriever.getStoreVersionCompressionStrategy(topic));
    responseWrapper.setDatabaseLookupLatency(0);
    boolean isChunked = metadataRetriever.isStoreVersionChunked(topic);
    for (MultiGetRouterRequestKeyV1 key : keys) {
      int subPartitionId = getSubPartitionId(key.partitionId, request.getResourceName(),
          partitionerConfig, key.keyBytes.array());
      subPartitionIds.add(subPartitionId);
      MultiGetResponseRecordV1 record =
          BatchGetChunkingAdapter.get(store, subPartitionId, key.keyBytes, isChunked, responseWrapper);
      if (null == record) {
        if (request.isStreamingRequest()) {
          // For streaming, we would like to send back non-existing keys since the end-user won't know the status of
          // non-existing keys in the response if the response is partial.
          record = new MultiGetResponseRecordV1();
          // Negative key index to indicate the non-existing keys
          record.keyIndex = Math.negateExact(key.keyIndex);
          record.schemaId = StreamingConstants.NON_EXISTING_KEY_SCHEMA_ID;
          record.value = StreamingUtils.EMPTY_BYTE_BUFFER;
        }
      } else {
        record.keyIndex = key.keyIndex;
      }

      if (null != record) {
        // TODO: streaming support in storage node
        responseWrapper.addRecord(record);
      }
    }

    // Offset data
    for (int subPartitionId : subPartitionIds) {
      addPartitionOffsetMapping(topic, subPartitionId, responseWrapper);
    }

    return responseWrapper;
  }

  private ReadResponse handleComputeRequest(ComputeRouterRequestWrapper request) {
    String topic = request.getResourceName();
    String storeName = request.getStoreName();
    Iterable<ComputeRouterRequestKeyV1> keys = request.getKeys();
    AbstractStorageEngine store = storageEngineRepository.getLocalStorageEngine(topic);
    PartitionerConfig partitionerConfig = getPartitionerConfig(request.getResourceName());
    Set<Integer> subPartitionIds = new HashSet<>();

    Schema valueSchema;
    if (request.getValueSchemaId() != -1) {
      valueSchema = this.schemaRepo.getValueSchema(storeName, request.getValueSchemaId()).getSchema();
    } else {
      valueSchema = this.schemaRepo.getLatestValueSchema(storeName).getSchema();
    }
    ComputeRequestWrapper computeRequestWrapper = request.getComputeRequest();

    // try to get the result schema from the cache
    Utf8 computeResultSchemaStr = (Utf8) computeRequestWrapper.getResultSchemaStr();
    Schema computeResultSchema = computeResultSchemaCache.get(computeResultSchemaStr);
    if (computeResultSchema == null) {
      computeResultSchema = Schema.parse(computeResultSchemaStr.toString());
      // sanity check on the result schema
      ComputeUtils.checkResultSchema(computeResultSchema, valueSchema, computeRequestWrapper.getComputeRequestVersion(), computeRequestWrapper.getOperations());
      computeResultSchemaCache.putIfAbsent(computeResultSchemaStr, computeResultSchema);
    }

    ComputeResponseWrapper responseWrapper = new ComputeResponseWrapper();
    CompressionStrategy compressionStrategy = metadataRetriever.getStoreVersionCompressionStrategy(topic);
    boolean isChunked = metadataRetriever.isStoreVersionChunked(topic);

    // The following metrics will get incremented for each record processed in computeResult()
    responseWrapper.setReadComputeDeserializationLatency(0.0);
    responseWrapper.setDatabaseLookupLatency(0.0);
    responseWrapper.setReadComputeSerializationLatency(0.0);
    responseWrapper.setReadComputeLatency(0.0);

    responseWrapper.setCompressionStrategy(CompressionStrategy.NO_OP);

    ReusableObjects reusableObjects = threadLocalReusableObjects.get();

    GenericRecord reuseValueRecord = reusableObjects.reuseValueRecordMap.computeIfAbsent(valueSchema, k -> new GenericData.Record(valueSchema));
    Schema finalComputeResultSchema1 = computeResultSchema;
    GenericRecord reuseResultRecord =  reusableObjects.reuseResultRecordMap.computeIfAbsent(computeResultSchema, k -> new GenericData.Record(finalComputeResultSchema1));

    // Reuse the same value record and result record instances for all values
    ByteBuffer reusedRawValue = null;
    if (rocksDBComputeAccessMode == RocksDBComputeAccessMode.SINGLE_GET_WITH_REUSE) {
      reusedRawValue = reusableObjects.reusedByteBuffer;
    }

    RecordSerializer<GenericRecord> resultSerializer;
    if (fastAvroEnabled) {
      resultSerializer = FastSerializerDeserializerFactory.getFastAvroGenericSerializer(computeResultSchema);
    } else {
      resultSerializer = SerializerDeserializerFactory.getAvroGenericSerializer(computeResultSchema);
    }

    BinaryDecoder binaryDecoder = DecoderFactory.defaultFactory().createBinaryDecoder(BINARY_DECODER_PARAM, null);
    Map<String, Object> globalContext = new HashMap<>();
    for (ComputeRouterRequestKeyV1 key : keys) {
      clearFieldsInReusedRecord(reuseResultRecord, computeResultSchema);
      int subPartitionId = getSubPartitionId(key.partitionId, request.getResourceName(),
          partitionerConfig, key.keyBytes.array());
      subPartitionIds.add(subPartitionId);
      ComputeResponseRecordV1 record = computeResult(store,
                                                     storeName,
                                                     key.keyBytes,
                                                     key.keyIndex,
                                                     subPartitionId,
                                                     computeRequestWrapper.getComputeRequestVersion(),
                                                     computeRequestWrapper.getOperations(),
                                                     compressionStrategy,
                                                     computeResultSchema,
                                                     resultSerializer,
                                                     reuseValueRecord,
                                                     reuseResultRecord,
                                                     binaryDecoder,
                                                     isChunked,
                                                     request.isStreamingRequest(),
                                                     responseWrapper,
                                                     globalContext,
                                                     reusedRawValue
                                                     );
      if (null != record) {
        // TODO: streaming support in storage node
        responseWrapper.addRecord(record);
      }
    }

    // Offset data
    for (int subPartitionId : subPartitionIds) {
      addPartitionOffsetMapping(topic, subPartitionId, responseWrapper);
    }

    return responseWrapper;
  }

  private BinaryResponse handleDictionaryFetchRequest(DictionaryFetchRequest request) {
    String topic = request.getResourceName();
    ByteBuffer dictionary = metadataRetriever.getStoreVersionCompressionDictionary(topic);

    return new BinaryResponse(dictionary);
  }

  private void addPartitionOffsetMapping(String topic, int partitionId,
      MultiKeyResponseWrapper responseWrapper) {
    Optional<Long> offsetObj = metadataRetriever.getOffset(topic, partitionId);
    long offset = offsetObj.isPresent() ? offsetObj.get() : OffsetRecord.LOWEST_OFFSET;
    responseWrapper.addPartitionOffsetMapping(partitionId, offset);
  }

  private void clearFieldsInReusedRecord(GenericRecord record, Schema schema) {
    for (int idx = 0; idx < schema.getFields().size(); idx++) {
      record.put(idx, null);
    }
  }

  private ComputeResponseRecordV1 computeResult(
      AbstractStorageEngine store,
      String storeName,
      ByteBuffer key,
      final int keyIndex,
      int partition,
      int computeRequestVersion,
      List<ComputeOperation> operations,
      CompressionStrategy compressionStrategy,
      Schema computeResultSchema,
      RecordSerializer<GenericRecord> resultSerializer,
      GenericRecord reuseValueRecord,
      GenericRecord reuseResultRecord,
      BinaryDecoder binaryDecoder,
      boolean isChunked,
      boolean isStreaming,
      ComputeResponseWrapper response,
      Map<String, Object> globalContext,
      ByteBuffer reuseRawValue) {

    switch (rocksDBComputeAccessMode) {
      case SINGLE_GET:
        reuseValueRecord =
            GenericRecordChunkingAdapter.INSTANCE.get(store, partition, key, isChunked, reuseValueRecord,
                binaryDecoder, response, compressionStrategy, fastAvroEnabled, this.schemaRepo, storeName, compressorFactory);
        break;
      case SINGLE_GET_WITH_REUSE:
        reuseValueRecord = GenericRecordChunkingAdapter.INSTANCE.get(storeName, store, partition, ByteUtils.extractByteArray(key),
          reuseRawValue, reuseValueRecord, binaryDecoder, isChunked, compressionStrategy, fastAvroEnabled, this.schemaRepo, response, compressorFactory);
        break;
      default:
        throw new VeniceException("Unknown rocksDB compute storage operation");
    }

    if (null == reuseValueRecord) {
      if (isStreaming) {
        // For streaming, we need to send back non-existing keys
        ComputeResponseRecordV1 computeResponseRecord = new ComputeResponseRecordV1();
        // Negative key index to indicate non-existing key
        computeResponseRecord.keyIndex = Math.negateExact(keyIndex);
        computeResponseRecord.value = StreamingUtils.EMPTY_BYTE_BUFFER;
        return computeResponseRecord;
      }
      return null;
    }

    long computeStartTimeInNS = System.nanoTime();
    Map<String, String> computationErrorMap = new HashMap<>();

    // go through all operation
    for (ComputeOperation operation : operations) {
      ReadComputeOperator operator = ComputeOperationType.valueOf(operation).getOperator();
      String fieldName = operator.getOperatorFieldName(operation);
      String errorMessage = ComputeOperationUtils.validateNullableFieldAndGetErrorMsg(
          operator,
          reuseValueRecord,
          fieldName
      ).orElse(null);
      if (errorMessage != null) {
        operator.putDefaultResult(reuseResultRecord, operator.getResultFieldName(operation));
        computationErrorMap.put(operator.getResultFieldName(operation), errorMessage);
        continue;
      }
      incrementOperatorCount(response, operation);
      operator.compute(computeRequestVersion, operation, reuseValueRecord, reuseResultRecord, computationErrorMap, globalContext);
    }

    // fill the empty field in result schema
    for (Schema.Field field : computeResultSchema.getFields()) {
      if (reuseResultRecord.get(field.pos()) == null) {
        if (field.name().equals(VeniceConstants.VENICE_COMPUTATION_ERROR_MAP_FIELD_NAME)) {
          reuseResultRecord.put(field.pos(), computationErrorMap);
        } else {
          // project from value record
          reuseResultRecord.put(field.pos(), reuseValueRecord.get(field.name()));
        }
      }
    }
    response.addReadComputeLatency(LatencyUtils.getLatencyInMS(computeStartTimeInNS));

    // create a response record
    ComputeResponseRecordV1 responseRecord = new ComputeResponseRecordV1();
    responseRecord.keyIndex = keyIndex;

    // serialize the compute result
    long serializeStartTimeInNS = System.nanoTime();
    responseRecord.value = ByteBuffer.wrap(resultSerializer.serialize(reuseResultRecord));
    response.addReadComputeSerializationLatency(LatencyUtils.getLatencyInMS(serializeStartTimeInNS));

    return responseRecord;
  }

  private AdminResponse handleServerAdminRequest(AdminRequest adminRequest) {
    switch (adminRequest.getServerAdminAction()) {
      case DUMP_INGESTION_STATE:
        String topicName = adminRequest.getStoreVersion();
        Integer partitionId = adminRequest.getPartition();
        ComplementSet<Integer> partitions = (partitionId == null) ? ComplementSet.universalSet() : ComplementSet.of(partitionId);
        AdminResponse response = metadataRetriever.getConsumptionSnapshots(topicName, partitions);
        return response;
      case DUMP_SERVER_CONFIGS:
        AdminResponse configResponse = new AdminResponse();
        if (this.serverConfig == null) {
          configResponse.setError(true);
          configResponse.setMessage("Server config doesn't exist");
        } else {
          configResponse.addServerConfigs(this.serverConfig.getClusterProperties().toProperties());
        }
        return configResponse;
      default:
        throw new VeniceException("Not a valid admin action: " + adminRequest.getServerAdminAction().toString());
    }
  }

  private void incrementOperatorCount(ComputeResponseWrapper response, ComputeOperation operation){
    switch(ComputeOperationType.valueOf(operation)){
      case DOT_PRODUCT:
        response.incrementDotProductCount();
        break;
      case COSINE_SIMILARITY:
        response.incrementCosineSimilarityCount();
        break;
      case HADAMARD_PRODUCT:
        response.incrementHadamardProductCount();
        break;
      case COUNT:
        response.incrementCountOperatorCount();
        break;
    }
  }
}
