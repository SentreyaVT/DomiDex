package com.dominik.cardvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DomiDexBottomBarInsetsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottomInsetIsReservedBelowNavigationSurface() {
        compose.setContent {
            Box(Modifier.requiredSize(width = 400.dp, height = 300.dp)) {
                DomiDexBottomBarFrame(
                    modifier = Modifier.testTag("bottom-frame"),
                    bottomInsets = WindowInsets(bottom = 24),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .testTag("bottom-surface"),
                    )
                }
            }
        }

        val frame = compose.onNodeWithTag("bottom-frame").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("bottom-surface").fetchSemanticsNode().boundsInRoot

        assertEquals(24f, frame.bottom - surface.bottom, 0.5f)
    }

    @Test
    fun compactBottomBarKeepsAvailableWidth() {
        compose.setContent {
            Box(Modifier.requiredSize(width = 400.dp, height = 300.dp)) {
                DomiDexBottomBarFrame(
                    modifier = Modifier.testTag("bottom-frame"),
                    bottomInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    Box(Modifier.fillMaxSize().testTag("bottom-surface"))
                }
            }
        }

        val frame = compose.onNodeWithTag("bottom-frame").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("bottom-surface").fetchSemanticsNode().boundsInRoot

        assertEquals(frame.width, surface.width, 0.5f)
    }

    @Test
    fun horizontalSafeInsetsKeepNavigationActionsOutOfCutouts() {
        compose.setContent {
            Box(Modifier.requiredSize(width = 400.dp, height = 300.dp)) {
                DomiDexBottomBarFrame(
                    modifier = Modifier.testTag("bottom-frame"),
                    bottomInsets = WindowInsets(left = 18, top = 0, right = 22, bottom = 0),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .testTag("bottom-surface"),
                    )
                }
            }
        }

        val frame = compose.onNodeWithTag("bottom-frame").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("bottom-surface").fetchSemanticsNode().boundsInRoot

        assertEquals(18f, surface.left - frame.left, 0.5f)
        assertEquals(22f, frame.right - surface.right, 0.5f)
    }
}
