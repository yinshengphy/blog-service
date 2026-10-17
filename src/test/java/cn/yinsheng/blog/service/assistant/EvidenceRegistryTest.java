package cn.yinsheng.blog.service.assistant;

import cn.yinsheng.blog.service.model.Citation;
import cn.yinsheng.blog.service.tool.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRegistryTest {
  @Test void assignsDifferentIdsAcrossToolsAndReusesIdenticalEvidence() {
    EvidenceRegistry registry = new EvidenceRegistry();
    Citation a = new Citation("A", "第一节", "/a/#one", "原文 A");
    Citation b = new Citation("B", "第一节", "/b/#one", "原文 B");
    registry.bind(result(a));
    assertThat(registry.bind(result(b)).content()).contains("[Source 2]", "论述 [2]");
    assertThat(registry.bind(result(a)).content()).contains("[Source 1]", "论述 [1]");
    assertThat(registry.citations()).containsExactly(a, b);
  }
  private ToolResult result(Citation citation) {
    return ToolResult.success(new ToolCall("id", "read_section", Map.of()), "[Source 1] 论述 [1]",
        List.of(citation), List.of(), Map.of());
  }
}
