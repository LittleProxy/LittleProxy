package org.littleshoot.proxy.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;

public class ThreadDumpExtension
    implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
  private static final Namespace NAMESPACE = Namespace.create("ThreadDumpExtension");
  private static final int INITIAL_DELAY_MS = 8000;
  private static final int DELAY_MS = 1000;

  @Override
  public void beforeAll(ExtensionContext context) {
    getLogger(context.getDisplayName()).info("Starting tests ({})", memory());
  }

  @Override
  public void afterAll(ExtensionContext context) {
    getLogger(context.getDisplayName())
        .info("Finished tests - {} ({})", verdict(context), memory());
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    Logger log = logger(context);
    log.info("starting {} ({})...", context.getDisplayName(), memory());
    ScheduledExecutorService executor = newScheduledThreadPool(1);
    executor.scheduleWithFixedDelay(
        () -> takeThreadDump(log), initialDelayMs(context), DELAY_MS, MILLISECONDS);

    context.getStore(NAMESPACE).put("executor", executor);

    String originalThreadName = Thread.currentThread().getName();
    String newThreadName =
        String.format(
            "%s-%s-%s",
            originalThreadName,
            context.getTestClass().map(Class::getName).orElse(""),
            context.getDisplayName());
    Thread.currentThread().setName(newThreadName);
    context.getStore(NAMESPACE).put("originalThreadName", originalThreadName);
  }

  private int initialDelayMs(ExtensionContext context) {
    return context
        .getTestMethod()
        .map(method -> method.getAnnotation(Timeout.class))
        .map(timeout -> (int) timeout.unit().toMillis(timeout.value()) - 3000)
        .map(timeout -> Math.max(timeout, 1000))
        .orElse(INITIAL_DELAY_MS);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    logger(context)
        .info("finished {} - {} ({})", context.getDisplayName(), verdict(context), memory());
    String originalThreadName = context.getStore(NAMESPACE).get("originalThreadName", String.class);
    Thread.currentThread().setName(originalThreadName);
    ScheduledExecutorService executor =
        (ScheduledExecutorService) context.getStore(NAMESPACE).remove("executor");
    executor.shutdown();
  }

  private static Logger logger(ExtensionContext context) {
    return getLogger(
        context.getRequiredTestClass().getSimpleName()
            + '.'
            + context.getTestMethod().map(Method::getName).orElse("?"));
  }

  private String verdict(ExtensionContext context) {
    return context.getExecutionException().isPresent()
        ? (context.getExecutionException().get() instanceof TestAbortedException
            ? "skipped"
            : "NOK")
        : "OK";
  }

  private String memory() {
    long freeMemory = Runtime.getRuntime().freeMemory();
    long maxMemory = Runtime.getRuntime().maxMemory();
    long totalMemory = Runtime.getRuntime().totalMemory();
    long usedMemory = totalMemory - freeMemory;
    return "memory used:"
        + mb(usedMemory)
        + ", free:"
        + mb(freeMemory)
        + ", total:"
        + mb(totalMemory)
        + ", max:"
        + mb(maxMemory);
  }

  private long mb(long bytes) {
    return bytes / 1024 / 1024;
  }

  private void takeThreadDump(Logger log) {
    StringBuilder threadDump = new StringBuilder();
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    for (ThreadInfo threadInfo : threadMXBean.dumpAllThreads(true, true, 50)) {
      threadDump.append(threadInfo.toString());
    }
    File dump = new File("target/thread-dump-" + System.currentTimeMillis() + ".txt");
    try {
      FileUtils.writeStringToFile(dump, threadDump.toString(), UTF_8);
      log.info("Saved thread dump to file {}", dump);
    } catch (IOException e) {
      log.error("Failed to save thread dump to file {}", dump, e);
    }
  }
}
