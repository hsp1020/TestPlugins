package com.kktv

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.TextView
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()

        val scrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        // 전체 레이아웃 구성
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(16.dp(context), 16.dp(context), 16.dp(context), 16.dp(context))
        }
        scrollView.addView(root)

        // 타이틀
        val titleTextView = TextView(context).apply {
            text = "CUTV 설정"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 8.dp(context))
        }
        root.addView(titleTextView)

        // 설명 문구
        val descTextView = TextView(context).apply {
            text = "사이트 보안 강화로 인해 자동 우회가 차단될 수 있습니다.\n아래 화면에서 '사람인지 확인하세요' 체크박스가 나타나면 직접 클릭하여 완료해 주세요.\n우회가 완료되면 자동으로 쿠키가 수집됩니다."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16.dp(context))
        }
        root.addView(descTextView)

        // 쿠키 상태 텍스트
        val statusTextView = TextView(context).apply {
            val savedCookie = sharedPref.getString("cf_cookies", null)
            text = if (savedCookie != null) "상태: 우회 완료 (쿠키 저장됨)" else "상태: 우회 필요 (저장된 쿠키 없음)"
            textSize = 14f
            setTextColor(if (savedCookie != null) Color.GREEN else Color.RED)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16.dp(context))
        }
        root.addView(statusTextView)

        // 웹뷰 컨테이너
        val webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400.dp(context)
            )
        }

        // 버튼 행 구성
        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnReload = Button(context).apply {
            text = "페이지 새로고침"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8.dp(context)
            }
            setOnClickListener {
                webView.visibility = View.VISIBLE
                webView.loadUrl("https://cu-tv.com")
                showToast("우회 페이지를 새로고침합니다.")
            }
        }

        val btnReset = Button(context).apply {
            text = "쿠키 초기화"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                sharedPref.edit().apply {
                    remove("cf_cookies")
                    remove("cf_user_agent")
                    apply()
                }
                statusTextView.text = "상태: 우회 필요 (저장된 쿠키 없음)"
                statusTextView.setTextColor(Color.RED)
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
        root.addView(buttonsLayout)

        setupWebView(webView, statusTextView)
        root.addView(webView)

        return scrollView
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, statusTextView: TextView) {
        // 제공자 소스와 동일한 고정 User-Agent 세팅
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

                // Cloudflare 통과 인증 쿠키(cf_clearance) 검출 시 영구 저장
                if (cookies.contains("cf_clearance")) {
                    sharedPref.edit().apply {
                        putString("cf_cookies", cookies)
                        putString("cf_user_agent", userAgent)
                        apply()
                    }
                    activity?.runOnUiThread {
                        statusTextView.text = "상태: 우회 완료 (쿠키 저장됨)"
                        statusTextView.setTextColor(Color.GREEN)
                        showToast("우회 성공! 쿠키가 안전하게 저장되었습니다.")
                        webView.visibility = View.GONE
                    }
                }
            }
        }
        webView.loadUrl("https://cu-tv.com")
    }
}
