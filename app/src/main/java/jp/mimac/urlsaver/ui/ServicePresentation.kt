package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.domain.ServiceType

fun serviceTypeForUi(serviceType: ServiceType): ServiceType {
    return serviceType
}

fun serviceLabelForList(serviceType: ServiceType, normalizedHost: String): String {
    val uiType = serviceTypeForUi(serviceType)
    return when (uiType) {
        ServiceType.WEB -> normalizedHost
        ServiceType.TIKTOK -> "TikTok"
        else -> uiType.displayName
    }
}

fun filterLabelForService(serviceType: ServiceType): String {
    return when (serviceType) {
        ServiceType.ALL -> "すべて"
        ServiceType.YOUTUBE -> "YOUTUBE"
        ServiceType.TIKTOK -> "TIKTOK"
        ServiceType.X -> "X"
        ServiceType.INSTAGRAM -> "INSTAGRAM"
        ServiceType.WEB -> "WEB"
    }
}

fun serviceForFilterMatch(serviceType: ServiceType): ServiceType {
    return serviceType
}
