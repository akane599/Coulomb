// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.appdetail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.designsystem.component.CoulombOutlinedButton
import io.github.akane599.coulomb.core.designsystem.component.FadeInOnce
import io.github.akane599.coulomb.core.designsystem.component.SkeletonBlock
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.WindowWidth
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentWindowWidth
import io.github.akane599.coulomb.core.ui.DataTableSheet
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import io.github.akane599.coulomb.core.ui.rememberAppIcon
import java.time.ZoneId

/**
 * The screen's model for [uid] in the current navigation entry: the shell's title and the screen
 * share it (one entry, one store).
 */
@Composable
public fun appDetailViewModel(uid: Int): AppDetailViewModel =
    hiltViewModel<AppDetailViewModel, AppDetailViewModel.Factory>(key = "app-$uid", creationCallback = { it.create(uid) })

/** The app's name for the top app bar (lab: the app bar's title), or nothing while it loads. */
@Composable
public fun appDetailTitle(viewModel: AppDetailViewModel): String {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = (state as? AppDetailUiState.Ready)?.detail
    val formats = rememberFormats()
    return detail?.let { formats.appName(it.label, it.uid) }.orEmpty()
}

/**
 * App detail (SPEC 10.4): one app over the window chosen on Drain. The story, its Act insight and
 * the actions arrive in P5 with the insight engine and the Action Center (ADR-0041); App info
 * opens the system's page for the app.
 */
@Composable
public fun AppDetailScreen(
    viewModel: AppDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions =
        remember(viewModel, context) {
            AppDetailActions(
                retry = viewModel::retry,
                openAppInfo = { packageName ->
                    // The public settings page for one package (android.provider.Settings).
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // A build without the page: nothing to open, and nothing lost.
                    }
                },
                icon = viewModel::icon,
                peekIcon = viewModel::peekIcon,
            )
        }
    AppDetailContent(state, actions, modifier)
}

/** What App detail's controls do. */
internal class AppDetailActions(
    val retry: () -> Unit,
    val openAppInfo: (packageName: String) -> Unit,
    val icon: suspend (packageName: String, sizePx: Int) -> ImageBitmap?,
    val peekIcon: (packageName: String, sizePx: Int) -> ImageBitmap? = { _, _ -> null },
)

/** The two tables a chart opens ("View as table", SPEC 11.8). */
internal enum class AppTable { COMPONENTS, TREND }

@Composable
internal fun AppDetailContent(
    state: AppDetailUiState,
    actions: AppDetailActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = remember(state) { ZoneId.systemDefault() },
) {
    val gutter = currentGutter()
    when (state) {
        AppDetailUiState.Loading -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { Skeleton() }
        }

        AppDetailUiState.Error -> {
            Column(modifier.fillMaxSize().padding(horizontal = gutter)) { ErrorState(actions.retry) }
        }

        is AppDetailUiState.Ready -> {
            val detail = state.detail
            if (detail == null) {
                Column(modifier.fillMaxSize().padding(horizontal = gutter)) { Empty() }
            } else {
                val view = remember(detail, state.scaled) { AppDetailView.of(detail, state.scaled) }
                FadeInOnce(modifier.fillMaxSize()) { Ready(view, actions, zone) }
            }
        }
    }
}

/**
 * One list on compact widths. Where two columns of [MinColumnWidth] fit (SPEC 10.10 medium and
 * up), the app's figure and where it went fill the first, and its wakeups, network and trend the
 * second, as Drain splits its summary from its lens (ADR-0036 §23).
 */
@Composable
private fun Ready(
    view: AppDetailView,
    actions: AppDetailActions,
    zone: ZoneId,
) {
    val formats = rememberFormats()
    val gutter = currentGutter()
    var table by rememberSaveable { mutableStateOf<AppTable?>(null) }
    val icon = rememberAppIcon(view.detail.iconPackage, actions.icon, actions.peekIcon)
    val sections = Sections(view, formats, icon, zone, actions) { table = it }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = MinColumnWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val twoColumns = currentWindowWidth() != WindowWidth.Compact && maxWidth - gutter * GUTTERS >= column * 2
        if (!twoColumns) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Bottom)) {
                sections.first(this)
                sections.second(this)
            }
        } else {
            Row(
                Modifier.widthIn(max = MaxTwoColumnWidth).fillMaxSize().padding(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(gutter),
            ) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Bottom)) { sections.first(this) }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Bottom)) {
                    sections.second(this, besideFirst = true)
                }
            }
        }
    }
    table?.let { open -> DataTableSheet(tableOf(open, view, formats), onDismiss = { table = null }) }
}

