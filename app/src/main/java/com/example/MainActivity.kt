package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.SynapseAppRoot
import com.example.ui.viewmodel.SynapseViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SynapseViewModel by viewModels {
        SynapseViewModel.Factory(application as SynapseApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynapseAppRoot(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAll()
    }
}
