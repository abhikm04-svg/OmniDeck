package com.omnideck.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API

/**
 * Entry point Lint loads via the `Lint-Registry-v2` jar manifest attribute
 * (see build.gradle.kts). Registered by every Android module through
 * `lintChecks(project(":tools:lint-rules"))` (Extensions.kt#configureAndroidCommon).
 */
class OmniDeckIssueRegistry : IssueRegistry() {

    override val issues = listOf(
        RawLogDetector.ISSUE,
        RawPermissionDetector.ISSUE,
    )

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(
        vendorName = "OmniDeck",
        identifier = "com.omnideck:lint-rules",
        feedbackUrl = "https://github.com/omnideck/omnideck/issues",
    )
}
