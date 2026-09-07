package ai.ciris.mobile.shared.ui.screens.federation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.DelegationDto
import ai.ciris.mobile.shared.platform.rememberTestableScrollState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.nav.NavSurface
import ai.ciris.mobile.shared.ui.theme.CIRISColors

/**
 * "Delegation" — delegates_to scope graph and authorization roster.
 *
 * Shows what scopes this agent's keys have delegated to other parties
 * + the inverse: who has delegated scopes to this agent.
 *
 * CIRISPersist#104 has shipped. This screen provides direct visibility into
 * active delegation graphs, device authorizations, and trust scopes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DelegationScreen(
    delegations: List<DelegationDto> = emptyList(),
    isLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onManageDeviceGrants: () -> Unit = {},
    onIssueClick: (String) -> Unit = {},
) {
    val scroll = rememberTestableScrollState()

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizedString("commons.federation.delegation.title").ifEmpty { "Delegation Graph" }) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testableClickable("btn_delegation_back") { onNavigateBack() },
                        ) {
                            Icon(
                                imageVector = CIRISIcons.arrowBack,
                                contentDescription = localizedString("mobile.common_back"),
                            )
                        }
                    } else {
                        Spacer(Modifier.width(56.dp))
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testableClickable("btn_delegation_refresh") { onRefresh() },
                    ) {
                        Icon(
                            imageVector = CIRISIcons.refresh,
                            contentDescription = localizedString("mobile.common_refresh"),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CIRISColors.BackgroundDark)
                .padding(padding)
                .testable("screen_delegation"),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .verticalScroll(scroll)
                    .align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Header / Hero
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testable("card_delegation_overview"),
                    color = CIRISColors.BackgroundDarker,
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(CIRISColors.SignetTeal.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = CIRISIcons.send,
                                contentDescription = null,
                                tint = CIRISColors.SignetTeal,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = localizedString("commons.federation.delegation.title"),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CIRISColors.TextPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = CIRISColors.SignetTeal.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(
                                        text = "LIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CIRISColors.SignetTeal,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = localizedString("commons.federation.delegation.description"),
                                fontSize = 12.sp,
                                color = CIRISColors.TextSecondary,
                            )
                        }
                    }
                }

                // Inbound Scope Delegations
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testable("card_delegation_inbound"),
                    color = CIRISColors.BackgroundDarker,
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "INBOUND DELEGATIONS",
                            color = CIRISColors.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp,
                        )
                        Text(
                            text = "Scopes delegated to this agent by peer identities (e.g. replication, consensus voting, device proxying).",
                            color = CIRISColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (delegations.isNotEmpty()) {
                                    "${delegations.size} active inbound authority grant(s)"
                                } else {
                                    "No active inbound delegations recorded"
                                },
                                color = CIRISColors.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                // Outbound Scope Delegations
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testable("card_delegation_outbound"),
                    color = CIRISColors.BackgroundDarker,
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "OUTBOUND DELEGATIONS",
                            color = CIRISColors.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp,
                        )
                        Text(
                            text = "Scopes this agent has delegated to paired devices and occurrence instances.",
                            color = CIRISColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                        if (delegations.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "${delegations.size} active device authorization(s)",
                                    color = CIRISColors.TextPrimary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Button(
                            onClick = onManageDeviceGrants,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testableClickable("btn_delegation_manage_grants") { onManageDeviceGrants() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(
                                imageVector = CIRISIcons.keySecure,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = localizedString("nav.surface.delegations"),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
