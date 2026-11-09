package cn.yinsheng.blog.service.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
    String aiComputeBaseUrl,
    String aiComputeToken,
    String ollamaBaseUrl,
    String chatModel,
    String fallbackChatModel,
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
    String indexApiToken,
    String siteBasePath,
    String rateLimitDbPath,
    int rateLimitPerMinute,
    int rateLimitPerDay
) {
  public Duration requestTimeout() {
    return Duration.ofSeconds(Math.max(10, requestTimeoutSeconds));
  }

  public String effectiveOllamaBaseUrl() {
    return (ollamaBaseUrl != null && !ollamaBaseUrl.isBlank()) ? ollamaBaseUrl : aiComputeBaseUrl;
  }

  public String effectiveFallbackChatModel() {
    return (fallbackChatModel != null && !fallbackChatModel.isBlank()) ? fallbackChatModel : chatModel;
  }
}
