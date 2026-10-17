package cn.yinsheng.blog.service.tool.impl;

import cn.yinsheng.blog.service.indexer.BlogPost;
import cn.yinsheng.blog.service.indexer.MarkdownChunker;
import cn.yinsheng.blog.service.model.Citation;
import cn.yinsheng.blog.service.model.RelatedPost;
import cn.yinsheng.blog.service.rag.BlogLibrary;
import cn.yinsheng.blog.service.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BlogKnowledgeTools {
  private final BlogLibrary library;
  private final MarkdownChunker chunker;
  private final ObjectMapper mapper;

  public BlogKnowledgeTools(BlogLibrary library, MarkdownChunker chunker, ObjectMapper mapper) {
    this.library = library; this.chunker = chunker; this.mapper = mapper;
  }

  @Bean ToolRegistry.ToolHandler searchPostsTool() {
    return handler("search_posts", "按关键词、分类、标签、发布日期筛选公开文章；支持最新排序、分页和目录统计。查询为空时列出目录。", Map.of(
        "query", text("简短主题或标题关键词，可留空"), "category", text("分类，可留空"),
        "tag", text("标签，可留空"), "date_from", text("发布日期下限 YYYY-MM-DD"),
        "date_to", text("发布日期上限 YYYY-MM-DD"), "sort", text("date_desc 或 date_asc"),
        "offset", number("从第几篇开始，默认 0"), "limit", number("每页篇数，1 至 10")), this::search);
  }
  @Bean ToolRegistry.ToolHandler readPostOutlineTool() {
    return handler("read_post_outline", "读取指定或当前文章的章节目录，先了解结构再选择章节深入阅读。",
        Map.of("target", text("文章 slug 或标题，留空使用当前文章")),
        (call, context) -> read(call, context, "outline"));
  }
  @Bean ToolRegistry.ToolHandler readSectionTool() {
    return handler("read_section", "读取文章指定章节的完整原文；可分页继续阅读，证据不足时优先调用。", Map.of(
        "target", text("文章 slug 或标题，留空使用当前文章"), "section", text("目录中的章节 ID 或标题，重名章节必须使用 ID"),
        "offset", number("章节正文字符偏移，默认 0"), "max_chars", number("本次最多读取字符数，默认 12000，上限 16000")),
        (call, context) -> read(call, context, "section"));
  }
  @Bean ToolRegistry.ToolHandler readPostTool() {
    return handler("read_post", "读取指定或当前博客正文；返回 nextOffset 时可继续读取，不能把未读部分当成已读。", Map.of(
        "target", text("文章 slug 或标题，留空使用当前文章"), "offset", number("正文字符偏移，默认 0"),
        "max_chars", number("本次最多读取字符数，默认 12000，上限 16000")),
        (call, context) -> read(call, context, "post"));
  }

  @Bean ToolRegistry.ToolHandler siteProfileTool() {
    return handler("get_site_profile", "读取经过确认的站点名称、作者公开称呼、文章数量与分类；作者经历应继续检索文章，不得猜测。", Map.of(),
        (call, context) -> {
          try {
            var resource = new org.springframework.core.io.ClassPathResource("site-profile.json");
            Map<String, Object> profile = mapper.readValue(resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8), Map.class);
            profile.put("publishedPosts", library.posts().size());
            profile.put("categories", counts(library.posts().stream().flatMap(p -> p.categories().stream()).toList()));
            return ToolResult.success(call, "[Source 1]\n" + json(profile), List.of(
                new Citation(String.valueOf(profile.get("siteName")), "站点公开信息", "/", "本站作者公开称呼为 yinsheng，助手并非作者本人。")), List.of(), Map.of());
          } catch (java.io.IOException ex) { return ToolResult.failure(call, "站点公开信息暂时无法读取"); }
        });
  }

  private ToolRegistry.ToolHandler handler(String name, String description, Map<String, Object> properties,
      java.util.function.BiFunction<ToolCall, ToolExecutionContext, ToolResult> action) {
    return new ToolRegistry.ToolHandler() {
      public ToolDefinition definition() { return new ToolDefinition(name, description,
          Map.of("type", "object", "properties", properties, "additionalProperties", false)); }
      public ToolResult execute(ToolCall call, ToolExecutionContext context) { return action.apply(call, context); }
    };
  }

  private ToolResult search(ToolCall call, ToolExecutionContext context) {
    String query = BlogLibrary.normalize(arg(call, "query"));
    String category = arg(call, "category"), tag = arg(call, "tag");
    String from = arg(call, "date_from"), to = arg(call, "date_to");
    for (String date : List.of(from, to)) {
      if (!date.isBlank()) try { java.time.LocalDate.parse(date); }
      catch (Exception ex) { return ToolResult.failure(call, "日期必须为 YYYY-MM-DD"); }
    }
    List<BlogPost> all = library.posts();
    Comparator<BlogPost> order = Comparator.comparing(BlogPost::date).thenComparing(BlogPost::slug);
    if (!"date_asc".equals(arg(call, "sort"))) order = order.reversed();
    List<BlogPost> matches = all.stream()
        .filter(p -> query.isBlank() || BlogLibrary.normalize(p.title() + " " + p.description() + " "
            + String.join(" ", p.tags()) + " " + String.join(" ", p.categories())).contains(query))
        .filter(p -> category.isBlank() || p.categories().stream().anyMatch(c -> c.equalsIgnoreCase(category)))
        .filter(p -> tag.isBlank() || p.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tag)))
        .filter(p -> from.isBlank() || p.date().compareTo(from) >= 0)
        .filter(p -> to.isBlank() || (!p.date().isBlank() && p.date().substring(0, Math.min(10, p.date().length())).compareTo(to) <= 0))
        .sorted(order).toList();
    int offset = integer(call, "offset", 0, 0, 100000), limit = integer(call, "limit", 5, 1, 10);
    List<BlogPost> page = matches.stream().skip(offset).limit(limit).toList();
    List<Citation> citations = new ArrayList<>();
    List<Map<String, Object>> entries = new ArrayList<>();
    for (BlogPost post : page) {
      citations.add(new Citation(post.title(), "文章信息", "/" + post.slug() + "/", post.description()));
      entries.add(Map.of("source", "[" + citations.size() + "]", "slug", post.slug(), "title", post.title(), "date", post.date(),
          "categories", post.categories(), "tags", post.tags(), "description", post.description()));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("totalPosts", all.size()); data.put("matchedPosts", matches.size()); data.put("posts", entries);
    data.put("nextOffset", offset + page.size() < matches.size() ? offset + page.size() : -1);
    data.put("categories", counts(all.stream().flatMap(p -> p.categories().stream()).toList()));
    data.put("tags", counts(all.stream().flatMap(p -> p.tags().stream()).toList()));
    String content = json(data);
    for (int i = 0; i < entries.size(); i++) content += "\n[Source " + (i + 1) + "] " + entries.get(i).get("title");
    return ToolResult.success(call, content, citations, page.stream().map(p -> new RelatedPost(p.title(), "/" + p.slug() + "/")).toList(),
        Map.of("catalogCount", all.size(), "recentPosts", page.stream().map(p -> Map.of("slug", p.slug(), "title", p.title())).toList()));
  }

  private ToolResult read(ToolCall call, ToolExecutionContext context, String mode) {
    String target = arg(call, "target");
    if (target.isBlank() && context.pageContext() != null && context.pageContext().isBlogPost()) target = context.pageContext().slug();
    List<BlogPost> matches = library.resolve(target);
    if (matches.size() != 1) return ToolResult.failure(call, matches.isEmpty()
        ? "未找到文章，请使用 search_posts 查找，或请用户指定文章。"
        : "存在多篇同名或相近文章，请澄清：" + matches.stream().map(p -> p.title() + " (" + p.slug() + ")").toList());
    BlogPost post = matches.get(0);
    List<Section> sections = sections(post);
    if ("outline".equals(mode)) {
      return ToolResult.success(call, json(Map.of("slug", post.slug(), "title", post.title(), "sections", sections.stream()
          .map(s -> Map.of("id", "s" + (sections.indexOf(s) + 1), "title", s.title(), "characters", s.content().length(), "url", s.url())).toList())),
          List.of(new Citation(post.title(), "文章目录", "/" + post.slug() + "/", "")), List.of(), Map.of("slug", post.slug()));
    }
    String content = post.body(), title = post.title(), url = "/" + post.slug() + "/";
    if ("section".equals(mode)) {
      String section = BlogLibrary.normalize(arg(call, "section"));
      List<Section> found = sections.stream().filter(s -> !section.isBlank() && (BlogLibrary.normalize(s.title()).equals(section)
          || ("s" + (sections.indexOf(s) + 1)).equals(section))).toList();
      if (found.size() != 1) return ToolResult.failure(call, "章节不存在或标题重复，请先读取目录并明确章节：" + sections.stream().map(Section::title).toList());
      content = found.get(0).content(); title = found.get(0).title(); url = found.get(0).url();
    }
    int offset = integer(call, "offset", 0, 0, content.length());
    int end = Math.min(content.length(), offset + integer(call, "max_chars", 12000, 1000, 16000));
    String part = content.substring(offset, end);
    return ToolResult.success(call, "[Source 1]\n" + json(Map.of("title", post.title(), "slug", post.slug(),
        "section", title, "sourceText", part, "totalCharacters", content.length(), "offset", offset,
        "nextOffset", end < content.length() ? end : -1)),
        List.of(new Citation(post.title(), title, url, part.substring(0, Math.min(160, part.length())))),
        List.of(), Map.of("slug", post.slug(), "readComplete", end == content.length() && offset == 0));
  }

  private List<Section> sections(BlogPost post) {
    List<Section> values = new ArrayList<>();
    Pattern heading = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");
    String title = post.title(); StringBuilder content = new StringBuilder(); String fence = "";
    String url = "/" + post.slug() + "/";
    Map<String, Integer> anchors = new java.util.HashMap<>();
    for (String line : post.body().split("\\R")) {
      String trimmed = line.stripLeading();
      if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
        String marker = trimmed.substring(0, 3);
        if (fence.isEmpty()) fence = marker; else if (fence.equals(marker)) fence = "";
        content.append(line).append('\n'); continue;
      }
      var match = heading.matcher(line);
      if (fence.isEmpty() && match.matches()) {
        if (!content.isEmpty() || !url.equals("/" + post.slug() + "/")) values.add(new Section(title, content.toString(), url));
        title = match.group(1); content.setLength(0);
        String anchor = chunker.slugify(title);
        int count = anchors.merge(anchor, 1, Integer::sum);
        url = "/" + post.slug() + "/#" + anchor + (count > 1 ? "-" + (count - 1) : "");
      } else content.append(line).append('\n');
    }
    if (!content.isEmpty() || !url.equals("/" + post.slug() + "/")) values.add(new Section(title, content.toString(), url));
    return values;
  }

  private Map<String, Long> counts(List<String> values) {
    return values.stream().collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
  }
  private String arg(ToolCall call, String name) { Object value = call.arguments().get(name); return value == null ? "" : String.valueOf(value).trim(); }
  private int integer(ToolCall call, String name, int fallback, int min, int max) {
    try { return Math.max(min, Math.min(max, Integer.parseInt(arg(call, name)))); } catch (Exception ex) { return fallback; }
  }
  private Map<String, Object> text(String description) { return Map.of("type", "string", "description", description); }
  private Map<String, Object> number(String description) { return Map.of("type", "integer", "description", description); }
  private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("文章数据序列化失败"); } }
  private record Section(String title, String content, String url) { }
}
