package cn.yinsheng.blog.rag.tool.impl;

import cn.yinsheng.blog.rag.model.Citation;
import cn.yinsheng.blog.rag.model.PageContext;
import cn.yinsheng.blog.rag.model.RelatedPost;
import cn.yinsheng.blog.rag.model.RetrievedChunk;
import cn.yinsheng.blog.rag.rag.BlogCatalogService;
import cn.yinsheng.blog.rag.rag.BlogRetriever;
import cn.yinsheng.blog.rag.rag.CitationBuilder;
import cn.yinsheng.blog.rag.rag.RelatedPostBuilder;
import cn.yinsheng.blog.rag.tool.ToolCall;
import cn.yinsheng.blog.rag.tool.ToolDefinition;
import cn.yinsheng.blog.rag.tool.ToolExecutionContext;
import cn.yinsheng.blog.rag.tool.ToolRegistry;
import cn.yinsheng.blog.rag.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BlogQaTool implements ToolRegistry.ToolHandler {
  private final BlogRetriever retriever;
  private final BlogCatalogService catalog;
  private final CitationBuilder citationBuilder;
  private final RelatedPostBuilder relatedPostBuilder;

  public BlogQaTool(BlogRetriever retriever, BlogCatalogService catalog, CitationBuilder citationBuilder, RelatedPostBuilder relatedPostBuilder) {
    this.retriever = retriever;
    this.catalog = catalog;
    this.citationBuilder = citationBuilder;
    this.relatedPostBuilder = relatedPostBuilder;
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition("blog_qa", "基于本站索引证据进行博客问答、章节定位、站内搜索和文章推荐", Map.of(
        "type", "object",
        "properties", Map.of(
            "query", Map.of("type", "string"),
            "task", Map.of("type", "string", "enum", List.of("ANSWER", "SEARCH", "LOCATE", "RECOMMEND")),
            "scope", Map.of("type", "string", "enum", List.of("CURRENT_POST", "SPECIFIED_POST", "ALL_POSTS")),
            "target", Map.of("type", "string")
        ),
        "required", List.of("query", "task", "scope")
    ));
  }

  @Override
  public ToolResult execute(ToolCall call, ToolExecutionContext context) {
    String query = stringArg(call, "query");
    String task = normalizedTask(stringArg(call, "task"));
    String scope = stringArg(call, "scope");
    String slug = resolveSlug(scope, stringArg(call, "target"), context.pageContext());
    if ("__missing_current__".equals(slug)) {
      return ToolResult.failure(call, "No current blog post is available. Ask the user to name an article.");
    }
    if (slug != null && slug.startsWith("__candidates__")) {
      return ToolResult.failure(call, "The article name is ambiguous. Candidates: " + slug.substring("__candidates__".length()));
    }
    if ("__not_found__".equals(slug)) {
      return ToolResult.failure(call, "The requested blog post was not found.");
    }
    List<RetrievedChunk> chunks = retriever.retrieve(query, slug);
    if (chunks.isEmpty() || chunks.get(0).score() < 0.30) {
      return ToolResult.failure(call, "No sufficiently relevant blog content was found.");
    }
    List<Citation> citations = citationBuilder.build(chunks, "");
    List<RelatedPost> related = relatedPostBuilder.build(chunks);
    StringBuilder content = new StringBuilder(taskInstruction(task));
    for (int i = 0; i < chunks.size(); i++) {
      RetrievedChunk chunk = chunks.get(i);
      content.append("[Source ").append(i + 1).append("]\n")
          .append("Title: ").append(chunk.title()).append('\n')
          .append("Section: ").append(chunk.section()).append('\n')
          .append("URL: ").append(chunk.url()).append('\n')
          .append("Content: ").append(chunk.content()).append("\n\n");
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("ragTopScore", chunks.get(0).score());
    metadata.put("task", task);
    metadata.put("scope", scope);
    if (slug != null) metadata.put("slug", slug);
    return ToolResult.success(call, content.toString(), citations, related, metadata);
  }

  private String normalizedTask(String value) {
    return switch (value.toUpperCase(java.util.Locale.ROOT)) {
      case "SEARCH", "LOCATE", "RECOMMEND" -> value.toUpperCase(java.util.Locale.ROOT);
      default -> "ANSWER";
    };
  }

  private String taskInstruction(String task) {
    return switch (task) {
      case "LOCATE" -> "Task: LOCATE\nFirst state the exact blog title and section. Use the Source URL as a clickable citation. Quote only the minimum text needed.\n\n";
      case "SEARCH" -> "Task: SEARCH\nFirst answer whether the site contains this topic, then list only supported matching posts and their relevant sections.\n\n";
      case "RECOMMEND" -> "Task: RECOMMEND\nRecommend unique posts only. Explain each recommendation from its evidence and do not treat multiple chunks as multiple posts.\n\n";
      default -> "Task: ANSWER\nAnswer the question directly from evidence. If evidence does not support a detail, say so instead of adding it.\n\n";
    };
  }

  private String resolveSlug(String scope, String target, PageContext pageContext) {
    if ("ALL_POSTS".equalsIgnoreCase(scope)) return null;
    if ("CURRENT_POST".equalsIgnoreCase(scope)) {
      return pageContext != null && pageContext.isBlogPost() ? pageContext.slug() : "__missing_current__";
    }
    BlogCatalogService.Resolution resolution = catalog.resolve(target);
    if (resolution.found()) return resolution.post().slug();
    if (resolution.ambiguous()) {
      return "__candidates__" + resolution.candidates().stream().map(item -> item.title() + " (" + item.slug() + ")").toList();
    }
    return "__not_found__";
  }

  private String stringArg(ToolCall call, String name) {
    Object value = call.arguments().get(name);
    return value == null ? "" : String.valueOf(value).trim();
  }
}
