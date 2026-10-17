package cn.yinsheng.blog.service.publication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BlogContentMaterializer {
  private static final long BREAKING_RETENTION_SECONDS = 72 * 60 * 60;
  static final String REVIEW_SECTION_START = "<!-- news-review:start -->";
  static final String REVIEW_SECTION_END = "<!-- news-review:end -->";
  private final ObjectMapper objectMapper;

  public BlogContentMaterializer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void writePost(Path repository, PostPublicationRequest request) throws IOException {
    Path root = repository.resolve("src/content/posts").normalize();
    Path target = root.resolve(request.slug() + ".md").normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("非法文章路径");
    }
    Files.createDirectories(root);
    String markdown = "---\n"
        + "title: \"" + yaml(request.title()) + "\"\n"
        + "date: \"" + request.date() + "\"\n"
        + "description: \"" + yaml(request.description()) + "\"\n"
        + "tags: " + yamlList(request.tags()) + "\n"
        + "categories: " + yamlList(request.categories()) + "\n"
        + "---\n\n"
        + request.body().strip() + "\n";
    Files.writeString(target, markdown, StandardCharsets.UTF_8);
  }

  public void upsertBreakingEvent(Path repository, BreakingEventPublicationRequest request) throws IOException {
    Path target = repository.resolve("src/data/breaking-news.json").normalize();
    Path expectedRoot = repository.resolve("src/data").normalize();
    if (!target.startsWith(expectedRoot)) {
      throw new IllegalArgumentException("非法突发新闻路径");
    }
    Files.createDirectories(target.getParent());
    ArrayNode events = Files.exists(target)
        ? (ArrayNode) objectMapper.readTree(Files.readString(target, StandardCharsets.UTF_8))
        : objectMapper.createArrayNode();
    ArrayNode retained = objectMapper.createArrayNode();
    Instant cutoff = Instant.now().minusSeconds(BREAKING_RETENTION_SECONDS);
    for (JsonNode event : events) {
      if (request.fingerprint().equals(event.path("fingerprint").asText())) {
        continue;
      }
      if (eventTimestamp(event).map(timestamp -> !timestamp.isBefore(cutoff)).orElse(false)) {
        retained.add(event);
      }
    }
    ObjectNode added = retained.addObject();
    added.put("fingerprint", request.fingerprint());
    added.put("title", request.title().strip());
    added.put("summary", request.summary().strip());
    added.put("source", request.source().strip());
    added.put("discoveredAt", request.discoveredAt());
    added.put("severity", request.severity().strip());
    if (request.link() != null && !request.link().isBlank()) {
      added.put("link", request.link().strip());
    }
    List<JsonNode> sorted = new ArrayList<>();
    retained.forEach(sorted::add);
    sorted.sort(Comparator.comparing(this::sortTimestamp).reversed());
    ArrayNode output = objectMapper.createArrayNode();
    sorted.forEach(output::add);
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), output);
    Files.writeString(target, System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
  }

  public void upsertDailyNewsSection(Path repository, DailyNewsSectionPublicationRequest request) throws IOException {
    Path root = repository.resolve("src/content/posts").normalize();
    Path target = root.resolve(request.slug() + ".md").normalize();
    if (!target.startsWith(root) || Files.isSymbolicLink(target) || !Files.isRegularFile(target)) {
      throw new IllegalArgumentException("目标日报不存在或路径非法");
    }
    String markdown = Files.readString(target, StandardCharsets.UTF_8);
    if (!markdown.startsWith("---\n") || markdown.indexOf("\n---\n", 4) < 0) {
      throw new IllegalArgumentException("目标日报 front matter 格式不合法");
    }
    String block = REVIEW_SECTION_START + "\n" + request.body().strip() + "\n" + REVIEW_SECTION_END;
    int start = markdown.indexOf(REVIEW_SECTION_START);
    int end = markdown.indexOf(REVIEW_SECTION_END);
    int frontMatterEnd = markdown.indexOf("\n---\n", 4) + 5;
    if ((start >= 0 && start < frontMatterEnd) || (end >= 0 && end < frontMatterEnd)) {
      throw new IllegalArgumentException("争议议题标记不得位于 front matter");
    }
    String updated;
    if (start >= 0 || end >= 0) {
      if (start < 0 || end < start || markdown.indexOf(REVIEW_SECTION_START, start + 1) >= 0
          || markdown.indexOf(REVIEW_SECTION_END, end + 1) >= 0) {
        throw new IllegalArgumentException("目标日报的争议议题标记不完整");
      }
      updated = markdown.substring(0, start) + block + markdown.substring(end + REVIEW_SECTION_END.length());
    } else {
      updated = markdown.stripTrailing() + "\n\n" + block + "\n";
    }
    Files.writeString(target, updated, StandardCharsets.UTF_8);
  }

  private Instant sortTimestamp(JsonNode event) {
    return eventTimestamp(event).orElse(Instant.EPOCH);
  }

  private java.util.Optional<Instant> eventTimestamp(JsonNode event) {
    try {
      return java.util.Optional.of(OffsetDateTime.parse(event.path("discoveredAt").asText()).toInstant());
    } catch (DateTimeParseException ignored) {
      return java.util.Optional.empty();
    }
  }

  private String yamlList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "[]";
    }
    return values.stream().map(value -> "\"" + yaml(value) + "\"").collect(java.util.stream.Collectors.joining(", ", "[", "]"));
  }

  private String yaml(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ").strip();
  }
}
