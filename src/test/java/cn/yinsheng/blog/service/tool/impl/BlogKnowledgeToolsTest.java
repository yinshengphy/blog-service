package cn.yinsheng.blog.service.tool.impl;

import cn.yinsheng.blog.service.indexer.BlogPost;
import cn.yinsheng.blog.service.indexer.MarkdownChunker;
import cn.yinsheng.blog.service.rag.BlogLibrary;
import cn.yinsheng.blog.service.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BlogKnowledgeToolsTest {
  private final BlogLibrary library = mock(BlogLibrary.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final MarkdownChunker chunker = mock(MarkdownChunker.class);
  private final BlogKnowledgeTools tools = new BlogKnowledgeTools(library, chunker, mapper);
  private final ToolExecutionContext context = new ToolExecutionContext("test", "session", null);

  @Test void filtersCountsAndPaginatesCatalog() throws Exception {
    when(library.posts()).thenReturn(List.of(post("a", "2026-10-15", "A"), post("b", "2026-10-17", "B"), post("c", "2026-10-16", "C")));
    var result = tools.searchPostsTool().execute(new ToolCall("1", "search_posts", Map.of("category", "技术学习", "tag", "Java", "date_from", "2026-10-16", "limit", 1)), context);
    var data = mapper.readTree(result.content().split("\n")[0]);
    assertThat(data.path("totalPosts").asInt()).isEqualTo(3);
    assertThat(data.path("matchedPosts").asInt()).isEqualTo(2);
    assertThat(data.path("posts").get(0).path("slug").asText()).isEqualTo("b");
    assertThat(data.path("nextOffset").asInt()).isEqualTo(1);
    assertThat(result.metadata()).containsKey("recentPosts");
  }

  @Test void outlinesIgnoreCodeHeadingsAndReadsSectionInPages() throws Exception {
    BlogPost post = post("rsa", "2026-10-17", "# 空章节\n## 正文\n" + "a".repeat(2100) + "\n```java\n# 不是章节\n```\n");
    when(library.resolve("rsa")).thenReturn(List.of(post));
    when(chunker.slugify(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    var outline = tools.readPostOutlineTool().execute(new ToolCall("1", "read_post_outline", Map.of("target", "rsa")), context);
    assertThat(mapper.readTree(outline.content()).path("sections").size()).isEqualTo(2);
    var result = tools.readSectionTool().execute(new ToolCall("2", "read_section", Map.of("target", "rsa", "section", "s2", "max_chars", 1000)), context);
    var data = mapper.readTree(result.content().substring("[Source 1]\n".length()));
    assertThat(data.path("nextOffset").asInt()).isEqualTo(1000);
    assertThat(data.path("sourceText").asText()).hasSize(1000);
    assertThat(result.metadata().get("readComplete")).isEqualTo(false);
  }

  private BlogPost post(String slug, String date, String body) {
    return new BlogPost(slug, slug, date, date, List.of("Java"), List.of("技术学习"), "描述", body, "hash");
  }
}
