package io.lanealert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.lanealert.ui.DriveScreen
import io.lanealert.ui.DriveViewModel

/**
 * 시연 자동화(adb):
 *   am start -n io.lanealert/.MainActivity --ei scenario 0 --ez play true [--ez fast true] [--ez car true]
 *   am start -n io.lanealert/.MainActivity --es ask "몇 차로야?"
 *   am start -n io.lanealert/.MainActivity --ez live true
 */
class MainActivity : ComponentActivity() {
    private val vm: DriveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val need = listOf(Manifest.permission.RECORD_AUDIO, "android.car.permission.CAR_SPEED")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        setContent { DriveScreen(vm) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        if (i.hasExtra("car")) vm.setCarSignals(i.getBooleanExtra("car", false))
        if (i.hasExtra("fast")) vm.setFast(i.getBooleanExtra("fast", false))
        if (i.hasExtra("demo") && i.getBooleanExtra("demo", false) != vm.ui.value.demoOpen) vm.toggleDemo()
        if (i.hasExtra("scenario")) vm.selectScenario(i.getIntExtra("scenario", 0), autoplay = i.getBooleanExtra("play", false))
        if (i.getBooleanExtra("live", false)) vm.toggleLive()
        if (i.getBooleanExtra("listen", false)) vm.startQuestion()
        i.getStringExtra("ask")?.let { vm.ask(it) }
    }
}
