package cn.yinsheng.blog.service.tool;

import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {
  private final ToolRegistry toolRegistry;

  public ToolExecutor(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  public ToolResult execute(ToolCall call, ToolExecutionContext context) {
    return toolRegistry.find(call.name())
        .map(handler -> {
          try {
            String error = validate(call.arguments(), handler.definition().parameters());
            if (!error.isEmpty()) return ToolResult.failure(call, error);
            return handler.execute(call, context);
          } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(ToolExecutor.class).warn(
                "tool_failed traceId={} tool={} errorType={}", context.traceId(), call.name(), ex.getClass().getSimpleName());
            return ToolResult.failure(call, "工具暂时不可用，请尝试其他只读工具，或说明当前无法完成查证。");
          }
        })
        .orElseGet(() -> ToolResult.failure(call, "未知工具：" + call.name()));
  }

  public ToolResult execute(ToolCall call) {
    return execute(call, new ToolExecutionContext("", "", null));
  }

  private String validate(java.util.Map<String, Object> arguments, java.util.Map<String, Object> schema) {
    if (arguments.size() > 20) return "工具参数过多";
    if (schema.get("required") instanceof java.util.List<?> required) {
      for (Object name : required) if (!arguments.containsKey(name) || arguments.get(name) == null) return "缺少必填参数：" + name;
    }
    java.util.Map<?, ?> fields = schema.get("properties") instanceof java.util.Map<?, ?> map ? map : java.util.Map.of();
    for (var entry : arguments.entrySet()) {
      if (Boolean.FALSE.equals(schema.get("additionalProperties")) && !fields.containsKey(entry.getKey())) return "未知参数：" + entry.getKey();
      Object value = entry.getValue();
      if (value instanceof String text && text.length() > 8000) return "参数过长：" + entry.getKey();
      if (fields.get(entry.getKey()) instanceof java.util.Map<?, ?> field) {
        String type = String.valueOf(field.get("type"));
        boolean valid = switch (type) {
          case "string" -> value instanceof String;
          case "integer" -> value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() == number.longValue();
          case "number" -> value instanceof Number number && Double.isFinite(number.doubleValue());
          case "boolean" -> value instanceof Boolean;
          default -> false;
        };
        if (!valid) return "参数类型错误：" + entry.getKey();
        if (field.get("enum") instanceof java.util.List<?> options && !options.contains(value)) return "参数取值不支持：" + entry.getKey();
      }
    }
    return "";
  }
}
