package jp.mimac.urlsaver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.mimac.urlsaver.data.AiDiffProposal
import jp.mimac.urlsaver.data.AiDraft
import jp.mimac.urlsaver.data.AiSendPreview
import jp.mimac.urlsaver.data.AiSendReceipt
import jp.mimac.urlsaver.data.AiTransparencyFeature
import jp.mimac.urlsaver.data.AiTransparencyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AiTransparencyUiState(
    val preview: AiSendPreview? = null,
    val receipt: AiSendReceipt? = null,
    val draft: AiDraft? = null,
    val diffProposal: AiDiffProposal? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
)

class AiTransparencyViewModel(
    private val repository: AiTransparencyRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AiTransparencyUiState())
    val uiState: StateFlow<AiTransparencyUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (!AiTransparencyFeature.isEnabled) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            runCatching { repository.buildPreview() }
                .onSuccess { preview ->
                    _uiState.value = AiTransparencyUiState(preview = preview)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "AI動作の確認を読み込めませんでした",
                    )
                }
        }
    }

    fun runLocalMock() {
        val preview = _uiState.value.preview ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            runCatching {
                val receipt = repository.saveReceipt(preview)
                val draft = repository.generateDraftWithFallback(preview, receipt)
                val proposal = repository.createLocalMockMemoDiff(preview, draft)
                Triple(receipt, draft, proposal)
            }.onSuccess { (receipt, draft, proposal) ->
                _uiState.value = _uiState.value.copy(
                    receipt = receipt,
                    draft = draft,
                    diffProposal = proposal,
                    isLoading = false,
                    message = "端末内で確認記録を作成しました",
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "端末内モックを実行できませんでした",
                )
            }
        }
    }

    fun applyDiffProposal(confirm: Boolean) {
        val proposalId = _uiState.value.diffProposal?.proposalId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)
            val applied = runCatching { repository.applyDiffProposal(proposalId, confirm) }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                message = if (applied) {
                    "変更案を反映しました"
                } else {
                    "変更案は反映されませんでした。条件を満たすか確認してください"
                },
            )
        }
    }
}
