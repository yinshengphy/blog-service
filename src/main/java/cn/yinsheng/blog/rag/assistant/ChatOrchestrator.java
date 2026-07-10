package cn.yinsheng.blog.rag.assistant;

import cn.yinsheng.blog.rag.compute.AiComputeClient;
import cn.yinsheng.blog.rag.config.AssistantProperties;
import cn.yinsheng.blog.rag.model.ChatRequest;
import cn.yinsheng.blog.rag.model.ChatResponse;
import cn.yinsheng.blog.rag.model.Citation;
import cn.yinsheng.blog.rag.model.ImageAttachment;
import cn.yinsheng.blog.rag.model.PageContext;
import cn.yinsheng.blog.rag.model.RelatedPost;
import cn.yinsheng.blog.rag.tool.ToolCall;
import cn.yinsheng.blog.rag.tool.ToolDefinition;
import cn.yinsheng.blog.rag.tool.ToolExecutionContext;
import cn.yinsheng.blog.rag.tool.ToolExecutor;
import cn.yinsheng.blog.rag.tool.ToolRegistry;
import cn.yinsheng.blog.rag.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ChatOrchestrator {
  private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
  private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
  private static final int MAX_IMAGES = 3;
  private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_TOTAL_IMAGE_BYTES = 8 * 1024 * 1024;

  private final AiComputeClient aiComputeClient;
  private final ToolRegistry toolRegistry;
  private final ToolExecutor toolExecutor;
  private final AssistantProperties properties;
  private final AssistantSessionMemory sessionMemory;
  private final ModelRoutePlanner routePlanner;
  private final ObjectMapper objectMapper;
  private final String baseSystemPrompt;

  public ChatOrchestrator(
      AiComputeClient aiComputeClient,
      ToolRegistry toolRegistry,
      ToolExecutor toolExecutor,
      AssistantProperties properties,
      AssistantSessionMemory sessionMemory,
      ObjectMapper objectMapper,
      ModelRoutePlanner routePlanner
  ) {
    this.aiComputeClient = aiComputeClient;
    this.toolRegistry = toolRegistry;
    this.toolExecutor = toolExecutor;
    this.properties = properties;
    this.sessionMemory = sessionMemory;
    this.objectMapper = objectMapper;
    this.routePlanner = routePlanner;
    this.baseSystemPrompt = loadPrompt();
  }

  public ChatResponse answer(ChatRequest request) {
    return streamAnswer(request, response -> { }, delta -> { });
  }

  public ChatResponse answer(String question, String sessionId) {
    return answer(new ChatRequest(question, sessionId, null, List.of()));
  }

  public ChatResponse answer(String question) {
    return answer(question, null);
  }

  public ChatResponse streamAnswer(
      ChatRequest request,
      Consumer<ChatResponse> metaConsumer,
      Consumer<String> deltaConsumer
  ) {
    long startedAt = System.currentTimeMillis();
    String traceId = UUID.randomUUID().toString();
    List<ImageAttachment> images = validateImages(request.images());
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt()));
    messages.addAll(sessionMemory.history(request.sessionId()));
    String contextualQuestion = contextualQuestion(request.question(), request.pageContext());
    messages.add(userMessage(contextualQuestion, images));

    List<ToolDefinition> definitions = toolRegistry.definitions().stream().toList();
    List<String> usedTools = new ArrayList<>();
    List<Citation> citations = new ArrayList<>();
    List<RelatedPost> relatedPosts = new ArrayList<>();
    Map<String, Object> metadata = new LinkedHashMap<>();
    String answer = "";
    String errorCode = "";
    ModelRoutePlanner.RoutePlan routePlan = routePlanner.plan(request);
    metadata.put("route", routePlan.route().name());
    metaConsumer.accept(response("", "AGENT", usedTools, citations, relatedPosts, metadata));

    try {
      int loops = Math.max(1, Math.min(properties.getMaxToolCallsPerRequest(), 5));
      if (!routePlan.toolName().isBlank()) {
        Map<String, Object> arguments = new LinkedHashMap<>(routePlan.arguments());
        arguments.putIfAbsent("query", request.question());
        ToolCall plannedCall = new ToolCall("planned_" + traceId, routePlan.toolName(), Map.copyOf(arguments));
        messages.add(assistantToolMessage(new AiComputeClient.AgentTurn("", List.of(plannedCall))));
        ToolResult result = toolExecutor.execute(
            plannedCall,
            new ToolExecutionContext(traceId, request.sessionId(), request.pageContext())
        );
        usedTools.add(plannedCall.name());
        mergeCitations(citations, result.citations());
        mergeRelatedPosts(relatedPosts, result.relatedPosts());
        metadata.putAll(result.metadata());
        messages.add(toolResultMessage(result));
        metaConsumer.accept(response("", "TOOL", usedTools, citations, relatedPosts, metadata));
      }
      boolean nativeToolFallback = routePlan.route() == ModelRoutePlanner.Route.UNKNOWN;
      for (int loop = 0; loop <= loops; loop++) {
        List<ToolDefinition> availableTools = nativeToolFallback && usedTools.size() < loops ? definitions : List.of();
        AiComputeClient.AgentTurn turn = aiComputeClient.streamCompletion(messages, availableTools, deltaConsumer);
        if (turn.toolCalls().isEmpty()) {
          answer = turn.content();
          break;
        }
        messages.add(assistantToolMessage(turn));
        for (ToolCall call : turn.toolCalls()) {
          if (usedTools.size() >= loops) {
            messages.add(toolResultMessage(ToolResult.failure(call, "The tool call limit has been reached. Produce the best final answer from existing results.")));
            continue;
          }
          ToolResult result = toolExecutor.execute(call, new ToolExecutionContext(traceId, request.sessionId(), request.pageContext()));
          usedTools.add(call.name());
          mergeCitations(citations, result.citations());
          mergeRelatedPosts(relatedPosts, result.relatedPosts());
          metadata.putAll(result.metadata());
          messages.add(toolResultMessage(result));
        }
        metaConsumer.accept(response("", "TOOL", usedTools, citations, relatedPosts, metadata));
      }
      if (answer.isBlank()) {
        throw new IllegalStateException("Agent completed without an answer");
      }
      answer = sanitizeCitationMarkers(answer, citations.size());
      sessionMemory.remember(request.sessionId(), contextualQuestion, answer);
      return response(answer, usedTools.isEmpty() ? "DIRECT" : "TOOL", usedTools, citations, relatedPosts, metadata);
    } catch (RuntimeException ex) {
      errorCode = ex.getClass().getSimpleName();
      throw ex;
    } finally {
      log.info(
          "assistant_request traceId={} mode={} usedTools={} latencyMs={} imageCount={} pageSlug={} errorCode={}",
          traceId,
          usedTools.isEmpty() ? "DIRECT" : "TOOL",
          usedTools,
          System.currentTimeMillis() - startedAt,
          images.size(),
          request.pageContext() == null ? "" : request.pageContext().slug(),
          errorCode
      );
    }
  }

  public ChatResponse streamAnswer(String question, String sessionId, Consumer<ChatResponse> metaConsumer, Consumer<String> deltaConsumer) {
    return streamAnswer(new ChatRequest(question, sessionId, null, List.of()), metaConsumer, deltaConsumer);
  }

  public ChatResponse streamAnswer(String question, Consumer<ChatResponse> metaConsumer, Consumer<String> deltaConsumer) {
    return streamAnswer(question, null, metaConsumer, deltaConsumer);
  }

  public ChatResponse streamAnswer(String question, Consumer<String> deltaConsumer) {
    return streamAnswer(question, null, response -> { }, deltaConsumer);
  }

  private String systemPrompt() {
    StringBuilder capabilities = new StringBuilder(baseSystemPrompt).append("\n\nEnabled tools (authoritative):\n");
    for (ToolDefinition definition : toolRegistry.definitions()) {
      capabilities.append("- ").append(definition.name()).append(": ").append(definition.description()).append('\n');
    }
    return capabilities.toString();
  }

  private String contextualQuestion(String question, PageContext pageContext) {
    if (pageContext == null || !pageContext.isBlogPost()) return question.trim();
    try {
      return question.trim() + "\n\n<current_page_context>" + objectMapper.writeValueAsString(pageContext) + "</current_page_context>";
    } catch (Exception ex) {
      return question.trim();
    }
  }

  private Map<String, Object> userMessage(String text, List<ImageAttachment> images) {
    if (images.isEmpty()) return Map.of("role", "user", "content", text);
    List<Map<String, Object>> content = new ArrayList<>();
    content.add(Map.of("type", "text", "text", text));
    for (ImageAttachment image : images) {
      content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl(image))));
    }
    return Map.of("role", "user", "content", content);
  }

  private String dataUrl(ImageAttachment image) {
    if (image.data().startsWith("data:")) return image.data();
    return "data:" + image.mimeType() + ";base64," + image.data();
  }

  private List<ImageAttachment> validateImages(List<ImageAttachment> images) {
    if (images == null || images.isEmpty()) return List.of();
    if (images.size() > MAX_IMAGES) throw new IllegalArgumentException("Too many images");
    int total = 0;
    List<ImageAttachment> valid = new ArrayList<>();
    for (ImageAttachment image : images) {
      if (image == null || !IMAGE_TYPES.contains(image.mimeType()) || image.data() == null) {
        throw new IllegalArgumentException("Unsupported image");
      }
      String base64 = image.data().contains(",") ? image.data().substring(image.data().indexOf(',') + 1) : image.data();
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
      } catch (Exception ex) {
        throw new IllegalArgumentException("Invalid image data", ex);
      }
      if (decoded.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Image is too large");
      total += decoded.length;
      valid.add(image);
    }
    if (total > MAX_TOTAL_IMAGE_BYTES) throw new IllegalArgumentException("Total image size is too large");
    return List.copyOf(valid);
  }

  private Map<String, Object> assistantToolMessage(AiComputeClient.AgentTurn turn) {
    List<Map<String, Object>> calls = turn.toolCalls().stream().map(call -> Map.<String, Object>of(
        "id", call.id(),
        "type", "function",
        "function", Map.of("name", call.name(), "arguments", json(call.arguments()))
    )).toList();
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "assistant");
    message.put("content", turn.content());
    message.put("tool_calls", calls);
    return message;
  }

  private Map<String, Object> toolResultMessage(ToolResult result) {
    return Map.of(
        "role", "tool",
        "tool_call_id", result.toolCallId(),
        "name", result.name(),
        "content", result.success() ? result.content() : "TOOL_ERROR: " + result.content()
    );
  }

  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; }
  }

  private String sanitizeCitationMarkers(String answer, int citationCount) {
    return java.util.regex.Pattern.compile("\\[(\\d+)]").matcher(answer).replaceAll(match -> {
      int index = Integer.parseInt(match.group(1));
      return index >= 1 && index <= citationCount ? match.group() : "";
    }).replaceAll("[ \\t]+(?=\\r?$)", "").trim();
  }

  private void mergeCitations(List<Citation> target, List<Citation> values) {
    Set<String> keys = new LinkedHashSet<>();
    target.forEach(item -> keys.add(item.url() + "#" + item.section()));
    for (Citation item : values) {
      if (keys.add(item.url() + "#" + item.section())) target.add(item);
    }
  }

  private void mergeRelatedPosts(List<RelatedPost> target, List<RelatedPost> values) {
    Set<String> keys = new LinkedHashSet<>();
    target.forEach(item -> keys.add(item.url()));
    for (RelatedPost item : values) if (keys.add(item.url())) target.add(item);
  }

  private ChatResponse response(
      String answer,
      String mode,
      List<String> usedTools,
      List<Citation> citations,
      List<RelatedPost> relatedPosts,
      Map<String, Object> metadata
  ) {
    return new ChatResponse(answer, List.copyOf(citations), List.copyOf(relatedPosts), mode, "MODEL_ROUTED", List.of(), List.copyOf(usedTools), Map.copyOf(metadata));
  }

  private String loadPrompt() {
    try {
      return new ClassPathResource("prompts/agent-system.md").getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to load assistant system prompt", ex);
    }
  }
}
