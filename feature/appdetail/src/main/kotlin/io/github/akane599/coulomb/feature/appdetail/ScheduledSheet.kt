// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.akane599.coulomb.core.designsystem.component.ButtonEdge
import io.github.akane599.coulomb.core.designsystem.component.CoulombTextButton
import io.github.akane599.coulomb.core.designsystem.theme.CoulombRadius
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.page.InlineTable
import io.github.akane599.coulomb.core.ui.page.Note
import io.github.akane599.coulomb.core.ui.page.Subhead
import io.github.akane599.coulomb.core.ui.page.TableRow

/** The way from App detail to what the app has scheduled now (S13, S10, on demand). */
@Composable
internal fun ScheduledRow(onOpen: () -> Unit) {
    CoulombTextButton(
        stringResource(R.string.appdetail_scheduled_open),
        onOpen,
        Modifier.padding(top = CoulombSpacing.Space16),
        edge = ButtonEdge.Start,
    )
}

/** Reads S13 and S10 when it opens and on Read again; nothing read is kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledSheet(
    read: suspend () -> ScheduledNow?,
    onDismiss: () -> Unit,
) {
    val colors = CoulombTheme.colors
    val formats = rememberFormats()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ScheduledState>(ScheduledState.Reading) }
    val latestRead by rememberUpdatedState(read)
    LaunchedEffect(attempt) {
        state = ScheduledState.Reading
        state = latestRead()?.let { ScheduledState.Read(it) } ?: ScheduledState.Failed
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        shape = RoundedCornerShape(topStart = CoulombRadius.Sheet, topEnd = CoulombRadius.Sheet),
        containerColor = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space24),
        ) {
            Text(
                stringResource(R.string.appdetail_scheduled),
                Modifier.semantics { heading() },
                color = colors.textPrimary,
                style = CoulombTheme.typography.title,
            )
            ScheduledBody(state, formats)
            if (state != ScheduledState.Reading) {
                CoulombTextButton(
                    stringResource(R.string.appdetail_scheduled_again),
                    { attempt++ },
                    Modifier.padding(top = CoulombSpacing.Space8),
                    edge = ButtonEdge.Start,
                )
            }
        }
    }
}

@Composable
internal fun ScheduledBody(
    state: ScheduledState,
    formats: Formats,
) {
    when (state) {
        ScheduledState.Reading -> {
            Note(stringResource(R.string.appdetail_scheduled_reading))
        }

        ScheduledState.Failed -> {
            Note(stringResource(R.string.appdetail_scheduled_failed))
        }

        is ScheduledState.Read -> {
            Note(stringResource(R.string.appdetail_scheduled_caption))
            Jobs(state.now.jobs, formats)
            Alarms(state.now.alarms, formats)
        }
    }
}

@Composable
private fun Jobs(
    jobs: ScheduledJobs?,
    formats: Formats,
) {
    Subhead(stringResource(R.string.appdetail_scheduled_jobs), provenance = Provenance.MEASURED.takeIf { jobs != null })
    if (jobs == null) {
        Note(stringResource(R.string.appdetail_scheduled_jobs_unread), top = 0.dp)
        return
    }
    val registered =
        pluralStringResource(R.plurals.appdetail_scheduled_registered, jobs.registered, formats.integer(jobs.registered.toLong()))
    val line =
        if (jobs.registered == 0) {
            registered
        } else {
            stringResource(R.string.appdetail_scheduled_ready, registered, formats.integer(jobs.ready.toLong()))
        }
    Note(
        listOfNotNull(
            line,
            jobs.bucket?.let {
                stringResource(R.string.appdetail_scheduled_bucket, bucketName(it))
            },
        ).joinToString(" "),
        top = 0.dp,
    )
    Counted(R.string.appdetail_scheduled_waiting_for, jobs.waitingFor.map { constraintName(it.first) to it.second }, formats)
    Counted(R.string.appdetail_scheduled_restricted_by, jobs.restrictedBy.map { stopName(it.first) to it.second }, formats)
    if (jobs.stops.isNotEmpty()) {
        Counted(R.string.appdetail_scheduled_stopped, jobs.stops.map { stopName(it.first) to it.second }, formats)
        Note(stringResource(R.string.appdetail_scheduled_stopped_note))
    }
}

@Composable
private fun Counted(
    @StringRes nameColumn: Int,
    rows: List<Pair<String, Int>>,
    formats: Formats,
) {
    if (rows.isEmpty()) return
    InlineTable(
        columns = listOf(stringResource(nameColumn), stringResource(R.string.appdetail_scheduled_column_jobs)),
        rows = rows.map { (name, count) -> TableRow(name, listOf(formats.integer(count.toLong()))) },
    )
}

@Composable
private fun Alarms(
    alarms: ScheduledAlarms?,
    formats: Formats,
) {
    Subhead(stringResource(R.string.appdetail_scheduled_alarms), provenance = Provenance.MEASURED.takeIf { alarms != null })
    if (alarms == null) {
        Note(stringResource(R.string.appdetail_scheduled_alarms_unread), top = 0.dp)
        return
    }
    val waiting =
        pluralStringResource(R.plurals.appdetail_scheduled_pending, alarms.pending, formats.integer(alarms.pending.toLong()))
    val next = alarms.nextInMs?.let { stringResource(R.string.appdetail_scheduled_next, formats.held(it)) }
    val wakeups = alarms.pendingWakeups
    val wakers =
        pluralStringResource(R.plurals.appdetail_scheduled_pending_wakeups, wakeups, formats.integer(wakeups.toLong()))
            .takeIf { alarms.pending > 0 }
    Note(listOfNotNull(waiting, wakers, next).joinToString(" "), top = 0.dp)
    Note(
        stringResource(
            R.string.appdetail_scheduled_delivered,
            formats.integer(alarms.delivered.toLong()),
            formats.integer(alarms.deliveredWakeups.toLong()),
            formats.integer(alarms.deliveredQuiet.toLong()),
        ),
    )
}

/** `JobStatus.dumpConstraints`' names (android-16.0.0_r4 L2729-2774, android-17.0.0_r1 L3155-3200) in words. */
@Composable
private fun constraintName(name: String): String =
    when (name) {
        "CHARGING" -> stringResource(R.string.appdetail_constraint_charging)
        "BATTERY_NOT_LOW" -> stringResource(R.string.appdetail_constraint_battery_not_low)
        "STORAGE_NOT_LOW" -> stringResource(R.string.appdetail_constraint_storage_not_low)
        "TIMING_DELAY" -> stringResource(R.string.appdetail_constraint_timing_delay)
        "DEADLINE" -> stringResource(R.string.appdetail_constraint_deadline)
        "IDLE" -> stringResource(R.string.appdetail_constraint_idle)
        "CONNECTIVITY" -> stringResource(R.string.appdetail_constraint_connectivity)
        "FLEXIBILITY" -> stringResource(R.string.appdetail_constraint_flexibility)
        "CONTENT_TRIGGER" -> stringResource(R.string.appdetail_constraint_content_trigger)
        "DEVICE_NOT_DOZING" -> stringResource(R.string.appdetail_constraint_not_dozing)
        "BACKGROUND_NOT_RESTRICTED" -> stringResource(R.string.appdetail_constraint_background)
        "PREFETCH" -> stringResource(R.string.appdetail_constraint_prefetch)
        "WITHIN_QUOTA" -> stringResource(R.string.appdetail_constraint_quota)
        else -> name
    }

