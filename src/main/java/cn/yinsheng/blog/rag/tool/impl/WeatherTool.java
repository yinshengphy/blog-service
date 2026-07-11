package cn.yinsheng.blog.rag.tool.impl;

import cn.yinsheng.blog.rag.skill.impl.WeatherProvider;
import cn.yinsheng.blog.rag.skill.impl.WeatherReport;
import cn.yinsheng.blog.rag.tool.ToolCall;
import cn.yinsheng.blog.rag.tool.ToolDefinition;
import cn.yinsheng.blog.rag.tool.ToolExecutionContext;
import cn.yinsheng.blog.rag.tool.ToolRegistry;
import cn.yinsheng.blog.rag.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool implements ToolRegistry.ToolHandler {
  private final WeatherProvider provider;
  private final ObjectMapper objectMapper;

  public WeatherTool(WeatherProvider provider, ObjectMapper objectMapper) {
    this.provider = provider;
    this.objectMapper = objectMapper;
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition("weather", "查询指定城市的实时天气，数据来自 Open-Meteo", Map.of(
        "type", "object",
        "properties", Map.of("city", Map.of("type", "string")),
        "required", List.of("city")
    ));
  }

  @Override
  public ToolResult execute(ToolCall call, ToolExecutionContext context) {
    String city = String.valueOf(call.arguments().getOrDefault("city", "")).trim();
    if (city.isBlank()) return ToolResult.failure(call, "A city is required. Ask the user which city to query.");
    WeatherReport report = provider.currentWeather(city);
    try {
      return ToolResult.success(call, objectMapper.writeValueAsString(report), List.of(), List.of(), Map.of("source", report.source(), "city", report.city()));
    } catch (Exception ex) {
      return ToolResult.failure(call, "Weather data serialization failed.");
    }
  }
}
