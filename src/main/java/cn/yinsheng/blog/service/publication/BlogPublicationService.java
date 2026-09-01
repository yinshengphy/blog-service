package cn.yinsheng.blog.service.publication;

import cn.yinsheng.blog.service.config.PublicationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
