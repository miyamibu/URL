package jp.mimac.urlsaver.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.StorePurchaseRemoteDataSource
import jp.mimac.urlsaver.domain.BillingPeriod
import jp.mimac.urlsaver.domain.PlanType
import jp.mimac.urlsaver.domain.SubscriptionProductIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

sealed interface BillingPurchaseResult {
    data object Started : BillingPurchaseResult
    data object AuthRequired : BillingPurchaseResult
    data object ProductUnavailable : BillingPurchaseResult
    data class Failure(val message: String) : BillingPurchaseResult
}

class GooglePlayBillingService(
    context: Context,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val remoteDataSource: StorePurchaseRemoteDataSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PurchasesUpdatedListener {
    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enableAutoServiceReconnection()
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .build()

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK || purchases.isNullOrEmpty()) return
        purchases.forEach { purchase -> scope.launchProcessPurchase(purchase) }
    }

    suspend fun launchPurchase(
        activity: Activity,
        planType: PlanType,
        billingPeriod: BillingPeriod,
    ): BillingPurchaseResult {
        val session = authSessionProvider.session.value ?: return BillingPurchaseResult.AuthRequired
        ensureReady()
        val productId = SubscriptionProductIds.expectedProductId(planType, billingPeriod)
        val productDetails = queryProductDetails(productId) ?: return BillingPurchaseResult.ProductUnavailable
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return BillingPurchaseResult.ProductUnavailable
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build(),
                    ),
                )
                .setObfuscatedAccountId(session.authUserId)
                .build(),
        )
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            BillingPurchaseResult.Started
        } else {
            BillingPurchaseResult.Failure(result.debugMessage)
        }
    }

    suspend fun processCurrentPurchases() {
        ensureReady()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(params) { _, purchases ->
                purchases.forEach { scope.launchProcessPurchase(it) }
                continuation.resume(Unit)
            }
        }
    }

    private suspend fun queryProductDetails(productId: String): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(params) { billingResult, result ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(null)
                    return@queryProductDetailsAsync
                }
                continuation.resume(result.productDetailsList.firstOrNull())
            }
        }
    }

    private suspend fun ensureReady(): BillingResult {
        if (billingClient.isReady) return okBillingResult()
        return suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    continuation.resume(billingResult)
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    private suspend fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val session = authSessionProvider.session.value ?: return
        val productId = purchase.products.firstOrNull() ?: return
        val result = runCatching {
            remoteDataSource.verifyStorePurchase(
                session = session,
                storePlatform = "google_play",
                storeProductId = productId,
                purchaseToken = purchase.purchaseToken,
                storeTransactionId = purchase.orderId,
            )
        }.getOrNull()
        if (!purchase.isAcknowledged && result?.status == "verified") {
            acknowledgePurchase(purchase.purchaseToken)
        }
    }

    private suspend fun acknowledgePurchase(purchaseToken: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                billingClient.acknowledgePurchase(params) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    private fun CoroutineScope.launchProcessPurchase(purchase: Purchase) {
        launch { runCatching { processPurchase(purchase) } }
    }

    private companion object {
        fun okBillingResult(): BillingResult =
            BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.OK)
                .build()
    }
}
