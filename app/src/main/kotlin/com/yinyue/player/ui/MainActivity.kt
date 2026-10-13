package com.yinyue.player.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yinyue.player.ui.navigation.AppRoot
import com.yinyue.player.ui.theme.YinYueTheme
import com.yinyue.player.player.PlayerController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerController.connect(this)
        setContent {
            YinYueTheme {
                AppRoot()
            }
        }
    }
}
