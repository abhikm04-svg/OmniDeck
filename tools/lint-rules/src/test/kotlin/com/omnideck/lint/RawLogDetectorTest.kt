package com.omnideck.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.detector.api.Detector

class RawLogDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = RawLogDetector()

    override fun getIssues() = listOf(RawLogDetector.ISSUE)

    fun testRawLogCallIsFlagged() {
        lint().files(
            ANDROID_LOG_STUB,
            kotlin(
                """
                package com.omnideck.modules.notes

                import android.util.Log

                class NotesRepository {
                    fun save() {
                        Log.d("Notes", "saving")
                    }
                }
                """.trimIndent(),
            ),
        )
            .issues(RawLogDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    fun testTelemetryServiceCallIsClean() {
        lint().files(
            ANDROID_LOG_STUB,
            kotlin(
                """
                package com.omnideck.modules.notes

                class NotesRepository(private val telemetry: Telemetry) {
                    fun save() {
                        telemetry.logBreadcrumb("saving")
                    }
                }

                interface Telemetry {
                    fun logBreadcrumb(message: String)
                }
                """.trimIndent(),
            ),
        )
            .issues(RawLogDetector.ISSUE)
            .run()
            .expectClean()
    }

    private companion object {
        val ANDROID_LOG_STUB: TestFile = kotlin(
            """
            package android.util

            object Log {
                @JvmStatic fun v(tag: String, msg: String): Int = 0
                @JvmStatic fun d(tag: String, msg: String): Int = 0
                @JvmStatic fun i(tag: String, msg: String): Int = 0
                @JvmStatic fun w(tag: String, msg: String): Int = 0
                @JvmStatic fun e(tag: String, msg: String): Int = 0
                @JvmStatic fun wtf(tag: String, msg: String): Int = 0
            }
            """.trimIndent(),
        )
    }
}