/** The screen's sections as list items, in the two groups the wide layout splits them into. */
private class Sections(
    val view: AppDetailView,
    val formats: Formats,
    val icon: ImageBitmap?,
    val zone: ZoneId,
    val actions: AppDetailActions,
    val viewTable: (AppTable) -> Unit,
) {
    fun first(scope: LazyListScope) {
        scope.item(key = "header", contentType = "header") { Header(view, view.detail.report.span, formats, icon) }
        if (view.detail.app != null) {
            scope.item(key = "where", contentType = "where") { WhereItWent(view, formats) { viewTable(AppTable.COMPONENTS) } }
        }
    }

    fun second(
        scope: LazyListScope,
        besideFirst: Boolean = false,
    ) {
        scope.item(key = "woke", contentType = "woke") { Woke(view, formats, besideFirst) }
        scope.item(key = "network", contentType = "network") { Network(view, formats) }
        scope.item(key = "trend", contentType = "trend") { Trend(view, formats, zone) { viewTable(AppTable.TREND) } }
        view.detail.iconPackage?.let { packageName ->
            scope.item(key = "app-info", contentType = "link") { AppInfoRow { actions.openAppInfo(packageName) } }
        }
    }
}

/** Two columns have a gutter at each side and one between them. */
private const val GUTTERS = 3

/** Each column needs this much at 100% text; larger text needs it in proportion (as Drain). */
private val MinColumnWidth = 280.dp
private val MaxTwoColumnWidth = 1200.dp
private val Bottom: Dp = CoulombSpacing.Space48

/** SPEC 10.11 loading: a still skeleton in the header's and first section's shape. */
@Composable
private fun Skeleton() {
    val loading = stringResource(R.string.appdetail_loading)
    Column(Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8).semantics { contentDescription = loading }) {
        Row {
            SkeletonBlock(40.dp, Modifier.width(40.dp))
            Column(Modifier.padding(start = CoulombSpacing.Space16)) {
                SkeletonBlock(14.dp, Modifier.width(180.dp))
                SkeletonBlock(12.dp, Modifier.padding(top = CoulombSpacing.Space8).width(220.dp))
            }
        }
        SkeletonBlock(60.dp, Modifier.padding(top = CoulombSpacing.Space24).width(180.dp))
        SkeletonBlock(16.dp, Modifier.padding(top = CoulombSpacing.Space8).width(260.dp))
        SkeletonBlock(26.dp, Modifier.padding(top = CoulombSpacing.Space32).width(160.dp))
        SkeletonBlock(14.dp, Modifier.padding(top = CoulombSpacing.Space16).fillMaxWidth())
    }
}

/** SPEC 10.11 empty: the window has no two ends yet. */
@Composable
private fun Empty() {
    Text(
        stringResource(R.string.appdetail_empty),
        Modifier.widthIn(max = ReadingWidth).padding(top = CoulombSpacing.Space24),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.body,
    )
}

private val ReadingWidth = 560.dp

/** SPEC 10.11 error: what happened and what to do. */
@Composable
private fun ErrorState(retry: () -> Unit) {
    Column(Modifier.widthIn(max = ReadingWidth).padding(top = CoulombSpacing.Space24)) {
        Text(
            stringResource(R.string.appdetail_error_title),
            Modifier.fillMaxWidth().semantics { heading() },
            color = CoulombTheme.colors.textPrimary,
            style = CoulombTheme.typography.title,
        )
        Text(
            stringResource(R.string.appdetail_error_body),
            Modifier.fillMaxWidth().padding(top = CoulombSpacing.Space8),
            color = CoulombTheme.colors.textSecondary,
            style = CoulombTheme.typography.body,
        )
        CoulombOutlinedButton(
            text = stringResource(R.string.appdetail_error_action),
            onClick = retry,
            modifier = Modifier.padding(top = CoulombSpacing.Space16),
        )
    }
}
