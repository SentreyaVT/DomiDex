package com.dominik.cardvault.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.ModalBottomSheet as MaterialModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.collect

internal enum class DomiDexWindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

internal fun domiDexWindowWidthClass(windowWidth: Dp): DomiDexWindowWidthClass = when {
    windowWidth < 600.dp -> DomiDexWindowWidthClass.COMPACT
    windowWidth < 840.dp -> DomiDexWindowWidthClass.MEDIUM
    else -> DomiDexWindowWidthClass.EXPANDED
}

internal fun domiDexContentWidth(windowWidth: Dp): Dp = when (domiDexWindowWidthClass(windowWidth)) {
    DomiDexWindowWidthClass.COMPACT -> windowWidth
    DomiDexWindowWidthClass.MEDIUM -> minOf(windowWidth, 720.dp)
    DomiDexWindowWidthClass.EXPANDED -> minOf(windowWidth, 960.dp)
}

internal data class DomiDexHorizontalPane(val start: Dp, val width: Dp)

/** Selects one unobstructed pane when a separating vertical hinge crosses the window. */
internal fun domiDexHorizontalPane(
    windowWidth: Dp,
    hingeStart: Dp?,
    hingeEnd: Dp?,
): DomiDexHorizontalPane {
    val start = hingeStart ?: return DomiDexHorizontalPane(0.dp, windowWidth)
    val end = hingeEnd ?: return DomiDexHorizontalPane(0.dp, windowWidth)
    if (start <= 0.dp || end < start || end >= windowWidth) return DomiDexHorizontalPane(0.dp, windowWidth)
    val leftWidth = start
    val rightWidth = windowWidth - end
    return if (rightWidth > leftWidth) DomiDexHorizontalPane(end, rightWidth)
    else DomiDexHorizontalPane(0.dp, leftWidth)
}

internal fun domiDexDialogHorizontalOffset(windowWidth: Dp, pane: DomiDexHorizontalPane): Dp =
    pane.start + (pane.width / 2) - (windowWidth / 2)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun rememberSeparatingVerticalHinge(): Rect? {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return null
    val feature by produceState<FoldingFeature?>(initialValue = null, activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            value = info.displayFeatures
                .filterIsInstance<FoldingFeature>()
                .firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
        }
    }
    return feature?.bounds
}

@Composable
private fun adaptivePane(windowWidth: Dp): DomiDexHorizontalPane {
    val hinge = rememberSeparatingVerticalHinge()
    val density = LocalDensity.current
    return if (hinge == null) DomiDexHorizontalPane(0.dp, windowWidth) else with(density) {
        domiDexHorizontalPane(windowWidth, hinge.left.toDp(), hinge.right.toDp())
    }
}

/**
 * Material dialogs use their own window, so the activity content's fold-aware pane cannot move
 * them. This wrapper only changes dialog placement when a separating vertical hinge is present;
 * ordinary phones keep the platform-default dialog behavior byte-for-byte.
 */
@Composable
internal fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val hinge = rememberSeparatingVerticalHinge()
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = context.findActivity()
    val windowWidth = with(density) { (activity?.window?.decorView?.width ?: 0).toDp() }
    val pane = if (hinge == null || windowWidth <= 0.dp) null else with(density) {
        domiDexHorizontalPane(windowWidth, hinge.left.toDp(), hinge.right.toDp())
    }
    val hingeModifier = pane?.let {
        modifier
            .offset(x = domiDexDialogHorizontalOffset(windowWidth, it))
            .width(minOf(560.dp, (it.width - 48.dp).coerceAtLeast(1.dp)))
    } ?: modifier
    val hingeProperties = if (pane == null) properties else DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = properties.decorFitsSystemWindows,
        windowTitle = properties.windowTitle,
    )
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = hingeModifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = hingeProperties,
    )
}

/** Keeps modal sheet content inside the selected pane while retaining the full-window scrim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DomiDexModalBottomSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hinge = rememberSeparatingVerticalHinge()
    val context = LocalContext.current
    val density = LocalDensity.current
    val activity = context.findActivity()
    val windowWidth = with(density) { (activity?.window?.decorView?.width ?: 0).toDp() }
    val pane = if (hinge == null || windowWidth <= 0.dp) null else with(density) {
        domiDexHorizontalPane(windowWidth, hinge.left.toDp(), hinge.right.toDp())
    }
    val modifier = pane?.let {
        Modifier
            .offset(x = domiDexDialogHorizontalOffset(windowWidth, it))
            .width((it.width - 1.dp).coerceAtLeast(1.dp))
    } ?: Modifier
    MaterialModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = containerColor,
        content = content,
    )
}

/** Keeps the phone layout unchanged while preventing it from stretching across wide windows. */
@Composable
internal fun DomiDexAdaptiveContent(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        val pane = adaptivePane(maxWidth)
        Box(
            modifier = Modifier
                .offset(x = pane.start)
                .width(pane.width)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(domiDexContentWidth(pane.width))
                    .fillMaxHeight(),
                content = content,
            )
        }
    }
}

/**
 * Reserves the bottom gesture/navigation area outside the visual navigation surface.
 * An injectable inset keeps this behavior directly testable without changing production UI.
 */
@Composable
internal fun DomiDexBottomBarFrame(
    modifier: Modifier = Modifier,
    bottomInsets: WindowInsets = WindowInsets.safeDrawing
        .union(WindowInsets.safeGestures)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        val pane = adaptivePane(maxWidth)
        Box(
            modifier = Modifier
                .offset(x = pane.start)
                .width(pane.width)
                .windowInsetsPadding(bottomInsets),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier.width(domiDexContentWidth(maxWidth)),
                    content = content,
                )
            }
        }
    }
}
