package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.StaffViewModel
import com.example.myapplication.ui.screens.StaffAppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val staffViewModel: StaffViewModel = viewModel(
                factory = StaffViewModel.factory(applicationContext)
            )
            StaffAppRoot(viewModel = staffViewModel)
        }
    }
}
