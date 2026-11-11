package io.lanealert

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.lanealert.ui.DriveScreen
import io.lanealert.ui.DriveViewModel

class MainActivity : ComponentActivity() {
    private val vm: DriveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val need = listOf(Manifest.permission.RECORD_AUDIO, "android.car.permission.CAR_SPEED")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        setContent { DriveScreen(vm) }
        // 시연 자동화: adb shell am start -n io.lanealert/.MainActivity --ei scenario 0 --ez play true
        intent?.let {
            if (it.hasExtra("scenario")) vm.selectScenario(it.getIntExtra("scenario", 0), autoplay = it.getBooleanExtra("play", false))
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("scenario")) vm.selectScenario(intent.getIntExtra("scenario", 0), autoplay = intent.getBooleanExtra("play", false))
        intent.getStringExtra("ask")?.let { vm.ask(it) }
        if (intent.getBooleanExtra("fast", false) != vm.ui.value.fast && intent.hasExtra("fast")) vm.toggleFast()
    }
}
