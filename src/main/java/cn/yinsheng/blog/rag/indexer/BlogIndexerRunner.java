package cn.yinsheng.blog.rag.indexer;

import cn.yinsheng.blog.rag.config.RagProperties;
import cn.yinsheng.blog.rag.compute.AiComputeClient;
import cn.yinsheng.blog.rag.model.ChunkRecord;
import cn.yinsheng.blog.rag.qdrant.QdrantClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile("indexer")
public class BlogIndexerRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(BlogIndexerRunner.class);

  private final RagProperties properties;
  private final MarkdownPostReader postReader;
  private final MarkdownChunker chunker;
  private final IndexStatusRepository statusRepository;
  private final AiComputeClient aiComputeClient;
  private final QdrantClient qdrantClient;
  private final RestClient restClient;

  public BlogIndexerRunner(
      RagProperties properties,
      MarkdownPostReader postReader,
      MarkdownChunker chunker,
      IndexStatusRepository statusRepository,
      AiComputeClient aiComputeClient,
      QdrantClient qdrantClient,
      RestClient restClient
  ) {
    this.properties = properties;
    this.postReader = postReader;
    this.chunker = chunker;
    this.statusRepository = statusRepository;
    this.aiComputeClient = aiComputeClient;
    this.qdrantClient = qdrantClient;
    this.restClient = restClient;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (isChatBusy()) {
      log.info("Chat API is busy, skip this indexer run to keep GPU available for users");
      return;
    }

    boolean collectionRebuilt = qdrantClient.ensureCollection(properties.embeddingDimension());
    List<BlogPost> posts = postReader.readPosts(Path.of(properties.contentDir()));
    Map<String, BlogPost> currentPosts = new LinkedHashMap<>();
    posts.forEach(post -> currentPosts.put(post.slug(), post));

    Map<String, IndexStatusRepository.StatusRow> statusRows = statusRepository.load(properties.indexDbPath());
    for (String indexedSlug : new ArrayList<>(statusRows.keySet())) {
      if (!currentPosts.containsKey(indexedSlug)) {
        log.info("Deleting removed post from Qdrant: {}", indexedSlug);
        qdrantClient.deleteBySlug(indexedSlug);
        statusRepository.delete(properties.indexDbPath(), indexedSlug);
      }
    }

    for (BlogPost post : posts) {
      IndexStatusRepository.StatusRow row = statusRows.get(post.slug());
      if (!collectionRebuilt && row != null && row.contentHash().equals(post.contentHash())) {
        log.info("Skip unchanged post: {}", post.slug());
        continue;
      }
      if (isChatBusy()) {
        log.info("Chat became busy, stop indexing before mutating post {}", post.slug());
        return;
      }
      indexPost(post);
    }
  }

  private void indexPost(BlogPost post) {
    List<ChunkRecord> chunks = chunker.chunk(post);
    List<List<Double>> vectors = new ArrayList<>();
    for (ChunkRecord chunk : chunks) {
      if (isChatBusy()) {
        log.info("Chat became busy, abort embedding post {}", post.slug());
        return;
      }
      vectors.add(aiComputeClient.embed(chunk.retrievalText()));
    }
    if (!vectors.isEmpty()) {
      qdrantClient.ensureCollection(vectors.get(0).size());
    }
    log.info("Reindex post {} with {} chunks", post.slug(), chunks.size());
    qdrantClient.deleteBySlug(post.slug());

    int batchSize = Math.max(1, properties.indexerBatchSize());
    for (int start = 0; start < chunks.size(); start += batchSize) {
      int end = Math.min(chunks.size(), start + batchSize);
      qdrantClient.upsert(chunks.subList(start, end), vectors.subList(start, end));
    }
    statusRepository.upsert(properties.indexDbPath(), post, chunks.size());
  }

  private boolean isChatBusy() {
    try {
      JsonNode status = restClient.get()
          .uri(properties.indexerChatStatusUrl())
          .retrieve()
          .body(JsonNode.class);
      return status.path("chatBusy").asBoolean(false);
    } catch (Exception ex) {
      log.debug("Cannot read chat status, continue indexing: {}", ex.getMessage());
      return false;
    }
  }
}
