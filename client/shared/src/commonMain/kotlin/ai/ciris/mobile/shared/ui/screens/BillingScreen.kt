package ai.ciris.mobile.shared.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import ai.ciris.mobile.shared.ui.icons.*
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import androidx.compose.material3.*
import ai.ciris.mobile.shared.ui.components.SessionExpiredBanner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableClickable
import ai.ciris.mobile.shared.platform.getAppVersion
import ai.ciris.mobile.shared.platform.getAppBuildNumber
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar

/**
 * Billing screen for purchasing CIRIS credits
 * Based on PurchaseActivity.kt
 *
 * Features:
 * - Display current credit balance
 * - Show available credit packages
 * - Google Play purchase flow (platform-specific)
 * - Purchase history
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    currentBalance: Int,
    products: List<CreditProduct>,
    isLoading: Boolean,
    errorMessage: String? = null,
    onProductClick: (CreditProduct) -> Unit,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit,
    onDismissError: () -> Unit = {},
    /** The billing credential is stale and only a sign-in renews it (CIRISClient#59). */
    authExpired: Boolean = false,
    onSignInAgain: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error in snackbar
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(
                message = errorMessage,
                duration = SnackbarDuration.Long
            )
            onDismissError()
        }
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(localizedString("mobile.screen_billing")) },
                navigationIcon = {
                    // Suppressed on compact viewports — the global 3-state
                    // overlay button in CIRISApp handles back navigation
                    // there to avoid the prior "back arrow + signet stacked"
                    // bug. Wider viewports (tablet/desktop) keep this arrow.
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testableClickable("btn_billing_back") { onNavigateBack() }
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // FIRST, ABOVE THE BALANCE. When the credential is stale the
                // balance below is not a fact about the account — it rendered
                // as 0 for 25 hours against 398 real credits — so the banner
                // outranks it, and the button is the only fix money cannot
                // buy (CIRISClient#59).
                if (authExpired) {
                    SessionExpiredBanner(
                        onSignInAgain = onSignInAgain,
                        tag = "btn_billing_sign_in_again",
                    )
                }

                if (currentBalance == BALANCE_NOT_ON_THIS_NODE) {
                    // This node has no billing route. Say THAT — not a balance:
                    // the old node path fabricated hasCredit=true, 0 credits,
                    // which is a fact about an account nobody asked about
                    // (CSD-056). No balance, no packages to buy.
                    ReadFailureBlock(
                        failure = ReadFailure.NotOnThisNode(),
                        tagPrefix = "billing",
                        notOnThisNode = localizedString("mobile.billing_not_on_this_node"),
                    )
                } else {
                // Current balance card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = localizedString("mobile.billing_balance"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (currentBalance >= 0) "$currentBalance credits" else localizedString("mobile.login_signin_provider").replace("{provider}", ""),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testable("text_billing_balance")
                        )
                    }
                }

                // Products section header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = localizedString("mobile.billing_packages"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(
                            onClick = onRefresh,
                            modifier = Modifier.testableClickable("btn_billing_refresh") { onRefresh() }
                        ) {
                            Text(localizedString("mobile.common_refresh"))
                        }
                    }
                }

                // Product list
                if (products.isEmpty() && !isLoading) {
                    // Empty state
                    Card(
                        modifier = Modifier.fillMaxWidth().testable("text_billing_no_products")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = localizedString("mobile.billing_no_products"),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = localizedString("mobile.billing_check_connection"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(products) { product ->
                            ProductCard(
                                product = product,
                                onClick = { onProductClick(product) }
                            )
                        }
                    }
                }
                } // not BALANCE_NOT_ON_THIS_NODE

                // Version info at bottom
                Spacer(modifier = Modifier.weight(if (products.isEmpty() && !isLoading) 1f else 0.01f))
                Text(
                    text = "CIRIS v${getAppVersion()} (${getAppBuildNumber()})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp)
                        .testable("txt_billing_version")
                )
            }

            // Loading overlay
            if (isLoading && products.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).testable("billing_loading")
                )
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: CreditProduct,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testableClickable("item_product_${product.productId}") { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${product.credits} CIRIS Credits",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = product.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = product.price,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Button(
                    onClick = onClick,
                    modifier = Modifier.testableClickable("btn_buy_${product.productId}") { onClick() },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(localizedString("mobile.billing_buy"))
                }
            }
        }
    }
}

/**
 * [BillingScreen]'s `currentBalance` when this node serves no billing route.
 * Distinct from -1 ("not loaded / sign in"): it is a fact about the node, and
 * the screen draws neither a balance nor anything to buy.
 */
const val BALANCE_NOT_ON_THIS_NODE = -2

/**
 * Data class for credit products
 * Matches ProductDetails from BillingManager.kt
 */
data class CreditProduct(
    val productId: String,
    val credits: Int,
    val price: String,
    val description: String
)
