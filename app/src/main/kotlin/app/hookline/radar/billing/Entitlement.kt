package app.hookline.radar.billing

import android.app.Activity
import android.content.Context
import app.hookline.radar.BuildConfig
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import app.hookline.radar.i18n.tr

/** What the user may do right now. */
data class Access(
    /** Bought the one-time unlock (or a debug build). */
    val unlocked: Boolean = false,
    /** Whole days left in the free trial; 0 once it has ended. */
    val trialDaysLeft: Int = TRIAL_DAYS,
    /** Localised price from Google Play, e.g. "€19.99"; null until loaded. */
    val price: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val allowed: Boolean get() = unlocked || trialDaysLeft > 0

    companion object { const val TRIAL_DAYS = 7 }
}

/**
 * Hookline is sold once: a 7-day free trial, then a single in-app purchase
 * ([PRODUCT_ID], a one-time "managed product" in Play Console) unlocks it for
 * good. No subscription, no account: Google Play remembers the purchase, so
 * "Restore" works on a new phone.
 */
class Entitlement private constructor(private val app: Context) {

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _access = MutableStateFlow(compute(null))
    val access: StateFlow<Access> = _access.asStateFlow()
    private var details: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener { result, purchases -> onPurchases(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        if (prefs.getLong(KEY_FIRST, 0L) == 0L) prefs.edit().putLong(KEY_FIRST, System.currentTimeMillis()).apply()
        _access.value = compute(_access.value)
        if (!BuildConfig.DEBUG) connect()
    }

    private fun compute(prev: Access?): Access {
        val first = prefs.getLong(KEY_FIRST, System.currentTimeMillis()).takeIf { it > 0 } ?: System.currentTimeMillis()
        val elapsedDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - first).toInt()
        val left = (Access.TRIAL_DAYS - elapsedDays).coerceIn(0, Access.TRIAL_DAYS)
        val unlocked = BuildConfig.DEBUG || prefs.getBoolean(KEY_UNLOCKED, false)
        return prev?.copy(unlocked = unlocked, trialDaysLeft = left) ?: Access(unlocked, left)
    }

    /** Re-check the trial clock (call when the app comes to the foreground). */
    fun refresh() {
        _access.value = compute(_access.value)
        if (!BuildConfig.DEBUG && client.isReady) queryPurchases()
    }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) {
                    queryDetails()
                    queryPurchases()
                }
            }
            override fun onBillingServiceDisconnected() = Unit // auto-reconnection is enabled
        })
    }

    private fun queryDetails() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(ProductType.INAPP).build()),
        ).build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode != BillingResponseCode.OK) return@queryProductDetailsAsync
            details = list.productDetailsList.firstOrNull()
            val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
            _access.update { it.copy(price = price) }
        }
    }

    private fun queryPurchases() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) handle(purchases, fromRestore = false)
        }
    }

    /** Opens Google Play's purchase sheet. */
    fun purchase(activity: Activity) {
        val d = details
        if (d == null) {
            _access.update { it.copy(message = tr("Google Play isn't reachable right now. Check your connection and try again.")) }
            if (!client.isReady) connect() else queryDetails()
            return
        }
        _access.update { it.copy(busy = true, message = null) }
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()),
        ).build()
        val r = client.launchBillingFlow(activity, flow)
        if (r.responseCode != BillingResponseCode.OK) {
            _access.update { it.copy(busy = false, message = tr("Couldn't open Google Play (%s).", r.debugMessage.ifBlank { "code ${r.responseCode}" })) }
        }
    }

    /** Looks up an earlier purchase on this Google account. */
    fun restore() {
        if (!client.isReady) { connect(); _access.update { it.copy(message = tr("Connecting to Google Play…")) }; return }
        _access.update { it.copy(busy = true, message = null) }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) handle(purchases, fromRestore = true)
            else _access.update { it.copy(busy = false, message = tr("Couldn't reach Google Play.")) }
        }
    }

    fun clearMessage() = _access.update { it.copy(message = null) }

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> handle(purchases.orEmpty(), fromRestore = false)
            BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            BillingResponseCode.USER_CANCELED -> _access.update { it.copy(busy = false) }
            else -> _access.update { it.copy(busy = false, message = tr("Purchase didn't complete (%s).", result.debugMessage.ifBlank { "code ${result.responseCode}" })) }
        }
    }

    private fun handle(purchases: List<Purchase>, fromRestore: Boolean) {
        val ours = purchases.filter { PRODUCT_ID in it.products }
        val bought = ours.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        if (bought != null) {
            prefs.edit().putBoolean(KEY_UNLOCKED, true).apply()
            if (!bought.isAcknowledged) {
                // Unacknowledged purchases are refunded by Google after three days.
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(bought.purchaseToken).build()) { }
            }
        } else if (fromRestore && ours.isEmpty()) {
            // Nothing on this account (also covers a refund): keep whatever trial is left.
            prefs.edit().putBoolean(KEY_UNLOCKED, false).apply()
        }
        _access.update {
            compute(it).copy(
                busy = false,
                message = when {
                    bought != null && fromRestore -> tr("Purchase restored. Thank you!")
                    pending -> tr("Your payment is pending. Hookline unlocks as soon as Google Play confirms it.")
                    fromRestore && bought == null -> tr("No earlier purchase found on this Google account.")
                    else -> null
                },
            )
        }
    }

    companion object {
        const val PRODUCT_ID = "hookline_unlock"
        private const val PREFS = "entitlement"
        private const val KEY_FIRST = "first_launch"
        private const val KEY_UNLOCKED = "unlocked"

        @Volatile private var instance: Entitlement? = null
        fun get(context: Context): Entitlement = instance ?: synchronized(this) {
            instance ?: Entitlement(context.applicationContext).also { instance = it }
        }

        /** Cheap check for background work (no billing connection). */
        fun allowed(context: Context): Boolean {
            if (BuildConfig.DEBUG) return true
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getBoolean(KEY_UNLOCKED, false)) return true
            val first = p.getLong(KEY_FIRST, 0L).takeIf { it > 0 } ?: return true
            return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - first) < Access.TRIAL_DAYS
        }
    }
}
