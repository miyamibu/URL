package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import jp.mimac.urlsaver.domain.ServiceType

internal val fixedServiceFilterOrder = listOf(
    ServiceType.ALL,
    ServiceType.YOUTUBE,
    ServiceType.TIKTOK,
    ServiceType.X,
    ServiceType.INSTAGRAM,
    ServiceType.WEB,
)

@Composable
fun ServiceFilterRow(
    selectedService: ServiceType,
    onSelect: (ServiceType) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        items(fixedServiceFilterOrder) { service ->
            val selectedState = selectedService == service
            FilterChip(
                selected = selectedState,
                onClick = { onSelect(service) },
                label = { Text(service.displayName) },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .semantics {
                        selected = selectedState
                        stateDescription = if (selectedState) "選択中" else "未選択"
                    },
            )
        }
    }
}
