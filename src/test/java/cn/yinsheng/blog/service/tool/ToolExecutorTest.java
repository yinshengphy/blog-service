package cn.yinsheng.blog.service.tool;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolExecutorTest {
  @Test void rejectsInvalidUnknownAndOversizedArgumentsBeforeExecution() {
    var handler = mock(ToolRegistry.ToolHandler.class);
    when(handler.definition()).thenReturn(new ToolDefinition("read", "读取", Map.of("type", "object", "properties", Map.of("offset", Map.of("type", "integer"), "target", Map.of("type", "string")), "additionalProperties", false)));
    var executor = new ToolExecutor(new ToolRegistry(List.of(handler)));
    for (var arguments : List.of(Map.<String, Object>of("offset", "1"), Map.<String, Object>of("offset", 1.5), Map.<String, Object>of("command", "execute"), Map.<String, Object>of("target", "x".repeat(8001)))) {
      assertThat(executor.execute(new ToolCall("1", "read", arguments)).success()).isFalse();
    }
    verify(handler, never()).execute(any(), any());
  }
}
