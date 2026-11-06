// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** Coulomb's own lint checks; `:app` loads them with `lintChecks`. */
public class CoulombIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(NoWakeupDetector.ALARM_MANAGER, NoWakeupDetector.UNTIMED_WAKE_LOCK)

    override val api: Int = CURRENT_API

    override val minApi: Int = CURRENT_API

    override val vendor: Vendor = Vendor(vendorName = "Coulomb")
}
