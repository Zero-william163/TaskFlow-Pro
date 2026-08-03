package com.taskflow.app.ui.permission

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PermissionViewModel(
    private val permissionManager: com.taskflow.app.permission.PermissionManager
) : ViewModel() {

    private val _items = MutableStateFlow(permissionManager.all())
    val items: StateFlow<List<com.taskflow.app.permission.PermissionItem>> = _items.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _items.value = permissionManager.all() }
    }

    fun intentFor(type: com.taskflow.app.permission.PermissionType): Intent? =
        permissionManager.intentFor(type)
}
