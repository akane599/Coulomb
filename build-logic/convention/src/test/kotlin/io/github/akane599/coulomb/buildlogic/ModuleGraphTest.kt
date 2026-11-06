// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModuleGraphTest {
    @Test
    fun `allowed dependencies pass`() {
        assertThat(ModuleGraph.violations(":core:collect", setOf(":core:privileged", ":core:parsing", ":core:database"))).isEmpty()
        assertThat(ModuleGraph.violations(":feature:now", setOf(":core:ui", ":core:model"))).isEmpty()
        assertThat(ModuleGraph.violations(":app", setOf(":feature:now", ":core:collect", ":widget"))).isEmpty()
    }

    @Test
    fun `features never depend on each other`() {
        assertThat(ModuleGraph.violations(":feature:now", setOf(":feature:drain"))).containsExactly(":feature:drain")
    }

    @Test
    fun `pure modules stay pure`() {
        assertThat(ModuleGraph.violations(":core:analytics", setOf(":core:database"))).containsExactly(":core:database")
        assertThat(ModuleGraph.violations(":core:model", setOf(":core:common"))).containsExactly(":core:common")
        assertThat(ModuleGraph.violations(":core:designsystem", setOf(":core:model"))).containsExactly(":core:model")
    }

    @Test
    fun `only core privileged is reachable from collect, not the UI`() {
        assertThat(ModuleGraph.violations(":core:collect", setOf(":core:ui"))).containsExactly(":core:ui")
    }

    @Test
    fun `unknown modules must be added to the graph first`() {
        assertThat(ModuleGraph.violations(":core:new", emptySet())).hasSize(1)
    }

    @Test
    fun `nothing may depend on the benchmark module`() {
        assertThat(ModuleGraph.violations(":app", setOf(":benchmark"))).containsExactly(":benchmark")
    }
}
