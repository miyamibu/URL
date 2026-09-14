package jp.mimac.urlsaver.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import jp.mimac.urlsaver.UrlSaverApp
import jp.mimac.urlsaver.app.AppContainer

fun Context.appContainer(): AppContainer = (applicationContext as UrlSaverApp).container

class SimpleFactory<T : ViewModel>(
    private val creator: () -> T,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
}

class SavedStateFactory<T : ViewModel>(
    private val creator: (SavedStateHandle) -> T,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>, extras: CreationExtras): VM {
        return creator(extras.createSavedStateHandle()) as VM
    }
}
