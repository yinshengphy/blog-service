package cn.yinsheng.blog.rag.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.yinsheng.blog.rag.compute.AiComputeClient;
import cn.yinsheng.blog.rag.config.AssistantProperties;
import cn.yinsheng.blog.rag.model.ChatRequest;
import cn.yinsheng.blog.rag.tool.ToolCall;
import cn.yinsheng.blog.rag.tool.ToolDefinition;
import cn.yinsheng.blog.rag.tool.ToolExecutionContext;
import cn.yinsheng.blog.rag.tool.ToolExecutor;
import cn.yinsheng.blog.rag.tool.ToolRegistry;
import cn.yinsheng.blog.rag.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatOrchestratorTest {
  @Test
  void shouldLetModelAnswerDirectlyWithoutTool() {
    AiComputeClient ai = mock(AiComputeClient.class);
    when(ai.streamCompletion(anyList(), anyList(), any())).thenAnswer(invocation -> {
      Consumer<String> consumer = invocation.getArgument(2);
      consumer.accept("动态笑话 [1]");
      return new AiComputeClient.AgentTurn("动态笑话 [1]", List.of());
    });
    ToolRegistry registry = new ToolRegistry(List.of(handler("weather")));
    ChatOrchestrator orchestrator = orchestrator(ai, registry, new ModelRoutePlanner.RoutePlan(ModelRoutePlanner.Route.DIRECT, Map.of()));

    var response = orchestrator.answer(new ChatRequest("讲个笑话", "s1", null, List.of()));

    assertThat(response.answer()).isEqualTo("动态笑话");
    assertThat(response.usedTools()).isEmpty();
    assertThat(response.intent()).isEqualTo("MODEL_ROUTED");
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

    var response = orchestrator.answer(new ChatRequest("上海天气如何", "s1", null, List.of()));

    assertThat(response.answer()).contains("上海");
    assertThat(response.usedTools()).containsExactly("weather");
  }

  private ChatOrchestrator orchestrator(AiComputeClient ai, ToolRegistry registry, ModelRoutePlanner.RoutePlan plan) {
    ModelRoutePlanner planner = mock(ModelRoutePlanner.class);
    when(planner.plan(any())).thenReturn(plan);
    return new ChatOrchestrator(
        ai,
        registry,
        new ToolExecutor(registry),
        new AssistantProperties(),
        new AssistantSessionMemory(),
        new ObjectMapper(),
        planner
    );
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
