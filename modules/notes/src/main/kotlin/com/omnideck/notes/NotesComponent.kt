package com.omnideck.notes

import com.omnideck.core.Clock
import com.omnideck.core.SystemClock
import com.omnideck.notes.data.NotesDatabase
import com.omnideck.notes.data.NotesRepository
import com.omnideck.notes.data.RoomOutbox
import com.omnideck.notes.sync.NotesSyncScheduler
import com.omnideck.notes.sync.NotesSyncSink
import com.omnideck.notes.sync.NotesSyncTransport
import com.omnideck.sdk.PlatformServices
import com.omnideck.sdk.capability.Router
import com.omnideck.sdk.capability.TelemetryService
import com.omnideck.sdk.sync.ConflictResolver
import com.omnideck.sdk.sync.SyncEngine

/**
 * Manual composition root (architecture.md §6.4).
 *
 * A module builds its own object graph from [PlatformServices] and nothing else. No
 * Hilt, no service locator, no `Context` smuggled out of the Shell — which is what
 * keeps the module buildable and testable against `:platform:testing` with no Shell
 * and no kernel present at all.
 */
class NotesComponent(
    val repository: NotesRepository,
    val telemetry: TelemetryService,
    val router: Router,
    /** Null when no sync endpoint is configured: the module is then local-only. */
    val sync: NotesSync?,
) {

    /** Everything that only exists when there is somewhere to sync to. */
    class NotesSync(val engine: SyncEngine, val scheduler: NotesSyncScheduler)

    companion object {

        /**
         * Remote Config key (ADR-009). Sync is off until a server tells the module
         * where the service is, so a build with no backend behind it is honestly
         * local-only rather than silently retrying against a placeholder host.
         */
        const val SYNC_ENDPOINT_FLAG = "notes.sync.endpoint"

        fun build(services: PlatformServices, clock: Clock = SystemClock): NotesComponent {
            val database = services.storage.database("notes", NotesDatabase::class.java)
            val repository = NotesRepository(database, clock)

            return NotesComponent(
                repository = repository,
                telemetry = services.telemetry,
                router = services.router,
                sync = services.flags.string(SYNC_ENDPOINT_FLAG, "")
                    .takeIf(String::isNotBlank)
                    ?.let { endpoint -> buildSync(services, database, repository, clock, endpoint) },
            )
        }

        private fun buildSync(
            services: PlatformServices,
            database: NotesDatabase,
            repository: NotesRepository,
            clock: Clock,
            endpoint: String,
        ) = NotesSync(
            engine = SyncEngine(
                outbox = RoomOutbox(database.outbox()),
                transport = NotesSyncTransport(
                    baseUrl = endpoint,
                    // Built per call, not per module: the kernel's client carries the
                    // current session token, and holding one across a sign-out would
                    // keep sending the old one.
                    clientFactory = { services.network.client() },
                ),
                clock = clock,
                telemetry = services.telemetry,
                // A note is a single-owner document, so the last edit is the one the
                // user meant. A shared or financial entity would take serverWins.
                conflicts = ConflictResolver.lastWriteWins(),
                sink = NotesSyncSink(repository, services.telemetry),
            ),
            scheduler = NotesSyncScheduler(services.work),
        )
    }
}
