package jp.mimac.urlsaver.video

import android.content.Context
import android.net.Uri
import java.io.File

object AppMediaStore {
    private const val SCHEME = "app-media"
    private const val AUTHORITY = "media"
    private const val ROOT_DIRECTORY = "media"

    fun fileFor(context: Context, entryId: Long, fileName: String): File {
        val directory = File(File(context.filesDir, ROOT_DIRECTORY), entryId.toString())
        return File(directory, fileName)
    }

    fun localUri(entryId: Long, fileName: String): String {
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(AUTHORITY)
            .appendPath(entryId.toString())
            .appendPath(fileName)
            .build()
            .toString()
    }

    fun fileForLocalUri(context: Context, localUri: String?): File? {
        val uri = runCatching { Uri.parse(localUri ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY) return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val entryId = segments[0].toLongOrNull() ?: return null
        val fileName = segments[1].takeIf { it.isNotBlank() && "/" !in it } ?: return null
        val file = fileFor(context, entryId, fileName)
        val root = File(context.filesDir, ROOT_DIRECTORY).canonicalFile
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }
}
