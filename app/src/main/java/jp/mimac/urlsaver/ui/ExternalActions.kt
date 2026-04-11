package jp.mimac.urlsaver.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

fun Context.tryOpenExternalUrl(openUrl: String): Boolean {
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
