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
 * Bans direct permission-check/request calls (architecture.md §11.3 —
 * "Modules cannot call `requestPermissions` directly (Lint-enforced)"; §12.1
 * "Elevation: PermissionBroker enforces declared `androidPermissions`").
 *
 * The only legitimate caller is the kernel's own `PermissionBroker`
 * implementation, which this detector allowlists by package.
 */
class RawPermissionDetector :
    Detector(),
    Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String> = BANNED_METHODS.keys.toList()

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val containingClass = method.containingClass?.qualifiedName ?: return
        val expectedOwner = BANNED_METHODS[method.name] ?: return
        if (containingClass != expectedOwner) return

        if (context.uastFile?.classes.orEmpty().any {
                it.qualifiedName?.startsWith(ALLOWLISTED_PACKAGE) == true
            }
        ) {
            return
        }

        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = "Do not call `${method.name}()` on `$containingClass` directly. Request " +
                "the permission through `PlatformServices.permissions.ensure(...)` " +
                "(`PermissionBroker`) so rationale, denial handling, the settings deep link and " +
                "the audit event all happen consistently.",
        )
    }

    companion object {
        private const val ALLOWLISTED_PACKAGE = "com.omnideck.kernel.services"

        // method name -> the exact class that declares it, so we don't false-positive on an
        // unrelated method that happens to share a name.
        private val BANNED_METHODS = mapOf(
            "checkSelfPermission" to "androidx.core.content.ContextCompat",
            "requestPermissions" to "androidx.core.app.ActivityCompat",
            "shouldShowRequestPermissionRationale" to "androidx.core.app.ActivityCompat",
        )

        val ISSUE: Issue = Issue.create(
            id = "OmniDeckRawPermission",
            briefDescription = "Direct permission check/request call",
            explanation = """
                OmniDeck modules must not call `ContextCompat`/`ActivityCompat` permission APIs \
                directly. Go through `PlatformServices.permissions` (`PermissionBroker`), which \
                centralises rationale UI, "don't ask again" handling, the settings deep link, \
                and emits an audit event per grant/denial. A raw call bypasses all of that and \
                bypasses enforcement of the module's declared `androidPermissions`.
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(RawPermissionDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
