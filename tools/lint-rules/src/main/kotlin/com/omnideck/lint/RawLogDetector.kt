package com.omnideck.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Bans direct `android.util.Log.*` calls (architecture.md §12.1 — "Log leakage";
 * §14.1 fitness function "no `Log.` in release sources").
 *
 * Raw `Log` calls bypass `TelemetryService`: no module attribution, no PII
 * redaction filter, and nothing stops a debug log line from shipping to release
 * (R8 strips `Log.*` as a safety net, but Lint catches it at write-time instead
 * of relying on that net).
 */
class RawLogDetector :
    Detector(),
    Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = LOG_METHODS

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val containingClass = method.containingClass?.qualifiedName ?: return
        if (containingClass != "android.util.Log") return

        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = "Do not call `android.util.Log.${method.name}()` directly. Use " +
                "`TelemetryService.logBreadcrumb()`/`recordError()` (via `PlatformServices`) " +
                "so log events get module attribution and PII redaction.",
        )
    }

    companion object {
        private val LOG_METHODS = listOf("v", "d", "i", "w", "e", "wtf")

        val ISSUE: Issue = Issue.create(
            id = "OmniDeckRawLog",
            briefDescription = "Direct android.util.Log call",
            explanation = """
                OmniDeck modules must not call `android.util.Log` directly. Route all logging \
                through `TelemetryService` (exposed on `PlatformServices`), which attributes \
                events to the calling module, applies the PII redaction filter, and exports to \
                both Crashlytics and the OTLP pipeline. A raw `Log` call is invisible to both.
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(RawLogDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
