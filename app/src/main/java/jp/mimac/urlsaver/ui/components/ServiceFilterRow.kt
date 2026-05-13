package jp.mimac.urlsaver.ui.components
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.filterLabelForService
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal val fixedServiceFilterOrder = listOf(
    ServiceType.ALL,
    ServiceType.YOUTUBE,
    ServiceType.X,
    ServiceType.INSTAGRAM,
    ServiceType.TIKTOK,
    ServiceType.WEB,
)

internal fun resolvedTopFilterTokens(
    serviceOrder: List<ServiceType>,
    collections: List<CollectionEntity>,
    topFilterOrderTokens: List<String>,
): List<String> {
    return buildMovableItems(
        serviceOrder = serviceOrder,
        collections = collections,
        topFilterOrderTokens = topFilterOrderTokens,
    ).map { item -> item.token }
}

internal fun mergedTopFilterTokens(
    currentTokens: List<String>,
    latestTokens: List<String>,
): List<String> {
    val latestSet = latestTokens.toSet()
    val merged = currentTokens
        .filter { token -> token in latestSet }
        .toMutableList()
    latestTokens.forEach { token ->
        if (token !in merged) {
            merged.add(token)
        }
    }
    return merged
}

private sealed interface TopFilterItem {
    val token: String

    data object All : TopFilterItem {
        override val token: String = "all"
    }

    data class Collection(
        val id: Long,
        val label: String,
    ) : TopFilterItem {
        override val token: String = "collection_$id"
    }

    data class Service(
        val service: ServiceType,
    ) : TopFilterItem {
        override val token: String = "service_${service.name}"
    }
}

private data class ServiceItemBounds(
    val left: Float,
    val right: Float,
) {
    val center: Float get() = (left + right) / 2f
    val width: Float get() = right - left

    fun contains(x: Float): Boolean = x in left..right
}

