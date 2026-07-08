package cn.yinsheng.blog.rag.compute;

import cn.yinsheng.blog.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AiComputeClient {
  private final RestClient restClient;
  private final RagProperties properties;
  private final ObjectMapper objectMapper;

  public AiComputeClient(RestClient restClient, RagProperties properties, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public List<Double> embed(String text) {
    JsonNode response = restClient.post()
        .uri(properties.aiComputeBaseUrl() + "/v1/embeddings")
        .headers(headers -> setAuth(headers, properties.aiComputeToken()))
        .body(Map.of("model", properties.embeddingModel(), "input", List.of(text)))
        .retrieve()
        .body(JsonNode.class);

    JsonNode vector = response == null ? null : response.path("data").path(0).path("embedding");
    if (vector == null || !vector.isArray()) {
      throw new IllegalStateException("AI Compute embedding response does not contain a vector");
    }
    List<Double> values = new ArrayList<>(vector.size());
    vector.forEach(value -> values.add(value.asDouble()));
    return values;
  }

  public String chat(String systemPrompt, String userPrompt) {
    JsonNode response = restClient.post()
        .uri(properties.aiComputeBaseUrl() + "/v1/chat/completions")
        .headers(headers -> setAuth(headers, properties.aiComputeToken()))
        .body(Map.of(
            "model", properties.chatModel(),
            "max_tokens", properties.maxAnswerTokens(),
            "temperature", 0.2,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            )
        ))
        .retrieve()
        .body(JsonNode.class);

    String content = response == null ? "" : response.path("choices").path(0).path("message").path("content").asText();
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("AI Compute chat response is empty");
    }
    return content.trim();
  }

  public String chatStream(String systemPrompt, String userPrompt, Consumer<String> deltaConsumer) {
    StringBuilder answer = new StringBuilder();
    restClient.post()
        .uri(properties.aiComputeBaseUrl() + "/v1/chat/completions")
        .headers(headers -> setAuth(headers, properties.aiComputeToken()))
        .body(Map.of(
            "model", properties.chatModel(),
            "stream", true,
            "max_tokens", properties.maxAnswerTokens(),
            "temperature", 0.2,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            )
        ))
        .exchange((httpRequest, httpResponse) -> {
          try (BufferedReader reader = new BufferedReader(
              new InputStreamReader(httpResponse.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (!line.startsWith("data:")) {
                continue;
              }
              String data = line.substring("data:".length()).trim();
              if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
              }
              JsonNode node = objectMapper.readTree(data);
              String delta = node.path("choices").path(0).path("delta").path("content").asText("");
              if (!delta.isEmpty()) {
                answer.append(delta);
                deltaConsumer.accept(delta);
              }
            }
          }
          return null;
        });
    String value = answer.toString().trim();
    if (value.isBlank()) {
      throw new IllegalStateException("AI Compute streaming chat response is empty");
    }
    return value;
  }

  private void setAuth(org.springframework.http.HttpHeaders headers, String token) {
    if (token != null && !token.isBlank()) {
      headers.setBearerAuth(token);
    }
  }
}
