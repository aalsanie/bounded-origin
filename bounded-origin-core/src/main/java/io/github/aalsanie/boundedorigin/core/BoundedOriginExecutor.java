package io.github.aalsanie.boundedorigin.core;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.ExecutionStrategy;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.Materializer;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class BoundedOriginExecutor implements AutoCloseable {
  private final Object stateLock = new Object();
  private final Budget globalBudget;
  private final long failureCooldownNanos;
  private final int maxCooldownEntries;
  private final LongSupplier nanoTime;
  private final ThreadFactory workerThreads;
  private final ThreadFactory timeoutThreads;
  private final Map<OperationKey, Job> inFlight = new HashMap<>();
  private final Map<String, ArrayDeque<Job>> queuedByPolicy = new HashMap<>();
  private final ArrayDeque<String> queueRotation = new ArrayDeque<>();
  private final Map<String, Integer> activeByPolicy = new HashMap<>();
  private final LinkedHashMap<OperationKey, Long> cooldowns = new LinkedHashMap<>();

  private int activeJobs;
  private int queuedJobs;
  private boolean closed;

  public BoundedOriginExecutor(
      Budget globalBudget, Duration failureCooldown, int maxCooldownEntries) {
    this(globalBudget, failureCooldown, maxCooldownEntries, System::nanoTime);
  }

  BoundedOriginExecutor(
      Budget globalBudget,
      Duration failureCooldown,
      int maxCooldownEntries,
      LongSupplier nanoTime) {
    this(
        globalBudget,
        failureCooldown,
        maxCooldownEntries,
        nanoTime,
        Thread.ofVirtual().name("bounded-origin-worker-", 0).factory(),
        Thread.ofVirtual().name("bounded-origin-timeout-", 0).factory());
  }

  BoundedOriginExecutor(
      Budget globalBudget,
      Duration failureCooldown,
      int maxCooldownEntries,
      LongSupplier nanoTime,
      ThreadFactory workerThreads,
      ThreadFactory timeoutThreads) {
    this.globalBudget = Objects.requireNonNull(globalBudget, "globalBudget");
    Objects.requireNonNull(failureCooldown, "failureCooldown");
    if (failureCooldown.isZero() || failureCooldown.isNegative()) {
      throw new IllegalArgumentException("failureCooldown must be positive");
    }
    try {
      this.failureCooldownNanos = failureCooldown.toNanos();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("failureCooldown is too large", exception);
    }
    if (maxCooldownEntries <= 0) {
      throw new IllegalArgumentException("maxCooldownEntries must be positive");
    }
    this.maxCooldownEntries = maxCooldownEntries;
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.workerThreads = Objects.requireNonNull(workerThreads, "workerThreads");
    this.timeoutThreads = Objects.requireNonNull(timeoutThreads, "timeoutThreads");
  }

  public CompletionStage<Artifact> execute(
      OriginDecision.Selected decision, Materializer materializer) {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(materializer, "materializer");

    OriginPolicy policy = decision.policy();
    ExecutionStrategy strategy = policy.strategy();
    if (strategy != ExecutionStrategy.BOUNDED_COMPUTE
        && strategy != ExecutionStrategy.MATERIALIZE) {
      throw new IllegalArgumentException(
          "execution requires BOUNDED_COMPUTE or MATERIALIZE strategy");
    }

    Budget policyBudget =
        policy
            .budget()
            .orElseThrow(() -> new IllegalArgumentException("execution policy requires a budget"));
    OperationKey key = decision.operationKey();
    String policyId = policy.id();

    Job startNow = null;
    CompletionStage<Artifact> stage;
    synchronized (stateLock) {
      if (closed) {
        return failedStage(failure(OriginExecutionFailure.CLOSED));
      }

      Job existing = inFlight.get(key);
      if (existing != null) {
        return existing.stage;
      }

      pruneExpiredCooldownsLocked();
      if (isCoolingDownLocked(key)) {
        return failedStage(failure(OriginExecutionFailure.COOLDOWN));
      }

      Duration timeout = effectiveTimeout(policyBudget);
      long maxResultBytes = effectiveMaxResultBytes(policyBudget);
      if (canStartLocked(policyId, policyBudget)) {
        Job job = new Job(decision, materializer, policyBudget, timeout, maxResultBytes);
        inFlight.put(key, job);
        reserveActiveLocked(job);
        startNow = job;
        stage = job.stage;
      } else {
        OriginExecutionFailure queueFailure = queueFailureLocked(policyId, policyBudget);
        if (queueFailure != null) {
          return failedStage(failure(queueFailure));
        }

        Job job = new Job(decision, materializer, policyBudget, timeout, maxResultBytes);
        inFlight.put(key, job);
        enqueueLocked(job);
        stage = job.stage;
      }
    }

    if (startNow != null) {
      startJob(startNow);
    }
    return stage;
  }

  @Override
  public void close() {
    List<Job> queuedToFail = new ArrayList<>();
    List<Job> activeToStop = new ArrayList<>();

    synchronized (stateLock) {
      if (closed) {
        return;
      }
      closed = true;
      cooldowns.clear();

      for (Job job : inFlight.values()) {
        if (job.queued) {
          job.queued = false;
          queuedToFail.add(job);
        } else if (job.active) {
          if (job.termination.compareAndSet(Termination.NONE, Termination.CLOSED)) {
            activeToStop.add(job);
          }
        }
      }

      queuedByPolicy.clear();
      queueRotation.clear();
      queuedJobs = 0;
      inFlight.entrySet().removeIf(entry -> !entry.getValue().active);
    }

    for (Job job : activeToStop) {
      interrupt(job.workerThread);
      interrupt(job.timeoutThread);
    }
    for (Job job : queuedToFail) {
      job.result.completeExceptionally(failure(OriginExecutionFailure.CLOSED));
    }
    for (Job job : activeToStop) {
      job.result.completeExceptionally(failure(OriginExecutionFailure.CLOSED));
    }
  }

  int activeJobs() {
    synchronized (stateLock) {
      return activeJobs;
    }
  }

  int queuedJobs() {
    synchronized (stateLock) {
      return queuedJobs;
    }
  }

  int inFlightJobs() {
    synchronized (stateLock) {
      return inFlight.size();
    }
  }

  int cooldownEntries() {
    synchronized (stateLock) {
      pruneExpiredCooldownsLocked();
      return cooldowns.size();
    }
  }

  OriginExecutorStats snapshotStats() {
    synchronized (stateLock) {
      pruneExpiredCooldownsLocked();
      return new OriginExecutorStats(activeJobs, queuedJobs, inFlight.size(), cooldowns.size());
    }
  }

  int activeJobs(String policyId) {
    Objects.requireNonNull(policyId, "policyId");
    synchronized (stateLock) {
      return activeByPolicy.getOrDefault(policyId, 0);
    }
  }

  int queuedJobs(String policyId) {
    Objects.requireNonNull(policyId, "policyId");
    synchronized (stateLock) {
      ArrayDeque<Job> queue = queuedByPolicy.get(policyId);
      return queue == null ? 0 : queue.size();
    }
  }

  private void startJob(Job job) {
    Thread worker;
    Thread timeout;
    try {
      worker = Objects.requireNonNull(workerThreads.newThread(() -> runJob(job)), "worker thread");
      timeout =
          Objects.requireNonNull(
              timeoutThreads.newThread(() -> watchTimeout(job)), "timeout thread");
    } catch (RuntimeException | Error throwable) {
      handleStartFailure(job, throwable, false);
      return;
    }

    job.workerThread = worker;
    job.timeoutThread = timeout;

    if (job.termination.get() != Termination.NONE) {
      startJobs(finishActive(job, false));
      return;
    }

    try {
      worker.start();
    } catch (RuntimeException | Error throwable) {
      handleStartFailure(job, throwable, false);
      return;
    }

    try {
      timeout.start();
    } catch (RuntimeException | Error throwable) {
      handleStartFailure(job, throwable, true);
    }
  }

  private void runJob(Job job) {
    if (job.termination.get() != Termination.NONE) {
      List<Job> starts = finishActive(job, false);
      startJobs(starts);
      return;
    }

    WorkOutcome outcome;
    try {
      Artifact artifact =
          Objects.requireNonNull(
              job.materializer.materialize(job.decision.operation()), "materializer result");
      if (artifact.contentLength() > job.maxResultBytes) {
        outcome = WorkOutcome.failed(failure(OriginExecutionFailure.RESULT_TOO_LARGE));
      } else {
        outcome = WorkOutcome.succeeded(artifact);
      }
    } catch (RuntimeException | MaterializationException exception) {
      outcome =
          WorkOutcome.failed(failure(OriginExecutionFailure.MATERIALIZATION_FAILED, exception));
    } catch (Error error) {
      outcome =
          WorkOutcome.fatal(failure(OriginExecutionFailure.MATERIALIZATION_FAILED, error), error);
    }

    if (job.termination.compareAndSet(Termination.NONE, Termination.COMPLETED)) {
      List<Job> starts = finishActive(job, outcome.failure != null);
      interrupt(job.timeoutThread);
      startJobs(starts);
      if (outcome.failure == null) {
        job.result.complete(outcome.artifact);
      } else {
        job.result.completeExceptionally(outcome.failure);
      }
      if (outcome.fatal != null) {
        throw outcome.fatal;
      }
      return;
    }

    Termination termination = job.termination.get();
    boolean recordCooldown =
        termination == Termination.TIMEOUT || termination == Termination.INTERNAL_ERROR;
    List<Job> starts = finishActive(job, recordCooldown);
    interrupt(job.timeoutThread);
    startJobs(starts);
    if (outcome.fatal != null) {
      throw outcome.fatal;
    }
  }

  private void watchTimeout(Job job) {
    try {
      Thread.sleep(job.timeout);
    } catch (InterruptedException exception) {
      return;
    }

    if (job.termination.compareAndSet(Termination.NONE, Termination.TIMEOUT)) {
      interrupt(job.workerThread);
      job.result.completeExceptionally(failure(OriginExecutionFailure.TIMEOUT));
    }
  }

  private void handleStartFailure(Job job, Throwable throwable, boolean workerStarted) {
    boolean internalFailure =
        job.termination.compareAndSet(Termination.NONE, Termination.INTERNAL_ERROR);

    if (workerStarted) {
      if (internalFailure) {
        interrupt(job.workerThread);
        job.result.completeExceptionally(failure(OriginExecutionFailure.INTERNAL_ERROR, throwable));
      }
      return;
    }

    List<Job> starts = finishActive(job, internalFailure);
    if (internalFailure) {
      job.result.completeExceptionally(failure(OriginExecutionFailure.INTERNAL_ERROR, throwable));
    }
    startJobs(starts);
  }

  private List<Job> finishActive(Job job, boolean recordCooldown) {
    synchronized (stateLock) {
      if (!job.active) {
        return List.of();
      }

      job.active = false;
      activeJobs--;
      decrementActivePolicyLocked(job.policyId);
      inFlight.remove(job.key, job);
      if (recordCooldown && !closed) {
        recordCooldownLocked(job.key);
      }
      return drainQueuedLocked();
    }
  }

  private void startJobs(List<Job> jobs) {
    for (Job job : jobs) {
      startJob(job);
    }
  }

  private boolean canStartLocked(String policyId, Budget policyBudget) {
    return activeJobs < globalBudget.maxActive()
        && activeByPolicy.getOrDefault(policyId, 0) < policyBudget.maxActive();
  }

  private OriginExecutionFailure queueFailureLocked(String policyId, Budget policyBudget) {
    if (queuedJobs >= globalBudget.maxQueued()) {
      return OriginExecutionFailure.GLOBAL_QUEUE_LIMIT;
    }

    ArrayDeque<Job> policyQueue = queuedByPolicy.get(policyId);
    int policyQueued = policyQueue == null ? 0 : policyQueue.size();
    if (policyQueued >= policyBudget.maxQueued()) {
      return OriginExecutionFailure.POLICY_QUEUE_LIMIT;
    }
    return null;
  }

  private void reserveActiveLocked(Job job) {
    job.active = true;
    activeJobs++;
    activeByPolicy.merge(job.policyId, 1, Integer::sum);
  }

  private void enqueueLocked(Job job) {
    ArrayDeque<Job> queue =
        queuedByPolicy.computeIfAbsent(job.policyId, ignored -> new ArrayDeque<>());
    boolean wasEmpty = queue.isEmpty();
    queue.addLast(job);
    job.queued = true;
    queuedJobs++;
    if (wasEmpty) {
      queueRotation.addLast(job.policyId);
    }
  }

  private List<Job> drainQueuedLocked() {
    if (closed) {
      return List.of();
    }

    List<Job> starts = new ArrayList<>();
    while (!queueRotation.isEmpty()) {
      if (activeJobs == globalBudget.maxActive()) {
        break;
      }
      int candidates = queueRotation.size();
      Job selected = null;

      while (candidates != 0) {
        candidates--;
        String policyId = queueRotation.removeFirst();
        ArrayDeque<Job> queue =
            Objects.requireNonNull(queuedByPolicy.get(policyId), "queued policy");
        Job candidate = queue.getFirst();
        if (canStartLocked(policyId, candidate.policyBudget)) {
          selected = queue.removeFirst();
          selected.queued = false;
          queuedJobs--;

          if (queue.isEmpty()) {
            queuedByPolicy.remove(policyId);
          } else {
            queueRotation.addLast(policyId);
          }

          reserveActiveLocked(selected);
          starts.add(selected);
          break;
        }

        queueRotation.addLast(policyId);
      }

      if (selected == null) {
        break;
      }
    }
    return starts;
  }

  private void decrementActivePolicyLocked(String policyId) {
    int current = Objects.requireNonNull(activeByPolicy.get(policyId), "active policy");
    if (current == 1) {
      activeByPolicy.remove(policyId);
    } else {
      activeByPolicy.put(policyId, current - 1);
    }
  }

  private boolean isCoolingDownLocked(OperationKey key) {
    return cooldowns.containsKey(key);
  }

  private void recordCooldownLocked(OperationKey key) {
    long expiresAt = nanoTime.getAsLong() + failureCooldownNanos;
    cooldowns.put(key, expiresAt);
    if (cooldowns.size() > maxCooldownEntries) {
      Iterator<OperationKey> iterator = cooldowns.keySet().iterator();
      iterator.next();
      iterator.remove();
    }
  }

  private void pruneExpiredCooldownsLocked() {
    long now = nanoTime.getAsLong();
    Iterator<Map.Entry<OperationKey, Long>> iterator = cooldowns.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<OperationKey, Long> entry = iterator.next();
      if (now - entry.getValue() < 0) {
        break;
      }
      iterator.remove();
    }
  }

  private Duration effectiveTimeout(Budget policyBudget) {
    return globalBudget.timeout().compareTo(policyBudget.timeout()) <= 0
        ? globalBudget.timeout()
        : policyBudget.timeout();
  }

  private long effectiveMaxResultBytes(Budget policyBudget) {
    return Math.min(globalBudget.maxResultBytes(), policyBudget.maxResultBytes());
  }

  private static CompletionStage<Artifact> failedStage(OriginExecutionException exception) {
    return CompletableFuture.<Artifact>failedFuture(exception).minimalCompletionStage();
  }

  private static OriginExecutionException failure(OriginExecutionFailure failure) {
    return new OriginExecutionException(failure, failureMessage(failure));
  }

  private static OriginExecutionException failure(OriginExecutionFailure failure, Throwable cause) {
    return new OriginExecutionException(failure, failureMessage(failure), cause);
  }

  private static String failureMessage(OriginExecutionFailure failure) {
    return switch (failure) {
      case GLOBAL_QUEUE_LIMIT -> "global unique-job queue is full";
      case POLICY_QUEUE_LIMIT -> "policy unique-job queue is full";
      case TIMEOUT -> "origin execution timed out";
      case RESULT_TOO_LARGE -> "origin result exceeds the configured limit";
      case MATERIALIZATION_FAILED -> "origin materialization failed";
      case COOLDOWN -> "operation is in failure cooldown";
      case CLOSED -> "origin executor is closed";
      case INTERNAL_ERROR -> "origin executor failed to start bounded work";
    };
  }

  private static void interrupt(Thread thread) {
    if (thread != null) {
      thread.interrupt();
    }
  }

  private enum Termination {
    NONE,
    COMPLETED,
    TIMEOUT,
    CLOSED,
    INTERNAL_ERROR
  }

  private static final class Job {
    private final OriginDecision.Selected decision;
    private final Materializer materializer;
    private final Budget policyBudget;
    private final Duration timeout;
    private final long maxResultBytes;
    private final OperationKey key;
    private final String policyId;
    private final CompletableFuture<Artifact> result = new CompletableFuture<>();
    private final CompletionStage<Artifact> stage = result.minimalCompletionStage();
    private final AtomicReference<Termination> termination =
        new AtomicReference<>(Termination.NONE);

    private volatile Thread workerThread;
    private volatile Thread timeoutThread;
    private boolean active;
    private boolean queued;

    private Job(
        OriginDecision.Selected decision,
        Materializer materializer,
        Budget policyBudget,
        Duration timeout,
        long maxResultBytes) {
      this.decision = decision;
      this.materializer = materializer;
      this.policyBudget = policyBudget;
      this.timeout = timeout;
      this.maxResultBytes = maxResultBytes;
      this.key = decision.operationKey();
      this.policyId = decision.policy().id();
    }
  }

  private static final class WorkOutcome {
    private final Artifact artifact;
    private final OriginExecutionException failure;
    private final Error fatal;

    private WorkOutcome(Artifact artifact, OriginExecutionException failure, Error fatal) {
      this.artifact = artifact;
      this.failure = failure;
      this.fatal = fatal;
    }

    private static WorkOutcome succeeded(Artifact artifact) {
      return new WorkOutcome(artifact, null, null);
    }

    private static WorkOutcome failed(OriginExecutionException failure) {
      return new WorkOutcome(null, failure, null);
    }

    private static WorkOutcome fatal(OriginExecutionException failure, Error fatal) {
      return new WorkOutcome(null, failure, fatal);
    }
  }
}
