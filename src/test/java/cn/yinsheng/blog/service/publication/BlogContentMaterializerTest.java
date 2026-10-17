package cn.yinsheng.blog.service.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

  @Test
  void appendsAndIdempotentlyReplacesOnlyTheMarkedDailyNewsSection() throws Exception {
    Path posts = repository.resolve("src/content/posts");
    Files.createDirectories(posts);
    Path post = posts.resolve("daily-news-20261017.md");
    Files.writeString(post, "---\ntitle: \"日报\"\ndate: \"2026-10-17\"\n---\n\n原有正文\n");
    String first = "## 境外报道与争议议题\n\n第一版内容";
    materializer.upsertDailyNewsSection(repository, sectionRequest(first, hash(first)));
    String second = "## 境外报道与争议议题\n\n第二版内容";
    materializer.upsertDailyNewsSection(repository, sectionRequest(second, hash(second)));

    String markdown = Files.readString(post);
    assertThat(markdown).startsWith("---\ntitle: \"日报\"").contains("原有正文")
        .contains(second).doesNotContain("第一版内容");
    assertThat(markdown.split(BlogContentMaterializer.REVIEW_SECTION_START, -1)).hasSize(2);
    assertThat(markdown.split(BlogContentMaterializer.REVIEW_SECTION_END, -1)).hasSize(2);
  }

  @Test
  void refusesMissingDailyNewsPost() {
    String body = "## 境外报道与争议议题\n\n内容";
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> materializer.upsertDailyNewsSection(repository, sectionRequest(body, hash(body))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private DailyNewsSectionPublicationRequest sectionRequest(String body, String hash) {
    return new DailyNewsSectionPublicationRequest(
        "daily-news-section:2026-10-17:v1", "daily-news-20261017", "2026-10-17", hash, body);
  }

  private String hash(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
