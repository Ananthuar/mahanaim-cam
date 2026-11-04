package com.mahanaim.cam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mahanaim.cam.ui.screens.HomeScreen
import com.mahanaim.cam.ui.theme.MahanaimCamTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MahanaimCamTheme {
                HomeScreen()
            }
        }
    }
}
