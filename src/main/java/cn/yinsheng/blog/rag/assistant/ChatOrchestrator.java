package cn.yinsheng.blog.rag.assistant;

import cn.yinsheng.blog.rag.compute.AiComputeClient;
import cn.yinsheng.blog.rag.config.AssistantProperties;
import cn.yinsheng.blog.rag.model.ChatRequest;
import cn.yinsheng.blog.rag.model.ChatResponse;
import cn.yinsheng.blog.rag.model.Citation;
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
    return answer(new ChatRequest(question, sessionId, null));
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
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt()));
    List<Map<String, Object>> history = sessionMemory.history(request.sessionId());
    messages.addAll(history);
    String contextualQuestion = contextualQuestion(request.question(), request.pageContext());
    messages.add(Map.of("role", "user", "content", contextualQuestion));

    List<ToolDefinition> definitions = toolRegistry.definitions().stream().toList();
    List<String> usedTools = new ArrayList<>();
    List<Citation> citations = new ArrayList<>();
    List<RelatedPost> relatedPosts = new ArrayList<>();
    Map<String, Object> metadata = new LinkedHashMap<>();
    String answer = "";
    String errorCode = "";
    ModelRoutePlanner.RoutePlan routePlan = routePlanner.plan(request, history);
    metadata.put("route", routePlan.route().name());
    metaConsumer.accept(response("", "AGENT", usedTools, citations, relatedPosts, metadata));

    try {
      String fixedAnswer = fixedAnswer(routePlan.route());
      if (!fixedAnswer.isBlank()) {
        deltaConsumer.accept(fixedAnswer);
        sessionMemory.remember(request.sessionId(), contextualQuestion, fixedAnswer);
        return response(fixedAnswer, "DIRECT", usedTools, citations, relatedPosts, metadata);
      }
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
          "assistant_request traceId={} intent={} mode={} usedTools={} latencyMs={} pageSlug={} errorCode={}",
          traceId,
          routePlan.route(),
          usedTools.isEmpty() ? "DIRECT" : "TOOL",
          usedTools,
          System.currentTimeMillis() - startedAt,
          request.pageContext() == null ? "" : request.pageContext().slug(),
          errorCode
      );
    }
  }

  public ChatResponse streamAnswer(String question, String sessionId, Consumer<ChatResponse> metaConsumer, Consumer<String> deltaConsumer) {
    return streamAnswer(new ChatRequest(question, sessionId, null), metaConsumer, deltaConsumer);
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

  private String fixedAnswer(ModelRoutePlanner.Route route) {
    if (route == ModelRoutePlanner.Route.CAPABILITY) {
      StringBuilder answer = new StringBuilder("我目前启用的站点能力有：\n");
      for (ToolDefinition definition : toolRegistry.definitions()) {
        answer.append("- ").append(definition.description()).append('\n');
      }
      return answer.append("此外，我可以直接进行普通聊天、技术问答、翻译、润色、计算和文本整理。当前不支持图片识别和公共网页搜索。").toString();
    }
    if (route == ModelRoutePlanner.Route.UNSUPPORTED) {
      return "当前未启用图片识别、公共网页搜索或服务器命令执行。我可以继续协助博客问答、全文摘要、天气查询和普通文本问题。";
    }
    return "";
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
    String intent = String.valueOf(metadata.getOrDefault("route", "UNKNOWN"));
    return new ChatResponse(answer, List.copyOf(citations), List.copyOf(relatedPosts), mode, intent, List.of(), List.copyOf(usedTools), Map.copyOf(metadata));
  }

  private String loadPrompt() {
    try {
      return new ClassPathResource("prompts/agent-system.md").getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to load assistant system prompt", ex);
    }
  }
}
