package cn.yinsheng.blog.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlogContentMaterializerTest {

  @TempDir
  Path repository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final BlogContentMaterializer materializer = new BlogContentMaterializer(objectMapper);

  @Test
  void writesCanonicalMarkdownPost() throws Exception {
    materializer.writePost(repository, new PostPublicationRequest(
        "daily-news:2026-09-01", "daily-news-20260901", "每日热点早报", "2026-09-01",
        "当天的重点新闻", List.of("每日早报"), List.of("热点新闻"), "正文内容"
    ));

    String markdown = Files.readString(repository.resolve("src/content/posts/daily-news-20260901.md"));
    assertThat(markdown).contains("title: \"每日热点早报\"").contains("categories: [\"每日早报\"]").contains("正文内容");
  }

  @Test
  void upsertsBreakingEventAndPrunesExpiredEntries() throws Exception {
    Path data = repository.resolve("src/data");
    Files.createDirectories(data);
    Files.writeString(data.resolve("breaking-news.json"), """
        [{"fingerprint":"expired","title":"旧消息","summary":"旧","source":"旧","discoveredAt":"2020-01-01T00:00:00+00:00","severity":"重大"}]
        """);
    String now = OffsetDateTime.now().toString();
    BreakingEventPublicationRequest request = new BreakingEventPublicationRequest(
        "breaking:one", "one", "新突发", "新的摘要", "测试来源", now, "重大", "https://example.com"
    );

    materializer.upsertBreakingEvent(repository, request);
    materializer.upsertBreakingEvent(repository, request);

    var events = objectMapper.readTree(Files.readString(data.resolve("breaking-news.json")));
    assertThat(events).hasSize(1);
    assertThat(events.get(0).path("fingerprint").asText()).isEqualTo("one");
    assertThat(events.get(0).path("title").asText()).isEqualTo("新突发");
  }
}
