package cn.yinsheng.blog.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yinsheng.blog.service.compute.AiComputeClient;
import cn.yinsheng.blog.service.config.AssistantProperties;
import cn.yinsheng.blog.service.model.ChatRequest;
import cn.yinsheng.blog.service.tool.ToolCall;
import cn.yinsheng.blog.service.tool.ToolDefinition;
import cn.yinsheng.blog.service.tool.ToolExecutionContext;
import cn.yinsheng.blog.service.tool.ToolExecutor;
import cn.yinsheng.blog.service.tool.ToolRegistry;
import cn.yinsheng.blog.service.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatOrchestratorTest {
  @Test
  void shouldRemoveModelWrittenCitationLinksButKeepInlineMarkers() {
    String answer = "结论见正文 [1]。\n\n[1] /rsa/#实际使用中的-rsa\n[2] https://example.com/source";

    assertThat(ChatOrchestrator.sanitizeCitationMarkers(answer, 2))
        .isEqualTo("结论见正文 [1]。");
  }

  @Test
  void shouldLetModelAnswerDirectlyWithoutTool() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.streamCompletion(anyList(), anyList(), any())).thenAnswer(invocation -> {
      Consumer<String> consumer = invocation.getArgument(2);
      consumer.accept("动态笑话 [1]");
      return new AiComputeClient.AgentTurn("动态笑话 [1]", List.of());
    });
    ToolRegistry registry = new ToolRegistry(List.of(handler("weather")));
    ChatOrchestrator orchestrator = orchestrator(ai, registry, new ModelRoutePlanner.RoutePlan(ModelRoutePlanner.Route.DIRECT_GENERAL, Map.of()));

    var response = orchestrator.answer(new ChatRequest("讲个笑话", "s1", null));

    assertThat(response.answer()).isEqualTo("动态笑话");
    assertThat(response.usedTools()).isEmpty();
    assertThat(response.intent()).isEqualTo("DIRECT_GENERAL");
  }

  @Test
  void shouldExecuteModelSelectedTool() {
    AiComputeClient ai = mock(AiComputeClient.class);
    ToolCall call = new ToolCall("call-1", "weather", Map.of("city", "上海"));
    when(ai.streamCompletion(anyList(), anyList(), any()))
        .thenReturn(new AiComputeClient.AgentTurn("", List.of(call)))
        .thenReturn(new AiComputeClient.AgentTurn("上海当前天气晴。", List.of()));
    ToolRegistry registry = new ToolRegistry(List.of(handler("weather")));
    ChatOrchestrator orchestrator = orchestrator(ai, registry, new ModelRoutePlanner.RoutePlan(ModelRoutePlanner.Route.UNKNOWN, Map.of()));

    var response = orchestrator.answer(new ChatRequest("上海天气如何", "s1", null));

    assertThat(response.answer()).contains("上海");
    assertThat(response.usedTools()).containsExactly("weather");
  }

  @Test
  void shouldOnlyExecutePlannedBlogToolForCurrentPostQuestion() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.streamCompletion(anyList(), anyList(), any()))
        .thenReturn(new AiComputeClient.AgentTurn("答案 [1]", List.of()));
    ToolRegistry.ToolHandler blogQa = handler("blog_qa");
    ToolRegistry.ToolHandler weather = handler("weather");
    ToolRegistry registry = new ToolRegistry(List.of(blogQa, weather));
    var plan = new ModelRoutePlanner.RoutePlan(
        ModelRoutePlanner.Route.BLOG_CURRENT_QA,
        Map.of("task", "ANSWER", "scope", "CURRENT_POST")
    );
    ChatOrchestrator orchestrator = orchestrator(ai, registry, plan);

    var response = orchestrator.answer(new ChatRequest("这一节说了什么", "s1", null));

    assertThat(response.intent()).isEqualTo("BLOG_CURRENT_QA");
    assertThat(response.usedTools()).containsExactly("blog_qa");
    verify(ai).streamCompletion(anyList(), org.mockito.ArgumentMatchers.argThat(tools -> !tools.isEmpty()), any());
  }

  private ChatOrchestrator orchestrator(AiComputeClient ai, ToolRegistry registry, ModelRoutePlanner.RoutePlan plan) {
    ModelRoutePlanner planner = mock(ModelRoutePlanner.class);
    when(planner.plan(any(), anyList())).thenReturn(plan);
    return new ChatOrchestrator(
        ai,
        registry,
        new ToolExecutor(registry),
        new AssistantProperties(),
        new AssistantSessionMemory(new AssistantProperties()),
        new ObjectMapper(),
        planner,
        new CitationNormalizer(),
        new FollowUpQuestionGenerator(new AssistantProperties())
    );
  }

  @Test
  void shouldContinueInvestigationAfterPlannedToolAndRemapEvidence() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.streamCompletion(anyList(), anyList(), any()))
        .thenReturn(new AiComputeClient.AgentTurn("", List.of(new ToolCall("second", "read_post", Map.of("target", "b")))))
        .thenAnswer(invocation -> {
          List<Map<String, Object>> messages = invocation.getArgument(0);
          assertThat(messages.stream().filter(m -> "tool".equals(m.get("role"))).map(m -> String.valueOf(m.get("content"))).toList())
              .anyMatch(text -> text.contains("[Source 2]"));
          return new AiComputeClient.AgentTurn("两篇的区别 [1] [2]", List.of());
        });
    ToolRegistry.ToolHandler read = new ToolRegistry.ToolHandler() {
      public ToolDefinition definition() { return new ToolDefinition("read_post", "读取", Map.of("type", "object", "properties", Map.of("target", Map.of("type", "string")))); }
      public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        String target = String.valueOf(call.arguments().get("target"));
        return ToolResult.success(call, "[Source 1] " + target, List.of(new cn.yinsheng.blog.service.model.Citation(target, "正文", "/" + target + "/", target)), List.of(), Map.of());
      }
    };
    ToolRegistry.ToolHandler qa = new ToolRegistry.ToolHandler() {
      public ToolDefinition definition() { return new ToolDefinition("blog_qa", "检索", Map.of("type", "object")); }
      public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        return ToolResult.success(call, "[Source 1] a", List.of(new cn.yinsheng.blog.service.model.Citation("a", "正文", "/a/", "a")), List.of(), Map.of());
      }
    };
    var registry = new ToolRegistry(List.of(qa, read));
    var result = orchestrator(ai, registry, new ModelRoutePlanner.RoutePlan(ModelRoutePlanner.Route.BLOG_SITE_QA, Map.of()))
        .answer(new ChatRequest("比较两篇", "multi", null));
    assertThat(result.usedTools()).containsExactly("blog_qa", "read_post");
    assertThat(result.citations()).hasSize(2);
  }

  private ToolRegistry.ToolHandler handler(String name) {
    return new ToolRegistry.ToolHandler() {
      @Override
      public ToolDefinition definition() {
        return new ToolDefinition(name, "test", Map.of("type", "object"));
      }

      @Override
      public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        return ToolResult.success(call, "{\"city\":\"上海\",\"weather\":\"晴\"}");
      }
    };
  }
}
