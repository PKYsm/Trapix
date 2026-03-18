package com.trapix.app.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trapix.app.data.db.AppDatabase
import com.trapix.app.data.model.IntruderLog
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).intruderDao()
    val allLogs = dao.getAllLogs()
    val totalCount = dao.getCount()

    fun delete(log: IntruderLog) = viewModelScope.launch { dao.delete(log) }
    fun deleteByIds(ids: List<Long>) = viewModelScope.launch { dao.deleteByIds(ids) }
    fun deleteAll() = viewModelScope.launch { dao.deleteAll() }
}
