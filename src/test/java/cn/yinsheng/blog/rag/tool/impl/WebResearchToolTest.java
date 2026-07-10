package cn.yinsheng.blog.rag.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yinsheng.blog.rag.config.WebSearchProperties;
import cn.yinsheng.blog.rag.tool.ToolCall;
import cn.yinsheng.blog.rag.tool.ToolExecutionContext;
import cn.yinsheng.blog.rag.web.WebPageReader;
import cn.yinsheng.blog.rag.web.WebSearchProvider;
import cn.yinsheng.blog.rag.web.WebSearchResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebResearchToolTest {
  @Test
  void fallsBackToGeneralSearchWhenCategoryHasNoResults() {
    WebSearchProvider provider = mock(WebSearchProvider.class);
    when(provider.search("AI news", "auto", "news", 1)).thenReturn(List.of());
    when(provider.search("AI news", "auto", "general", 1)).thenReturn(List.of(
        new WebSearchResult("AI update", "https://example.com/ai", "Recent AI update", "bing", "2026-07-11")
    ));
    WebPageReader reader = mock(WebPageReader.class);
    when(reader.read("https://example.com/ai")).thenReturn("");
    WebResearchTool tool = new WebResearchTool(provider, reader, new WebSearchProperties());

    var result = tool.execute(
        new ToolCall("1", "web_research", Map.of("query", "AI news", "category", "news")),
        new ToolExecutionContext("", "", null)
    );

    assertThat(result.success()).isTrue();
    assertThat(result.citations()).hasSize(1);
    assertThat(result.metadata()).containsEntry("category", "general");
    verify(provider).search("AI news", "auto", "news", 1);
    verify(provider).search("AI news", "auto", "general", 1);
  }

  @Test
  void fallsBackToAutomaticEnginesWhenRequestedEngineIsUnavailable() {
    WebSearchProvider provider = mock(WebSearchProvider.class);
    when(provider.search("人工智能", "baidu", "general", 1)).thenReturn(List.of());
    when(provider.search("人工智能", "auto", "general", 1)).thenReturn(List.of(
        new WebSearchResult("AI update", "https://example.com/ai", "Recent AI update", "bing", "2026-07-11")
    ));
    WebPageReader reader = mock(WebPageReader.class);
    when(reader.read("https://example.com/ai")).thenReturn("");
    WebResearchTool tool = new WebResearchTool(provider, reader, new WebSearchProperties());

    var result = tool.execute(
        new ToolCall("1", "web_research", Map.of("query", "人工智能", "engine", "baidu")),
        new ToolExecutionContext("", "", null)
    );

    assertThat(result.success()).isTrue();
    assertThat(result.content()).contains("Requested engine baidu returned no results");
    assertThat(result.metadata())
        .containsEntry("engine", "baidu")
        .containsEntry("effectiveEngine", "auto");
  }
}
