package cn.yinsheng.blog.service.indexer;

import cn.yinsheng.blog.service.chat.ChatLimiter;
import cn.yinsheng.blog.service.compute.AiComputeClient;
import cn.yinsheng.blog.service.config.RagProperties;
import cn.yinsheng.blog.service.model.ChunkRecord;
import cn.yinsheng.blog.service.qdrant.QdrantClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BlogIndexService {
  private static final Logger log = LoggerFactory.getLogger(BlogIndexService.class);

  private final RagProperties properties;
  private final MarkdownPostReader postReader;
  private final MarkdownChunker chunker;
  private final IndexStatusRepository statusRepository;
  private final AiComputeClient aiComputeClient;
  private final QdrantClient qdrantClient;
  private final ChatLimiter chatLimiter;
  private final ReentrantLock indexLock = new ReentrantLock();

  public BlogIndexService(
      RagProperties properties,
      MarkdownPostReader postReader,
      MarkdownChunker chunker,
      IndexStatusRepository statusRepository,
      AiComputeClient aiComputeClient,
      QdrantClient qdrantClient,
      ChatLimiter chatLimiter
  ) {
    this.properties = properties;
    this.postReader = postReader;
    this.chunker = chunker;
    this.statusRepository = statusRepository;
    this.aiComputeClient = aiComputeClient;
    this.qdrantClient = qdrantClient;
    this.chatLimiter = chatLimiter;
  }

  public IndexResult synchronize() {
    return execute(IndexMode.INCREMENTAL);
  }

  public IndexResult rebuild() {
    return execute(IndexMode.REBUILD);
  }

  private IndexResult execute(IndexMode mode) {
    if (!indexLock.tryLock()) {
      return result(mode, "BUSY", 0, 0, 0, 0, List.of(), "已有索引任务正在执行");
    }
    try {
      return doIndex(mode);
    } catch (Exception ex) {
      log.error("博客索引失败，模式={}", mode, ex);
      return result(mode, "FAILED", 0, 0, 0, 0, List.of(), "索引失败：" + ex.getMessage());
    } finally {
      indexLock.unlock();
    }
  }

  private IndexResult doIndex(IndexMode mode) throws Exception {
    List<BlogPost> posts = postReader.readPosts(Path.of(properties.contentDir()));
    Map<String, BlogPost> currentPosts = new LinkedHashMap<>();
    posts.forEach(post -> currentPosts.put(post.slug(), post));

    if (mode == IndexMode.REBUILD) {
      return rebuildSafely(posts);
    } else {
      qdrantClient.ensureCollection(properties.embeddingDimension());
    }

    Map<String, IndexStatusRepository.StatusRow> statusRows = statusRepository.load(properties.indexDbPath());
    int deleted = 0;
    for (String indexedSlug : new ArrayList<>(statusRows.keySet())) {
      if (!currentPosts.containsKey(indexedSlug)) {
        qdrantClient.deleteBySlug(indexedSlug);
        statusRepository.delete(properties.indexDbPath(), indexedSlug);
        deleted++;
      }
    }

    int skipped = 0;
    List<String> indexedSlugs = new ArrayList<>();
    for (BlogPost post : posts) {
      IndexStatusRepository.StatusRow row = statusRows.get(post.slug());
      if (mode == IndexMode.INCREMENTAL && row != null && row.contentHash().equals(post.contentHash())) {
        skipped++;
        continue;
      }
      indexPost(post);
      indexedSlugs.add(post.slug());
    }
    return result(
        mode,
        "COMPLETED",
        posts.size(),
        indexedSlugs.size(),
        skipped,
        deleted,
        indexedSlugs,
        mode == IndexMode.REBUILD ? "全部博客索引重建完成" : "博客索引同步完成"
    );
  }

  private void indexPost(BlogPost post) {
    List<ChunkRecord> chunks = chunker.chunk(post);
    List<List<Double>> vectors = new ArrayList<>();
    for (ChunkRecord chunk : chunks) {
      vectors.add(aiComputeClient.embed(chunk.retrievalText()));
    }
    if (!vectors.isEmpty()) {
      qdrantClient.ensureCollection(vectors.get(0).size());
    }
    qdrantClient.replacePost(post.slug(), chunks, vectors);
    statusRepository.upsert(properties.indexDbPath(), post, chunks.size());
    log.info("博客已建立索引，slug={}，分块数={}", post.slug(), chunks.size());
  }

  private IndexResult rebuildSafely(List<BlogPost> posts) {
    if (posts.isEmpty()) throw new IllegalStateException("公开文章目录为空，保留现有索引");
    String staging = qdrantClient.beginRebuild(properties.embeddingDimension());
    Map<String, Integer> chunkCounts = new LinkedHashMap<>();
    int total = 0;
    for (BlogPost post : posts) {
      List<ChunkRecord> chunks = chunker.chunk(post);
      if (chunks.isEmpty()) throw new IllegalStateException("文章正文为空，停止重建并保留旧索引");
      List<List<Double>> vectors = chunks.stream().map(c -> aiComputeClient.embed(c.retrievalText())).toList();
      if (vectors.stream().anyMatch(v -> v.size() != properties.embeddingDimension())) {
        throw new IllegalStateException("向量维度校验失败，保留旧索引");
      }
      qdrantClient.upsertInto(staging, chunks, vectors);
      chunkCounts.put(post.slug(), chunks.size()); total += chunks.size();
    }
    qdrantClient.activateRebuild(staging, total);
    statusRepository.clear(properties.indexDbPath());
    for (BlogPost post : posts) statusRepository.upsert(properties.indexDbPath(), post, chunkCounts.get(post.slug()));
    return result(IndexMode.REBUILD, "COMPLETED", posts.size(), posts.size(), 0, 0,
        posts.stream().map(BlogPost::slug).toList(), "新索引已完成校验并原子切换，旧集合保留用于回滚");
  }

  private IndexResult result(
      IndexMode mode,
      String status,
      int total,
      int indexed,
      int skipped,
      int deleted,
      List<String> slugs,
      String message
  ) {
    return new IndexResult(mode, status, total, indexed, skipped, deleted, List.copyOf(slugs), message);
  }
}

