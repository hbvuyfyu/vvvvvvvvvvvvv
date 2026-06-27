package com.vcam.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vcam.utils.RootManager
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _mediaUri          = MutableLiveData<Uri?>()
    val mediaUri: LiveData<Uri?>   = _mediaUri

    private val _isVideo           = MutableLiveData(false)
    val isVideo: LiveData<Boolean> = _isVideo

    private val _isServiceRunning           = MutableLiveData(false)
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning

    private val _rootStatus           = MutableLiveData(false)
    val rootStatus: LiveData<Boolean> = _rootStatus

    fun initRoot() {
        viewModelScope.launch {
            _rootStatus.value = RootManager.requestRoot()
        }
    }

    fun setMediaUri(uri: Uri, context: Context) {
        _mediaUri.value  = uri
        val mime         = context.contentResolver.getType(uri)
        _isVideo.value   = mime?.startsWith("video/") == true
    }

    fun clearMedia() { _mediaUri.value = null; _isVideo.value = false }
    fun setServiceRunning(running: Boolean) { _isServiceRunning.value = running }
}
