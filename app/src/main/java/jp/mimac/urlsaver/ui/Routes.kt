package jp.mimac.urlsaver.ui

object Routes {
    const val MAIN = "main"
    const val ARCHIVE = "archive"
    const val EXPORT = "export"
    const val DETAIL_PATTERN = "detail/{entryId}"
    const val TAG_DETAIL_PATTERN = "tag/{tagId}"
    const val INVITE_PATTERN = "invite/{inviteToken}"
    const val CLOUD_AUTH = "cloud-auth"
    const val CLOUD_AUTH_PATTERN = "cloud-auth?promoCode={promoCode}"

    fun detail(entryId: Long): String = "detail/$entryId"
    fun tagDetail(tagId: Long): String = "tag/$tagId"
    fun invite(inviteToken: String): String = "invite/$inviteToken"
    fun cloudAuth(promoCode: String? = null): String {
        return if (promoCode.isNullOrBlank()) CLOUD_AUTH else "cloud-auth?promoCode=${android.net.Uri.encode(promoCode)}"
    }
}
