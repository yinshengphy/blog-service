package cn.yinsheng.blog.service.publication;

import cn.yinsheng.blog.service.config.PublicationProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "publication", name = {"enabled", "worker-enabled"}, havingValue = "true")
public class PublicationWorker {
  private static final Logger log = LoggerFactory.getLogger(PublicationWorker.class);
  private final PublicationStore store;
  private final GitBlogPublisher publisher;

  public PublicationWorker(PublicationStore store, GitBlogPublisher publisher, PublicationProperties properties) {
    this.store = store;
    this.publisher = publisher;
  }

  @Scheduled(fixedDelayString = "${publication.worker-poll-ms:10000}")
  public void publishOne() {
    Optional<PublicationStore.PublicationItem> candidate = store.claimNext();
    if (candidate.isEmpty()) {
      return;
    }
    PublicationStore.PublicationItem item = candidate.get();
    try {
      String commit = publisher.publish(item);
      store.markPublished(item.id(), commit);
      log.info("博客发布完成：id={}, kind={}, commit={}", item.id(), item.kind(), commit);
    } catch (Exception ex) {
      store.markRetry(item.id(), item.attempts(), ex.getMessage());
      log.warn("博客发布失败，将自动重试：id={}, kind={}, attempts={}", item.id(), item.kind(), item.attempts(), ex);
    }
  }
}
