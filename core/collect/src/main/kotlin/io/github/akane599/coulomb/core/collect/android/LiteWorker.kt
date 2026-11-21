// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import java.util.concurrent.TimeUnit

/** The collector for the components Android creates without Hilt: the boot receiver and [LiteWorker]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CollectorEntryPoint {
    fun collector(): Collector
}

internal fun Context.collector(): Collector =
    EntryPointAccessors.fromApplication(applicationContext, CollectorEntryPoint::class.java).collector()

/**
 * Lite mode's one periodic job (SPEC 7.1): a snapshot every six hours, so that the system's
 * history, which keeps only so much, is read before it rolls over. It has no constraints and asks
 * for no exact time: WorkManager's job runs when the system next lets jobs run, and wakes nothing
 * in Doze (SPEC 7.4).
 */
internal class LiteWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val collector = applicationContext.collector()
        if (collector.lite()) {
            collector.snapshot(SnapshotTrigger.LITE_PERIODIC)
        } else {
            // Left over from a mode change this process did not see, as after "Delete all data".
            cancel(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "lite-snapshot"
        private const val EVERY_HOURS = 6L

        /** Keeps a job that is already scheduled, with its next run. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequest.Builder(LiteWorker::class.java, EVERY_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
