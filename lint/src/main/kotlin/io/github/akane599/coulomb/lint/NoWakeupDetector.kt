// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement

/**
 * SPEC 7.4, the no-wakeup policy: no `AlarmManager` call anywhere, and no `WakeLock.acquire()`
 * without a timeout of at most 2 s. An allowlisted class carries `@SuppressLint` with the issue id
 * and the reason beside it (ADR-0016).
 *
 * The classes, in frameworks/base:
 * - `android.app.AlarmManager`: apex/jobscheduler/framework/java/android/app/AlarmManager.java,
 *   android-16.0.0_r4 L92, android-17.0.0_r1 L95.
 * - `PowerManager.WakeLock` with `acquire()` and `acquire(long timeout)`:
 *   core/java/android/os/PowerManager.java, android-16.0.0_r4 L4217, L4288 and L4304;
 *   android-17.0.0_r1 L4260, L4331 and L4347.
 */
public class NoWakeupDetector :
    Detector(),
    SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                val method = node.resolve() ?: return
                val owner = method.containingClass?.qualifiedName ?: return
                when {
                    owner in ALARM_CLASSES -> {
                        context.report(
                            ALARM_MANAGER,
                            node,
                            context.getCallLocation(node, includeReceiver = false, includeArguments = false),
                            "`$owner.${method.name}` is not allowed: Coulomb never sets alarms (SPEC 7.4)",
                        )
                    }

                    owner == WAKE_LOCK && method.name == "acquire" -> {
                        checkTimeout(context, node)
                    }
                }
            }
        }

    private fun checkTimeout(
        context: JavaContext,
        node: UCallExpression,
    ) {
        val timeout = node.valueArguments.firstOrNull()
        val message =
            if (timeout == null) {
                "`WakeLock.acquire()` without a timeout; pass at most $MAX_TIMEOUT_MS ms (SPEC 7.4)"
            } else {
                val ms = ConstantEvaluator.evaluate(context, timeout) as? Number
                if (ms == null || ms.toLong() <= MAX_TIMEOUT_MS) return
                "Wakelock timeout of ${ms.toLong()} ms; the limit is $MAX_TIMEOUT_MS ms (SPEC 7.4)"
            }
        context.report(UNTIMED_WAKE_LOCK, node, context.getLocation(node), message)
    }

    public companion object {
        private const val WAKE_LOCK = "android.os.PowerManager.WakeLock"
        private const val MAX_TIMEOUT_MS = 2_000L
        private val ALARM_CLASSES = setOf("android.app.AlarmManager", "androidx.core.app.AlarmManagerCompat")

        private val IMPLEMENTATION = Implementation(NoWakeupDetector::class.java, Scope.JAVA_FILE_SCOPE)

        @JvmField
        public val ALARM_MANAGER: Issue =
            Issue.create(
                id = "CoulombAlarmManager",
                briefDescription = "AlarmManager call",
                explanation =
                    "Coulomb sets no exact or inexact alarms (SPEC 7.4): its own wakeups would show up " +
                        "in the drain it measures. Use broadcasts the system already sends, such as " +
                        "`BATTERY_CHANGED`. An allowlisted class carries `@SuppressLint(\"CoulombAlarmManager\")` " +
                        "with its reason.",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.ERROR,
                implementation = IMPLEMENTATION,
            )

        @JvmField
        public val UNTIMED_WAKE_LOCK: Issue =
            Issue.create(
                id = "CoulombUntimedWakeLock",
                briefDescription = "Wakelock without a short timeout",
                explanation =
                    "Every wakelock is acquired with a timeout of at most 2 s and released in a " +
                        "`finally` block (SPEC 7.4), so a crash or a missed release cannot keep the " +
                        "CPU awake. A timeout that is not a constant is not checked.",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.ERROR,
                implementation = IMPLEMENTATION,
            )
    }
}
