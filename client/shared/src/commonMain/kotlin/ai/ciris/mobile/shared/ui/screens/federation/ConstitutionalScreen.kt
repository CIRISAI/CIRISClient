package ai.ciris.mobile.shared.ui.screens.federation

import ai.ciris.mobile.shared.ui.theme.CirisTheme

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
import ai.ciris.mobile.shared.models.federation.AccordEditionGate
import ai.ciris.mobile.shared.models.federation.HaltArmed
import ai.ciris.mobile.shared.models.federation.HaltStanding
import ai.ciris.mobile.shared.models.federation.KillSwitch
import ai.ciris.mobile.shared.models.capability.NodeEdition
import ai.ciris.mobile.shared.platform.rememberTestableScrollState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableWithHandler
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.nav.NavSurface
import ai.ciris.mobile.shared.ui.theme.CIRISColors
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

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
    /** m as the node serves it; null = the node has not said. Never defaulted to 2. */
    holderThreshold: Int? = null,
    haltStatus: AccordHaltStatusResponse? = null,
    isLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onOpenAccordCeremony: () -> Unit = {},
    onOpenProvisionHolder: () -> Unit = {},
    onIssueClick: (String) -> Unit = {},
    /** Which mesh the node is on; the in-app 0.5.x ceremonies are offered only on edition 1. */
    nodeEdition: NodeEdition = NodeEdition.UNKNOWN,
) {
    val scroll = rememberTestableScrollState()

    LaunchedEffect(Unit) {
        onRefresh()
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("commons.federation.constitutional.title")) },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testableWithHandler("btn_constitutional_back") { onNavigateBack() },
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
                        modifier = Modifier.testableWithHandler("btn_constitutional_refresh") { onRefresh() },
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
                                    color = if (family != null) CIRISColors.SignetTeal.copy(alpha = 0.18f) else CirisTheme.tokens.sunken,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.testable("chip_constitutional_family"),
                                ) {
                                    Text(
                                        text = if (family != null) {
                                            localizedString("mobile.constitutional_family_live")
                                        } else {
                                            localizedString("mobile.constitutional_family_not_configured")
                                        },
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
                            text = localizedString("mobile.constitutional_killswitch_heading"),
                            color = CIRISColors.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp,
                        )
                        // TWO FACTS (CSD-003 §2.1a): whether a halt is in force (standing)
                        // and whether a halt would reach this node at all (armed). Not
                        // halted is never drawn as "disarmed".
                        val reading = KillSwitch.read(haltStatus, isLoading)
                        val (standingKey, standingTag, standingColor) = when (val st = reading.standing) {
                            HaltStanding.Reading -> Triple(localizedString("mobile.constitutional_killswitch_loading"), "txt_killswitch_loading", CIRISColors.TextDim)
                            HaltStanding.Unknown -> Triple(localizedString("mobile.constitutional_killswitch_unknown"), "txt_killswitch_unknown", CIRISColors.TextDim)
                            HaltStanding.NotHalted -> Triple(localizedString("mobile.constitutional_halt_not_halted"), "txt_killswitch_not_halted", CIRISColors.SignetTeal)
                            HaltStanding.Lapsed -> Triple(localizedString("mobile.constitutional_halt_lapsed"), "txt_killswitch_lapsed", CIRISColors.TextSecondary)
                            is HaltStanding.Lone, HaltStanding.Confirmed -> {
                                val head = haltStatus?.record?.invocationId?.let { id ->
                                    localizedString("mobile.constitutional_halt_active_invocation", "id", id)
                                } ?: localizedString("mobile.constitutional_halt_active")
                                val tail = when (st) {
                                    is HaltStanding.Lone -> st.lapsesAt?.let {
                                        localizedString("mobile.constitutional_halt_lone_lapses", "time", it)
                                    } ?: localizedString("mobile.constitutional_halt_lone")
                                    else -> localizedString("mobile.constitutional_halt_confirmed")
                                }
                                Triple("$head\n$tail", "txt_killswitch_halted", CIRISColors.StatusWarn)
                            }
                        }
                        Surface(
                            color = if (standingTag == "txt_killswitch_halted") CIRISColors.StatusWarn.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = standingKey,
                                color = standingColor,
                                fontSize = 12.sp,
                                fontWeight = if (standingTag == "txt_killswitch_halted") FontWeight.Bold else FontWeight.Normal,
                                fontFamily = if (standingTag == "txt_killswitch_halted") FontFamily.Default else FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testable(standingTag),
                            )
                        }
                        Text(
                            text = localizedString(
                                when (reading.armed) {
                                    HaltArmed.ARMED -> "mobile.constitutional_armed_yes"
                                    HaltArmed.NOT_ARMED -> "mobile.constitutional_armed_no"
                                    HaltArmed.UNKNOWN -> "mobile.constitutional_armed_unknown"
                                },
                            ),
                            color = CIRISColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.testable(
                                when (reading.armed) {
                                    HaltArmed.ARMED -> "txt_killswitch_armed"
                                    HaltArmed.NOT_ARMED -> "txt_killswitch_not_armed"
                                    HaltArmed.UNKNOWN -> "txt_killswitch_armed_unknown"
                                },
                            ),
                        )
                        Text(
                            // The live threshold when the node has told us one,
                            // the design's shape when it has not. A fixed
                            // "2-of-3" printed over a family with a different
                            // threshold is a statement about the constitution
                            // that the constitution does not make.
                            text = if (holders.isNotEmpty() && holderThreshold != null) {
                                localizedString(
                                    "mobile.constitutional_family_desc_threshold",
                                    mapOf("m" to holderThreshold.toString(), "n" to holders.size.toString()),
                                )
                            } else {
                                localizedString("mobile.constitutional_family_desc")
                            },
                            color = CIRISColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                        AccordEditionGate.noteKey(nodeEdition)?.let { key ->
                            Text(
                                text = localizedString(key),
                                color = CIRISColors.TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.testable("note_constitutional_edition"),
                            )
                        }
                        if (AccordEditionGate.offersInAppCeremony(nodeEdition)) Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = onOpenAccordCeremony,
                                modifier = Modifier
                                    .weight(1f)
                                    .testableWithHandler("btn_open_accord_ceremony") { onOpenAccordCeremony() },
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
                                    .testableWithHandler("btn_open_provision_holder") { onOpenProvisionHolder() },
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
                            text = localizedString("mobile.constitutional_reserved_heading"),
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
                            // The threshold is the node's (`GET /v1/accord-holders`
                            // `threshold`), printed only beside a real roster.
                            Text(
                                text = if (holders.isNotEmpty() && holderThreshold != null) {
                                    localizedString(
                                        "mobile.constitutional_holders_count",
                                        mapOf("m" to holderThreshold.toString(), "n" to holders.size.toString()),
                                    )
                                } else if (holders.isNotEmpty()) {
                                    localizedString(
                                        "mobile.constitutional_holders_count_m_unknown",
                                        mapOf("n" to holders.size.toString()),
                                    )
                                } else {
                                    localizedString("mobile.constitutional_holders_none")
                                },
                                color = CIRISColors.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testable("txt_constitutional_threshold"),
                            )
                        }
                        Text(
                            text = localizedString("mobile.constitutional_reserved_desc"),
                            color = CIRISColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