@Composable
fun ServiceFilterRow(
    selectedService: ServiceType,
    onSelectService: (ServiceType) -> Unit,
    serviceOrder: List<ServiceType> = fixedServiceFilterOrder.filterNot { it == ServiceType.ALL },
    topFilterOrderTokens: List<String> = emptyList(),
    onReorderServices: ((List<ServiceType>) -> Unit)? = null,
    collections: List<CollectionEntity> = emptyList(),
    selectedCollectionId: Long? = null,
    onSelectCollection: ((Long?) -> Unit)? = null,
    onCreateCollection: (() -> Unit)? = null,
    onReorderTopFilters: ((List<String>) -> Unit)? = null,
    onReorderCollections: ((List<Long>) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val edgeThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    val latestMovableItems = remember(serviceOrder, collections, topFilterOrderTokens) {
        buildMovableItems(
            serviceOrder = serviceOrder,
            collections = collections,
            topFilterOrderTokens = topFilterOrderTokens,
        )
    }

    var orderedMovableItems by remember { mutableStateOf(latestMovableItems) }
    var draggedToken by remember { mutableStateOf<String?>(null) }
    var draggedOffsetPx by remember { mutableFloatStateOf(0f) }
    var draggedPointerX by remember { mutableFloatStateOf(0f) }
    var draggedTouchOffsetX by remember { mutableFloatStateOf(0f) }
    var dragStartedTokens by remember { mutableStateOf(emptyList<String>()) }
    val itemBounds = remember { mutableStateMapOf<String, ServiceItemBounds>() }

    LaunchedEffect(latestMovableItems) {
        if (draggedToken == null) {
            orderedMovableItems = mergeMovableItems(
                current = orderedMovableItems,
                latest = latestMovableItems,
            )
            itemBounds.clear()
        }
    }

    val canReorder = onReorderCollections != null || onReorderServices != null || onReorderTopFilters != null
    val isDragging = draggedToken != null
    fun resetDragState() {
        draggedToken = null
        draggedOffsetPx = 0f
        draggedPointerX = 0f
        draggedTouchOffsetX = 0f
        dragStartedTokens = emptyList()
    }

    fun settleDraggedItemOnRelease() {
        val activeToken = draggedToken ?: return
        val activeBounds = itemBounds[activeToken] ?: return
        val currentIndex = orderedMovableItems.indexOfFirst { current -> current.token == activeToken }
        if (currentIndex == -1 || currentIndex == orderedMovableItems.lastIndex) return

        val draggedItemRight = draggedPointerX - draggedTouchOffsetX + activeBounds.width
        val nextItem = orderedMovableItems[currentIndex + 1]
        val nextCenter = itemBounds[nextItem.token]?.center ?: return
        if (draggedItemRight + activeBounds.width <= nextCenter) return

        val updated = orderedMovableItems.toMutableList()
        val draggedItem = updated.removeAt(currentIndex)
        updated.add((currentIndex + 1).coerceIn(0, updated.size), draggedItem)
        orderedMovableItems = updated
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .testTag("top_filter_row")
            .fillMaxWidth()
            .padding(horizontal = OrbitTokens.screenHorizontalPadding)
            .then(
                if (canReorder) {
                    Modifier.pointerInput(canReorder) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val target = orderedMovableItems.firstOrNull { current ->
                                itemBounds[current.token]?.contains(down.position.x) == true
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

                            val currentBounds = itemBounds[target.token] ?: return@awaitEachGesture
                            draggedToken = target.token
                            draggedTouchOffsetX = latestPosition.x - currentBounds.left
                            draggedPointerX = latestPosition.x
                            draggedOffsetPx = 0f
                            dragStartedTokens = orderedMovableItems.map { current -> current.token }

                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { current -> current.id == down.id } ?: continue
                                if (!change.pressed) {
                                    settleDraggedItemOnRelease()
                                    val reorderedTokens = orderedMovableItems.map { current -> current.token }
                                    if (dragStartedTokens.isNotEmpty() && reorderedTokens != dragStartedTokens) {
                                        val reorderedCollections = orderedMovableItems
                                            .filterIsInstance<TopFilterItem.Collection>()
                                            .map { current -> current.id }
                                        val reorderedServices = orderedMovableItems
                                            .filterIsInstance<TopFilterItem.Service>()
                                            .map { current -> current.service }
                                        onReorderTopFilters?.invoke(reorderedTokens)
                                        onReorderCollections?.invoke(reorderedCollections)
                                        onReorderServices?.invoke(reorderedServices)
                                    }
                                    resetDragState()
                                    break
                                }

                                val activeToken = draggedToken ?: break
                                val activeBounds = itemBounds[activeToken] ?: break
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
                                val draggedItemLeft = draggedPointerX - draggedTouchOffsetX
                                val draggedItemRight = draggedItemLeft + activeBounds.width

                                val currentIndex = orderedMovableItems.indexOfFirst { current -> current.token == activeToken }
                                if (currentIndex == -1) {
                                    continue
                                }

                                var targetIndex = currentIndex
                                while (targetIndex < orderedMovableItems.lastIndex) {
                                    val nextItem = orderedMovableItems[targetIndex + 1]
                                    val nextCenter = itemBounds[nextItem.token]?.center ?: break
                                    if (draggedItemRight > nextCenter) {
                                        targetIndex += 1
                                    } else {
                                        break
                                    }
                                }
                                while (targetIndex > 0) {
                                    val previousItem = orderedMovableItems[targetIndex - 1]
                                    val previousCenter = itemBounds[previousItem.token]?.center ?: break
                                    if (draggedItemLeft < previousCenter) {
                                        targetIndex -= 1
                                    } else {
                                        break
                                    }
                                }
                                if (targetIndex == currentIndex) {
                                    continue
                                }

                                val updated = orderedMovableItems.toMutableList()
                                val draggedItem = updated.removeAt(currentIndex)
                                updated.add(targetIndex.coerceIn(0, updated.size), draggedItem)
                                orderedMovableItems = updated
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onCreateCollection != null) {
            item(key = "create_collection") {
                OrbitFilterChip(
                    label = "+",
                    selected = false,
                    labelFontSize = 28.sp,
                    modifier = Modifier
                        .testTag("top_filter_create")
                        .clickable(enabled = !isDragging) { onCreateCollection() },
                )
            }
        }

        items(
            items = orderedMovableItems,
            key = { it.token },
        ) { item ->
            val draggedState = draggedToken == item.token
            val selectedState = when (item) {
                TopFilterItem.All -> selectedCollectionId == null && selectedService == ServiceType.ALL
                is TopFilterItem.Collection -> selectedCollectionId == item.id
                is TopFilterItem.Service -> selectedCollectionId == null && selectedService == item.service
            }

            val baseModifier = Modifier
                .onGloballyPositioned { coordinates ->
                    val left = coordinates.positionInParent().x
                    val right = left + coordinates.size.width
                    itemBounds[item.token] = ServiceItemBounds(left = left, right = right)
                }
                .graphicsLayer {
                    if (draggedState) {
                        translationX = draggedOffsetPx
                        alpha = 0.92f
                    }
                }
                .zIndex(if (draggedState) 1f else 0f)

            OrbitFilterChip(
                label = when (item) {
                    TopFilterItem.All -> filterLabelForService(ServiceType.ALL)
                    is TopFilterItem.Collection -> item.label
                    is TopFilterItem.Service -> filterLabelForService(item.service)
                },
                selected = selectedState,
                modifier = baseModifier
                    .testTag("top_filter_${item.token}")
                    .clickable(enabled = !isDragging) {
                        when (item) {
                            TopFilterItem.All -> onSelectService(ServiceType.ALL)
                            is TopFilterItem.Collection -> onSelectCollection?.invoke(item.id)
                            is TopFilterItem.Service -> onSelectService(item.service)
                        }
                    }
                    .semantics {
                        selected = selectedState
                        stateDescription = if (draggedState) {
                            "並べ替え中"
                        } else if (selectedState) {
                            "選択中"
                        } else {
                            "未選択"
                        }
                    },
            )
        }
    }
}

private fun buildMovableItems(
    serviceOrder: List<ServiceType>,
    collections: List<CollectionEntity>,
    topFilterOrderTokens: List<String>,
): List<TopFilterItem> {
    val baseItems = buildList {
        add(TopFilterItem.All)
        collections.forEach { collection ->
            add(
                TopFilterItem.Collection(
                    id = collection.id,
                    label = collection.name,
                ),
            )
        }
        serviceOrder
            .filterNot { it == ServiceType.ALL }
            .forEach { service ->
                add(TopFilterItem.Service(service))
            }
    }
    if (topFilterOrderTokens.isEmpty()) return baseItems

    val baseByToken = baseItems.associateBy { item -> item.token }
    val ordered = topFilterOrderTokens
        .mapNotNull { token -> baseByToken[token] }
        .toMutableList()
    baseItems.forEach { item ->
        if (ordered.none { current -> current.token == item.token }) {
            ordered.add(item)
        }
    }
    return ordered
}

private fun mergeMovableItems(
    current: List<TopFilterItem>,
    latest: List<TopFilterItem>,
): List<TopFilterItem> {
    if (current.isEmpty()) return latest

    val latestByToken = latest.associateBy { item -> item.token }
    return mergedTopFilterTokens(
        currentTokens = current.map { item -> item.token },
        latestTokens = latest.map { item -> item.token },
    ).mapNotNull { token -> latestByToken[token] }
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
            ((draggedPointerX - (viewportEnd - edgeThresholdPx)) * 0.25f).coerceAtMost(16f)
        }
        draggedPointerX < viewportStart + edgeThresholdPx -> {
            ((draggedPointerX - (viewportStart + edgeThresholdPx)) * 0.25f).coerceAtLeast(-16f)
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
