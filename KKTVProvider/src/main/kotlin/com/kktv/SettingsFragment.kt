package com.kktv

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin

class SettingsFragment(
    private val plugin: Plugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {

    private fun Int.dp(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return (this * density).toInt()
    }

    // Ultima Evo의 테마 디자인 차용
    private fun getDarkThemeBg() = GradientDrawable().apply { setColor(Color.parseColor("#000000")); cornerRadius = 25f }
    private fun getCardBg() = GradientDrawable().apply { setColor(Color.parseColor("#0C0C0C")); setStroke(2, Color.parseColor("#1A1A1A")); cornerRadius = 18f }
    private fun getButtonBg() = GradientDrawable().apply { setColor(Color.parseColor("#151515")); cornerRadius = 12f }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()

        val scrollView = NestedScrollView(ctx).apply {
            background = getDarkThemeBg()
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(120)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 타이틀 헤더
        mainLayout.addView(TextView(ctx).apply {
            text = "CUTV 우회 설정"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 15)
            gravity = Gravity.CENTER
        })

        // 안내 문구
        mainLayout.addView(TextView(ctx).apply {
            text = "사이트 보안 강화로 자동 우회가 차단되었습니다.\n아래 화면에 체크박스가 뜨면 직접 클릭해 주세요."
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        // 쿠키 상태 표시 (카드 UI)
        val statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            background = getCardBg()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
        }

        val statusTextView = TextView(ctx).apply {
            val savedCookie = sharedPref.getString("cf_cookies", null)
            text = if (savedCookie != null) "현재 상태 : 우회 완료 (쿠키 저장됨)" else "현재 상태 : 우회 필요 (저장된 쿠키 없음)"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (savedCookie != null) Color.parseColor("#55FF55") else Color.parseColor("#FF5555"))
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusTextView)
        mainLayout.addView(statusCard)

        // 웹뷰 컨테이너 (카드 UI)
        val webViewCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            background = getCardBg()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400.dp(ctx)
            ).apply { setMargins(0, 0, 0, 30) }
        }
        val webView = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        webViewCard.addView(webView)
        mainLayout.addView(webViewCard)

        // 버튼 행 구성
        val buttonsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnReload = Button(ctx).apply {
            text = "페이지 새로고침"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 15
            }
            setOnClickListener {
                webView.visibility = View.VISIBLE
                webView.loadUrl("https://cu-tv.com")
                showToast("우회 페이지를 새로고침합니다.")
            }
        }

        val btnReset = Button(ctx).apply {
            text = "쿠키 초기화"
            setTextColor(Color.parseColor("#FF5555")) // 초기화 버튼은 붉은색 포인트
            isAllCaps = false
            background = getButtonBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                sharedPref.edit().apply {
                    remove("cf_cookies")
                    remove("cf_user_agent")
                    apply()
                }
                statusTextView.text = "현재 상태 : 우회 필요 (저장된 쿠키 없음)"
                statusTextView.setTextColor(Color.parseColor("#FF5555"))
                webView.visibility = View.VISIBLE
                
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                
                webView.loadUrl("https://cu-tv.com")
                showToast("저장된 쿠키가 초기화되었습니다.")
            }
        }

        buttonsLayout.addView(btnReload)
        buttonsLayout.addView(btnReset)
        mainLayout.addView(buttonsLayout)

        setupWebView(webView, statusTextView)
        
        scrollView.addView(mainLayout)
        return scrollView
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }
        // 배경 투명화 처리 (둥근 모서리 보존)
        (view?.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, statusTextView: TextView) {
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString = userAgent

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return

                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie(url) ?: ""

                if (cookies.contains("cf_clearance")) {
                    sharedPref.edit().apply {
                        putString("cf_cookies", cookies)
                        putString("cf_user_agent", userAgent)
                        apply()
                    }
                    activity?.runOnUiThread {
                        statusTextView.text = "현재 상태 : 우회 완료 (쿠키 저장됨)"
                        statusTextView.setTextColor(Color.parseColor("#55FF55"))
                        showToast("우회 성공! 쿠키가 안전하게 저장되었습니다.")
                    }
                }
            }
        }
        webView.loadUrl("https://cu-tv.com")
    }
}
