package cn.yinsheng.blog.service.tool.impl;

import cn.yinsheng.blog.service.compute.AiComputeClient;
import cn.yinsheng.blog.service.model.Citation;
import cn.yinsheng.blog.service.model.PageContext;
import cn.yinsheng.blog.service.model.RelatedPost;
import cn.yinsheng.blog.service.model.RetrievedChunk;
import cn.yinsheng.blog.service.qdrant.QdrantClient;
import cn.yinsheng.blog.service.rag.BlogCatalogService;
import cn.yinsheng.blog.service.rag.CitationBuilder;
import cn.yinsheng.blog.service.rag.RelatedPostBuilder;
import cn.yinsheng.blog.service.tool.ToolCall;
import cn.yinsheng.blog.service.tool.ToolDefinition;
import cn.yinsheng.blog.service.tool.ToolExecutionContext;
import cn.yinsheng.blog.service.tool.ToolRegistry;
import cn.yinsheng.blog.service.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BlogSummaryTool implements ToolRegistry.ToolHandler {
  private static final int GROUP_CHARS = 5200;
  private final QdrantClient qdrantClient;
  private final BlogCatalogService catalog;
  private final AiComputeClient aiComputeClient;
  private final CitationBuilder citationBuilder;
  private final RelatedPostBuilder relatedPostBuilder;

  public BlogSummaryTool(QdrantClient qdrantClient, BlogCatalogService catalog, AiComputeClient aiComputeClient, CitationBuilder citationBuilder, RelatedPostBuilder relatedPostBuilder) {
    this.qdrantClient = qdrantClient;
    this.catalog = catalog;
    this.aiComputeClient = aiComputeClient;
    this.citationBuilder = citationBuilder;
    this.relatedPostBuilder = relatedPostBuilder;
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition("blog_summary", "读取当前或指定博客的完整有序正文并生成全文摘要", Map.of(
        "type", "object",
        "properties", Map.of(
            "target", Map.of("type", "string", "description", "Article title or slug. Leave empty only for the current page."),
            "focus", Map.of("type", "string", "description", "Optional summary focus requested by the user.")
        )
    ));
  }

  @Override
  public ToolResult execute(ToolCall call, ToolExecutionContext context) {
    String target = value(call, "target");
    String slug = resolveSlug(target, context.pageContext());
    if (slug == null) return ToolResult.failure(call, "No current article is available. Ask the user to name the article to summarize.");
    if (slug.startsWith("__candidates__")) return ToolResult.failure(call, "The article name is ambiguous. Candidates: " + slug.substring("__candidates__".length()));
    if ("__not_found__".equals(slug)) return ToolResult.failure(call, "The requested article was not found.");
    List<RetrievedChunk> chunks = qdrantClient.listBySlug(slug);
    if (chunks.isEmpty()) return ToolResult.failure(call, "The requested article has no indexed content.");

    List<String> groups = groups(chunks);
    List<String> partials = new ArrayList<>();
    String focus = value(call, "focus");
    for (String group : groups) {
      partials.add(aiComputeClient.chat(
          "Summarize blog source text faithfully. Preserve technical facts and section structure. Do not add outside facts. Answer in the source language.",
          "Summary focus: " + focus + "\n\nSource text:\n" + group
      ));
    }
    String summary = partials.size() == 1 ? partials.get(0) : aiComputeClient.chat(
        "Merge partial blog summaries into one concise, faithful structured summary. Do not add outside facts. Answer in the source language.",
        String.join("\n\n--- PARTIAL SUMMARY ---\n", partials)
    );
    List<RetrievedChunk> citedChunks = representativeChunks(chunks);
    List<Citation> citations = citationBuilder.build(citedChunks, "");
    String currentSlug = context.pageContext() != null && context.pageContext().isBlogPost() ? context.pageContext().slug() : null;
    List<RelatedPost> related = relatedPostBuilder.build(citedChunks, currentSlug);
    String content = "Complete article summary generated from " + chunks.size() + " ordered chunks:\n" + summary
        + "\nUse citation markers [1] through [" + citations.size() + "] when presenting this summary.";
    return ToolResult.success(call, content, citations, related, Map.of("slug", slug, "chunkCount", chunks.size(), "summaryLevels", partials.size() > 1 ? 2 : 1));
  }

  private String resolveSlug(String target, PageContext pageContext) {
    if (target.isBlank()) return pageContext != null && pageContext.isBlogPost() ? pageContext.slug() : null;
    BlogCatalogService.Resolution resolution = catalog.resolve(target);
    if (resolution.found()) return resolution.post().slug();
    if (resolution.ambiguous()) return "__candidates__" + resolution.candidates().stream().map(QdrantClient.PostEntry::title).toList();
    return "__not_found__";
  }

  private List<String> groups(List<RetrievedChunk> chunks) {
    List<String> values = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    String previous = "";
    for (RetrievedChunk chunk : chunks) {
      String content = removeOverlap(previous, chunk.content());
      String item = "\n## " + chunk.section() + "\n" + content + "\n";
      if (current.length() > 0 && current.length() + item.length() > GROUP_CHARS) {
        values.add(current.toString());
        current.setLength(0);
      }
      current.append(item);
      previous = chunk.content();
    }
    if (!current.isEmpty()) values.add(current.toString());
    return values;
  }

  private String removeOverlap(String previous, String current) {
    int max = Math.min(180, Math.min(previous.length(), current.length()));
    for (int size = max; size >= 32; size--) {
      if (previous.regionMatches(previous.length() - size, current, 0, size)) {
        return current.substring(size);
      }
    }
    return current;
  }

  private List<RetrievedChunk> representativeChunks(List<RetrievedChunk> chunks) {
    if (chunks.size() <= 3) return chunks;
    return List.of(chunks.get(0), chunks.get(chunks.size() / 2), chunks.get(chunks.size() - 1));
  }

  private String value(ToolCall call, String name) {
    Object value = call.arguments().get(name);
    return value == null ? "" : String.valueOf(value).trim();
  }
}
