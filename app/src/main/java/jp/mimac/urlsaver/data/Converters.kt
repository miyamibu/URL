package jp.mimac.urlsaver.data

import androidx.room.TypeConverter
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagMemberStatus
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.domain.SharedTagSyncOperationType

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

    @TypeConverter
    fun toMetadataBodyKind(value: String?): MetadataBodyKind? = value?.let { MetadataBodyKind.valueOf(it) }

    @TypeConverter
    fun fromMetadataBodyKind(value: MetadataBodyKind?): String? = value?.name

    @TypeConverter
    fun toSharedTagScope(value: String): SharedTagScope = SharedTagScope.valueOf(value)

    @TypeConverter
    fun fromSharedTagScope(value: SharedTagScope): String = value.name

    @TypeConverter
    fun toSharedTagSyncStatus(value: String): SharedTagSyncStatus = SharedTagSyncStatus.valueOf(value)

    @TypeConverter
    fun fromSharedTagSyncStatus(value: SharedTagSyncStatus): String = value.name

    @TypeConverter
    fun toSharedTagMemberRole(value: String): SharedTagMemberRole = SharedTagMemberRole.valueOf(value)

    @TypeConverter
    fun fromSharedTagMemberRole(value: SharedTagMemberRole): String = value.name

    @TypeConverter
    fun toSharedTagMemberStatus(value: String): SharedTagMemberStatus = SharedTagMemberStatus.valueOf(value)

    @TypeConverter
    fun fromSharedTagMemberStatus(value: SharedTagMemberStatus): String = value.name

    @TypeConverter
    fun toSharedTagSyncOperationType(value: String): SharedTagSyncOperationType = SharedTagSyncOperationType.valueOf(value)

    @TypeConverter
    fun fromSharedTagSyncOperationType(value: SharedTagSyncOperationType): String = value.name

    @TypeConverter
    fun toSharedTagSyncOutboxState(value: String): SharedTagSyncOutboxState = SharedTagSyncOutboxState.valueOf(value)

    @TypeConverter
    fun fromSharedTagSyncOutboxState(value: SharedTagSyncOutboxState): String = value.name
}
