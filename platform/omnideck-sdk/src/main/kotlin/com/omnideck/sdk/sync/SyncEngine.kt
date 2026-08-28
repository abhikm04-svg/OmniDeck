package com.omnideck.sdk.sync

import com.omnideck.core.Clock
import com.omnideck.sdk.capability.TelemetryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The shared offline-first synchroniser (architecture.md §11.2, OD-210).
 *
 * It exists so twelve modules do not write twelve subtly different sync loops. The
 * parts that are genuinely module-specific — the durable queue, the wire call, the
 * conflict decision — are interfaces the module supplies; everything that is only
 * ever *got wrong* differently — ordering, backoff, jitter, attempt ceilings,
 * re-entrancy, dead-lettering, telemetry — lives here once.
 *
 * Notably absent: any scheduling. The engine drains when asked. Deciding *when* is
 * the [com.omnideck.sdk.capability.WorkScheduler]'s job, so that a module's sync work
 * carries the module tag that lets quarantine and purge cancel it atomically.
 *
 * ```
 * val engine = SyncEngine(outbox, transport, clock, services.telemetry)
 * val report = engine.drain()          // from a WorkManager worker
 * if (report.remaining > 0) Result.retry() else Result.success()
 * ```
 */
class SyncEngine(
    private val outbox: Outbox,
    private val transport: SyncTransport,
    private val clock: Clock,
    private val telemetry: TelemetryService,
    private val conflicts: ConflictResolver = ConflictResolver.lastWriteWins(),
    private val sink: SyncSink = SyncSink.NoOp,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val random: Random = Random.Default,
) {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle(pending = 0))

    /** Observable state for a module's UI — a sync badge, a "saving…" chip, an error row. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    // A second drain running concurrently would push the same records twice: both
    // would read the same due() batch before either removed anything. WorkManager
    // makes that easy to hit — an expedited job and the periodic one can overlap.
    private val draining = Mutex()

    /**
     * Delivers every record that is due, in order, and returns what happened.
     *
     * Ordering is strict on purpose. Two edits to the same entity delivered out of
     * order leave the *older* value on the server, so the first retryable failure
     * stops the batch rather than skipping past it.
     */
    suspend fun drain(): SyncReport = draining.withLock {
        val startedAt = clock.nowMillis()
        val due = outbox.due(startedAt, batchSize)

        if (due.isEmpty()) {
            val idle = outbox.size()
            _state.value = SyncState.Idle(pending = idle)
            return@withLock SyncReport(remaining = idle)
        }

        _state.value = SyncState.Syncing(pending = due.size)
        var report = SyncReport()

        for (record in due) {
            report = apply(record, push(record), report)

            // Stop at the first record that could not be delivered: everything behind
            // it in the queue may depend on it having landed.
            if (report.stopped) break
        }

        val remaining = outbox.size()
        telemetry.metric("sync_drain_ms", (clock.nowMillis() - startedAt).toDouble())
        telemetry.event(
            "sync_drain",
            mapOf(
                "pushed" to report.pushed,
                "conflicts" to report.conflicts,
                "dead_lettered" to report.deadLettered,
                "remaining" to remaining,
            ),
        )

        _state.value = report.lastError
            ?.let { SyncState.Failed(pending = remaining, reason = it) }
            ?: SyncState.Idle(pending = remaining)

        report.copy(remaining = remaining)
    }

    /**
     * A transport that throws is a transport bug, not a delivery verdict — but the
     * user's change must survive it either way, so it is contained and treated as
     * transient rather than allowed to abort the drain.
     */
    private suspend fun push(record: OutboxRecord): PushOutcome = runCatching { transport.push(record) }
        .getOrElse { error ->
            telemetry.recordError(error, "sync_transport_threw:${record.entityType}")
            PushOutcome.Retryable(error.message ?: error::class.java.simpleName)
        }

    private suspend fun apply(record: OutboxRecord, outcome: PushOutcome, report: SyncReport): SyncReport =
        when (outcome) {
            is PushOutcome.Applied -> {
                sink.onApplied(record, outcome.remoteVersion)
                outbox.remove(record.id)
                report.copy(pushed = report.pushed + 1)
            }

            is PushOutcome.Conflict -> resolve(record, outcome, report)

            is PushOutcome.Retryable -> retryLater(record, outcome.reason, report)

            is PushOutcome.Rejected -> {
                deadLetter(record, outcome.reason)
                report.copy(deadLettered = report.deadLettered + 1)
            }
        }

    private suspend fun resolve(record: OutboxRecord, conflict: PushOutcome.Conflict, report: SyncReport): SyncReport {
        val next = report.copy(conflicts = report.conflicts + 1)
        telemetry.event(
            "sync_conflict",
            mapOf("entity" to record.entityType, "base" to record.baseVersion, "remote" to conflict.remoteVersion),
        )

        return when (val resolution = conflicts.resolve(record, conflict)) {
            is ConflictResolver.Resolution.AcceptRemote -> {
                sink.onRemoteAccepted(record, conflict)
                outbox.remove(record.id)
                next
            }

            is ConflictResolver.Resolution.Retry -> {
                val rebased = record.copy(payload = resolution.payload, baseVersion = resolution.baseVersion)
                // Re-pushed immediately rather than rescheduled: the conflict is
                // resolved, so waiting only widens the window for a third writer.
                val second = push(rebased)
                if (second is PushOutcome.Applied) {
                    sink.onApplied(rebased, second.remoteVersion)
                    outbox.remove(record.id)
                    next.copy(pushed = next.pushed + 1)
                } else {
                    // A second conflict means someone is writing faster than we can
                    // resolve. Back off rather than spin.
                    retryLater(rebased, "conflict_unresolved", next)
                }
            }
        }
    }

    private suspend fun retryLater(record: OutboxRecord, reason: String, report: SyncReport): SyncReport {
        val attempt = record.attempt + 1

        if (attempt >= backoff.maxAttempts) {
            deadLetter(record, "Gave up after $attempt attempts: $reason")
            return report.copy(deadLettered = report.deadLettered + 1, stopped = true, lastError = reason)
        }

        outbox.reschedule(
            id = record.id,
            attempt = attempt,
            nextAttemptAtEpochMs = clock.nowMillis() + backoff.delayFor(attempt, random).inWholeMilliseconds,
            lastError = reason,
        )
        return report.copy(retried = report.retried + 1, stopped = true, lastError = reason)
    }

    private suspend fun deadLetter(record: OutboxRecord, reason: String) {
        telemetry.event(
            "sync_dead_lettered",
            mapOf("entity" to record.entityType, "operation" to record.operation.name, "reason" to reason),
        )
        outbox.deadLetter(record.id, reason)
        sink.onDeadLettered(record, reason)
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 50
    }
}

