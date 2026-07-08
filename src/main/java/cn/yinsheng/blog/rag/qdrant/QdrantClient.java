package cn.yinsheng.blog.rag.qdrant;

import cn.yinsheng.blog.rag.config.RagProperties;
import cn.yinsheng.blog.rag.model.ChunkRecord;
import cn.yinsheng.blog.rag.model.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class QdrantClient {
  private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);

  private final RestClient restClient;
  private final RagProperties properties;

  public QdrantClient(RestClient restClient, RagProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  public boolean ensureCollection(int vectorSize) {
    try {
      JsonNode collection = restClient.get()
          .uri(collectionUrl())
          .retrieve()
          .body(JsonNode.class);
      int currentSize = collection.path("result")
          .path("config")
          .path("params")
          .path("vectors")
          .path("size")
          .asInt(-1);
      if (currentSize == vectorSize) {
        return false;
      }
      log.warn(
          "Qdrant collection {} vector size changed from {} to {}, recreating it",
          properties.qdrantCollection(),
          currentSize,
          vectorSize
      );
      restClient.delete()
          .uri(collectionUrl() + "?timeout=30")
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound ignored) {
      log.info("Qdrant collection {} does not exist, creating it", properties.qdrantCollection());
    }

    restClient.put()
        .uri(collectionUrl())
        .body(Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine")))
        .retrieve()
        .toBodilessEntity();
    return true;
  }

  public void deleteBySlug(String slug) {
    Map<String, Object> body = Map.of(
        "filter", Map.of(
            "must", List.of(Map.of("key", "slug", "match", Map.of("value", slug)))
        )
    );
    restClient.post()
        .uri(collectionUrl() + "/points/delete?wait=true")
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  public void upsert(List<ChunkRecord> chunks, List<List<Double>> vectors) {
    if (chunks.isEmpty()) {
      return;
    }
    List<Map<String, Object>> points = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      ChunkRecord chunk = chunks.get(i);
      points.add(Map.of(
          "id", stablePointId(chunk.chunkId()),
          "vector", vectors.get(i),
          "payload", payload(chunk)
      ));
    }
    restClient.put()
        .uri(collectionUrl() + "/points?wait=true")
        .body(Map.of("points", points))
        .retrieve()
        .toBodilessEntity();
  }

  public List<RetrievedChunk> search(List<Double> vector, int limit) {
    Map<String, Object> body = Map.of(
        "vector", vector,
        "limit", limit,
        "with_payload", true
    );
    JsonNode response = restClient.post()
        .uri(collectionUrl() + "/points/search")
        .body(body)
        .retrieve()
        .body(JsonNode.class);

    List<RetrievedChunk> chunks = new ArrayList<>();
    for (JsonNode result : response.path("result")) {
      JsonNode payload = result.path("payload");
      chunks.add(new RetrievedChunk(
          result.path("score").asDouble(),
          payload.path("chunk_id").asText(),
          payload.path("slug").asText(),
          payload.path("title").asText(),
          payload.path("section").asText(),
          payload.path("url").asText(),
          payload.path("content").asText(),
          readTags(payload.path("tags")),
          payload.path("updated_at").asText()
      ));
    }
    return chunks;
  }

  private Map<String, Object> payload(ChunkRecord chunk) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("chunk_id", chunk.chunkId());
    payload.put("slug", chunk.slug());
    payload.put("title", chunk.title());
    payload.put("section", chunk.section());
    payload.put("heading_path", chunk.headingPath());
    payload.put("url", chunk.url());
    payload.put("content", chunk.content());
    payload.put("content_hash", chunk.contentHash());
    payload.put("chunk_hash", chunk.chunkHash());
    payload.put("tags", chunk.tags());
    payload.put("date", chunk.date());
    payload.put("updated_at", chunk.updatedAt());
    return payload;
  }

  private List<String> readTags(JsonNode tags) {
    List<String> values = new ArrayList<>();
    if (tags.isArray()) {
      tags.forEach(tag -> values.add(tag.asText()));
    }
    return values;
  }

  private String stablePointId(String chunkId) {
    return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private String collectionUrl() {
    return properties.qdrantUrl() + "/collections/" + properties.qdrantCollection();
  }
}
