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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
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
        val readyResult = ensureReady()
        if (readyResult.responseCode != BillingClient.BillingResponseCode.OK) {
            return BillingPurchaseResult.Failure(readyResult.debugMessage)
        }
        val productId = SubscriptionProductIds.expectedProductId(planType, billingPeriod)
        val productDetails = queryProductDetails(productId) ?: return BillingPurchaseResult.ProductUnavailable
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return BillingPurchaseResult.ProductUnavailable
        val obfuscatedAccountId = obfuscatedAccountId(session.authUserId)
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
                .setObfuscatedAccountId(obfuscatedAccountId)
                .setObfuscatedProfileId(obfuscatedAccountId)
                .build(),
        )
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            BillingPurchaseResult.Started
        } else {
            BillingPurchaseResult.Failure(result.debugMessage)
        }
    }

    suspend fun processCurrentPurchases() {
        var lastFailure: IOException? = null
        repeat(3) { attempt ->
            try {
                processCurrentPurchasesOnce()
                return
            } catch (error: IOException) {
                lastFailure = error
                if (attempt < 2) delay((attempt + 1) * 1_000L)
            }
        }
        throw lastFailure ?: IOException("Google Play purchase query failed")
    }

    private suspend fun processCurrentPurchasesOnce() {
        val readyResult = ensureReady()
        if (readyResult.responseCode != BillingClient.BillingResponseCode.OK) {
            throw IOException("Google Play Billing is not ready: ${readyResult.debugMessage}")
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val purchases = suspendCancellableCoroutine<List<Purchase>> { continuation ->
            billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resumeWith(
                        Result.failure(IOException("Google Play purchase query failed: ${billingResult.debugMessage}")),
                    )
                    return@queryPurchasesAsync
                }
                continuation.resume(purchases)
            }
        }
        purchases.forEach { processPurchase(it) }
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

                override fun onBillingServiceDisconnected() {
                    scope.launch { runCatching { processCurrentPurchases() } }
                }
            })
        }
    }

    private suspend fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val session = authSessionProvider.session.value ?: return
        val productId = purchase.products.firstOrNull() ?: return
        val result = remoteDataSource.verifyStorePurchase(
            session = session,
            storePlatform = "google_play",
            storeProductId = productId,
            purchaseToken = purchase.purchaseToken,
            storeTransactionId = purchase.orderId,
        )
        if (result.status != "verified") throw IOException("Google Play purchase was not verified")
        if (!purchase.isAcknowledged) {
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
                    if (it.responseCode == BillingClient.BillingResponseCode.OK) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWith(
                            Result.failure(IOException("Google Play purchase acknowledgement failed: ${it.debugMessage}")),
                        )
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchProcessPurchase(purchase: Purchase) {
        launch { runCatching { processPurchaseWithRetry(purchase) } }
    }

    private suspend fun processPurchaseWithRetry(purchase: Purchase) {
        var lastFailure: IOException? = null
        repeat(3) { attempt ->
            try {
                processPurchase(purchase)
                return
            } catch (error: IOException) {
                lastFailure = error
                if (attempt < 2) delay((attempt + 1) * 1_000L)
            }
        }
        throw lastFailure ?: IOException("Google Play purchase processing failed")
    }

    private fun obfuscatedAccountId(userId: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        fun okBillingResult(): BillingResult =
            BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.OK)
                .build()
    }
}