/**
 * Jittered exponential backoff.
 *
 * The jitter is not decoration. Without it, every device that lost connectivity in
 * the same outage retries at the same instant when it returns, and the retry storm
 * finishes the job the outage started.
 */
data class BackoffPolicy(
    val initial: Duration = 30.seconds,
    val max: Duration = 6.hours,
    val multiplier: Double = 2.0,
    /** Fraction of the computed delay to randomise either way. `0.2` means plus or minus 20%. */
    val jitter: Double = 0.2,
    /** Attempts before a record is dead-lettered. Roughly a day at the defaults. */
    val maxAttempts: Int = 8,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(jitter in 0.0..1.0) { "jitter must be a fraction between 0 and 1" }
    }

    /** Delay before attempt number [attempt] (1-based). */
    fun delayFor(attempt: Int, random: Random = Random.Default): Duration {
        require(attempt >= 1) { "attempt is 1-based" }
        val exponential = initial.inWholeMilliseconds * multiplier.pow(attempt - 1)
        val capped = min(exponential.toLong(), max.inWholeMilliseconds)
        val spread = (capped * jitter).toLong()
        val offset = if (spread == 0L) 0L else random.nextLong(-spread, spread + 1)
        return (capped + offset).coerceAtLeast(0L).milliseconds
    }
}

/** Outcome of one [SyncEngine.drain]. */
data class SyncReport(
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val retried: Int = 0,
    val deadLettered: Int = 0,
    /** Records still queued after this drain. Non-zero means "ask WorkManager to retry". */
    val remaining: Int = 0,
    /** True when the drain stopped early to preserve ordering. */
    val stopped: Boolean = false,
    val lastError: String? = null,
)

/** What a module's UI shows about synchronisation. */
sealed interface SyncState {
    val pending: Int

    data class Idle(override val pending: Int) : SyncState
    data class Syncing(override val pending: Int) : SyncState
    data class Failed(override val pending: Int, val reason: String) : SyncState
}
