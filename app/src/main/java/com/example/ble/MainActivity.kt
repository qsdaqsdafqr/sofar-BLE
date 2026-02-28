package com.example.ble

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.example.ble.presentation.BleViewModel
import com.example.ble.ui.BleApp
import com.example.ble.ui.theme.BLETheme

class MainActivity : ComponentActivity() {
    private val bleViewModel: BleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BLETheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    bleViewModel.refreshPermissionState()
                }
                LaunchedEffect(Unit) {
                    bleViewModel.refreshPermissionState()
                }
                BleApp(
                    viewModel = bleViewModel,
                    requestPermissions = {
                        permissionLauncher.launch(BlePermissionHelper.requiredPermissions())
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bleViewModel.refreshPermissionState()
    }
}
