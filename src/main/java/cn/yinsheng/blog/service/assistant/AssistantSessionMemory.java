package cn.yinsheng.blog.service.assistant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import cn.yinsheng.blog.service.config.AssistantProperties;
import cn.yinsheng.blog.service.model.ConversationMessage;
import cn.yinsheng.blog.service.model.Citation;
import cn.yinsheng.blog.service.model.PageContext;
import org.springframework.stereotype.Component;

@Component
public class AssistantSessionMemory {
  private final AssistantProperties properties;
  private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

  public AssistantSessionMemory(AssistantProperties properties) {
    this.properties = properties;
  }

  public List<Map<String, Object>> history(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) return List.of();
    SessionState state = sessions.get(sessionId);
    if (state == null || state.expiresAt().isBefore(Instant.now())) {
      sessions.remove(sessionId);
      return List.of();
    }
    return List.copyOf(state.messages());
  }

  public void remember(String sessionId, String userMessage, String assistantMessage) {
    if (sessionId == null || sessionId.isBlank() || assistantMessage == null || assistantMessage.isBlank()) return;
    List<Map<String, Object>> messages = new ArrayList<>(history(sessionId));
    messages.add(Map.of("role", "user", "content", compact(userMessage)));
    messages.add(Map.of("role", "assistant", "content", compact(assistantMessage)));
    int maxMessages = Math.max(2, properties.getSessionMaxMessages());
    if (messages.size() > maxMessages) {
      messages = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }
    removeExpired();
    if (sessions.size() >= Math.max(10, properties.getSessionMaxEntries()) && !sessions.containsKey(sessionId)) {
      sessions.entrySet().stream()
          .min(Map.Entry.comparingByValue(java.util.Comparator.comparing(SessionState::expiresAt)))
          .map(Map.Entry::getKey)
          .ifPresent(sessions::remove);
    }
    sessions.put(sessionId, new SessionState(
        List.copyOf(messages),
        Instant.now().plus(Math.max(1, properties.getSessionTtlMinutes()), ChronoUnit.MINUTES),
        context(sessionId)
    ));
  }

  public List<Map<String, Object>> normalize(List<ConversationMessage> messages) {
    if (messages == null || messages.isEmpty()) return List.of();
    int maxMessages = Math.max(2, properties.getSessionMaxMessages());
    List<ConversationMessage> tail = messages.size() <= maxMessages
        ? messages
        : messages.subList(messages.size() - maxMessages, messages.size());
    List<Map<String, Object>> normalized = new ArrayList<>();
    for (ConversationMessage message : tail) {
      if (message == null || message.content() == null || message.content().isBlank()) continue;
      String role = "assistant".equalsIgnoreCase(message.role()) ? "assistant" : "user";
      normalized.add(Map.of("role", role, "content", compact(message.content())));
    }
    return List.copyOf(normalized);
  }

  public void clear(String sessionId) {
    if (sessionId != null) sessions.remove(sessionId);
  }

  public Map<String, Object> context(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) return Map.of();
    SessionState state = sessions.get(sessionId);
    return state == null || state.expiresAt().isBefore(Instant.now()) ? Map.of() : state.context();
  }

  public void rememberContext(String sessionId, PageContext page, List<Citation> citations,
      Map<String, Object> metadata) {
    if (sessionId == null || sessionId.isBlank()) return;
    sessions.computeIfPresent(sessionId, (id, state) -> {
      Map<String, Object> values = new java.util.LinkedHashMap<>(state.context());
      if (page != null && page.isBlogPost()) values.put("lastReadPost", page);
      if (!citations.isEmpty()) values.put("recentSources", citations.stream().limit(8)
          .map(c -> Map.of("title", c.title(), "section", c.section(), "url", c.url())).toList());
      if (metadata.containsKey("slug")) values.put("resolvedSlug", metadata.get("slug"));
      if (metadata.containsKey("recentPosts")) values.put("recentPosts", metadata.get("recentPosts"));
      return new SessionState(state.messages(), state.expiresAt(), Map.copyOf(values));
    });
  }

  private String compact(String value) {
    if (value == null) return "";
    int maxChars = Math.max(100, properties.getSessionMaxMessageChars());
    return value.length() <= maxChars ? value : value.substring(0, maxChars);
  }

  private void removeExpired() {
    Instant now = Instant.now();
    sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
  }

  private record SessionState(List<Map<String, Object>> messages, Instant expiresAt, Map<String, Object> context) {
  }
}
