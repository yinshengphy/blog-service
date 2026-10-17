package cn.yinsheng.blog.service.api;

import cn.yinsheng.blog.service.config.PublicationProperties;
import cn.yinsheng.blog.service.publication.BlogPublicationService;
import cn.yinsheng.blog.service.publication.BlogPublicationService.PublicationNotFoundException;
import cn.yinsheng.blog.service.publication.BlogPublicationService.PublicationUnavailableException;
import cn.yinsheng.blog.service.publication.BreakingEventPublicationRequest;
import cn.yinsheng.blog.service.publication.DailyNewsSectionPublicationRequest;
import cn.yinsheng.blog.service.publication.PostPublicationRequest;
import cn.yinsheng.blog.service.publication.PublicationReceipt;
import cn.yinsheng.blog.service.publication.PublicationStatus;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "publication", name = "api-enabled", havingValue = "true", matchIfMissing = true)
public class InternalPublicationController {
  private final BlogPublicationService publicationService;
  private final PublicationProperties properties;

  public InternalPublicationController(BlogPublicationService publicationService, PublicationProperties properties) {
    this.publicationService = publicationService;
    this.properties = properties;
  }

  @PostMapping("/internal/publications/posts")
  public ResponseEntity<PublicationReceipt> publishPost(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody PostPublicationRequest request
  ) {
    requireAuthorized(authorization);
    PublicationReceipt receipt = publicationService.publishPost(request);
    return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(receipt);
  }

  @PostMapping("/internal/publications/breaking-events")
  public ResponseEntity<PublicationReceipt> publishBreakingEvent(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody BreakingEventPublicationRequest request
  ) {
    requireAuthorized(authorization);
    PublicationReceipt receipt = publicationService.publishBreakingEvent(request);
    return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(receipt);
  }

  @PostMapping("/internal/publications/daily-news-sections")
  public ResponseEntity<PublicationReceipt> publishDailyNewsSection(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody DailyNewsSectionPublicationRequest request
  ) {
    requireAuthorized(authorization);
    PublicationReceipt receipt = publicationService.publishDailyNewsSection(request);
    return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(receipt);
  }

  @GetMapping("/internal/publications/{idempotencyKey:.+}")
  public PublicationStatus status(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String idempotencyKey
  ) {
    requireAuthorized(authorization);
    return publicationService.status(idempotencyKey);
  }

  @ExceptionHandler(PublicationUnavailableException.class)
  ResponseEntity<String> unavailable(PublicationUnavailableException exception) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(exception.getMessage());
  }

  @ExceptionHandler(PublicationNotFoundException.class)
  ResponseEntity<String> notFound(PublicationNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.getMessage());
  }

  private void requireAuthorized(String authorization) {
    String expected = properties.apiToken();
    if (expected == null || expected.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) {
      throw new UnauthorizedPublicationException();
    }
    byte[] actual = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedBytes, actual)) {
      throw new UnauthorizedPublicationException();
    }
  }

  @ExceptionHandler(UnauthorizedPublicationException.class)
  ResponseEntity<Void> unauthorized(UnauthorizedPublicationException exception) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }

  private static class UnauthorizedPublicationException extends RuntimeException {
  }
}
