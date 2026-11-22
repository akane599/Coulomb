// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.APP
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.OTHER
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.SYSTEM_APP
import io.github.akane599.coulomb.core.model.cmd.AppOpMode
import io.github.akane599.coulomb.core.model.cmd.AppOpReading
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.privileged.PrivilegedAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActionCenterTest {
    private val phone = FakePhone()
    private val log = FakeLog()
    private var on = true
    private var now = 1_000L
    private val center = ActionCenter(phone, log, { on }, { now })

    private val restrict = ActionRequest.RestrictBackground(APP)

    private fun mode(packageName: String) = phone.appOps[packageName]?.effective

    @Test
    fun whileOffNothingRunsAndThePhoneIsNotAsked() =
        runTest {
            on = false
            assertThat(center.preview(restrict).block).isEqualTo(ActionBlock.OFF)
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Blocked(ActionBlock.OFF))
            assertThat(phone.reads).isEqualTo(0)
            assertThat(phone.acts).isEmpty()
            assertThat(log.all).isEmpty()
        }

    @Test
    fun thePreviewShowsTheCommandThatRunsAndTheOneThatUndoesIt() =
        runTest {
            val preview = center.preview(restrict)
            assertThat(preview.block).isNull()
            assertThat(preview.before).isEqualTo("allow")
            assertThat(preview.after).isEqualTo("ignore")
            assertThat(preview.command).isEqualTo("adb shell cmd appops set --user 0 $APP RUN_ANY_IN_BACKGROUND ignore")
            assertThat(preview.revertCommand).isEqualTo("adb shell cmd appops set --user 0 $APP RUN_ANY_IN_BACKGROUND allow")
            // Looking changes nothing.
            assertThat(phone.acts).isEmpty()
            assertThat(log.all).isEmpty()
        }

    @Test
    fun anActionIsLoggedWithWhatItReplacedAndRevertedToIt() =
        runTest {
            val done = center.run(restrict) as ActionResult.Done
            assertThat(done.value).isEqualTo("ignore")
            assertThat(mode(APP)).isEqualTo(AppOpMode.IGNORE)
            val record = center.records.first().single()
            assertThat(record.before).isEqualTo("allow")
            assertThat(record.after).isEqualTo("ignore")
            assertThat(record.active).isTrue()
            assertThat(record.revertCommand).isEqualTo("adb shell cmd appops set --user 0 $APP RUN_ANY_IN_BACKGROUND allow")
            now = 2_000L
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Done(done.id, "allow"))
            // The restore writes "allow": "default" would leave the app restricted.
            assertThat(phone.acts.last()).isEqualTo(PrivilegedAction.SetRunInBackground(APP, allowed = true))
            assertThat(
                center.records
                    .first()
                    .single()
                    .revertedWallMs,
            ).isEqualTo(2_000L)
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Blocked(ActionBlock.NOT_ACTIVE))
        }

    @Test
    fun backgroundRunIsRestrictedOnlyFromAPlainAllow() =
        runTest {
            phone.appOps[APP] = AppOpReading(uidMode = AppOpMode.IGNORE, packageMode = null, defaultMode = null)
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Blocked(ActionBlock.NOT_PLAIN_ALLOW))
            phone.appOps[APP] = AppOpReading(null, AppOpMode.DEFAULT, null)
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Blocked(ActionBlock.NOT_PLAIN_ALLOW))
            phone.appOps[APP] = AppOpReading(null, AppOpMode.IGNORE, null)
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Blocked(ActionBlock.ALREADY))
            assertThat(phone.acts).isEmpty()
        }

    @Test
    fun aSystemAppCoulombOrAMissingAppIsNotTouched() =
        runTest {
            assertThat(center.run(ActionRequest.ForceStop(SYSTEM_APP))).isEqualTo(ActionResult.Blocked(ActionBlock.PROTECTED))
            assertThat(
                center.run(ActionRequest.RestrictBackground("com.example.gone")),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.NOT_INSTALLED))
            assertThat(phone.acts).isEmpty()
        }

    @Test
    fun aCommandThatChangedNothingLeavesNoRow() =
        runTest {
            phone.obeys = false
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Unchanged("allow"))
            assertThat(log.all).isEmpty()
        }

    @Test
    fun aChangeThatCannotBeReadBackKeepsItsRow() =
        runTest {
            val request = ActionRequest.DozeAllowlist(APP, listed = true)
            center.preview(request)
            // The worker answers the plan's read, then the read-back fails.
            val result = center.runWith(request) { phone.readable = false }
            assertThat(result).isInstanceOf(ActionResult.Unverified::class.java)
            assertThat(log.all.single().afterValue).isEqualTo(ActionValues.LISTED)
            assertThat(phone.userListed).contains(APP)
        }

    /** Runs [request] with [afterWrite] applied once its command has run. */
    private suspend fun ActionCenter.runWith(
        request: ActionRequest,
        afterWrite: () -> Unit,
    ): ActionResult {
        val watcher = Watcher(phone, afterWrite)
        return ActionCenter(watcher, log, { on }, { now }).run(request)
    }

    /** The phone, with a hook between a command and its read-back. */
    private class Watcher(
        private val phone: FakePhone,
        private val afterWrite: () -> Unit,
    ) : ActionIo by phone {
        override suspend fun act(action: PrivilegedAction): Ran = phone.act(action).also { afterWrite() }
    }

    @Test
    fun withoutAWorkerNothingIsLogged() =
        runTest {
            phone.worker = false
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Blocked(ActionBlock.NO_WORKER))
            phone.worker = true
            // The worker goes between the plan and the command.
            val result = center.runWith(ActionRequest.ForceStop(APP)) {}
            assertThat(result).isInstanceOf(ActionResult.Done::class.java)
            phone.worker = false
            assertThat(center.run(ActionRequest.ForceStop(APP))).isEqualTo(ActionResult.Blocked(ActionBlock.NO_WORKER))
            assertThat(log.all).hasSize(1)
        }

    @Test
    fun withoutAWorkerTheCommandIsStillThereToCopy() =
        runTest {
            phone.worker = false
            val preview = center.preview(restrict)
            assertThat(preview.block).isEqualTo(ActionBlock.NO_WORKER)
            assertThat(preview.command).isEqualTo("adb shell cmd appops set --user 0 $APP RUN_ANY_IN_BACKGROUND ignore")
            // A profile's value comes from the phone, so it has no command without a read.
            assertThat(center.preview(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)).command).isNull()
        }

    @Test
    fun aBoardReadsEachValueOnce() =
        runTest {
            val board =
                center.board(
                    listOf(
                        ActionRequest.SetStandbyBucket(APP, StandbyBucket.RARE),
                        ActionRequest.SetStandbyBucket(APP, StandbyBucket.RESTRICTED),
                        ActionRequest.DozeAllowlist(APP, listed = true),
                        ActionRequest.DozeAllowlist(APP, listed = false),
                        ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED),
                        ActionRequest.ApplyDozeProfile(DozeProfile.AGGRESSIVE),
                    ),
                    withDoze = true,
                )
            assertThat(board.previews.map { it.block }).containsExactly(null, null, null, ActionBlock.ALREADY, null, null).inOrder()
            assertThat(board.doze?.owner).isEqualTo(DozeOwner.NOBODY)
            // The bucket, the allowlist, the setting and the device's values.
            assertThat(phone.reads).isEqualTo(4)
            on = false
            val off = center.board(listOf(restrict), withDoze = true)
            assertThat(off.previews.single().block).isEqualTo(ActionBlock.OFF)
            assertThat(off.doze).isNull()
            assertThat(phone.reads).isEqualTo(4)
        }

    @Test
    fun theBucketAndroidSetIsTheOneLogged() =
        runTest {
            phone.furthest[APP] = StandbyBucket.FREQUENT
            val done = center.run(ActionRequest.SetStandbyBucket(APP, StandbyBucket.RESTRICTED)) as ActionResult.Done
            assertThat(done.value).isEqualTo("frequent")
            assertThat(log.all.single().afterValue).isEqualTo("frequent")
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Done(done.id, "active"))
            assertThat(phone.buckets[APP]).isEqualTo(StandbyBucket.ACTIVE)
        }

    @Test
    fun anExemptAppAndOneAlreadyFurtherOutKeepTheirBucket() =
        runTest {
            phone.buckets[APP] = StandbyBucket.EXEMPTED
            assertThat(
                center.run(ActionRequest.SetStandbyBucket(APP, StandbyBucket.RARE)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.BUCKET_FIXED))
            phone.buckets[OTHER] = StandbyBucket.RESTRICTED
            assertThat(
                center.run(ActionRequest.SetStandbyBucket(OTHER, StandbyBucket.RARE)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.BUCKET_FIXED))
            assertThat(
                center.run(ActionRequest.SetStandbyBucket(OTHER, StandbyBucket.RESTRICTED)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.ALREADY))
            // Only Rare and Restricted are offered.
            assertThat(
                center.run(ActionRequest.SetStandbyBucket(OTHER, StandbyBucket.NEVER)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.BUCKET_FIXED))
            assertThat(phone.acts).isEmpty()
        }

    @Test
    fun aSystemEntryOfTheDozeAllowlistIsNotChanged() =
        runTest {
            phone.systemListed += APP
            assertThat(
                center.run(ActionRequest.DozeAllowlist(APP, listed = true)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.SYSTEM_LISTED))
            assertThat(
                center.run(ActionRequest.DozeAllowlist(APP, listed = false)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.SYSTEM_LISTED))
            assertThat(center.run(ActionRequest.DozeAllowlist(OTHER, listed = false))).isEqualTo(ActionResult.Blocked(ActionBlock.ALREADY))
            val done = center.run(ActionRequest.DozeAllowlist(OTHER, listed = true)) as ActionResult.Done
            assertThat(phone.userListed).containsExactly(OTHER)
            center.revert(done.id)
            assertThat(phone.userListed).isEmpty()
        }

    @Test
    fun aValueChangedOutsideCoulombIsMarkedOnceAndNeverOverwritten() =
        runTest {
            val done = center.run(restrict) as ActionResult.Done
            center.checkDrift()
            assertThat(log.all.single().externalDriftWall).isNull()
            // The user allows it again in Settings.
            phone.appOps[APP] = AppOpReading(null, AppOpMode.ALLOW, null)
            now = 5_000L
            center.checkDrift()
            val row = log.all.single()
            assertThat(row.externalDriftWall).isEqualTo(5_000L)
            assertThat(row.externalDriftValue).isEqualTo("allow")
            assertThat(
                center.records
                    .first()
                    .single()
                    .active,
            ).isFalse()
            val acts = phone.acts.size
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Blocked(ActionBlock.NOT_ACTIVE))
            assertThat(center.revertAll()).isEmpty()
            assertThat(phone.acts).hasSize(acts)
        }

    @Test
    fun aRevertOverAChangedValueRecordsTheDriftInstead() =
        runTest {
            val done = center.run(ActionRequest.SetStandbyBucket(APP, StandbyBucket.RARE)) as ActionResult.Done
            phone.buckets[APP] = StandbyBucket.WORKING_SET
            val acts = phone.acts.size
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Drifted(done.id, "working_set"))
            assertThat(phone.acts).hasSize(acts)
            assertThat(phone.buckets[APP]).isEqualTo(StandbyBucket.WORKING_SET)
        }

    @Test
    fun aReadThatFailsIsNotDriftAndNoRowsMeansNoReads() =
        runTest {
            center.checkDrift()
            assertThat(phone.reads).isEqualTo(0)
            center.run(restrict)
            phone.readable = false
            center.checkDrift()
            phone.worker = false
            center.checkDrift()
            assertThat(log.all.single().externalDriftWall).isNull()
        }

    @Test
    fun aDozeSettingThatCannotBeReadIsNotDriftEither() =
        runTest {
            val applied = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)) as ActionResult.Done
            // The manager stops: heavy snapshots go on without it, and the setting cannot be read.
            phone.worker = false
            center.checkDrift()
            phone.worker = true
            phone.readable = false
            center.checkDrift()
            phone.readable = true
            assertThat(log.all.single().externalDriftWall).isNull()
            assertThat(center.doze()?.owner).isEqualTo(DozeOwner.PROFILE)
            assertThat(center.revert(applied.id)).isInstanceOf(ActionResult.Done::class.java)
            assertThat(phone.doze).isNull()
        }

    @Test
    fun aSecondChangeOverOneInForceIsRefusedUnlessItUndoesIt() =
        runTest {
            val rare = center.run(ActionRequest.SetStandbyBucket(APP, StandbyBucket.RARE)) as ActionResult.Done
            val further = ActionRequest.SetStandbyBucket(APP, StandbyBucket.RESTRICTED)
            assertThat(center.preview(further).block).isEqualTo(ActionBlock.IN_FORCE)
            assertThat(center.run(further)).isEqualTo(ActionResult.Blocked(ActionBlock.IN_FORCE))
            center.checkDrift()
            assertThat(log.all.single().externalDriftWall).isNull()
            // Taking an app off the list Coulomb put it on is that change undone: one row, reverted.
            val added = center.run(ActionRequest.DozeAllowlist(OTHER, listed = true)) as ActionResult.Done
            assertThat(
                center.run(ActionRequest.DozeAllowlist(OTHER, listed = false)),
            ).isEqualTo(ActionResult.Done(added.id, ActionValues.UNLISTED))
            assertThat(log.all).hasSize(2)
            assertThat(log.all.first { it.id == added.id }.revertedWall).isNotNull()
            assertThat(phone.userListed).isEmpty()
            // And so for battery saver.
            val saver = center.run(ActionRequest.BatterySaver(on = true)) as ActionResult.Done
            assertThat(center.run(ActionRequest.BatterySaver(on = false))).isEqualTo(ActionResult.Done(saver.id, ActionValues.OFF))
            assertThat(center.revertAll().single()).isEqualTo(ActionResult.Done(rare.id, "active"))
            assertThat(phone.buckets[APP]).isEqualTo(StandbyBucket.ACTIVE)
        }

    @Test
    fun aChangeOverAValueThatMovedOnMarksTheOldRowAndStartsANewOne() =
        runTest {
            val rare = center.run(ActionRequest.SetStandbyBucket(APP, StandbyBucket.RARE)) as ActionResult.Done
            // Android moved the app back as it was used.
            phone.buckets[APP] = StandbyBucket.FREQUENT
            val again = center.run(ActionRequest.SetStandbyBucket(APP, StandbyBucket.RESTRICTED)) as ActionResult.Done
            assertThat(log.all.first { it.id == rare.id }.externalDriftValue).isEqualTo("frequent")
            assertThat(log.all.first { it.id == again.id }.beforeValue).isEqualTo("frequent")
            center.revertAll()
            assertThat(phone.buckets[APP]).isEqualTo(StandbyBucket.FREQUENT)
        }

    @Test
    fun aCommandThatReportsAFailureIsStillReadBack() =
        runTest {
            phone.reports = false
            // It reported a failure and changed the value: the change is on record.
            val done = center.run(restrict) as ActionResult.Done
            assertThat(log.all.single().afterValue).isEqualTo("ignore")
            // The revert too: what counts is what the phone holds.
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Done(done.id, "allow"))
            assertThat(log.all.single().revertedWall).isNotNull()
            // It reported a failure and changed nothing: no row.
            phone.obeys = false
            assertThat(center.run(ActionRequest.DozeAllowlist(APP, listed = true))).isEqualTo(ActionResult.Failed(""))
            assertThat(center.run(ActionRequest.ForceStop(APP))).isEqualTo(ActionResult.Failed(""))
            assertThat(log.all).hasSize(1)
        }

    @Test
    fun withoutAWorkerAnActionThatReadsNothingIsRefusedBeforeItIsConfirmed() =
        runTest {
            phone.worker = false
            val requests = listOf(ActionRequest.ForceStop(APP), ActionRequest.BatterySaver(on = true), ActionRequest.ResetBatteryStats)
            val previews = center.board(requests).previews
            assertThat(previews.map { it.block }.toSet()).containsExactly(ActionBlock.NO_WORKER)
            assertThat(previews.last().command).isEqualTo("adb shell dumpsys batterystats --reset")
        }

    @Test
    fun withMoreRowsThanOnePassReadsEveryRowStillGetsItsTurn() =
        runTest {
            val apps = (1..30).map { "com.example.app$it" }
            phone.installed += apps
            apps.forEach { center.run(ActionRequest.SetStandbyBucket(it, StandbyBucket.RARE)) }
            apps.forEach { phone.buckets[it] = StandbyBucket.ACTIVE }
            center.checkDrift()
            assertThat(log.all.count { it.externalDriftWall != null }).isEqualTo(24)
            center.checkDrift()
            assertThat(log.all.count { it.externalDriftWall != null }).isEqualTo(30)
        }

    @Test
    fun anEmptyBaselineIsRestoredByDeletingTheKey() =
        runTest {
            phone.doze = ""
            val done = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)) as ActionResult.Done
            assertThat(center.revert(done.id)).isInstanceOf(ActionResult.Done::class.java)
            assertThat(phone.acts.last()).isEqualTo(PrivilegedAction.PutDozeConstants(null))
        }

    @Test
    fun revertAllUndoesTheNewestFirst() =
        runTest {
            center.run(restrict)
            center.run(ActionRequest.SetStandbyBucket(APP, StandbyBucket.RARE))
            center.run(ActionRequest.DozeAllowlist(OTHER, listed = true))
            center.run(ActionRequest.ForceStop(OTHER))
            val before = phone.acts.size
            val results = center.revertAll()
            assertThat(results).hasSize(3)
            assertThat(results.all { it is ActionResult.Done }).isTrue()
            assertThat(phone.acts.drop(before))
                .containsExactly(
                    PrivilegedAction.SetDozeAllowlisted(OTHER, listed = false),
                    PrivilegedAction.SetStandbyBucket(APP, StandbyBucket.ACTIVE),
                    PrivilegedAction.SetRunInBackground(APP, allowed = true),
                ).inOrder()
            // The force stop is on record and has nothing to put back.
            assertThat(log.all.count { it.revertedWall == null }).isEqualTo(1)
        }

    @Test
    fun aProfileOnAnUnsetPhoneIsWrittenCheckedAndDeletedAgain() =
        runTest {
            assertThat(center.doze()?.owner).isEqualTo(DozeOwner.NOBODY)
            assertThat(center.doze()?.idleAfterMs).isEqualTo(60 * 60_000L)
            val preview = center.preview(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED))
            assertThat(preview.dozeChanges.map { it.key }).containsExactly("inactive_to", "idle_after_inactive_to")
            assertThat(preview.command).isEqualTo(
                "adb shell cmd settings --user 0 put global device_idle_constants inactive_to=900000,idle_after_inactive_to=900000",
            )
            assertThat(preview.revertCommand).isEqualTo("adb shell cmd settings --user 0 delete global device_idle_constants")
            val done = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)) as ActionResult.Done
            val state = checkNotNull(center.doze())
            assertThat(state.owner).isEqualTo(DozeOwner.PROFILE)
            assertThat(state.profile).isEqualTo(DozeProfile.BALANCED)
            assertThat(state.idleAfterMs).isEqualTo(30 * 60_000L)
            // Another profile is computed from the phone's own values, so stock comes first.
            assertThat(
                center.run(ActionRequest.ApplyDozeProfile(DozeProfile.AGGRESSIVE)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.PROFILE_ACTIVE))
            center.revert(done.id)
            assertThat(phone.doze).isNull()
            assertThat(center.doze()?.owner).isEqualTo(DozeOwner.NOBODY)
        }

    @Test
    fun aValueFoundOnThePhoneIsReadOnlyUntilTakenOverAndRestoredVerbatim() =
        runTest {
            val found = "light_after_inactive_to=30000,inactive_to=180000,idle_after_inactive_to=120000,skip_sensing=true"
            phone.doze = found
            assertThat(center.doze()?.owner).isEqualTo(DozeOwner.EXTERNAL)
            val aggressive = ActionRequest.ApplyDozeProfile(DozeProfile.AGGRESSIVE)
            assertThat(center.run(aggressive)).isEqualTo(ActionResult.Blocked(ActionBlock.MANAGED_EXTERNALLY))
            val taken = center.run(ActionRequest.TakeOverDoze("  From the phone  ")) as ActionResult.Done
            assertThat(phone.acts).isEmpty()
            assertThat(checkNotNull(center.doze()).run { owner to importedName }).isEqualTo(DozeOwner.BASELINE to "From the phone")
            // The phone already idles sooner than Balanced asks.
            assertThat(
                center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)),
            ).isEqualTo(ActionResult.Blocked(ActionBlock.NOTHING_TO_CHANGE))
            val applied = center.run(aggressive) as ActionResult.Done
            // The baseline's pairs stay as they are; the changed keys follow them.
            assertThat(phone.doze).isEqualTo("$found,motion_inactive_to=300000,idle_to=7200000")
            assertThat(center.revert(taken.id)).isEqualTo(ActionResult.Blocked(ActionBlock.PROFILE_ACTIVE))
            center.revert(applied.id)
            assertThat(phone.doze).isEqualTo(found)
            assertThat(center.revert(taken.id)).isInstanceOf(ActionResult.Done::class.java)
            assertThat(center.doze()?.owner).isEqualTo(DozeOwner.EXTERNAL)
            assertThat(phone.doze).isEqualTo(found)
        }

    @Test
    fun aProfileThatDoesNotTakeEffectIsUndoneAtOnce() =
        runTest {
            phone.dozeTakesEffect = false
            assertThat(center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED))).isEqualTo(ActionResult.Failed(null))
            assertThat(phone.doze).isNull()
            assertThat(log.all).isEmpty()
        }

    /** Audit DATA-004: a worker that drops before the undo leaves the profile on the phone, so its row stays. */
    @Test
    fun aRollbackThatCannotReadKeepsTheRowAndWritesNothing() =
        runTest {
            phone.dozeTakesEffect = false
            var effectiveReads = 0
            phone.onEffectiveRead = { if (++effectiveReads == ROLLBACK_EFFECTIVE_READS) phone.readable = false }
            val result = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED))
            assertThat(result).isInstanceOf(ActionResult.Unverified::class.java)
            assertThat(phone.doze).isNotNull()
            assertThat(phone.acts).hasSize(1)
            assertThat(log.all).hasSize(1)
        }

    /** Audit CONC-007: a value another manager wrote during the check is not overwritten. */
    @Test
    fun aRollbackLeavesAValueAnotherManagerWrote() =
        runTest {
            phone.dozeTakesEffect = false
            var effectiveReads = 0
            phone.onEffectiveRead = { if (++effectiveReads == ROLLBACK_EFFECTIVE_READS) phone.doze = "inactive_to=60000" }
            val result = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED))
            assertThat(result).isInstanceOf(ActionResult.Drifted::class.java)
            assertThat(phone.doze).isEqualTo("inactive_to=60000")
            assertThat(log.all.single().externalDriftValue).isEqualTo("inactive_to=60000")
        }

    /** The undo ran but its result cannot be read: the row stays rather than claim the baseline. */
    @Test
    fun anUndoThatCannotBeReadBackKeepsTheRow() =
        runTest {
            phone.dozeTakesEffect = false
            phone.afterAct = { if (phone.acts.size == 2) phone.readable = false }
            val result = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED))
            assertThat(result).isInstanceOf(ActionResult.Unverified::class.java)
            assertThat(log.all).hasSize(1)
        }

    @Test
    fun aValueThatIsNotPairsIsNeitherTakenOverNorRewritten() =
        runTest {
            phone.doze = "inactive_to 180000"
            assertThat(center.run(ActionRequest.TakeOverDoze("x"))).isEqualTo(ActionResult.Blocked(ActionBlock.MALFORMED))
            phone.doze = null
            assertThat(center.run(ActionRequest.TakeOverDoze("x"))).isEqualTo(ActionResult.Blocked(ActionBlock.NOTHING_TO_TAKE_OVER))
            assertThat(log.all).isEmpty()
        }

    @Test
    fun aProfileChangedOutsideIsDriftAndTheSettingIsThenSomeoneElses() =
        runTest {
            val done = center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED)) as ActionResult.Done
            phone.doze = "inactive_to=60000"
            center.checkDrift()
            assertThat(log.all.single().externalDriftValue).isEqualTo("inactive_to=60000")
            assertThat(center.doze()?.owner).isEqualTo(DozeOwner.EXTERNAL)
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Blocked(ActionBlock.NOT_ACTIVE))
            assertThat(phone.doze).isEqualTo("inactive_to=60000")
        }

    @Test
    fun aProfileDeletedOutsideIsDriftToo() =
        runTest {
            center.run(ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED))
            phone.doze = null
            center.checkDrift()
            assertThat(log.all.single().externalDriftWall).isNotNull()
        }

    @Test
    fun batterySaverWaitsForTheChargerToGoAndIsNotWatched() =
        runTest {
            phone.plugged = true
            assertThat(center.run(ActionRequest.BatterySaver(on = true))).isEqualTo(ActionResult.Blocked(ActionBlock.CHARGING))
            phone.plugged = false
            val done = center.run(ActionRequest.BatterySaver(on = true)) as ActionResult.Done
            assertThat(phone.saver).isTrue()
            // Plugging in clears it by design: that is not "changed outside Coulomb".
            phone.saver = false
            center.checkDrift()
            assertThat(log.all.single().externalDriftWall).isNull()
            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Drifted(done.id, "off"))
        }

    @Test
    fun aResetCountsOnlyWhenTheCommandSaysSo() =
        runTest {
            phone.resetOutput = "Permission Denial: can't dump BatteryStatsService"
            assertThat(center.run(ActionRequest.ResetBatteryStats)).isEqualTo(ActionResult.Failed(phone.resetOutput))
            assertThat(log.all).isEmpty()
            phone.resetOutput = "Battery stats reset."
            assertThat(center.run(ActionRequest.ResetBatteryStats)).isInstanceOf(ActionResult.Done::class.java)
            val record = center.records.first().single()
            assertThat(record.active).isFalse()
            assertThat(record.revertCommand).isNull()
        }

    /** Audit DATA-019: a lost reply does not say the reset did not run, so its record stays. */
    @Test
    fun aResetWhoseReplyIsLostStaysOnRecord() =
        runTest {
            phone.resetUnclear = true
            assertThat(center.run(ActionRequest.ResetBatteryStats)).isInstanceOf(ActionResult.Unverified::class.java)
            assertThat(log.all).hasSize(1)
        }

    private companion object {
        /** In force is read three times before a rollback; the third read is the last before it. */
        const val ROLLBACK_EFFECTIVE_READS = 3
    }
}
