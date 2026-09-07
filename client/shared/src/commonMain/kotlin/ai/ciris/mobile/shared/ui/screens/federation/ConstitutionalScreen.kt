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
import ai.ciris.mobile.shared.models.federation.AccordFamilyDto
import ai.ciris.mobile.shared.models.federation.AccordHaltStatusResponse
import ai.ciris.mobile.shared.models.federation.AccordHolderDto
import ai.ciris.mobile.shared.platform.rememberTestableScrollState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.nav.NavSurface
import ai.ciris.mobile.shared.ui.theme.CIRISColors

/**
 * "Constitutional" — accord-holder identity + reserved-prefix attestations.
 *
 * Per FSD-002 §4.1, `accord:*` is the one constitutional asymmetry: only
 * `identity_type=accord_holder` may emit those attestations, and the
 * federation directory authoritatively lists current holders.
 *
 * CIRISRegistry#23 has shipped. This screen provides direct visibility into
 * the HUMANITY_ACCORD 2-of-3 kill-switch family, holder roster, and authority.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConstitutionalScreen(
    family: AccordFamilyDto? = null,
    holders: List<AccordHolderDto> = emptyList(),
    holderThreshold: Int = 2,
    haltStatus: AccordHaltStatusResponse? = null,
    isLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onOpenAccordCeremony: () -> Unit = {},
    onOpenProvisionHolder: () -> Unit = {},
    onIssueClick: (String) -> Unit = {},
) {
    val scroll = rememberTestableScrollState()

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizedString("commons.federation.constitutional.title").ifEmpty { "Constitutional Standing" }) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testableClickable("btn_constitutional_back") { onNavigateBack() },
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
                        modifier = Modifier.testableClickable("btn_constitutional_refresh") { onRefresh() },
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
                .testable("screen_constitutional"),
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
                        .testable("card_constitutional_overview"),
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
                                imageVector = CIRISIcons.instructions,
                                contentDescription = null,
                                tint = CIRISColors.SignetTeal,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = localizedString("commons.federation.constitutional.title"),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CIRISColors.TextPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = if (family != null) CIRISColors.SignetTeal.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(
                                        text = if (family != null) "LIVE" else "NOT CONFIGURED",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (family != null) CIRISColors.SignetTeal else CIRISColors.TextDim,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = localizedString("commons.federation.constitutional.description"),
                                fontSize = 12.sp,
                                color = CIRISColors.TextSecondary,
                            )
                        }
                    }
                }

                // 2-of-3 Kill-Switch & Roster Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testable("card_accord_killswitch"),
                    color = CIRISColors.BackgroundDarker,
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "HUMANITY_ACCORD KILL-SWITCH",
                            color = CIRISColors.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp,
                        )
                        if (haltStatus?.halted == true) {
                            Surface(
                                color = CIRISColors.StatusWarn.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (haltStatus.record?.invocationId != null) {
                                        "ACTIVE HALT — Node execution halted by invocation ${haltStatus.record.invocationId}"
                                    } else {
                                        "ACTIVE HALT — Node execution is halted"
                                    },
                                    color = CIRISColors.StatusWarn,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "Killswitch disarmed — All systems nominal",
                                    color = CIRISColors.SignetTeal,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Text(
                            text = "A 2-of-3 human kill-switch family: 3 humans each holding a primary SEAT + a cold SPARE (6 keys total). FIPS YubiKey + ML-DSA hardware custody.",
                            color = CIRISColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = onOpenAccordCeremony,
                                modifier = Modifier
                                    .weight(1f)
                                    .testableClickable("btn_open_accord_ceremony") { onOpenAccordCeremony() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text(
                                    text = localizedString("nav.surface.accord_ceremony"),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            OutlinedButton(
                                onClick = onOpenProvisionHolder,
                                modifier = Modifier
                                    .weight(1f)
                                    .testableClickable("btn_open_provision_holder") { onOpenProvisionHolder() },
                            ) {
                                Text(
                                    text = localizedString("nav.surface.provision_accord_holder"),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // Reserved Prefix Authority Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testable("card_accord_holders"),
                    color = CIRISColors.BackgroundDarker,
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "RESERVED PREFIX AUTHORITY",
                            color = CIRISColors.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (holders.isNotEmpty()) {
                                    "${holders.size} registered accord holder(s) (${holderThreshold}-of-${holders.size} threshold)"
                                } else {
                                    "No registered accord holders"
                                },
                                color = CIRISColors.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        Text(
                            text = "Only certified accord_holder identities may emit accord:* namespace attestations. All attempts to forge constitutional claims are refused fail-closed by node verification.",
                            color = CIRISColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
