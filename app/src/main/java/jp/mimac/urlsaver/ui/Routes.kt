package jp.mimac.urlsaver.ui

object Routes {
    const val MAIN = "main"
    const val ARCHIVE = "archive"
    const val DETAIL_PATTERN = "detail/{entryId}"

    fun detail(entryId: Long): String = "detail/$entryId"
}
