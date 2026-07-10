package jp.mimac.urlsaver.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import jp.mimac.urlsaver.UrlSaverApp
import jp.mimac.urlsaver.app.AppContainer

fun Context.appContainer(): AppContainer = (applicationContext as UrlSaverApp).container

class SimpleFactory<T : ViewModel>(
    private val creator: () -> T,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
}
