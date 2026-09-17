package com.personal.assistant.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.assistant.AppContainer

/**
 * Builds a screen's ViewModel from the app container.
 *
 * Small enough to not want a DI framework, but still routed through [viewModel] so state survives
 * configuration changes instead of being rebuilt on every rotation.
 */
@Composable
inline fun <reified T : ViewModel> containerViewModel(
    container: AppContainer,
    key: String? = null,
    crossinline create: (AppContainer) -> T,
): T = viewModel(
    key = key ?: T::class.java.name,
    factory = viewModelFactory { initializer { create(container) } },
)
