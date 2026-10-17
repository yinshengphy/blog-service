package cn.yinsheng.blog.service.qdrant;

import cn.yinsheng.blog.service.config.RagProperties;
import cn.yinsheng.blog.service.model.ChunkRecord;
import cn.yinsheng.blog.service.model.RetrievedChunk;
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
      throw new IllegalStateException("向量维度不一致，必须建立新集合后显式切换，不能覆盖现有索引");
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

  public void recreateCollection(int vectorSize) {
    try {
      restClient.delete()
          .uri(collectionUrl() + "?timeout=30")
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.NotFound ignored) {
      log.info("Qdrant collection {} does not exist before rebuild", properties.qdrantCollection());
    }
    restClient.put()
        .uri(collectionUrl())
        .body(Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine")))
        .retrieve()
        .toBodilessEntity();
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
    upsertInto(properties.qdrantCollection(), chunks, vectors);
  }

  public void upsertInto(String collection, List<ChunkRecord> chunks, List<List<Double>> vectors) {
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
        .uri(properties.qdrantUrl() + "/collections/" + collection + "/points?wait=true")
        .body(Map.of("points", points))
        .retrieve()
        .toBodilessEntity();
  }

  public void replacePost(String slug, List<ChunkRecord> chunks, List<List<Double>> vectors) {
    if (chunks.isEmpty()) throw new IllegalArgumentException("空正文不能覆盖现有索引");
    // 一次写入整篇的新向量，成功后才删除缩短文章遗留的尾部分块。
    upsert(chunks, vectors);
    int lastIndex = chunks.stream().mapToInt(ChunkRecord::chunkIndex).max().orElse(0);
    restClient.post().uri(collectionUrl() + "/points/delete?wait=true").body(Map.of("filter", Map.of("must", List.of(
        Map.of("key", "slug", "match", Map.of("value", slug)),
        Map.of("key", "chunk_index", "range", Map.of("gt", lastIndex)))))).retrieve().toBodilessEntity();
  }

  public String beginRebuild(int vectorSize) {
    JsonNode aliases = restClient.get().uri(properties.qdrantUrl() + "/aliases").retrieve().body(JsonNode.class);
    boolean activeAlias = false;
    if (aliases != null) for (JsonNode alias : aliases.path("result").path("aliases")) {
      if (properties.qdrantCollection().equals(alias.path("alias_name").asText())) activeAlias = true;
    }
    if (!activeAlias) throw new IllegalStateException("全量重建必须通过索引别名执行，请先完成别名迁移");
    String staging = properties.qdrantCollection() + "_build_" + UUID.randomUUID().toString().replace("-", "");
    restClient.put().uri(properties.qdrantUrl() + "/collections/" + staging)
        .body(Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine")))
        .retrieve().toBodilessEntity();
    return staging;
  }

  public void activateRebuild(String staging, int expectedPoints) {
    if (!staging.startsWith(properties.qdrantCollection() + "_build_") || expectedPoints < 1) {
      throw new IllegalArgumentException("拒绝切换空索引或未知重建集合");
    }
    JsonNode count = restClient.post().uri(properties.qdrantUrl() + "/collections/" + staging + "/points/count")
        .body(Map.of("exact", true)).retrieve().body(JsonNode.class);
    if (count == null || count.path("result").path("count").asInt(-1) != expectedPoints) {
      throw new IllegalStateException("重建分块数量校验失败，继续使用旧索引");
    }
    restClient.post().uri(properties.qdrantUrl() + "/collections/aliases")
        .body(Map.of("actions", List.of(
            Map.of("delete_alias", Map.of("alias_name", properties.qdrantCollection())),
            Map.of("create_alias", Map.of("alias_name", properties.qdrantCollection(), "collection_name", staging)))))
        .retrieve().toBodilessEntity();
  }

  public List<RetrievedChunk> search(List<Double> vector, int limit) {
    return search(vector, limit, null);
  }

  public List<RetrievedChunk> search(List<Double> vector, int limit, String slug) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("vector", vector);
    body.put("limit", limit);
    body.put("with_payload", true);
    if (slug != null && !slug.isBlank()) {
      body.put("filter", slugFilter(slug));
    }
    JsonNode response = restClient.post()
        .uri(collectionUrl() + "/points/search")
        .body(body)
        .retrieve()
        .body(JsonNode.class);

    List<RetrievedChunk> chunks = new ArrayList<>();
    for (JsonNode result : response.path("result")) {
      chunks.add(readChunk(result, result.path("score").asDouble()));
    }
    return chunks;
  }

  public List<RetrievedChunk> listBySlug(String slug) {
    return scrollChunks(slug, 256, 1);
  }

  public List<RetrievedChunk> listForRetrieval(String slug) {
    return scrollChunks(slug, 2048, 0);
  }

  private List<RetrievedChunk> scrollChunks(String slug, int limit, double score) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (slug != null && !slug.isBlank()) body.put("filter", slugFilter(slug));
    body.put("limit", limit);
    body.put("with_payload", true);
    body.put("with_vector", false);
    List<RetrievedChunk> chunks = new ArrayList<>();
    JsonNode offset;
    do {
      JsonNode response = restClient.post().uri(collectionUrl() + "/points/scroll")
          .body(body).retrieve().body(JsonNode.class);
      if (response == null) throw new IllegalStateException("索引分页响应为空");
      for (JsonNode point : response.path("result").path("points")) {
        chunks.add(readChunk(point, score));
      }
      offset = response.path("result").path("next_page_offset");
      if (!offset.isMissingNode() && !offset.isNull()) body.put("offset", offset);
    } while (!offset.isMissingNode() && !offset.isNull());
    return chunks.stream().sorted(java.util.Comparator.comparing(RetrievedChunk::slug)
        .thenComparingInt(RetrievedChunk::chunkIndex)).toList();
  }

  public List<PostEntry> listPosts() {
    Map<String, PostEntry> posts = new LinkedHashMap<>();
    for (RetrievedChunk chunk : listForRetrieval(null)) {
      posts.putIfAbsent(chunk.slug(), new PostEntry(chunk.slug(), chunk.title()));
    }
    return new ArrayList<>(posts.values());
  }

  private Map<String, Object> payload(ChunkRecord chunk) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("chunk_id", chunk.chunkId());
    payload.put("chunk_index", chunk.chunkIndex());
    payload.put("slug", chunk.slug());
    payload.put("title", chunk.title());
    payload.put("section", chunk.section());
    payload.put("heading_path", chunk.headingPath());
    payload.put("url", chunk.url());
    payload.put("content", chunk.content());
    payload.put("content_hash", chunk.contentHash());
    payload.put("chunk_hash", chunk.chunkHash());
    payload.put("tags", chunk.tags());
    payload.put("categories", chunk.categories());
    payload.put("description", chunk.description());
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

  private Map<String, Object> slugFilter(String slug) {
    return Map.of("must", List.of(Map.of("key", "slug", "match", Map.of("value", slug))));
  }

  private RetrievedChunk readChunk(JsonNode point, double score) {
    JsonNode payload = point.path("payload");
    return new RetrievedChunk(
        score,
        payload.path("chunk_id").asText(),
        payload.path("chunk_index").asInt(chunkIndex(payload.path("chunk_id").asText())),
        payload.path("slug").asText(),
        payload.path("title").asText(),
        payload.path("section").asText(),
        payload.path("heading_path").asText(),
        payload.path("url").asText(),
        payload.path("content").asText(),
        readTags(payload.path("tags")),
        readTags(payload.path("categories")),
        payload.path("description").asText(),
        payload.path("date").asText(),
        payload.path("updated_at").asText()
    );
  }

  private int chunkIndex(String chunkId) {
    int separator = chunkId.lastIndexOf('-');
    try {
      return separator < 0 ? 0 : Integer.parseInt(chunkId.substring(separator + 1));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  public record PostEntry(String slug, String title) {
  }

  private String stablePointId(String chunkId) {
    return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private String collectionUrl() {
    return properties.qdrantUrl() + "/collections/" + properties.qdrantCollection();
  }
}