/** `JobStatus.bucketName`'s names in words; another is shown as Android writes it. */
@Composable
private fun bucketName(name: String): String =
    when (name) {
        "ACTIVE" -> stringResource(R.string.appdetail_bucket_active)
        "WORKING_SET" -> stringResource(R.string.appdetail_bucket_working_set)
        "FREQUENT" -> stringResource(R.string.appdetail_bucket_frequent)
        "RARE" -> stringResource(R.string.appdetail_bucket_rare)
        "NEVER" -> stringResource(R.string.appdetail_bucket_never)
        "RESTRICTED" -> stringResource(R.string.appdetail_bucket_restricted)
        "EXEMPTED" -> stringResource(R.string.appdetail_bucket_exempted)
        else -> name
    }

/**
 * The commonest of `JobParameters.getInternalReasonCodeDescription`'s names in words
 * (android-16.0.0_r4 L171-185, android-17.0.0_r1 L179-194); the rest as Android writes them.
 */
@Composable
private fun stopName(reason: String): String =
    when (reason) {
        "successful_finish" -> stringResource(R.string.appdetail_stop_finished)
        "canceled" -> stringResource(R.string.appdetail_stop_canceled)
        "timeout_abandoned" -> stringResource(R.string.appdetail_stop_abandoned)
        "timeout" -> stringResource(R.string.appdetail_stop_timeout)
        "constraints" -> stringResource(R.string.appdetail_stop_constraints)
        "preempt" -> stringResource(R.string.appdetail_stop_preempt)
        "device_idle" -> stringResource(R.string.appdetail_stop_device_idle)
        "thermal" -> stringResource(R.string.appdetail_stop_thermal)
        "battery_saver" -> stringResource(R.string.appdetail_stop_battery_saver)
        else -> reason
    }
