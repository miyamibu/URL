package jp.mimac.urlsaver.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

sealed interface OpenUrlResult {
    data object Success : OpenUrlResult
    data object NoHandler : OpenUrlResult
    data object Failed : OpenUrlResult
}

fun Context.tryOpenExternalUrl(openUrl: String): OpenUrlResult {
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)))
        OpenUrlResult.Success
    } catch (_: ActivityNotFoundException) {
        OpenUrlResult.NoHandler
    } catch (_: Exception) {
        OpenUrlResult.Failed
    }
}
