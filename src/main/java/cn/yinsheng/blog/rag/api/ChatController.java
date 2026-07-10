package cn.yinsheng.blog.rag.api;

import cn.yinsheng.blog.rag.assistant.ChatOrchestrator;
import cn.yinsheng.blog.rag.chat.ChatBusyException;
import cn.yinsheng.blog.rag.chat.ChatLimiter;
import cn.yinsheng.blog.rag.model.ChatRequest;
import cn.yinsheng.blog.rag.model.ChatResponse;
import cn.yinsheng.blog.rag.ratelimit.RateLimitExceededException;
import cn.yinsheng.blog.rag.ratelimit.RateLimitService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatController {
  private static final Logger log = LoggerFactory.getLogger(ChatController.class);
  private static final long SSE_TIMEOUT_MS = 180_000L;

  private final ChatOrchestrator chatOrchestrator;
  private final ChatLimiter chatLimiter;
  private final RateLimitService rateLimitService;
  private final ExecutorService streamExecutor = new ThreadPoolExecutor(
      4,
      4,
      0,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(16),
      new ThreadPoolExecutor.AbortPolicy()
  );

  public ChatController(ChatOrchestrator chatOrchestrator, ChatLimiter chatLimiter, RateLimitService rateLimitService) {
    this.chatOrchestrator = chatOrchestrator;
    this.chatLimiter = chatLimiter;
    this.rateLimitService = rateLimitService;
  }

  @PostMapping("/api/chat")
  public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
    rateLimitService.checkAndRecord(clientIp(httpRequest));
    if (!chatLimiter.tryEnter()) throw new ChatBusyException("当前请求较多，请稍后再试。");
    try {
      return chatOrchestrator.answer(request);
    } finally {
      chatLimiter.leave();
    }
  }

  @PostMapping("/api/chat/stream")
  public SseEmitter chatStream(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
    rateLimitService.checkAndRecord(clientIp(httpRequest));
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    AtomicBoolean finished = new AtomicBoolean();
    AtomicReference<Future<?>> taskRef = new AtomicReference<>();
    Runnable cancelTask = () -> {
      if (finished.compareAndSet(false, true)) {
        Future<?> task = taskRef.get();
        if (task != null) task.cancel(true);
      }
    };
    emitter.onTimeout(cancelTask);
    emitter.onError(ignored -> cancelTask.run());
    emitter.onCompletion(() -> {
      if (!finished.get()) cancelTask.run();
    });
    try {
      Future<?> task = streamExecutor.submit(() -> {
      long startedAt = System.currentTimeMillis();
      boolean acquired = false;
      try {
        acquired = chatLimiter.tryEnter();
        if (!acquired) throw new ChatBusyException("当前请求较多，请稍后再试。");
        ChatResponse response = chatOrchestrator.streamAnswer(
            request,
            meta -> sendMeta(emitter, meta),
            delta -> sendDelta(emitter, delta)
        );
        emitter.send(SseEmitter.event().name("citations").data(response.citations()));
        emitter.send(SseEmitter.event().name("relatedPosts").data(response.relatedPosts()));
        emitter.send(SseEmitter.event()
            .name("done")
            .data(Map.of("elapsedMs", System.currentTimeMillis() - startedAt)));
        finished.set(true);
        emitter.complete();
      } catch (ChatBusyException ex) {
        sendError(emitter, HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage());
      } catch (Exception ex) {
        log.error("RAG stream failed", ex);
        sendError(emitter, HttpStatus.INTERNAL_SERVER_ERROR.value(), "聊天服务暂时不可用，请稍后再试。");
      } finally {
        if (acquired) chatLimiter.leave();
      }
      });
      taskRef.set(task);
      if (finished.get() && !task.isDone()) task.cancel(true);
    } catch (RejectedExecutionException ex) {
      finished.set(true);
      sendError(emitter, HttpStatus.TOO_MANY_REQUESTS.value(), "当前请求较多，请稍后再试。");
    }
    return emitter;
  }

  private String clientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    String realIp = request.getHeader("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) {
      return realIp.trim();
    }
    return request.getRemoteAddr();
  }

  @GetMapping("/internal/status")
  public Map<String, Object> status() {
    return Map.of(
        "chatBusy", chatLimiter.isBusy(),
        "activeChats", chatLimiter.activeChats()
    );
  }

  private void sendDelta(SseEmitter emitter, String text) {
    try {
      emitter.send(SseEmitter.event()
          .name("delta")
          .data(Map.of("text", text)));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to send chat delta", ex);
    }
  }

  private void sendMeta(SseEmitter emitter, ChatResponse response) {
    try {
      emitter.send(SseEmitter.event()
          .name("meta")
          .data(Map.of(
              "mode", response.mode() == null ? "" : response.mode(),
              "intent", response.intent() == null ? "" : response.intent(),
              "usedSkills", response.usedSkills(),
              "usedTools", response.usedTools()
          )));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to send chat meta", ex);
    }
  }

  private void sendError(SseEmitter emitter, int status, String message) {
    try {
      emitter.send(SseEmitter.event()
          .name("error")
          .data(Map.of("status", status, "message", message)));
    } catch (Exception ignored) {
      // 连接关闭过程中发送失败可以忽略。
    } finally {
      emitter.complete();
    }
  }

  @ExceptionHandler(ChatBusyException.class)
  public ResponseEntity<Map<String, String>> handleBusy(ChatBusyException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(Map.of("message", ex.getMessage()));
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, String>> handleRateLimit(RateLimitExceededException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(Map.of("message", ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
    return ResponseEntity.badRequest().body(Map.of("message", "问题不能为空。"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handleError(Exception ex) {
    log.error("RAG chat failed", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("message", "聊天服务暂时不可用，请稍后再试。"));
  }
}
