package io.synapse.app.ui.providers.mcloud

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.synapse.core.provider.mcloud.MCloudConstants
import io.synapse.core.provider.mcloud.MCloudCrypto

/**
 * 移动云盘的人机校验 WebView（阿里云验证码 3.0）。
 *
 * ## 为什么必须内嵌 WebView，不能自己画滑块
 *
 * 上游的滑块是**阿里云验证码 3.0**，跑在 `yun.139.com/fe/captcha/` 这个 H5 页面里：
 *
 * - `InitCaptchaV3` 要带约 2KB 的 `DeviceData`（JS 生成的混淆设备指纹）
 * - `VerifyCaptchaV3` 要带阿里云侧的 HMAC-SHA1 签名
 * - 图片、滑块轨迹判定全在阿里云服务端
 *
 * 纯 HTTP 复刻这套等于重写一遍阿里云风控 SDK，不现实。内嵌 WebView 让官方 JS
 * 自己跑，是成本最低也最稳的路（官方 App 自己就是这么干的）。
 *
 * ## 为什么用注入 JS 而不是 shouldInterceptRequest
 *
 * `WebViewClient.shouldInterceptRequest` 给的 `WebResourceRequest` **拿不到 POST body**
 * （Android WebView 的长期限制）。而校验页的发码请求是 axios 发的 XHR POST，
 * 我们真正关心的是它的**响应**（`success:true` 就说明码发出去了）。
 *
 * 所以在页面里 hook `XMLHttpRequest` 与 `fetch`，把 URL / 状态码 / 响应体
 * 通过 `@JavascriptInterface` 传回 Kotlin。
 *
 * ## 注入时机
 *
 * `onPageStarted` 注入一次，`onProgressChanged` 到 100 再补一次。
 * 只注入 `onPageFinished` 是不够的——校验页会跳多次（加载 SDK、拉图），
 * 中间任何一次跳转都可能把 hook 冲掉。重复注入有 `__npHooked` 幂等保护。
 *
 * @param url 校验页地址（[io.synapse.core.provider.mcloud.MCloudCaptcha.buildVerifyUrl]）
 * @param userAgent 必须伪装成官方 App 的 WebView UA，否则页面可能拒绝渲染
 * @param onPassed 校验页成功发出短信码时回调（**此时不要再自己发码**）
 * @param onFailed 明确失败时回调，参数是可展示给用户的原因
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MCloudCaptchaWebView(
    url: String,
    userAgent: String,
    onPassed: () -> Unit,
    onFailed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在加载校验页…") }
    // 用引用持有 WebView，便于销毁时清理（WebView 不释放会泄漏整个 Activity）
    val holder = remember { arrayOfNulls<WebView>(1) }

    val bridge = remember {
        CaptchaBridge(
            onPassed = {
                loading = false
                status = "校验通过，验证码已发送"
                onPassed()
            },
            onFailed = { reason ->
                status = reason
                onFailed(reason)
            },
            onStatus = { status = it },
        )
    }

    DisposableEffect(url) {
        onDispose {
            // WebView 必须显式销毁，否则后台仍会跑 JS 并持有 Context
            holder[0]?.let { wv ->
                runCatching { wv.stopLoading() }
                runCatching { wv.loadUrl("about:blank") }
                runCatching { wv.removeAllViews() }
                runCatching { wv.destroy() }
            }
            holder[0] = null
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(680f / 480f),
            factory = { ctx ->
                WebView(ctx).apply {
                    holder[0] = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 阿里云 SDK 要读 cookie 维持 certifyId
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        // 混合内容：图片走 https，但 SDK 偶尔请求 http 静态资源
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        userAgentString = userAgent
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    addJavascriptInterface(bridge, "NpCaptchaBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, u, favicon)
                            view?.evaluateJavascript(HOOK_JS, null)
                        }

                        override fun onPageFinished(view: WebView?, u: String?) {
                            super.onPageFinished(view, u)
                            // 再补一次：SDK 可能在我们注入前就建好了 XHR
                            view?.evaluateJavascript(HOOK_JS, null)
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            // 只报主文档的错误；子资源（图片、统计脚本）失败不影响校验
                            if (request?.isForMainFrame == true) {
                                onFailed("校验页加载失败：${error?.description ?: "网络错误"}")
                            }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            view?.evaluateJavascript(HOOK_JS, null)
                        }
                    }
                    loadUrl(url)
                }
            },
        )

        if (loading) {
            CircularProgressIndicator()
        }
    }

    if (status.isNotEmpty()) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // context 仅用于 factory；显式引用一次避免未使用告警
    @Suppress("UNUSED_EXPRESSION")
    context
}

/**
 * JS → Kotlin 的桥。
 *
 * 只在**主线程之外**被 WebView 调用（JS 桥有自己的线程），
 * 所以状态更新要走 Compose 的 snapshot 机制，这里直接调回调即可（回调内部是 mutableStateOf）。
 */
