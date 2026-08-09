package com.cfst.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.cfst.android.ui.nav.AppNavHost
import com.cfst.android.ui.theme.CfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CfTheme {
                AppNavHost()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppPreview() {
    CfTheme {
        AppNavHost()
    }
}