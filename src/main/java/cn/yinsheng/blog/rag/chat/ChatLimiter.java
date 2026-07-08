package cn.yinsheng.blog.rag.chat;

import cn.yinsheng.blog.rag.config.RagProperties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ChatLimiter {
  private final Semaphore semaphore;
  private final AtomicInteger activeChats = new AtomicInteger();
  private final long queueTimeoutMs;

  public ChatLimiter(RagProperties properties) {
    this.semaphore = new Semaphore(Math.max(1, properties.chatConcurrency()));
    this.queueTimeoutMs = Math.max(0, properties.chatQueueTimeoutMs());
  }

  public boolean tryEnter() {
    try {
      boolean acquired = semaphore.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
      if (acquired) {
        activeChats.incrementAndGet();
      }
      return acquired;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public void leave() {
    activeChats.updateAndGet(value -> Math.max(0, value - 1));
    semaphore.release();
  }

  public boolean isBusy() {
    return activeChats.get() > 0 || semaphore.availablePermits() == 0;
  }

  public int activeChats() {
    return activeChats.get();
  }
}
