package cn.yinsheng.blog.service.compute;

import cn.yinsheng.blog.service.config.RagProperties;
import cn.yinsheng.blog.service.tool.ToolCall;
import cn.yinsheng.blog.service.tool.ToolDefinition;
import cn.yinsheng.blog.service.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AiComputeClient {
  private static final Logger log = LoggerFactory.getLogger(AiComputeClient.class);
  private static final long CIRCUIT_BREAKER_MS = 60_000L;
  private static final String GUARDRAIL_SYSTEM_PROMPT =
      "本接口仅输出文本和请求中声明的工具调用。不要生成或编辑图片，不调用图片生成工具。";

  private final RestClient restClient;
  private final RagProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final AtomicLong primaryUnavailableUntil = new AtomicLong(0L);

  public AiComputeClient(RestClient restClient, RagProperties properties, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
    Duration timeout = properties.requestTimeout() != null ? properties.requestTimeout() : Duration.ofSeconds(120);
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public List<Double> embed(String text) {
    String baseUrl = properties.effectiveOllamaBaseUrl();
    JsonNode response = restClient.post()
        .uri(baseUrl + "/v1/embeddings")
        .headers(headers -> setAuth(headers, properties.aiComputeToken()))
        .body(Map.of(
            "model", properties.embeddingModel(),
            "input", List.of(text),
            "keep_alive", "-1s",
            "options", Map.of("num_gpu", 0)
        ))
        .retrieve()
        .body(JsonNode.class);

    JsonNode vector = response == null ? null : response.path("data").path(0).path("embedding");
    if ((vector == null || !vector.isArray()) && response != null) {
      vector = response.path("embeddings").path(0);
    }
    if (vector == null || !vector.isArray()) {
      throw new IllegalStateException("AI Compute embedding response does not contain a vector");
    }
    List<Double> values = new ArrayList<>(vector.size());
    vector.forEach(value -> values.add(value.asDouble()));
    return values;
  }

  public String chat(String systemPrompt, String userPrompt) {
    return chat(systemPrompt, userPrompt, properties.maxAnswerTokens(), 0.2);
  }

  public String classify(String systemPrompt, String userPrompt) {
    return chat(systemPrompt, userPrompt, 180, 0.0);
  }

  private String chat(String systemPrompt, String userPrompt, int maxTokens, double temperature) {
    List<Map<String, Object>> messages = List.of(
        Map.of("role", "system", "content", GUARDRAIL_SYSTEM_PROMPT),
        Map.of("role", "system", "content", systemPrompt),
        Map.of("role", "user", "content", userPrompt)
    );
    JsonNode message = executeWithFallback(model -> {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model);
      body.put("max_tokens", Math.max(2048, maxTokens));
      body.put("temperature", temperature);
      body.put("modalities", List.of("text"));
      body.put("reasoning_effort", "low");
      body.put("tools", List.of());
      body.put("tool_choice", "none");
      body.put("messages", messages);

      JsonNode response = restClient.post()
          .uri(properties.aiComputeBaseUrl() + "/v1/chat/completions")
          .headers(headers -> setAuth(headers, properties.aiComputeToken()))
          .body(body)
          .retrieve()
          .body(JsonNode.class);
      JsonNode msg = response == null ? null : response.path("choices").path(0).path("message");
      if (msg == null || msg.isMissingNode() || msg.path("content").asText("").isBlank()) {
        throw new IllegalStateException("AI Compute chat response is empty");
      }
      return msg;
    });
    return message.path("content").asText("").trim();
  }

  public String chatWithTools(
      String systemPrompt,
      String userPrompt,
      List<ToolDefinition> tools,
      Function<ToolCall, ToolResult> toolExecutor,
      int maxToolLoops
  ) {
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", GUARDRAIL_SYSTEM_PROMPT));
    messages.add(Map.of("role", "system", "content", systemPrompt));
    messages.add(Map.of("role", "user", "content", userPrompt));
    List<Map<String, Object>> openAiTools = tools.stream().map(ToolDefinition::toOpenAiTool).toList();
    int maxLoops = Math.max(1, Math.min(maxToolLoops, 3));
    for (int i = 0; i < maxLoops; i++) {
      JsonNode message = chatCompletion(messages, openAiTools);
      JsonNode toolCalls = message.path("tool_calls");
      if (!toolCalls.isArray() || toolCalls.isEmpty()) {
        String content = message.path("content").asText("");
        if (content.isBlank()) {
          throw new IllegalStateException("AI Compute tool chat response is empty");
        }
        return content.trim();
      }
      messages.add(assistantToolCallMessage(message));
      for (JsonNode toolCallNode : toolCalls) {
        ToolCall call = parseToolCall(toolCallNode);
        ToolResult result = toolExecutor.apply(call);
        messages.add(Map.of(
            "role", "tool",
            "tool_call_id", result.toolCallId(),
            "name", result.name(),
            "content", result.content()
        ));
      }
    }
    throw new IllegalStateException("AI Compute exceeded max tool loop count");
  }

  public String chatStream(String systemPrompt, String userPrompt, Consumer<String> deltaConsumer) {
    List<Map<String, Object>> messages = List.of(
        Map.of("role", "system", "content", GUARDRAIL_SYSTEM_PROMPT),
        Map.of("role", "system", "content", systemPrompt),
        Map.of("role", "user", "content", userPrompt)
    );
    return executeStreamWithFallback((model, published) -> {
      StringBuilder answer = new StringBuilder();
      boolean completed = false;
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model);
      body.put("stream", true);
      body.put("max_tokens", Math.max(2048, properties.maxAnswerTokens()));
      body.put("temperature", 0.2);
      body.put("modalities", List.of("text"));
      body.put("reasoning_effort", "low");
      body.put("tools", List.of());
      body.put("tool_choice", "none");
      body.put("messages", messages);

      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(properties.aiComputeBaseUrl() + "/v1/chat/completions"))
          .timeout(properties.requestTimeout())
          .header("Content-Type", "application/json")
          .header("Accept", "text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
      if (properties.aiComputeToken() != null && !properties.aiComputeToken().isBlank()) {
        requestBuilder.header("Authorization", "Bearer " + properties.aiComputeToken());
      }
      HttpResponse<java.io.InputStream> response = httpClient.send(
          requestBuilder.build(),
          HttpResponse.BodyHandlers.ofInputStream()
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("AI Compute streaming chat failed with status " + response.statusCode());
      }
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) {
            continue;
          }
          String data = line.substring("data:".length()).trim();
          if ("[DONE]".equals(data)) {
            completed = true;
            break;
          }
          if (data.isBlank()) {
            continue;
          }
          JsonNode node = objectMapper.readTree(data);
          if (node.has("error")) throw new IllegalStateException("模型响应中断，请重试");
          JsonNode choice = node.path("choices").path(0);
          if (choice.hasNonNull("finish_reason")) completed = true;
          String delta = choice.path("delta").path("content").asText("");
          if (!delta.isEmpty()) {
            published[0] = true;
            answer.append(delta);
            deltaConsumer.accept(delta);
          }
        }
      }
      String value = answer.toString().trim();
      if (!completed) throw new IllegalStateException("模型响应未完整接收，请重试");
      if (value.isBlank()) {
        throw new IllegalStateException("AI Compute streaming chat response is empty");
      }
      return value;
    });
  }

  public AgentTurn streamCompletion(
      List<Map<String, Object>> messages,
      List<ToolDefinition> tools,
      Consumer<String> deltaConsumer
  ) {
    List<Map<String, Object>> fullMessages = new ArrayList<>(messages.size() + 1);
    fullMessages.add(Map.of("role", "system", "content", GUARDRAIL_SYSTEM_PROMPT));
    fullMessages.addAll(messages);

    return executeStreamWithFallback((model, published) -> {
      StringBuilder answer = new StringBuilder();
      StreamToolCalls toolCalls = new StreamToolCalls(objectMapper);
      boolean completed = false;
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model);
      body.put("stream", true);
      body.put("max_tokens", Math.max(2048, properties.maxAnswerTokens()));
      body.put("temperature", 0.15);
      body.put("modalities", List.of("text"));
      body.put("reasoning_effort", "low");
      body.put("messages", fullMessages);
      if (tools != null && !tools.isEmpty()) {
        body.put("tools", tools.stream().map(ToolDefinition::toOpenAiTool).toList());
        body.put("tool_choice", "auto");
      } else {
        body.put("tools", List.of());
        body.put("tool_choice", "none");
      }

      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
          .uri(URI.create(properties.aiComputeBaseUrl() + "/v1/chat/completions"))
          .timeout(properties.requestTimeout())
          .header("Content-Type", "application/json")
          .header("Accept", "text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
      if (properties.aiComputeToken() != null && !properties.aiComputeToken().isBlank()) {
        requestBuilder.header("Authorization", "Bearer " + properties.aiComputeToken());
      }
      HttpResponse<java.io.InputStream> response = httpClient.send(
          requestBuilder.build(),
          HttpResponse.BodyHandlers.ofInputStream()
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("AI Compute agent stream failed with status " + response.statusCode());
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) {
            continue;
          }
          String data = line.substring("data:".length()).trim();
          if ("[DONE]".equals(data)) {
            completed = true;
            break;
          }
          if (data.isBlank()) {
            continue;
          }
          JsonNode event = objectMapper.readTree(data);
          if (event.has("error")) throw new IllegalStateException("模型响应中断，请重试");
          JsonNode choice = event.path("choices").path(0);
          if (choice.hasNonNull("finish_reason")) completed = true;
          JsonNode delta = choice.path("delta");
          String content = delta.path("content").asText("");
          if (!content.isEmpty()) {
            published[0] = true;
            answer.append(content);
            deltaConsumer.accept(content);
          }
          JsonNode tcNode = delta.path("tool_calls");
          if (tcNode.isArray() && !tcNode.isEmpty()) {
            published[0] = true;
          }
          toolCalls.accept(tcNode);
        }
      }
      if (!completed || !published[0]) {
        throw new IllegalStateException("模型响应未完整接收，请重试");
      }
      return new AgentTurn(answer.toString().trim(), toolCalls.complete().stream().map(this::parseToolCall).toList());
    });
  }

  public record AgentTurn(String content, List<ToolCall> toolCalls) {
  }

  @FunctionalInterface
  private interface StreamCallable<T> {
    T call(String model, boolean[] published) throws Exception;
  }

  private List<String> candidateModels() {
    String primary = properties.chatModel();
    String fallback = properties.effectiveFallbackChatModel();
    boolean skipPrimary = System.currentTimeMillis() < primaryUnavailableUntil.get();
    if (fallback == null || fallback.isBlank() || fallback.equals(primary)) {
      return List.of(primary);
    }
    return skipPrimary ? List.of(fallback) : List.of(primary, fallback);
  }

  private <T> T executeWithFallback(Function<String, T> action) {
    List<String> models = candidateModels();
    RuntimeException lastError = null;
    for (String model : models) {
      long started = System.nanoTime();
      try {
        T res = action.apply(model);
        if (model.equals(properties.chatModel())) {
          primaryUnavailableUntil.set(0L);
        }
        log.info("远程聊天完成 model={} fallback={} latencyMs={}",
            model, !model.equals(properties.chatModel()), (System.nanoTime() - started) / 1_000_000);
        return res;
      } catch (RuntimeException ex) {
        log.warn("远程聊天失败 model={} error={}", model, ex.getMessage());
        lastError = ex;
        if (model.equals(properties.chatModel())) {
          primaryUnavailableUntil.set(System.currentTimeMillis() + CIRCUIT_BREAKER_MS);
        }
      }
    }
    throw lastError != null ? lastError : new IllegalStateException("AI Compute chat request failed");
  }

  private <T> T executeStreamWithFallback(StreamCallable<T> action) {
    List<String> models = candidateModels();
    RuntimeException lastError = null;
    for (String model : models) {
      boolean[] published = {false};
      long started = System.nanoTime();
      try {
        T res = action.call(model, published);
        if (model.equals(properties.chatModel())) {
          primaryUnavailableUntil.set(0L);
        }
        log.info("远程流式聊天完成 model={} fallback={} latencyMs={}",
            model, !model.equals(properties.chatModel()), (System.nanoTime() - started) / 1_000_000);
        return res;
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("AI Compute streaming chat request was interrupted", ex);
      } catch (Exception ex) {
        log.warn("远程流式聊天失败 model={} published={} error={}", model, published[0], ex.getMessage());
        lastError = (ex instanceof RuntimeException re) ? re : new IllegalStateException("AI Compute streaming request failed", ex);
        if (published[0]) {
          throw lastError;
        }
        if (model.equals(properties.chatModel())) {
          primaryUnavailableUntil.set(System.currentTimeMillis() + CIRCUIT_BREAKER_MS);
        }
      }
    }
    throw lastError != null ? lastError : new IllegalStateException("AI Compute streaming request failed");
  }

  private void setAuth(org.springframework.http.HttpHeaders headers, String token) {
    if (token != null && !token.isBlank()) {
      headers.setBearerAuth(token);
    }
  }

  private JsonNode chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    return executeWithFallback(model -> {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", model);
      body.put("max_tokens", Math.max(2048, properties.maxAnswerTokens()));
      body.put("temperature", 0.2);
      body.put("modalities", List.of("text"));
      body.put("reasoning_effort", "low");
      body.put("messages", messages);
      if (!tools.isEmpty()) {
        body.put("tools", tools);
        body.put("tool_choice", "auto");
      } else {
        body.put("tools", List.of());
        body.put("tool_choice", "none");
      }
      JsonNode response = restClient.post()
          .uri(properties.aiComputeBaseUrl() + "/v1/chat/completions")
          .headers(headers -> setAuth(headers, properties.aiComputeToken()))
          .body(body)
          .retrieve()
          .body(JsonNode.class);
      JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
      if (message == null || message.isMissingNode() ||
          (message.path("content").asText("").isBlank() && message.path("tool_calls").isEmpty())) {
        throw new IllegalStateException("AI Compute chat response does not contain a message");
      }
      if (message instanceof ObjectNode obj) {
        obj.remove(List.of("images", "audio", "reasoning_content", "reasoning"));
      }
      return message;
    });
  }

  private Map<String, Object> assistantToolCallMessage(JsonNode message) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("role", "assistant");
    value.put("content", message.path("content").isMissingNode() ? "" : message.path("content").asText(""));
    value.put("tool_calls", objectMapper.convertValue(message.path("tool_calls"), List.class));
    return value;
  }

  private ToolCall parseToolCall(JsonNode node) {
    String id = node.path("id").asText("");
    JsonNode function = node.path("function");
    String name = function.path("name").asText("");
    String argumentsJson = function.path("arguments").asText("{}");
    Map<String, Object> arguments;
    try {
      arguments = objectMapper.readValue(argumentsJson, Map.class);
    } catch (Exception ex) {
      arguments = Map.of("raw", argumentsJson, "parseError", ex.getMessage());
    }
    return new ToolCall(id, name, arguments);
  }
}
