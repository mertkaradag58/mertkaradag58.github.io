package com.mertkaradag.stepviewer

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob reklamları: altta uyarlanabilir banner ve geçiş (tam ekran) reklamı.
 *
 * Önce Google'ın onay formu (UMP) gösterilir; Avrupa'da kullanıcı onay vermeden
 * reklam istenmez. Reklam kimlikleri build.gradle.kts içinde BuildConfig'e yazılır
 * (gerçek kimlikler verilmezse Google'ın test reklamları kullanılır).
 */
class Ads(private val activity: Activity, private val bannerContainer: ViewGroup) {

    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
    private val started = AtomicBoolean(false)
    private var banner: AdView? = null
    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false
    private var lastInterstitialAt = 0L

    /** Onay durumunu günceller, gerekirse formu gösterir, sonra reklamları başlatır. */
    fun start() {
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (consent.canRequestAds()) initAds()
                }
            },
            {
                // Ağ yoksa vb.: önceki oturumdan izin varsa yine de devam et
                if (consent.canRequestAds()) initAds()
            },
        )
        // Önceki oturumda onay alınmışsa beklemeden başla
        if (consent.canRequestAds()) initAds()
    }

    /** Menüde "Gizlilik seçenekleri" gösterilmeli mi (Avrupa kullanıcıları). */
    val privacyOptionsRequired: Boolean
        get() = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            if (consent.canRequestAds()) initAds()
        }
    }

    private fun initAds() {
        if (!started.compareAndSet(false, true)) return
        Thread {
            MobileAds.initialize(activity) {}
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                loadBanner()
                loadInterstitial()
            }
        }.start()
    }

    private fun loadBanner() {
        val view = AdView(activity)
        view.adUnitId = BuildConfig.ADMOB_BANNER_ID
        val metrics = activity.resources.displayMetrics
        val widthDp = (bannerContainer.width.takeIf { it > 0 } ?: metrics.widthPixels) / metrics.density
        view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerSize(activity, widthDp.toInt()))
        view.adListener = object : AdListener() {
            override fun onAdLoaded() {
                bannerContainer.visibility = View.VISIBLE
            }
        }
        bannerContainer.removeAllViews()
        bannerContainer.addView(view)
        banner = view
        view.loadAd(AdRequest.Builder().build())
    }

    private fun loadInterstitial() {
        if (loadingInterstitial || interstitial != null || !started.get()) return
        loadingInterstitial = true
        InterstitialAd.load(
            activity,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingInterstitial = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    interstitial = null
                }
            },
        )
    }

    /**
     * Hazırsa tam ekran reklamı gösterir. Kullanıcıyı boğmamak (ve AdMob kurallarına
     * uymak) için iki reklam arasında en az [MIN_INTERVAL_MS] geçer.
     */
    fun showInterstitial() {
        val ad = interstitial
        if (ad == null) {
            loadInterstitial()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastInterstitialAt != 0L && now - lastInterstitialAt < MIN_INTERVAL_MS) return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInterstitial()
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                loadInterstitial()
            }
        }
        interstitial = null
        lastInterstitialAt = now
        ad.show(activity)
    }

    fun onPause() = banner?.pause()
    fun onResume() = banner?.resume()
    fun onDestroy() {
        banner?.destroy()
        banner = null
    }

    companion object {
        private const val MIN_INTERVAL_MS = 60_000L
    }
}
