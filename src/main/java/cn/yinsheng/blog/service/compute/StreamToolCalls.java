package cn.yinsheng.blog.service.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 按 OpenAI 流式索引合并工具调用，参数必须在完整接收后才能解析。 */
final class StreamToolCalls {
  private final ObjectMapper mapper;
  private final Map<Integer, ObjectNode> calls = new TreeMap<>();

  StreamToolCalls(ObjectMapper mapper) { this.mapper = mapper; }

  void accept(JsonNode fragments) {
    if (!fragments.isArray()) return;
    for (JsonNode fragment : fragments) {
      int index = fragment.path("index").asInt(0);
      ObjectNode call = calls.computeIfAbsent(index, ignored -> {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "function");
        node.putObject("function").put("name", "").put("arguments", "");
        return node;
      });
      if (fragment.hasNonNull("id")) call.put("id", fragment.path("id").asText());
      ObjectNode function = (ObjectNode) call.path("function");
      for (String field : List.of("name", "arguments")) {
        JsonNode part = fragment.path("function").path(field);
        if (part.isTextual()) function.put(field, function.path(field).asText("") + part.asText());
      }
    }
  }

  List<JsonNode> complete() { return List.copyOf(calls.values()); }
}
