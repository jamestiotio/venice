package com.linkedin.venice.storage.chunking;

import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.listener.response.ReadResponse;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.storage.protocol.ChunkedValueManifest;
import org.apache.avro.io.BinaryDecoder;


/**
 * This interface allows each code path which needs to interact with chunked values
 * to optimize the way the re-assembly is handled so that the final form in which
 * the {@type VALUE} is consumed is generated as efficiently as possible, via the
 * use of a temporary {@link CHUNKS_CONTAINER}.
 */
public interface ChunkingAdapter<CHUNKS_CONTAINER, VALUE> {
  /**
   * Used to wrap a small {@param fullBytes} value fetched from the storage engine into the right type
   * of {@param VALUE} class needed by the query code.
   *
   * The following parameters are mandatory:
   * @param schemaId of the user's value
   * @param fullBytes includes both the schema ID header and the payload
   *
   * The following parameters can be ignored, by implementing {@link #constructValue(int, byte[])}:
   *
   * @param reusedValue a previous instance of {@type VALUE} to be re-used in order to minimize GC
   * @param reusedDecoder a previous instance of {@link BinaryDecoder} to be re-used in order to minimize GC
   * @param response the response returned by the query path, which carries certain metrics to be recorded at the end
   * @param compressionStrategy the store-version's {@link CompressionStrategy}
   * @param fastAvroEnabled whether to use fast-avro or not
   * @param schemaRepo handle from which to retrieve read and write schemas
   * @param storeName to deal with
   *
   * TODO: Consider whether we really need this API. We could instead always use the chunked approach,
   *       even if there is just a single small chunk.
   */
  default VALUE constructValue(
      int schemaId,
      byte[] fullBytes,
      VALUE reusedValue,
      BinaryDecoder reusedDecoder,
      ReadResponse response,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName) {
    return constructValue(schemaId, fullBytes);
  }

  /**
   * This function can be implemented by the adapters which need fewer parameters.
   *
   * @see #constructValue(int, byte[], Object, BinaryDecoder, ReadResponse, CompressionStrategy, boolean, ReadOnlySchemaRepository, String)
   */
  default VALUE constructValue(int schemaId, byte[] fullBytes) {
    throw new VeniceException("Not implemented.");
  }

  /**
   * Used to incrementally add a {@param valueChunk} into the {@param CHUNKS_CONTAINER}
   * container.
   */
  void addChunkIntoContainer(CHUNKS_CONTAINER container, int chunkIndex, byte[] valueChunk);

  /**
   * Used to construct the right kind of {@param CHUNKS_CONTAINER} container (according to
   * the query code) to hold a large value which needs to be incrementally re-assembled from many
   * smaller chunks.
   */
  CHUNKS_CONTAINER constructChunksContainer(ChunkedValueManifest chunkedValueManifest);

  /**
   * Used to wrap a large value re-assembled with the use of a {@param CHUNKS_CONTAINER}
   * into the right type of {@param VALUE} class needed by the query code.
   *
   * The following parameters are mandatory:
   *
   * @param schemaId of the user's value
   * @param chunksContainer temporary {@type CHUNKS_CONTAINER}, obtained from {@link #constructChunksContainer(ChunkedValueManifest)}
   *
   * The following parameters can be ignored, by implementing {@link #constructValue(int, Object)}:
   *
   * @param reusedValue a previous instance of {@type VALUE} to be re-used in order to minimize GC
   * @param reusedDecoder a previous instance of {@link BinaryDecoder} to be re-used in order to minimize GC
   * @param response the response returned by the query path, which carries certain metrics to be recorded at the end
   * @param compressionStrategy the store-version's {@link CompressionStrategy}
   * @param fastAvroEnabled whether to use fast-avro or not
   * @param schemaRepo handle from which to retrieve read and write schemas
   * @param storeName to deal with
   */
  default VALUE constructValue(
      int schemaId,
      CHUNKS_CONTAINER chunksContainer,
      VALUE reusedValue,
      BinaryDecoder reusedDecoder,
      ReadResponse response,
      CompressionStrategy compressionStrategy,
      boolean fastAvroEnabled,
      ReadOnlySchemaRepository schemaRepo,
      String storeName){
    return constructValue(schemaId, chunksContainer);
  }

  /**
   * This function can be implemented by the adapters which need fewer parameters.
   *
   * @see #constructValue(int, Object, Object, BinaryDecoder, ReadResponse, CompressionStrategy, boolean, ReadOnlySchemaRepository, String)
   */
  default VALUE constructValue(int schemaId, CHUNKS_CONTAINER chunksContainer) {
    throw new VeniceException("Not implemented.");
  }
}