package com.taha.sahb

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * «Sahb Pro»: one non-consumable in-app product sold through Google Play Billing.
 * The unlock lives in the buyer's Google account (not in the APK), so a copied APK stays free.
 * Purchases are checked against Google's signature and the app's own signing certificate.
 */
object Pro {
    const val PRODUCT = "sahb_pro"

    /**
     * Play Console → Monetize → Monetization setup → Licensing: the base64 RSA public key.
     * When set, every purchase must carry Google's valid signature (blocks fake-purchase tools).
     */
    private const val PLAY_LICENSE_KEY = ""

    /**
     * SHA-256 of the certificates allowed to sign this app. Play Console → Test and release → App integrity →
     * App signing key certificate. When the Play key is filled in, a re-signed (modified) copy never unlocks Pro.
     */
    private const val UPLOAD_CERT = "48827CEDB9F64AC2D150A3CF3F97E7A759D7812127B92A32EB40B314652AB1C4"
    private const val PLAY_CERT = ""

    private const val PREFS = "sahb_state"
    private const val KEY = "u"

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var pendingActivity: Activity? = null

    /** Set by the emulator test in debug builds only. */
    var debugOverride: Boolean? = null

    @Volatile private var owned = false
    @Volatile var available = false; private set

    val isPro: Boolean
        get() {
            if (BuildConfig.DEBUG) debugOverride?.let { return it }
            return owned && genuine()
        }

    val price: String? get() = details?.oneTimePurchaseOfferDetails?.formattedPrice

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (appCtx == null) {
            appCtx = app
            owned = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) == stamp(app)
        }
        val c = client
        if (c != null && c.isReady) { refresh(); return }
        if (c != null) return
        client = BillingClient.newBuilder(app)
            .setListener(updated)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        connect()
    }

    private fun connect() {
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                available = r.responseCode == BillingClient.BillingResponseCode.OK
                if (available) { loadDetails(); refresh() }
                notifyChanged()
            }
            override fun onBillingServiceDisconnected() { available = false }
        })
    }

    private fun loadDetails() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()
        )).build()
        client?.queryProductDetailsAsync(params) { r, result ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                details = result.productDetailsList.firstOrNull()
                notifyChanged()
            }
        }
    }

    /** Re-reads what this Google account owns (also catches refunds). */
    fun refresh() {
        val c = client ?: return
        if (!c.isReady) return
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val ok = list.any { handle(it) }
            setOwned(ok)
        }
    }

    private val updated = PurchasesUpdatedListener { r, list ->
        val act = pendingActivity
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                var gotPro = false; var pending = false
                list?.forEach { p ->
                    if (p.purchaseState == Purchase.PurchaseState.PENDING && p.products.contains(PRODUCT)) pending = true
                    if (handle(p)) gotPro = true
                }
                if (gotPro) { setOwned(true); toast(act, R.string.pro_thanks) }
                else if (pending) toast(act, R.string.pro_pending)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            else -> toast(act, R.string.pro_failed)
        }
    }

    /** Verifies and acknowledges one purchase; true when it grants Pro. */
    private fun handle(p: Purchase): Boolean {
        if (!p.products.contains(PRODUCT) || p.purchaseState != Purchase.PurchaseState.PURCHASED) return false
        if (!signatureOk(p.originalJson, p.signature)) { Log.w("Sahb", "purchase signature rejected"); return false }
        if (!p.isAcknowledged) {
            client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
        }
        return true
    }

    private fun setOwned(v: Boolean) {
        owned = v
        appCtx?.let { c ->
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (v) putString(KEY, stamp(c)) else remove(KEY) }.apply()
        }
        notifyChanged()
    }

    /** The cached flag is tied to this install, so copying the app's data to another phone doesn't carry it. */
    private fun stamp(c: Context): String {
        val id = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "x"
        return sha256("$id|${c.packageName}|$PRODUCT")
    }

    fun buy(activity: Activity) {
        val c = client; val d = details
        if (c == null || !c.isReady || d == null) { unavailable(activity); return }
        pendingActivity = activity
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
        )).build()
        c.launchBillingFlow(activity, params)
    }

    private fun unavailable(activity: Activity) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pro_title)
            .setMessage(R.string.pro_unavailable)
            .setPositiveButton(R.string.pro_open_store) { _, _ ->
                val pkg = activity.packageName
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
                catch (e: ActivityNotFoundException) { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ checks
    private fun signatureOk(json: String, sig: String): Boolean {
        if (PLAY_LICENSE_KEY.isBlank()) return true
        return runCatching {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PLAY_LICENSE_KEY, Base64.DEFAULT)))
            Signature.getInstance("SHA1withRSA").run { initVerify(key); update(json.toByteArray()); verify(Base64.decode(sig, Base64.DEFAULT)) }
        }.getOrDefault(false)
    }

    @Volatile private var genuineCache: Boolean? = null

    /** True when the installed app is signed by one of our own certificates (not repackaged). */
    private fun genuine(): Boolean {
        if (BuildConfig.DEBUG || PLAY_CERT.isBlank()) return true
        genuineCache?.let { return it }
        val c = appCtx ?: return true
        val ok = runCatching {
            val info = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val certs = info.signingInfo?.apkContentsSigners ?: emptyArray()
            certs.any { val h = sha256Hex(it.toByteArray()); h == UPLOAD_CERT || h == PLAY_CERT }
        }.getOrDefault(false)
        genuineCache = ok
        return ok
    }

    private fun sha256(s: String) = sha256Hex(s.toByteArray())
    private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02X".format(it) }

    private fun toast(a: Activity?, res: Int) {
        val c = a ?: appCtx ?: return
        main.post { Toast.makeText(c, res, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ paywall
    fun showPaywall(activity: Activity, reason: Int) {
        val sheet = BottomSheetDialog(activity)
        val v = LayoutInflater.from(activity).inflate(R.layout.sheet_pro, null)
        v.findViewById<TextView>(R.id.pro_reason).setText(reason)
        val buy = v.findViewById<MaterialButton>(R.id.pro_buy)
        fun bindPrice() {
            val p = price
            buy.text = if (p != null) activity.getString(R.string.pro_buy, p) else activity.getString(R.string.pro_buy_noprice)
        }
        bindPrice()
        val l: () -> Unit = { bindPrice(); if (isPro) sheet.dismiss() }
        addListener(l)
        sheet.setOnDismissListener { removeListener(l) }
        buy.setOnClickListener { buy(activity) }
        v.findViewById<MaterialButton>(R.id.pro_later).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(v)
        sheet.show()
        init(activity)
    }
}
