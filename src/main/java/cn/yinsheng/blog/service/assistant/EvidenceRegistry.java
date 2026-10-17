package cn.yinsheng.blog.service.assistant;

import cn.yinsheng.blog.service.model.Citation;
import cn.yinsheng.blog.service.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 为一次请求中的所有工具结果分配统一证据编号。 */
final class EvidenceRegistry {
  private static final Pattern REFERENCE = Pattern.compile("\\[Source (\\d+)]|\\[(\\d+)]");
  private final List<Citation> citations = new ArrayList<>();
  private final Map<String, Integer> ids = new LinkedHashMap<>();

  ToolResult bind(ToolResult result) {
    Map<Integer, Integer> mapping = new LinkedHashMap<>();
    for (int i = 0; i < result.citations().size(); i++) {
      Citation citation = result.citations().get(i);
      String key = citation.url() + "|" + citation.section() + "|" + citation.snippet();
      int id = ids.computeIfAbsent(key, ignored -> { citations.add(citation); return citations.size(); });
      mapping.put(i + 1, id);
    }
    String content = REFERENCE.matcher(result.content()).replaceAll(match -> {
      String local = match.group(1) == null ? match.group(2) : match.group(1);
      Integer global = mapping.get(Integer.parseInt(local));
      return global == null ? match.group() : "[" + (match.group(1) == null ? "" : "Source ") + global + "]";
    });
    if (!mapping.isEmpty()) {
      StringBuilder sources = new StringBuilder("\n\n本次工具结果可引用的证据（编号在整个对话请求中唯一）：\n");
      mapping.values().stream().distinct().forEach(id -> {
        Citation c = citations.get(id - 1);
        sources.append("[Source ").append(id).append("] ").append(c.title())
            .append(" / ").append(c.section()).append("\nURL: ").append(c.url())
            .append("\n原文片段: ").append(c.snippet()).append('\n');
      });
      content += sources;
    }
    return new ToolResult(result.toolCallId(), result.name(), content, result.success(),
        result.citations(), result.relatedPosts(), result.metadata());
  }

  List<Citation> citations() { return List.copyOf(citations); }
}
