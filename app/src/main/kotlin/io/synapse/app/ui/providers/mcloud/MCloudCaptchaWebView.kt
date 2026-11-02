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
import androidx.compose.ui.unit.dp
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
    // 主文档加载失败的原因：失败就不再重试，直接把原因交给外层报错。
    // （不在这里做「重试 / 换浏览器」按钮：验证页失败几乎都是网络层问题，
    //   用户重开一次即可；在弹窗里堆按钮反而挡住页面。）
    var loadError by remember { mutableStateOf("") }
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
                        // 关掉默认手势拦截：否则 WebView 会自己吞掉 touch 事件，
                        // 注入的「全屏可抓滑块」脚本收不到 touchstart/touchmove
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                    }
                    // WebView 默认对触摸比较"聪明"（可能触发滚动/缩放），
                    // 这里不拦截，让事件原样交给页面 + 我们的注入脚本
                    isLongClickable = false
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
                                loadError = describeWebViewError(error, request.url?.toString())
                                loading = false
                                onFailed("校验页加载失败：$loadError")
                            }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            view?.evaluateJavascript(HOOK_JS, null)
                        }
                    }
                    loadError = ""
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

  // ---------------------------------------------------------------
  // 滑块「全屏可抓」增强
  //
  // 阿里云验证码 3.0 的滑块按钮只有约 44x44px，而且它的 pointerdown 只绑在按钮自己身上，
  // 手指落偏 2mm 就完全没反应。前面用 CSS 伪元素撑热区不生效（阿里云会重排 DOM）。
  //
  // 这里改成**自己做一次事件合成**：整页监听 touchstart，
  // 只要按下的位置在滑块按钮上方（直到页面顶部），就手工构造一套
  // touchstart → touchmove → touchend 用**真实的移动量**喂给滑块按钮。
  // 于是手指在图上任何地方按住都能拖动，而且拖动方向/距离完全跟手。
  // ---------------------------------------------------------------
  var npTracking = false;
  var npStartX = 0;
  var npLastX = 0;
  var npBtn = null;

  function npFindBtn(){
    var sels = ['[class*="slider-btn"]','[class*="slide-btn"]','[class*="sliderBtn"]','[class*="btn-slide"]',
                '[class*="slider"] [class*="btn"]','.nc_iconfont.btn_slide'];
    for (var i=0;i<sels.length;i++){
      var list = document.querySelectorAll(sels[i]);
      for (var j=0;j<list.length;j++){
        var el = list[j];
        var r = el.getBoundingClientRect();
        // 只要可见且够小的元素（按钮），排除整块容器
        if (r.width > 0 && r.width < 120 && r.height > 0 && r.height < 120) return el;
      }
    }
    return null;
  }

  function npFire(type, target, x, y){
    try {
      var touch = new Touch({ identifier: 1, target: target, clientX: x, clientY: y,
                              pageX: x, pageY: y, screenX: x, screenY: y });
      var ev = new TouchEvent(type, {
        bubbles: true, cancelable: true, composed: true,
        touches: type === 'touchend' ? [] : [touch],
        targetTouches: type === 'touchend' ? [] : [touch],
        changedTouches: [touch]
      });
      target.dispatchEvent(ev);
    } catch(e) {
      // 老 WebView 不支持 TouchEvent 构造器时退回 PointerEvent
      try {
        var ptype = type === 'touchend' ? 'pointerup' : (type === 'touchmove' ? 'pointermove' : 'pointerdown');
        target.dispatchEvent(new PointerEvent(ptype, { bubbles: true, cancelable: true,
          clientX: x, clientY: y, pointerId: 1, pointerType: 'touch', isPrimary: true }));
      } catch(e2) {}
    }
  }

  document.addEventListener('touchstart', function(ev){
    try {
      var btn = npFindBtn();
      if (!btn) return;
      var r = btn.getBoundingClientRect();
      var t = ev.touches && ev.touches[0];
      if (!t) return;
      // 手指已经在按钮上 → 交给阿里云自己处理
      if (t.clientX >= r.left && t.clientX <= r.right && t.clientY >= r.top && t.clientY <= r.bottom) return;
      // 只在按钮正上方（页面顶部到按钮）区域内接管
      if (t.clientY > r.top + r.height || t.clientY < r.top - window.innerHeight) return;

      npBtn = btn;
      npTracking = true;
      npStartX = t.clientX;
      npLastX = t.clientX;
      // 用按钮中心作为按下点，让阿里云认为是从手柄开始拖的
      npFire('touchstart', btn, r.left + r.width / 2, r.top + r.height / 2);
      report('__np_slider_grab', 0, 'y=' + Math.round(t.clientY));
      ev.preventDefault();
    } catch(e) {}
  }, { passive: false, capture: true });

  document.addEventListener('touchmove', function(ev){
    if (!npTracking || !npBtn) return;
    try {
      var t = ev.touches && ev.touches[0];
      if (!t) return;
      var r = npBtn.getBoundingClientRect();
      // 真实位移：手指移动多少，按钮就收到多少
      npFire('touchmove', npBtn, r.left + r.width / 2 + (t.clientX - npStartX), r.top + r.height / 2);
      npLastX = t.clientX;
      ev.preventDefault();
    } catch(e) {}
  }, { passive: false, capture: true });

  document.addEventListener('touchend', function(ev){
    if (!npTracking || !npBtn) return;
    try {
      var r = npBtn.getBoundingClientRect();
      npFire('touchend', npBtn, r.left + r.width / 2 + (npLastX - npStartX), r.top + r.height / 2);
      report('__np_slider_release', 0, 'dx=' + Math.round(npLastX - npStartX));
    } catch(e) {}
    npTracking = false;
    npBtn = null;
  }, { passive: false, capture: true });
})();
""".trimIndent()

/**
 * 把 WebView 的错误翻译成用户能行动的说明。
 *
 * 原生返回的是 `net::ERR_NAME_NOT_RESOLVED` 这类内核错误码，用户看到只会一脸茫然。
 * 其中 DNS 失败最常见也最容易被误判成「App 坏了」：设备若配置了一个不可用的
 * **私有 DNS（DoT）服务器**，系统级请求（如 curl）可能仍走常规 DNS 而正常，
 * 但 WebView 会优先尝试 DoT 并直接失败、不回落——于是表现为「只有这个页面打不开」。
 * 这种情况下要明确告诉用户去关私有 DNS，而不是让他反复重试。
 */
private fun describeWebViewError(error: android.webkit.WebResourceError?, url: String?): String {
    val raw: String = (error?.description ?: "").toString()
    val code = error?.errorCode ?: 0
    val host: String = runCatching { java.net.URI(url ?: "").host ?: "" }.getOrDefault("")
    val where = if (host.isEmpty()) "" else "（$host）"
    return when {
        // ERROR_HOST_LOOKUP = -2：域名解析失败
        code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||
            raw.contains("ERR_NAME_NOT_RESOLVED") ||
            raw.contains("ERR_NAME_RESOLUTION_FAILED") -> {
            "域名解析失败$where。请检查手机是否开启了「私人 DNS」，" +
                "若开启请先关闭（设置 → 网络和互联网 → 私人 DNS），然后重试。"
        }
        code == android.webkit.WebViewClient.ERROR_TIMEOUT || raw.contains("ERR_TIMED_OUT") ->
            "连接超时$where，请检查网络后重试。"
        code == android.webkit.WebViewClient.ERROR_CONNECT || raw.contains("ERR_CONNECTION") ->
            "无法连接服务器$where，请检查网络或代理设置。"
        // 证书类问题：系统时间不对时最常见
        raw.contains("ERR_CERT") -> "页面证书校验失败$where，请检查手机系统时间是否正确。"
        raw.isEmpty() -> "网络错误$where"
        else -> raw
    }
}
