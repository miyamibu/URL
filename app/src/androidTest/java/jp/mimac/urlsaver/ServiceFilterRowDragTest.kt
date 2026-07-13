package jp.mimac.urlsaver

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.ui.components.ServiceFilterRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ServiceFilterRowDragTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dragAllChip_keepsDroppedMiddlePositionAfterRelease() {
        var latestTopTokens = emptyList<String>()

        composeRule.setContent {
            var selectedService by remember { mutableStateOf(ServiceType.ALL) }
            var selectedLocalTagId by remember { mutableStateOf<Long?>(null) }
            var serviceOrder by remember {
                mutableStateOf(
                listOf(
                    ServiceType.YOUTUBE,
                    ServiceType.X,
                    ServiceType.INSTAGRAM,
                    ServiceType.WEB,
                ),
                )
            }
            val localTags = remember { listOf(localTag(id = 10L, name = "仕事")) }
            var topTokens by remember {
                mutableStateOf(
                listOf(
                    "all",
                    "local_tag_10",
                    "service_YOUTUBE",
                    "service_X",
                    "service_INSTAGRAM",
                    "service_WEB",
                ),
                )
            }

            MaterialTheme {
                Box(modifier = androidx.compose.ui.Modifier.width(320.dp)) {
                    ServiceFilterRow(
                        selectedService = selectedService,
                        onSelectService = { service ->
                            selectedService = service
                            selectedLocalTagId = null
                        },
                        serviceOrder = serviceOrder,
                        topFilterOrderTokens = topTokens,
                        onReorderServices = { reordered ->
                            serviceOrder = reordered
                        },
                        onReorderTopFilters = { reordered ->
                            latestTopTokens = reordered
                            topTokens = reordered
                        },
                        localTags = localTags,
                        selectedLocalTagId = selectedLocalTagId,
                        onSelectLocalTag = { localTagId ->
                            selectedLocalTagId = localTagId
                            if (localTagId != null) selectedService = ServiceType.ALL
                        },
                    )
                }
            }
        }

        composeRule.waitForIdle()

        dragChipToRightEdge(sourceTag = "top_filter_all")

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "local_tag_10",
                    "service_YOUTUBE",
                    "service_X",
                    "all",
                    "service_INSTAGRAM",
                    "service_WEB",
                ),
                latestTopTokens,
            )
        }
    }

    @Test
    fun dragLocalTagChip_keepsDroppedMiddlePositionAfterRelease() {
        var latestTopTokens = emptyList<String>()

        composeRule.setContent {
            var selectedService by remember { mutableStateOf(ServiceType.ALL) }
            var selectedLocalTagId by remember { mutableStateOf<Long?>(null) }
            var serviceOrder by remember {
                mutableStateOf(
                listOf(
                    ServiceType.YOUTUBE,
                    ServiceType.X,
                    ServiceType.INSTAGRAM,
                    ServiceType.WEB,
                ),
                )
            }
            val localTags = remember { listOf(localTag(id = 10L, name = "仕事")) }
            var topTokens by remember {
                mutableStateOf(
                listOf(
                    "all",
                    "local_tag_10",
                    "service_YOUTUBE",
                    "service_X",
                    "service_INSTAGRAM",
                    "service_WEB",
                ),
                )
            }

            MaterialTheme {
                Box(modifier = androidx.compose.ui.Modifier.width(320.dp)) {
                    ServiceFilterRow(
                        selectedService = selectedService,
                        onSelectService = { service ->
                            selectedService = service
                            selectedLocalTagId = null
                        },
                        serviceOrder = serviceOrder,
                        topFilterOrderTokens = topTokens,
                        onReorderServices = { reordered ->
                            serviceOrder = reordered
                        },
                        onReorderTopFilters = { reordered ->
                            latestTopTokens = reordered
                            topTokens = reordered
                        },
                        localTags = localTags,
                        selectedLocalTagId = selectedLocalTagId,
                        onSelectLocalTag = { localTagId ->
                            selectedLocalTagId = localTagId
                            if (localTagId != null) selectedService = ServiceType.ALL
                        },
                    )
                }
            }
        }

        composeRule.waitForIdle()

        dragChipToRightEdge(sourceTag = "top_filter_local_tag_10")

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "all",
                    "service_YOUTUBE",
                    "service_X",
                    "local_tag_10",
                    "service_INSTAGRAM",
                    "service_WEB",
                ),
                latestTopTokens,
            )
        }
    }

    private fun dragChipToRightEdge(sourceTag: String) {
        val sourceBounds = composeRule.onNodeWithTag(sourceTag).fetchSemanticsNode().boundsInRoot
        val rowBounds = composeRule.onNodeWithTag("top_filter_row").fetchSemanticsNode().boundsInRoot
        val sourceCenter = Offset(
            x = (sourceBounds.left + sourceBounds.right) / 2f,
            y = (sourceBounds.top + sourceBounds.bottom) / 2f,
        )
        val edgeTarget = Offset(
            x = rowBounds.right - 6f,
            y = sourceCenter.y,
        )

        composeRule.onNodeWithTag("top_filter_row").performTouchInput {
            down(sourceCenter)
            advanceEventTime(900L)
            moveTo(
                Offset(
                    x = sourceCenter.x + (edgeTarget.x - sourceCenter.x) * 0.45f,
                    y = sourceCenter.y,
                ),
            )
            advanceEventTime(64L)
            moveTo(
                Offset(
                    x = sourceCenter.x + (edgeTarget.x - sourceCenter.x) * 0.8f,
                    y = sourceCenter.y,
                ),
            )
            advanceEventTime(64L)
            repeat(6) {
                moveTo(edgeTarget)
                advanceEventTime(64L)
            }
            up()
        }
    }

    private fun localTag(
        id: Long,
        name: String,
    ): TagWithCount {
        return TagWithCount(
            id = id,
            name = name,
            urlCount = 0,
        )
    }
}
