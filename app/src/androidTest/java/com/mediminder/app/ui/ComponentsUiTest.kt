package com.mediminder.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediminder.app.domain.model.DoseDisplayStatus
import com.mediminder.app.ui.components.BigButton
import com.mediminder.app.ui.components.DoseStatusChip
import com.mediminder.app.ui.theme.MediMinderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic Compose UI tests: the shared building blocks render and react.
 * Run on a device/emulator:  ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ComponentsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusChip_showsLabelForEveryStatus() {
        composeRule.setContent {
            MediMinderTheme {
                androidx.compose.foundation.layout.Column {
                    DoseStatusChip(status = DoseDisplayStatus.TAKEN)
                    DoseStatusChip(status = DoseDisplayStatus.MISSED)
                    DoseStatusChip(status = DoseDisplayStatus.UPCOMING)
                    DoseStatusChip(status = DoseDisplayStatus.SKIPPED)
                    DoseStatusChip(status = DoseDisplayStatus.SNOOZED)
                }
            }
        }
        composeRule.onNodeWithText("Taken").assertExists()
        composeRule.onNodeWithText("Missed").assertExists()
        composeRule.onNodeWithText("Upcoming").assertExists()
        composeRule.onNodeWithText("Skipped").assertExists()
        composeRule.onNodeWithText("Snoozed").assertExists()
    }

    @Test
    fun bigButton_firesOnClick() {
        var clicks = 0
        composeRule.setContent {
            MediMinderTheme {
                BigButton(text = "Press me", onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithText("Press me").performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(1, clicks)
    }
}
