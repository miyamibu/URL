package jp.mimac.urlsaver.data

import androidx.room.TypeConverter
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType

class Converters {
    @TypeConverter
    fun toRecordState(value: String): RecordState = RecordState.valueOf(value)

    @TypeConverter
    fun fromRecordState(value: RecordState): String = value.name

    @TypeConverter
    fun toServiceType(value: String): ServiceType = ServiceType.valueOf(value)

    @TypeConverter
    fun fromServiceType(value: ServiceType): String = value.name

    @TypeConverter
    fun toContentContext(value: String): ContentContext = ContentContext.valueOf(value)

    @TypeConverter
    fun fromContentContext(value: ContentContext): String = value.name

    @TypeConverter
    fun toMetadataState(value: String): MetadataState = MetadataState.valueOf(value)

    @TypeConverter
    fun fromMetadataState(value: MetadataState): String = value.name

    @TypeConverter
    fun toMetadataError(value: String?): MetadataError? = value?.let { MetadataError.valueOf(it) }

    @TypeConverter
    fun fromMetadataError(value: MetadataError?): String? = value?.name
}
