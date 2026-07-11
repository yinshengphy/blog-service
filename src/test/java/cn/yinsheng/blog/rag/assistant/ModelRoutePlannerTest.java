package cn.yinsheng.blog.rag.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.yinsheng.blog.rag.compute.AiComputeClient;
import cn.yinsheng.blog.rag.model.ChatRequest;
import cn.yinsheng.blog.rag.model.PageContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelRoutePlannerTest {
  @Test
  void shouldUseCurrentBlogScopeForCurrentPageQuestion() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.classify(anyString(), anyString())).thenReturn("""
        {"route":"BLOG_CURRENT_QA","query":"混合加密在哪里"}
        """);
    ModelRoutePlanner planner = new ModelRoutePlanner(ai, new ObjectMapper());

    var plan = planner.plan(new ChatRequest(
        "这篇文章的混合加密在哪里",
        "s1",
        new PageContext("BLOG_POST", "rsa", "RSA", "/rsa/", "")
    ));

    assertThat(plan.route()).isEqualTo(ModelRoutePlanner.Route.BLOG_CURRENT_QA);
    assertThat(plan.arguments())
        .containsEntry("scope", "CURRENT_POST")
        .containsEntry("task", "ANSWER");
  }

  @Test
  void shouldApplyLocateDefaultsFromCurrentPage() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.classify(anyString(), anyString())).thenReturn("""
        {"route":"BLOG_LOCATE","query":"RSA 名称由来"}
        """);
    ModelRoutePlanner planner = new ModelRoutePlanner(ai, new ObjectMapper());

    var plan = planner.plan(new ChatRequest(
        "名称由来在哪一节",
        "s1",
        new PageContext("BLOG_POST", "rsa", "RSA", "/rsa/", "")
    ));

    assertThat(plan.arguments())
        .containsEntry("scope", "CURRENT_POST")
        .containsEntry("task", "LOCATE");
  }

  @Test
  void shouldRecoverSummaryTargetFromQuestionWhenModelOmitsIt() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.classify(anyString(), anyString())).thenReturn("""
        {"route":"BLOG_SUMMARY"}
        """);
    ModelRoutePlanner planner = new ModelRoutePlanner(ai, new ObjectMapper());

    var plan = planner.plan(
        new ChatRequest("完整总结 RSA 那篇博客", "s1", new PageContext("HOME", "", "", "/", "")),
        List.of(Map.of("role", "user", "content", "上一轮问题"))
    );

    assertThat(plan.route()).isEqualTo(ModelRoutePlanner.Route.BLOG_SUMMARY);
    assertThat(plan.arguments()).containsEntry("target", "完整总结 RSA 那篇博客");
  }

  @Test
  void shouldKeepPersonaIntentWithoutTool() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.classify(anyString(), anyString())).thenReturn("""
        {"route":"DIRECT_PERSONA"}
        """);
    ModelRoutePlanner planner = new ModelRoutePlanner(ai, new ObjectMapper());

    var plan = planner.plan(new ChatRequest("你是谁", "s1", null));

    assertThat(plan.route()).isEqualTo(ModelRoutePlanner.Route.DIRECT_PERSONA);
    assertThat(plan.toolName()).isEmpty();
  }

  @Test
  void shouldRecognizeCapabilityAsIndependentIntent() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.classify(anyString(), anyString())).thenReturn("{\"route\":\"CAPABILITY\"}");
    ModelRoutePlanner planner = new ModelRoutePlanner(ai, new ObjectMapper());

    var plan = planner.plan(new ChatRequest("你有哪些能力", "s1", null));

    assertThat(plan.route()).isEqualTo(ModelRoutePlanner.Route.CAPABILITY);
    assertThat(plan.toolName()).isEmpty();
  }

  @Test
  void shouldKeepWeatherQuestionAsCityFallbackWhenModelOmitsCity() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.classify(anyString(), anyString())).thenReturn("{\"route\":\"WEATHER\"}");
    ModelRoutePlanner planner = new ModelRoutePlanner(ai, new ObjectMapper());

    var plan = planner.plan(new ChatRequest("上海今天下雨吗", "s1", null));

    assertThat(plan.arguments()).containsEntry("city", "上海今天下雨吗");
  }
}
