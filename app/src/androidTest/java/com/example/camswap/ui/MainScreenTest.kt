package com.example.camswap.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.camswap.ui.theme.CamSwapTheme
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainScreen_displaysTitle() {
        composeTestRule.setContent {
            CamSwapTheme {
                MainScreen(
                    onPermissionRequest = {},
                    onUploadImage = {}
                )
            }
        }

        composeTestRule.onNodeWithText("媒体源设置").assertIsDisplayed()
    }

    @Test
    fun mainScreen_switchesExist() {
        composeTestRule.setContent {
            CamSwapTheme {
                MainScreen(
                    onPermissionRequest = {},
                    onUploadImage = {}
                )
            }
        }

        composeTestRule.onNodeWithText("强制显示警告").assertIsDisplayed()
        composeTestRule.onNodeWithText("禁用模块").assertIsDisplayed()
    }
    
    @Test
    fun mainScreen_clickUploadImage_triggersCallback() {
        var clicked = false
        composeTestRule.setContent {
            CamSwapTheme {
                MainScreen(
                    onPermissionRequest = {},
                    onUploadImage = { clicked = true }
                )
            }
        }
        
        composeTestRule.onNodeWithText("上传图片").performClick()
        assert(clicked)
    }
}
