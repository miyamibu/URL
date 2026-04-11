package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class MainListViewModel(
    private val repository: UrlRepository,
) : ViewModel() {

    private val selectedService = MutableStateFlow(ServiceType.ALL)

    val uiState: StateFlow<ListSearchUiState> = combine(
        repository.observeActiveEntries(),
        selectedService,
    ) { entries, selected ->
        buildListSearchUiState(
            entries = entries,
            selectedService = selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListSearchUiState())

    val selectedServiceFlow: StateFlow<ServiceType> = selectedService

    fun selectService(serviceType: ServiceType) {
        selectedService.value = serviceType
    }

    suspend fun submitManualInput(input: String): Pair<ShareSaveResult, Long?> {
        val result = repository.saveFromManualInput(input)
        return result.result to result.entryId
    }
}
