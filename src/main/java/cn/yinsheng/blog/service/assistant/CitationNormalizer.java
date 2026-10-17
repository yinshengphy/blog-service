package cn.yinsheng.blog.service.assistant;

import cn.yinsheng.blog.service.model.Citation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CitationNormalizer {
  private static final Pattern MANUAL_LINK = Pattern.compile("(?m)^\\s*\\[\\d+]\\s+(?:https?://\\S+|/\\S+)\\s*$");
  private static final Pattern MARKER = Pattern.compile("\\[(\\d+)]");
  private static final Pattern NAMED_MARKER = Pattern.compile("\\[(?:Source|来源|源)\\s*(\\d+)]", Pattern.CASE_INSENSITIVE);

  public Result normalize(String answer, List<Citation> citations) {
    String canonical = NAMED_MARKER.matcher(answer == null ? "" : answer).replaceAll("[$1]");
    String cleaned = MANUAL_LINK.matcher(canonical).replaceAll("")
        .replaceAll("(?m)[ \\t]+$", "")
        .replaceAll("\\n{3,}", "\\n\\n")
        .trim();
    if (citations == null || citations.isEmpty()) {
      return new Result(MARKER.matcher(cleaned).replaceAll("").trim(), List.of());
    }

    Map<Integer, Integer> remapping = new LinkedHashMap<>();
    List<Citation> ordered = new ArrayList<>();
    Matcher matcher = MARKER.matcher(cleaned);
    StringBuffer rewritten = new StringBuffer();
    while (matcher.find()) {
      int oldIndex;
      try { oldIndex = Integer.parseInt(matcher.group(1)); }
      catch (NumberFormatException ex) { matcher.appendReplacement(rewritten, ""); continue; }
      if (oldIndex < 1 || oldIndex > citations.size()) {
        matcher.appendReplacement(rewritten, "");
        continue;
      }
      int newIndex = remapping.computeIfAbsent(oldIndex, ignored -> {
        ordered.add(citations.get(oldIndex - 1));
        return ordered.size();
      });
      matcher.appendReplacement(rewritten, Matcher.quoteReplacement("[" + newIndex + "]"));
    }
    matcher.appendTail(rewritten);

    String normalized = rewritten.toString().trim();
    return new Result(normalized, List.copyOf(ordered));
  }

  public record Result(String answer, List<Citation> citations) {
  }
}
