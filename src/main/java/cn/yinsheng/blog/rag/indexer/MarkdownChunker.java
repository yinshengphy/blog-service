package cn.yinsheng.blog.rag.indexer;

import cn.yinsheng.blog.rag.model.ChunkRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MarkdownChunker {
  private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{2,4})\\s+(.+?)\\s*$");
  private static final int TARGET_CHARS = 800;
  private static final int OVERLAP_CHARS = 120;

  public List<ChunkRecord> chunk(BlogPost post) {
    List<Section> sections = sections(post);
    List<ChunkRecord> chunks = new ArrayList<>();
    int index = 1;
    for (Section section : sections) {
      for (String content : splitSection(section.content())) {
        String chunkId = "%s-%04d".formatted(post.slug().replace("/", "-"), index++);
        String sectionTitle = section.title().isBlank() ? post.title() : section.title();
        String headingPath = post.title() + " > " + String.join(" > ", section.headingPath());
        String url = "/" + post.slug() + "/#" + slugify(sectionTitle);
        String normalizedContent = content.trim();
        String chunkHash = sha256(headingPath + "\n" + normalizedContent);
        chunks.add(new ChunkRecord(
            chunkId,
            post.slug(),
            post.title(),
            sectionTitle,
            headingPath,
            url,
            normalizedContent,
            post.contentHash(),
            chunkHash,
            post.tags(),
            post.date(),
            post.updatedAt()
        ));
      }
    }
    return chunks;
  }

  private List<Section> sections(BlogPost post) {
    List<Section> sections = new ArrayList<>();
    List<String> path = new ArrayList<>();
    String currentTitle = post.title();
    StringBuilder current = new StringBuilder();

    for (String line : post.body().split("\\R")) {
      Matcher matcher = HEADING_PATTERN.matcher(line);
      if (matcher.matches()) {
        flushSection(sections, currentTitle, path, current);
        int level = matcher.group(1).length();
        currentTitle = cleanHeading(matcher.group(2));
        while (path.size() >= level - 1) {
          path.remove(path.size() - 1);
        }
        path.add(currentTitle);
      } else {
        current.append(line).append('\n');
      }
    }
    flushSection(sections, currentTitle, path, current);
    return sections.stream()
        .filter(section -> !section.content().isBlank())
        .toList();
  }

  private void flushSection(List<Section> sections, String title, List<String> path, StringBuilder current) {
    if (current.length() > 0) {
      sections.add(new Section(title, List.copyOf(path.isEmpty() ? List.of(title) : path), current.toString()));
      current.setLength(0);
    }
  }

  private List<String> splitSection(String content) {
    String compact = content.replaceAll("\\n{3,}", "\n\n").trim();
    if (compact.length() <= TARGET_CHARS) {
      return List.of(compact);
    }
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < compact.length()) {
      int end = Math.min(compact.length(), start + TARGET_CHARS);
      chunks.add(compact.substring(start, end));
      if (end == compact.length()) {
        break;
      }
      start = Math.max(0, end - OVERLAP_CHARS);
    }
    return chunks;
  }

  public String slugify(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase()
        .trim()
        .replaceAll("[`*_~\\[\\](){}<>\"'，。！？；：、]", "")
        .replaceAll("\\s+", "-")
        .replaceAll("[^\\p{IsHan}\\p{Alnum}-]", "")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "");
    return normalized.isBlank() ? "section" : normalized;
  }

  private String cleanHeading(String heading) {
    return heading.replaceAll("\\s*#+\\s*$", "").trim();
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to calculate SHA-256", ex);
    }
  }

  private record Section(String title, List<String> headingPath, String content) {
  }
}
