package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private data class FilterItem(
    val collectionId: Long?,
    val label: String,
)

private data class ItemBounds(
    val left: Float,
    val right: Float,
) {
    val center: Float get() = (left + right) / 2f

    fun contains(x: Float): Boolean = x in left..right
}

@Composable
fun CollectionFilterRow(
    collections: List<CollectionEntity>,
    selectedCollectionId: Long?,
    includeAllOption: Boolean = true,
    onCreateCollection: (() -> Unit)? = null,
    onReorderCollections: ((List<Long>) -> Unit)? = null,
    onSelect: (Long?) -> Unit,
) {
    val listState = rememberLazyListState()
    val edgeThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    var orderedCollections by remember { mutableStateOf(collections) }
    var draggedCollectionId by remember { mutableLongStateOf(-1L) }
    var draggedOffsetPx by remember { mutableFloatStateOf(0f) }
    var draggedPointerX by remember { mutableFloatStateOf(0f) }
    var draggedTouchOffsetX by remember { mutableFloatStateOf(0f) }
    var dragStartedOrder by remember { mutableStateOf(emptyList<Long>()) }
    val itemBounds = remember { mutableStateMapOf<Long, ItemBounds>() }

    LaunchedEffect(collections) {
        if (draggedCollectionId == -1L) {
            orderedCollections = collections
            itemBounds.clear()
        }
    }

    val items = buildList {
        if (includeAllOption) {
            add(FilterItem(collectionId = null, label = "すべて"))
        }
        orderedCollections.forEach { collection ->
            add(FilterItem(collectionId = collection.id, label = collection.name))
        }
    }

    val isReorderEnabled = onReorderCollections != null && orderedCollections.isNotEmpty()
    val isDragging = draggedCollectionId != -1L
    fun resetDragState() {
        draggedCollectionId = -1L
        draggedOffsetPx = 0f
        draggedPointerX = 0f
        draggedTouchOffsetX = 0f
        dragStartedOrder = emptyList()
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.screenHorizontalPadding)
            .then(
                if (isReorderEnabled) {
                    Modifier.pointerInput(isReorderEnabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val target = orderedCollections.firstOrNull { collection ->
                                itemBounds[collection.id]?.contains(down.position.x) == true
                            } ?: return@awaitEachGesture

                            var latestPosition = down.position
                            val longPressReached = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { current -> current.id == down.id } ?: continue
                                    if (!change.pressed) {
                                        return@withTimeoutOrNull false
                                    }
                                    if (
                                        abs(change.position.x - down.position.x) > viewConfiguration.touchSlop ||
                                        abs(change.position.y - down.position.y) > viewConfiguration.touchSlop
                                    ) {
                                        return@withTimeoutOrNull false
                                    }
                                    latestPosition = change.position
                                }
                            } == null
                            if (!longPressReached) {
                                return@awaitEachGesture
                            }

                            val currentBounds = itemBounds[target.id] ?: return@awaitEachGesture
                            draggedCollectionId = target.id
                            draggedTouchOffsetX = latestPosition.x - currentBounds.left
                            draggedPointerX = latestPosition.x
                            draggedOffsetPx = 0f
                            dragStartedOrder = orderedCollections.map { it.id }

                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { current -> current.id == down.id } ?: continue
                                if (!change.pressed) {
                                    val reorderedIds = orderedCollections.map { it.id }
                                    if (dragStartedOrder.isNotEmpty() && reorderedIds != dragStartedOrder) {
                                        onReorderCollections?.invoke(reorderedIds)
                                    }
                                    resetDragState()
                                    break
                                }

                                val activeCollectionId = draggedCollectionId
                                if (activeCollectionId == -1L) {
                                    break
                                }
                                val activeCollection = orderedCollections.firstOrNull { collection ->
                                    collection.id == activeCollectionId
                                } ?: continue
                                val activeBounds = itemBounds[activeCollection.id] ?: continue
                                val deltaX = change.position.x - latestPosition.x
                                latestPosition = change.position
                                change.consume()
                                draggedPointerX += deltaX
                                maybeAutoScroll(
                                    listState = listState,
                                    draggedPointerX = draggedPointerX,
                                    edgeThresholdPx = edgeThresholdPx,
                                    onScrollConsumed = { consumed ->
                                        draggedPointerX += consumed
                                    },
                                )
                                draggedOffsetPx = draggedPointerX - activeBounds.left - draggedTouchOffsetX

                                val currentIndex = orderedCollections.indexOfFirst { it.id == activeCollectionId }
                                if (currentIndex == -1) {
                                    continue
                                }

                                var targetIndex = currentIndex
                                while (targetIndex < orderedCollections.lastIndex) {
                                    val nextCollection = orderedCollections[targetIndex + 1]
                                    val nextCenter = itemBounds[nextCollection.id]?.center ?: break
                                    if (draggedPointerX > nextCenter) {
                                        targetIndex += 1
                                    } else {
                                        break
                                    }
                                }
                                while (targetIndex > 0) {
                                    val previousCollection = orderedCollections[targetIndex - 1]
                                    val previousCenter = itemBounds[previousCollection.id]?.center ?: break
                                    if (draggedPointerX < previousCenter) {
                                        targetIndex -= 1
                                    } else {
                                        break
                                    }
                                }
                                if (currentIndex == targetIndex) {
                                    continue
                                }

                                val updated = orderedCollections.toMutableList()
                                val draggedItem = updated.removeAt(currentIndex)
                                updated.add(targetIndex.coerceIn(0, updated.size), draggedItem)
                                orderedCollections = updated
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onCreateCollection != null && !isDragging) {
            item(key = "create_collection") {
                OrbitFilterChip(
                    label = "+",
                    selected = false,
                    labelFontSize = 28.sp,
                    modifier = Modifier.clickable { onCreateCollection() },
                )
            }
        }
        items(items, key = { item -> item.collectionId ?: Long.MIN_VALUE }) { item ->
            val collectionId = item.collectionId
            val selectedState = selectedCollectionId == collectionId
            val draggedState = collectionId != null && draggedCollectionId == collectionId
            val baseModifier = Modifier
                .onGloballyPositioned { coordinates ->
                    if (collectionId != null) {
                        val left = coordinates.positionInParent().x
                        val right = left + coordinates.size.width
                        itemBounds[collectionId] = ItemBounds(left = left, right = right)
                    }
                }
                .graphicsLayer {
                    if (draggedState) {
                        translationX = draggedOffsetPx
                        alpha = 0.92f
                    }
                }
                .zIndex(if (draggedState) 1f else 0f)

            OrbitFilterChip(
                label = item.label,
                selected = selectedState,
                modifier = baseModifier
                    .clickable(enabled = !isDragging) { onSelect(collectionId) }
                    .semantics {
                        selected = selectedState
                        stateDescription = if (draggedState) "並べ替え中" else if (selectedState) "選択中" else "未選択"
                    },
            )
        }
    }
}

private fun maybeAutoScroll(
    listState: androidx.compose.foundation.lazy.LazyListState,
    draggedPointerX: Float,
    edgeThresholdPx: Float,
    onScrollConsumed: (Float) -> Unit,
) {
    val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
    val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
    val autoScrollDelta = when {
        draggedPointerX > viewportEnd - edgeThresholdPx -> {
            ((draggedPointerX - (viewportEnd - edgeThresholdPx)) * 0.12f).coerceAtMost(8f)
        }
        draggedPointerX < viewportStart + edgeThresholdPx -> {
            ((draggedPointerX - (viewportStart + edgeThresholdPx)) * 0.12f).coerceAtLeast(-8f)
        }
        else -> 0f
    }
    if (autoScrollDelta != 0f) {
        val consumed = listState.dispatchRawDelta(autoScrollDelta)
        if (consumed != 0f) {
            onScrollConsumed(consumed)
        }
    }
}