private class CaptchaBridge(
    private val onPassed: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    /** 是否已经报过成功，避免重复触发。 */
    @Volatile
    private var done = false

    @JavascriptInterface
    fun onResult(url: String, httpStatus: Int, body: String) {
        if (done) return
        // 只关心发码接口；其它请求（阿里云统计、日志）一律忽略
        if (!url.contains(MCloudConstants.PATH_SEND_SMS)) return

        val plain = decryptH5(body)
        if (plain.isEmpty()) {
            // 解不开就别猜，交给用户看页面
            onStatus("校验页返回了无法解析的响应（HTTP $httpStatus）")
            return
        }
        when {
            plain.contains("\"success\":true") -> {
                done = true
                onPassed()
            }
            plain.contains(MCloudConstants.CODE_CAPTCHA_FAILED) -> {
                // F019 之类：滑块过了但 token 被判无效，让用户重滑
                onFailed("滑块校验未通过（token 被拒），请重试")
            }
            plain.contains(MCloudConstants.CODE_NEED_CAPTCHA) -> {
                onStatus("等待完成滑块…")
            }
            else -> onStatus("校验页响应：${plain.take(80)}")
        }
    }

    /** 用 H5 那把 AES-256 密钥解响应；失败返回空串。 */
    private fun decryptH5(body: String): String {
        val raw = body.trim().removeSurrounding("\"")
        if (raw.isEmpty()) return ""
        // 上游可能直接回明文 JSON
        if (raw.startsWith("{")) return raw
        return runCatching {
            MCloudCrypto.decryptAesIv(raw, MCloudConstants.KEY_H5_CAPTCHA)
        }.getOrDefault("")
    }
}

/**
 * 注入到校验页的 hook 脚本。
 *
 * hook 两处：`XMLHttpRequest`（axios 走这条）和 `fetch`（SDK 的部分上报走这条）。
 * `__npHooked` 做幂等，重复注入不会叠加监听器。
 *
 * 响应体截断到 4096 字符：发码响应只有一两百字符，够用了，
 * 截断是为了防止 SDK 上报的大 payload 把 JS 桥撑爆。
 */
private val HOOK_JS = """
(function(){
  if (window.__npHooked) { return; }
  window.__npHooked = true;
  function report(u, s, t){
    try {
      if (!window.NpCaptchaBridge) { return; }
      window.NpCaptchaBridge.onResult(String(u||''), Number(s||0), String(t||'').slice(0,4096));
    } catch(e) {}
  }
  var oOpen = XMLHttpRequest.prototype.open;
  var oSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ this.__npUrl = u; return oOpen.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(b){
    var self = this;
    this.addEventListener('load', function(){
      var t = '';
      try { t = self.responseText || ''; } catch(e) {}
      report(self.__npUrl, self.status, t);
    });
    return oSend.apply(this, arguments);
  };
  var oFetch = window.fetch;
  if (oFetch) {
    window.fetch = function(input, init){
      var u = (typeof input === 'string') ? input : ((input && input.url) || '');
      return oFetch.apply(this, arguments).then(function(r){
        try {
          r.clone().text().then(function(t){ report(u, r.status, t); }).catch(function(){});
        } catch(e) {}
        return r;
      });
    };
  }
})();
""".trimIndent()
