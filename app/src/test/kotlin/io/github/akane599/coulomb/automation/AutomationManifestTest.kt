// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.actions.AutomationCalls
import io.github.akane599.coulomb.core.common.AutomationContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The manifest against the contract (SPEC 12.7, ADR-0065 §1-2): what other apps address is what
 * the code answers to, both components are closed until the setting opens them, and both are
 * behind the permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AutomationManifestTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val id: String get() = context.packageName
    private val packages: PackageManager get() = context.packageManager

    @Test
    fun thePermissionIsCoulombsOwnAndTheUserGrantsIt() {
        val info = packages.getPermissionInfo(AutomationContract.permission(id), 0)

        assertThat(info.packageName).isEqualTo(id)
        assertThat(info.protection).isEqualTo(PermissionInfo.PROTECTION_DANGEROUS)
        // Coulomb defines it and does not ask for it.
        val requested = packages.getPackageInfo(id, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertThat(requested.toList()).doesNotContain(AutomationContract.permission(id))
    }

    @Test
    fun theReceiverIsClosedBehindThePermissionAndAnswersToTheThreeActions() {
        val receiver =
            packages.getReceiverInfo(
                ComponentName(context, AutomationReceiver::class.java),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )

        assertThat(receiver.enabled).isFalse()
        assertThat(receiver.exported).isTrue()
        assertThat(receiver.permission).isEqualTo(AutomationContract.permission(id))
        for (action in listOf(
            AutomationContract.actionSnapshotNow(id),
            AutomationContract.actionSetChargeTarget(id),
            AutomationContract.actionApplyDozeProfile(id),
        )) {
            val found = packages.queryBroadcastReceivers(Intent(action).setPackage(id), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertThat(found.map { it.activityInfo.name }).containsExactly(AutomationReceiver::class.java.name)
        }
    }

    @Test
    fun theProviderIsClosedBehindThePermissionAndGrantsNoUri() {
        val provider =
            packages.getProviderInfo(
                ComponentName(context, AutomationProvider::class.java),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )

        assertThat(provider.enabled).isFalse()
        assertThat(provider.exported).isTrue()
        assertThat(provider.authority).isEqualTo(AutomationContract.authority(id))
        assertThat(provider.readPermission).isEqualTo(AutomationContract.permission(id))
        assertThat(provider.writePermission).isEqualTo(AutomationContract.permission(id))
        assertThat(provider.grantUriPermissions).isFalse()
    }

    @Test
    fun theSwitchOpensBothComponentsAndClosesThemAgain() {
        val components = listOf(AutomationReceiver::class.java, AutomationProvider::class.java).map { ComponentName(context, it) }

        AutomationSwitch.apply(context, on = true)
        assertThat(
            components.map(packages::getComponentEnabledSetting).distinct(),
        ).containsExactly(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        AutomationSwitch.apply(context, on = false)
        assertThat(
            components.map(packages::getComponentEnabledSetting).distinct(),
        ).containsExactly(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    }

    @Test
    fun anIntentIsReadAsACallAndAnotherActionAsNone() {
        val snapshot = AutomationReceiver.call(id, Intent(AutomationContract.actionSnapshotNow(id)), "org.example.tasks")
        assertThat(snapshot).isEqualTo(AutomationCall(AutomationCalls.SNAPSHOT_NOW, caller = "org.example.tasks"))

        fun target(put: Intent.() -> Unit) =
            AutomationReceiver.call(id, Intent(AutomationContract.actionSetChargeTarget(id)).apply(put), null)?.chargeTargetPct
        val extra = AutomationContract.extraChargeTargetPct(id)
        assertThat(target { putExtra(extra, 75) }).isEqualTo(75)
        // Automation apps often send every extra as text.
        assertThat(target { putExtra(extra, " 80 ") }).isEqualTo(80)
        assertThat(target { putExtra(extra, "eighty") }).isNull()
        assertThat(target { putExtra(extra, 80.5) }).isNull()
        assertThat(target { }).isNull()

        val doze = Intent(AutomationContract.actionApplyDozeProfile(id)).putExtra(AutomationContract.extraDozeProfile(id), "balanced")
        assertThat(AutomationReceiver.call(id, doze, null)?.dozeProfile).isEqualTo("balanced")

        assertThat(AutomationReceiver.call(id, Intent("$id.action.RESET_EVERYTHING"), null)).isNull()
        assertThat(AutomationReceiver.call(id, Intent(), null)).isNull()
        assertThat(
            AutomationReceiver.read(id, Intent(AutomationContract.actionSnapshotNow(id)), null),
        ).isEqualTo(AutomationCall(AutomationCalls.SNAPSHOT_NOW))
    }

    /** What needs no stored figure: an unknown path, a caller without the permission, a write. */
    @Test
    fun theProviderAnswersNothingOutsideItsFivePathsAndTakesNoWrite() {
        val provider = Robolectric.buildContentProvider(AutomationProvider::class.java).create(AutomationContract.authority(id)).get()
        val base = "content://${AutomationContract.authority(id)}"

        assertThat(provider.query(Uri.parse("$base/v1/everything"), null, null, null, null)).isNull()
        assertThat(provider.query(Uri.parse("$base/v2/level"), null, null, null, null)).isNull()
        assertThat(provider.getType(Uri.parse("$base/v1/everything"))).isNull()
        assertThat(provider.getType(Uri.parse("$base/${AutomationContract.PATH_LEVEL}"))).isNotNull()
        assertThat(provider.getTypeAnonymous(Uri.parse("$base/${AutomationContract.PATH_LEVEL}"))).isNull()
        val level = Uri.parse("$base/${AutomationContract.PATH_LEVEL}")
        assertThat(runCatching { provider.insert(level, null) }.exceptionOrNull()).isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(
            runCatching {
                provider.update(level, null, null, null)
            }.exceptionOrNull(),
        ).isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(
            runCatching { provider.delete(level, null, null) }.exceptionOrNull(),
        ).isInstanceOf(UnsupportedOperationException::class.java)
    }
}
