package cn.yinsheng.blog.service.publication;

import cn.yinsheng.blog.service.config.PublicationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class BlogPublicationService {
  private final PublicationStore store;
  private final PublicationProperties properties;
  private final ObjectMapper objectMapper;

  public BlogPublicationService(
      PublicationStore store,
      PublicationProperties properties,
      ObjectMapper objectMapper
  ) {
    this.store = store;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public PublicationReceipt publishPost(PostPublicationRequest request) {
    requireEnabled();
    LocalDate.parse(request.date());
    if (request.body().strip().startsWith("---")) {
      throw new IllegalArgumentException("文章正文不能自行包含 front matter");
    }
    return enqueue(PublicationKind.POST, request.idempotencyKey(), request);
  }

  public PublicationReceipt publishBreakingEvent(BreakingEventPublicationRequest request) {
    requireEnabled();
    OffsetDateTime.parse(request.discoveredAt());
    return enqueue(PublicationKind.BREAKING_EVENT, request.idempotencyKey(), request);
  }

  public PublicationReceipt publishDailyNewsSection(DailyNewsSectionPublicationRequest request) {
    requireEnabled();
    LocalDate date = LocalDate.parse(request.date());
    String expectedSlug = "daily-news-" + date.toString().replace("-", "");
    if (!expectedSlug.equals(request.slug())) {
      throw new IllegalArgumentException("日报 slug 与日期不匹配");
    }
    String body = request.body().strip();
    if (body.startsWith("---") || !body.startsWith("## 境外报道与争议议题")
        || body.contains("<!-- news-review:")) {
      throw new IllegalArgumentException("章节正文格式不合法");
    }
    if (!sha256(body).equals(request.sectionHash())) {
      throw new IllegalArgumentException("章节正文 hash 不匹配");
    }
    return enqueue(PublicationKind.DAILY_NEWS_SECTION, request.idempotencyKey(), request);
  }

  private String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("运行环境缺少 SHA-256", ex);
    }
  }

  public PublicationStatus status(String idempotencyKey) {
    requireEnabled();
    return store.status(idempotencyKey)
        .orElseThrow(() -> new PublicationNotFoundException(idempotencyKey));
  }

  private PublicationReceipt enqueue(PublicationKind kind, String idempotencyKey, Object request) {
    try {
      return store.enqueue(kind, idempotencyKey, objectMapper.writeValueAsString(request));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("发布内容无法序列化", ex);
    }
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new PublicationUnavailableException("博客发布功能尚未启用");
    }
  }

  public static class PublicationUnavailableException extends RuntimeException {
    PublicationUnavailableException(String message) {
      super(message);
    }
  }

  public static class PublicationNotFoundException extends RuntimeException {
    PublicationNotFoundException(String idempotencyKey) {
      super("未找到发布任务：" + idempotencyKey);
    }
  }
}
