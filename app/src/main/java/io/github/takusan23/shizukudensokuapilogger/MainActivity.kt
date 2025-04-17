package io.github.takusan23.shizukudensokuapilogger

import android.app.PictureInPictureParams
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.takusan23.shizukudensokuapilogger.ui.theme.ShizukuDensokuApiLoggerTheme
import org.lsposed.hiddenapibypass.HiddenApiBypass

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // リフレクション出来ない隠し API を叩くおまじない
        HiddenApiBypass.addHiddenApiExemptions("")

        // 自動 PinP
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .setAutoEnterEnabled(true)
                .build()
        )

        setContent {
            ShizukuDensokuApiLoggerTheme {
                MainScreen()
            }
        }
    }
}