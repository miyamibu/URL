package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.TagRepository
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.TagWithCount
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TagListViewModel(
    private val tagRepository: TagRepository,
) : ViewModel() {

    val tags: StateFlow<List<TagWithCount>> = tagRepository.observeAllTagsWithCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun createTag(name: String): CreateTagResult {
        return tagRepository.createTagWithResult(name)
    }

    suspend fun deleteTag(tagId: Long) {
        tagRepository.deleteTag(tagId)
    }
}
