package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.theme.SemanticColors
import ai.ciris.mobile.shared.utils.DisplayNames
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import ai.ciris.mobile.shared.ui.icons.*
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Services screen for service status management
 * Based on ~/CIRISGUI-Standalone/apps/agui/app/services/page.tsx
 *
 * Features:
 * - Service health overview (healthy/unhealthy counts)
 * - Handler-specific and global services listing
 * - Service priority and circuit breaker status display
 * - Priority management controls
 * - Circuit breaker reset functionality
 * - Service diagnostics
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    servicesData: ServicesData,
    isLoading: Boolean,
    expandedServiceIds: Set<String> = emptySet(),
    onToggleServiceExpanded: (String) -> Unit = {},
    onRefresh: () -> Unit,
    onDiagnose: () -> Unit,
    onResetCircuitBreakers: (serviceType: String?) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // `onDiagnose` and `onResetCircuitBreakers` are still accepted so the
    // caller compiles, and are no longer offered: there is no diagnostics route
    // (the old one re-counted this list) and no breaker-reset route (the old
    // one reported a reset it never made). CSD-016.

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.screen_service_management")) },
                navigationIcon = {
                    // Suppressed on compact viewports — the global 3-state
                    // overlay button in CIRISApp handles back navigation
                    // there to avoid the prior "back arrow + signet stacked"
                    // bug. Wider viewports (tablet/desktop) keep this arrow.
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testableClickable("btn_services_back") { onNavigateBack() }
                        ) {
                            Icon(
                                imageVector = CIRISIcons.arrowBack,
                                contentDescription = localizedString("mobile.common_back")
                            )
                        }
                    } else {
                        // Reserve the global signet/back overlay's footprint so the
                        // TopAppBar title doesn't slide underneath it on compact.
                        Spacer(Modifier.width(56.dp))
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !isLoading,
                        modifier = Modifier.testableClickable("btn_services_refresh") { onRefresh() }
                    ) {
                        Icon(
                            imageVector = CIRISIcons.refresh,
                            contentDescription = localizedString("mobile.common_refresh")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        if (isLoading && !servicesData.hasReading && servicesData.readFailure == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .testable("services_loading"),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .testable("services_list"),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // A failed or absent read is said, and nothing below draws: the
                // overview's zeros and "No Services Found" are claims about a
                // list nobody read (CSD-016, CSD/3 §2.2).
                servicesData.readFailure?.let { failure ->
                    item {
                        ReadFailureBlock(
                            failure = failure,
                            tagPrefix = "services",
                            notOnThisNode = localizedString("mobile.services_not_on_this_node"),
                        )
                    }
                }

                if (servicesData.hasReading) {
                // Service Health Overview
                item {
                    ServiceHealthOverviewCard(
                        overallHealth = servicesData.overallHealth,
                        totalServices = servicesData.totalServices,
                        healthyServices = servicesData.healthyServices,
                        unhealthyServices = servicesData.unhealthyServices
                    )
                }

                // Global Services Section
                if (servicesData.globalServices.isNotEmpty()) {
                    item {
                        Text(
                            text = localizedString("mobile.services_global"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    servicesData.globalServices.forEach { (serviceType, providers) ->
                        item {
                            ServiceTypeCard(
                                serviceType = serviceType,
                                providers = providers,
                                scope = "global",
                                expandedServiceIds = expandedServiceIds,
                                onToggleServiceExpanded = onToggleServiceExpanded
                            )
                        }
                    }
                }

                // Handler-Specific Services Section
                if (servicesData.handlerServices.isNotEmpty()) {
                    item {
                        Text(
                            text = localizedString("mobile.services_handler"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    servicesData.handlerServices.forEach { (handler, serviceTypes) ->
                        item {
                            HandlerServicesCard(
                                handler = handler,
                                serviceTypes = serviceTypes,
                                expandedServiceIds = expandedServiceIds,
                                onToggleServiceExpanded = onToggleServiceExpanded
                            )
                        }
                    }
                }

                // Empty state — a successful read that listed nothing.
                if (servicesData.globalServices.isEmpty() && servicesData.handlerServices.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().testable("services_empty")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = localizedString("mobile.services_no_services"),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                } // hasReading
            }
        }
    }
}

@Composable
private fun ServiceHealthOverviewCard(
    overallHealth: String,
    totalServices: Int,
    healthyServices: Int,
    unhealthyServices: Int,
    modifier: Modifier = Modifier
) {
    val semantic = SemanticColors.Default
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = localizedString("mobile.services_health"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Overall Health
                HealthMetricItem(
                    label = localizedString("status.all_operational"),
                    value = overallHealth.uppercase(),
                    color = when (overallHealth.lowercase()) {
                        "healthy" -> semantic.success
                        "degraded" -> semantic.warning
                        else -> semantic.error
                    }
                )

                // Total Services
                HealthMetricItem(
                    label = localizedString("mobile.common_all"),
                    value = totalServices.toString(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                // Healthy Services
                HealthMetricItem(
                    label = localizedString("status.online"),
                    value = healthyServices.toString(),
                    color = semantic.success
                )

                // Unhealthy Services
                HealthMetricItem(
                    label = localizedString("status.offline"),
                    value = unhealthyServices.toString(),
                    color = if (unhealthyServices > 0) semantic.error else semantic.success
                )
            }
        }
    }
}

@Composable
private fun HealthMetricItem(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ServiceTypeCard(
    serviceType: String,
    providers: List<ServiceProvider>,
    scope: String,
    expandedServiceIds: Set<String>,
    onToggleServiceExpanded: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = DisplayNames.humanizeCategory(serviceType),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${providers.size} provider${if (providers.size != 1) "s" else ""}", // Keep English for technical term
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            providers.forEachIndexed { index, provider ->
                // Use index to ensure unique serviceId even when display names collide
                val serviceId = "${serviceType}_${provider.name}_${index}"
                ServiceProviderRow(
                    provider = provider,
                    isExpanded = serviceId in expandedServiceIds,
                    onToggleExpand = { onToggleServiceExpanded(serviceId) }
                )
            }
        }
    }
}

@Composable
private fun ServiceProviderRow(
    provider: ServiceProvider,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevron_rotation"
    )

    val semantic = SemanticColors.Default
    // Colour from `healthy`, the one health fact the wire carries. A breaker
    // state is shown only if the host ever sends one; it is never derived.
    val statusColor = when (provider.healthy) {
        true -> semantic.success
        false -> semantic.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (provider.healthy) {
        true -> localizedString("status.online")
        false -> localizedString("status.offline")
        null -> NOT_READ
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header row (clickable to expand)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Circuit breaker status dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )

                // Human-friendly service name
                Text(
                    text = DisplayNames.humanizeServiceName(provider.name),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Human-friendly status badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }

                // Expand chevron
                Icon(
                    imageVector = CIRISIcons.arrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand", // Keep English for accessibility
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Expandable details section
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Each detail only when the host sent it. None of these is on
                // `GET /v1/system/services` today, so today none draws — the
                // old constants (NORMAL / 0 / FALLBACK) drew as if read.
                provider.priority?.let {
                    DetailRow(label = "Priority", value = it) // Keep English for technical term
                }
                provider.priorityGroup?.let {
                    DetailRow(label = "Priority Group", value = it.toString()) // Keep English for technical term
                }
                provider.strategy?.let {
                    DetailRow(label = "Strategy", value = DisplayNames.humanizeStrategy(it)) // Keep English for technical term
                }
                provider.circuitBreakerState?.let {
                    DetailRow(label = "Circuit breaker", value = DisplayNames.humanizeStatus(it)) // Keep English for technical term
                }
                if (provider.priority == null && provider.priorityGroup == null &&
                    provider.strategy == null && provider.circuitBreakerState == null &&
                    provider.capabilities == null
                ) {
                    Text(
                        text = localizedString("mobile.services_details_not_reported"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testable("services_details_not_reported"),
                    )
                }

                // Capabilities (if the host sent any)
                val capabilities = provider.capabilities.orEmpty()
                if (capabilities.isNotEmpty()) {
                    Text(
                        text = localizedString("mobile.services_capabilities"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        capabilities.take(4).forEach { capability ->
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = capability,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                        if (capabilities.size > 4) {
                            Text(
                                text = "+${capabilities.size - 4}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun HandlerServicesCard(
    handler: String,
    serviceTypes: Map<String, List<ServiceProvider>>,
    expandedServiceIds: Set<String>,
    onToggleServiceExpanded: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = localizedString("mobile.services_handler_label", "handler", handler),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            serviceTypes.forEach { (serviceType, providers) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = DisplayNames.humanizeCategory(serviceType),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    providers.forEachIndexed { index, provider ->
                        // Use index to ensure unique serviceId even when display names collide
                        val serviceId = "${handler}_${serviceType}_${provider.name}_${index}"
                        ServiceProviderRow(
                            provider = provider,
                            isExpanded = serviceId in expandedServiceIds,
                            onToggleExpand = { onToggleServiceExpanded(serviceId) }
                        )
                    }
                }

                if (serviceType != serviceTypes.keys.last()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

// Data classes

/**
 * Complete services data model
 */
data class ServicesData(
    val overallHealth: String = "unknown",
    val totalServices: Int = 0,
    val healthyServices: Int = 0,
    val unhealthyServices: Int = 0,
    val globalServices: Map<String, List<ServiceProvider>> = emptyMap(),
    val handlerServices: Map<String, Map<String, List<ServiceProvider>>> = emptyMap(),
    /** True only when the fields above came from a successful read. */
    val hasReading: Boolean = false,
    /** Why the last read produced no reading; null after a success. */
    val readFailure: ReadFailure? = null,
)

/**
 * Service provider data. Every field but [name] is null when the host did not
 * send it — never a constant standing in for a read (CSD-016).
 */
data class ServiceProvider(
    val name: String,
    val priority: String? = null,
    val priorityGroup: Int? = null,
    val strategy: String? = null,
    val circuitBreakerState: String? = null,
    val capabilities: List<String>? = null,
    val healthy: Boolean? = null,
)
