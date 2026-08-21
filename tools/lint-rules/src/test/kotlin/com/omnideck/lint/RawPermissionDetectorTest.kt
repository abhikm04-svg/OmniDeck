package com.omnideck.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.detector.api.Detector

class RawPermissionDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = RawPermissionDetector()

    override fun getIssues() = listOf(RawPermissionDetector.ISSUE)

    fun testRawPermissionCheckIsFlaggedInAModule() {
        lint().files(
            CONTEXT_COMPAT_STUB,
            kotlin(
                """
                package com.omnideck.modules.scanner

                import android.content.Context
                import androidx.core.content.ContextCompat

                class CameraGate(private val context: Context) {
                    fun hasCamera(): Boolean =
                        ContextCompat.checkSelfPermission(context, "android.permission.CAMERA") == 0
                }
                """.trimIndent(),
            ),
        )
            .issues(RawPermissionDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }

    fun testPermissionBrokerImplementationIsAllowlisted() {
        lint().files(
            CONTEXT_COMPAT_STUB,
            kotlin(
                """
                package com.omnideck.kernel.services

                import android.content.Context
                import androidx.core.content.ContextCompat

                class PermissionBrokerImpl(private val context: Context) {
                    fun isGranted(permission: String): Boolean =
                        ContextCompat.checkSelfPermission(context, permission) == 0
                }
                """.trimIndent(),
            ),
        )
            .issues(RawPermissionDetector.ISSUE)
            .run()
            .expectClean()
    }

    private companion object {
        val CONTEXT_COMPAT_STUB: TestFile = kotlin(
            """
            package androidx.core.content

            import android.content.Context

            object ContextCompat {
                @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = -1
            }
            """.trimIndent(),
        )
    }
}
