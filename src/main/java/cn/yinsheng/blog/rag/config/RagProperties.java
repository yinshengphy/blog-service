package cn.yinsheng.blog.rag.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
    String aiComputeBaseUrl,
    String aiComputeToken,
    String chatModel,
    String embeddingModel,
    String qdrantUrl,
    String qdrantCollection,
    int embeddingDimension,
    int topK,
    int maxContextChars,
    int maxAnswerTokens,
    int chatConcurrency,
    long chatQueueTimeoutMs,
    int requestTimeoutSeconds,
    String contentDir,
    String indexDbPath,
    int indexerBatchSize,
    String indexerChatStatusUrl,
    String siteBasePath,
    String rateLimitDbPath,
    int rateLimitPerMinute,
    int rateLimitPerDay
) {
  public Duration requestTimeout() {
    return Duration.ofSeconds(Math.max(10, requestTimeoutSeconds));
  }
}
