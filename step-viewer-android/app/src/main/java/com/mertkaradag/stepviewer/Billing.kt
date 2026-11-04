package com.mertkaradag.stepviewer

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

/**
 * Google Play mağaza içi satın alma: tek seferlik "Reklamsız" ürünü.
 *
 * Ürün kimliği Play Console'da [PRODUCT_AD_FREE] adıyla "tek seferlik ürün" olarak
 * oluşturulmalıdır; fiyat (ör. 299 TL) orada ayarlanır, kodda sabit fiyat yoktur.
 * Satın alma durumu cihazda önbelleğe alınır, böylece açılışta reklamlar hiç istenmez;
 * her açılışta Play'den doğrulanır (iade edilirse reklamlar geri gelir).
 *
 * Tüm geri çağrılar ana iş parçacığında çalışır.
 */
class Billing(
    context: Context,
    private val onAdFreeChanged: (Boolean) -> Unit,
    private val onMessage: (Int) -> Unit,
) : PurchasesUpdatedListener {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var productDetails: ProductDetails? = null
    private var manualRestore = false

    /** Önbellekteki durum: açılışta reklam kararını beklemeden vermek için. */
    var adFree: Boolean = prefs.getBoolean(KEY_AD_FREE, false)
        private set

    /** Play'den alınan yerel fiyat metni ("₺299,00" gibi); henüz yoksa null. */
    val formattedPrice: String?
        get() = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection bir sonraki çağrıda yeniden bağlanır
            }
        })
    }

    fun destroy() = client.endConnection()

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_AD_FREE)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val found = details.productDetailsList.firstOrNull { it.productId == PRODUCT_AD_FREE }
                ui { productDetails = found }
            }
        }
    }

    /** Sahip olunan satın almaları Play'den okur (yeni telefonda otomatik geri yükleme). */
    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                ui {
                    if (manualRestore) onMessage(R.string.billing_unavailable)
                    manualRestore = false
                }
                return@queryPurchasesAsync
            }
            val owned = purchases.any { it.ownsAdFree() }
            purchases.filter { it.ownsAdFree() }.forEach { acknowledge(it) }
            ui {
                setAdFree(owned)
                if (manualRestore) {
                    onMessage(if (owned) R.string.billing_restored else R.string.billing_nothing_to_restore)
                }
                manualRestore = false
            }
        }
    }

    /** "Satın alımı geri yükle": Play'den yeniden okur ve sonucu kullanıcıya söyler. */
    fun restore() {
        manualRestore = true
        if (client.isReady) {
            queryPurchases()
        } else {
            start()
        }
    }

    /** Satın alma ekranını açar. */
    fun buyAdFree(activity: Activity) {
        val details = productDetails
        if (!client.isReady || details == null) {
            onMessage(R.string.billing_unavailable)
            if (!client.isReady) start() else queryProduct()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty().filter { PRODUCT_AD_FREE in it.products }
                list.filter { it.ownsAdFree() }.forEach { acknowledge(it) }
                ui {
                    when {
                        list.any { it.ownsAdFree() } -> {
                            setAdFree(true)
                            onMessage(R.string.billing_thanks)
                        }
                        list.any { it.purchaseState == Purchase.PurchaseState.PENDING } ->
                            onMessage(R.string.billing_pending)
                    }
                }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> ui {
                setAdFree(true)
                onMessage(R.string.billing_restored)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> ui { onMessage(R.string.billing_failed) }
        }
    }

    /** Onaylanmayan satın almaları Play 3 gün içinde iade eder: hemen onaylanır. */
    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { }
    }

    private fun Purchase.ownsAdFree(): Boolean =
        PRODUCT_AD_FREE in products && purchaseState == Purchase.PurchaseState.PURCHASED

    private fun setAdFree(value: Boolean) {
        if (adFree == value) return
        adFree = value
        prefs.edit().putBoolean(KEY_AD_FREE, value).apply()
        onAdFreeChanged(value)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun ui(task: () -> Unit) {
        mainHandler.post(task)
    }

    companion object {
        const val PRODUCT_AD_FREE = "reklamsiz"
        private const val PREFS = "billing"
        private const val KEY_AD_FREE = "ad_free"
    }
}
