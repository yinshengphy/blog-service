package cn.yinsheng.blog.rag.tool.impl;

import cn.yinsheng.blog.rag.config.WebSearchProperties;
import cn.yinsheng.blog.rag.model.Citation;
import cn.yinsheng.blog.rag.tool.ToolCall;
import cn.yinsheng.blog.rag.tool.ToolDefinition;
import cn.yinsheng.blog.rag.tool.ToolExecutionContext;
import cn.yinsheng.blog.rag.tool.ToolRegistry;
import cn.yinsheng.blog.rag.tool.ToolResult;
import cn.yinsheng.blog.rag.web.WebPageReader;
import cn.yinsheng.blog.rag.web.WebSearchProvider;
import cn.yinsheng.blog.rag.web.WebSearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WebResearchTool implements ToolRegistry.ToolHandler {
  private static final int MAX_TOOL_CONTENT_CHARS = 7000;
  private final WebSearchProvider searchProvider;
  private final WebPageReader pageReader;
  private final WebSearchProperties properties;

  public WebResearchTool(WebSearchProvider searchProvider, WebPageReader pageReader, WebSearchProperties properties) {
    this.searchProvider = searchProvider;
    this.pageReader = pageReader;
    this.properties = properties;
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition("web_research", "Search the public web for current or external information, read several result pages, compare sources, and continue with later result pages when requested.", Map.of(
        "type", "object",
        "properties", Map.of(
            "query", Map.of("type", "string"),
            "engine", Map.of("type", "string", "description", "auto, google, baidu, bing, or a SearXNG engine name"),
            "category", Map.of("type", "string", "enum", List.of("general", "news", "it", "science")),
            "page", Map.of("type", "integer", "minimum", 1)
        ),
        "required", List.of("query")
    ));
  }

  @Override
  public ToolResult execute(ToolCall call, ToolExecutionContext context) {
    String query = String.valueOf(call.arguments().getOrDefault("query", "")).trim();
    String engine = String.valueOf(call.arguments().getOrDefault("engine", "auto")).trim();
    String category = String.valueOf(call.arguments().getOrDefault("category", "general")).trim();
    int page = integerArg(call.arguments().get("page"), 1);
    if (query.isBlank()) return ToolResult.failure(call, "A search query is required.");
    List<WebSearchResult> results = searchProvider.search(query, engine, category, page);
    String effectiveCategory = category;
    String effectiveEngine = engine;
    if (results.isEmpty() && !"general".equalsIgnoreCase(category)) {
      results = searchProvider.search(query, engine, "general", page);
      effectiveCategory = "general";
    }
    if (results.isEmpty() && !"auto".equalsIgnoreCase(engine)) {
      results = searchProvider.search(query, "auto", "general", page);
      effectiveEngine = "auto";
      effectiveCategory = "general";
    }
    if (results.isEmpty()) return ToolResult.failure(call, "The search provider returned no results.");

    StringBuilder content = new StringBuilder("Search query: ").append(query).append("\n");
    if (!effectiveEngine.equalsIgnoreCase(engine)) {
      content.append("Requested engine ").append(engine)
          .append(" returned no results, so the search continued with the automatic engine set.\n");
    }
    List<Citation> citations = new ArrayList<>();
    int fetched = 0;
    for (WebSearchResult result : results) {
      if (citations.size() >= properties.getResultLimit()) break;
      String body = "";
      if (fetched < properties.getFetchLimit()) {
        body = pageReader.read(result.url());
        fetched++;
      }
      String evidence = body.isBlank() ? result.snippet() : body;
      int remaining = MAX_TOOL_CONTENT_CHARS - content.length();
      if (remaining < 300) break;
      int evidenceLimit = Math.min(2200, remaining - 200);
      if (evidence.length() > evidenceLimit) evidence = evidence.substring(0, evidenceLimit);
      int index = citations.size() + 1;
      content.append("\n[Source ").append(index).append("]\n")
          .append("Title: ").append(result.title()).append('\n')
          .append("URL: ").append(result.url()).append('\n')
          .append("Engine: ").append(result.engine()).append('\n')
          .append("Published: ").append(result.publishedDate()).append('\n')
          .append("Evidence: ").append(evidence).append('\n');
      String snippet = evidence.length() <= 180 ? evidence : evidence.substring(0, 180) + "...";
      citations.add(new Citation(result.title(), result.engine(), result.url(), snippet));
    }
    return ToolResult.success(call, content.toString(), citations, List.of(), Map.of(
        "query", query,
        "engine", engine,
        "effectiveEngine", effectiveEngine,
        "category", effectiveCategory,
        "page", page,
        "resultCount", citations.size()
    ));
  }

  private int integerArg(Object value, int fallback) {
    if (value instanceof Number number) return Math.max(1, number.intValue());
    try { return Math.max(1, Integer.parseInt(String.valueOf(value))); } catch (Exception ex) { return fallback; }
  }
}
