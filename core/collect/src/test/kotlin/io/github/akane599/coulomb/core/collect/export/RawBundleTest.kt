// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.export

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** SPEC 14.5's raw bundle: the dumps as Android printed them, and a README that says what is missing. */
class RawBundleTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val origin = RawOrigin(writtenAtWallMs = 1_800_000_000_000, appVersionName = "1.0", fingerprint = "lab/build", sdkInt = 37)

    private fun entries(bytes: ByteArray): Map<String, String> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes().toString(Charsets.UTF_8) }
        }

    @Test
    fun theDumpsGoInUnchangedAndTheScratchFolderIsEmptied() =
        runTest {
            val asked = ArrayList<BatteryStatsMode>()
            val bundle =
                RawBundle { mode, into ->
                    asked += mode
                    into.writeText(if (mode == BatteryStatsMode.CHECKIN) "9,0,i,vers,36\n" else "Battery History:\n  0 (2) 100\n")
                    RawPart.COMPLETE
                }
            val scratch = folder.root.resolve("raw")
            val out = ByteArrayOutputStream()

            val result = bundle.write(out, scratch, origin)

            // `-c`, never `--checkin`, which deletes a system file (ADR-0005).
            assertThat(asked).containsExactly(BatteryStatsMode.CHECKIN, BatteryStatsMode.HISTORY).inOrder()
            assertThat(result).isEqualTo(RawBundleResult(RawPart.COMPLETE, RawPart.COMPLETE))
            val files = entries(out.toByteArray())
            assertThat(files.keys).containsExactly("README.txt", "batterystats-checkin.txt", "batterystats-history.txt").inOrder()
            assertThat(files.getValue("batterystats-checkin.txt")).isEqualTo("9,0,i,vers,36\n")
            assertThat(files.getValue("batterystats-history.txt")).isEqualTo("Battery History:\n  0 (2) 100\n")
            assertThat(files.getValue("README.txt")).contains("lab/build (SDK 37)")
            assertThat(scratch.exists()).isFalse()
        }

    @Test
    fun aDumpThatCouldNotBeReadIsLeftOutAndSaidSo() =
        runTest {
            val bundle =
                RawBundle { mode, into ->
                    if (mode == BatteryStatsMode.CHECKIN) {
                        into.writeText("9,0,i,vers")
                        RawPart.PARTIAL
                    } else {
                        RawPart.MISSING
                    }
                }
            val out = ByteArrayOutputStream()

            val result = bundle.write(out, folder.root.resolve("raw"), origin)

            assertThat(result.empty).isFalse()
            val files = entries(out.toByteArray())
            assertThat(files.keys).containsExactly("README.txt", "batterystats-checkin.txt")
            assertThat(
                files.getValue("README.txt"),
            ).contains("batterystats-checkin.txt: `dumpsys batterystats -c`, the current statistics in checkin format. Cut short")
            assertThat(files.getValue("README.txt")).contains("Not in this bundle: Coulomb could not read it.")
            assertThat(RawBundleResult(RawPart.MISSING, RawPart.MISSING).empty).isTrue()
        }

    @Test
    fun aDumpLargerThanTheBundleTakesIsCutAndReadToItsEnd() {
        val file = folder.root.resolve("big")
        val input = ByteArrayInputStream(ByteArray(1_000) { 'a'.code.toByte() })

        assertThat(RawBundle.copyCapped(input, file, max = 300)).isFalse()

        assertThat(file.length()).isEqualTo(300)
        // Read on to the end, so the producer is not left blocked on a full pipe.
        assertThat(input.available()).isEqualTo(0)
        assertThat(RawBundle.copyCapped(ByteArrayInputStream(ByteArray(10)), file, max = 300)).isTrue()
        assertThat(file.length()).isEqualTo(10)
    }
}
