package io.synapse.core.gateway

import com.google.gson.JsonObject
import io.synapse.core.pool.AccountImportResult
import io.synapse.core.pool.AccountPool
import io.synapse.core.pool.AccountSnapshot
import io.synapse.core.pool.AccountStatus
import io.synapse.core.pool.AccountTransfer
import io.synapse.core.pool.PoolSummary
import io.synapse.core.provider.ACTION_CHECKIN
import io.synapse.core.provider.ACTION_COMPLETE_TASKS
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.CredentialVerdict
import io.synapse.core.provider.CreditInfo
import io.synapse.core.provider.DeviceAuthPoll
import io.synapse.core.provider.DeviceAuthTicket
import io.synapse.core.provider.DeviceCodeSupport
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.LoopbackOAuthSupport
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderActionResult
import io.synapse.core.provider.ProviderTaskListView
import io.synapse.core.provider.PointsBill
import io.synapse.core.provider.PointsBillListView
import io.synapse.core.provider.ProviderCapability
import io.synapse.core.provider.ProviderHooks
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.ProviderRegistry
import io.synapse.core.provider.QuotaPack
import io.synapse.core.provider.RoutedModel
import io.synapse.core.provider.HumanCheckChallenge
import io.synapse.core.provider.HumanCheckSupport
import io.synapse.core.provider.SmsLoginSupport
import io.synapse.core.provider.WebLoginSupport
import io.synapse.core.provider.WebLoginTicket
import io.synapse.core.provider.antigravity.AntigravityProvider
import io.synapse.core.provider.custom.CustomProvider
import io.synapse.core.provider.raccoon.RaccoonProvider
import io.synapse.core.provider.trae.TraeProvider
import io.synapse.core.store.KeyValueStore
import io.synapse.core.usage.CallLogStore
import io.synapse.core.usage.CreditReport
import io.synapse.core.usage.RequestLog
import io.synapse.core.usage.StorageAudit
import io.synapse.core.usage.StorageReport
import io.synapse.core.usage.UsageStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray


/** 浏览器回调超时（原 companion，随登录段搬出）。 */
internal const val CALLBACK_TIMEOUT_MS = 5L * 60 * 1000
internal const val TRAE_CALLBACK_PORT = 51120
internal const val TRAE_CALLBACK_PATH = "/authorize"
internal const val RACCOON_CALLBACK_PORT = 51122
internal const val RACCOON_CALLBACK_PATH = "/callback"

/** [GatewayEngine] 的登录/导入能力（扩展函数，行为不变）。 */


    /**
     * 打开系统浏览器完成登录，返回要打开的 URL。
     *
     * - **Trae**：临时监听 51120 接住 `/authorize` 回调；
     * - **Antigravity**：监听 51121（Google 客户端注册的 redirect_uri 固定为此端口）；
     * - **WorkBuddy**：没有本地回调，返回授权页 URL 并附上轮询用的 state。
     *
     * 用系统浏览器而不是内置 WebView，是为了绕开设备 WebView 的渲染异常；
     * 登录期间会临时占用一个本地端口，收到回调或超时后自动释放。
     */
fun GatewayEngine.beginBrowserLogin(providerId: String, region: String = ""): BrowserLoginTicket {
        stopCallbackServer()
        val provider = registry.get(providerId) ?: throw IllegalStateException("未知供应商：$providerId")
        // 区域优先用调用方显式传入的：供应商设置是异步写入的，登录页与之后的轮询必须用同一区域，
        // 否则会在 A 站点创建 state、却去 B 站点轮询
        val loginRegion = region.ifEmpty { providerSettings(providerId).option("region", "") }
        return when {
            provider is WebLoginSupport && (providerId == TraeProvider.ID || providerId == RaccoonProvider.ID) -> {
                val (port, path) = if (providerId == RaccoonProvider.ID) {
                    RACCOON_CALLBACK_PORT to RACCOON_CALLBACK_PATH
                } else {
                    TRAE_CALLBACK_PORT to TRAE_CALLBACK_PATH
                }
                val callbackUrl = "http://127.0.0.1:$port$path"
                val ticket = provider.beginWebLogin(callbackUrl, loginRegion)
                startCallbackServer(port, path) { url ->
                    val outcome = completeWebLogin(providerId, url)
                    resultPage(outcome.ok, pool.displayNameOf(providerId, outcome.uid), outcome.error)
                }
                BrowserLoginTicket(ticket.loginUrl, "", loginRegion)
            }

            provider is LoopbackOAuthSupport -> {
                // 回调端口/路径由 provider 自己声明：redirect_uri 是上游注册值，写死会让第二家
                // loopback 供应商（如 ChatGPT 的 1455）永远收不到回调。
                startCallbackServer(provider.callbackPort, provider.callbackPath) { url ->
                    val outcome = completeOAuth(providerId, url)
                    resultPage(outcome.ok, pool.displayNameOf(providerId, outcome.uid), outcome.error)
                }
                BrowserLoginTicket(provider.buildAuthUrl())
            }

            provider is DeviceCodeSupport -> {
                // 区域感知的登录端点（WorkBuddy：cn=copilot.tencent.com / global=workbuddy.ai）
                val ticket = provider.startDeviceAuth(loginRegion)
                BrowserLoginTicket(ticket.loginUrl, ticket.state, loginRegion)
            }

            else -> throw IllegalStateException("该供应商不支持浏览器登录")
        }
    }

fun GatewayEngine.startCallbackServer(port: Int, path: String, handler: (String) -> String) {
        // 监听地址按 IPv4 → IPv6 依次尝试：只绑 IPv4 时，某些设备的浏览器把 localhost
        // 先解析成 ::1，第一次连接就 refused，用户只看到「无法访问此网站」。
        var lastError: Exception? = null
        var started: LoopbackCallbackServer? = null
        for (host in LoopbackCallbackServer.HOSTS) {
            val candidate = try {
                // handler 可能因为参数缺失/换 token 失败而返回失败页；那种情况**不能关端口**，
                // 否则用户点「重试登录」或刷新页面就再也连不上了。只有真正登录成功才释放。
                LoopbackCallbackServer(host, port, path) { url ->
                    val outcome = handler(url)
                    if (isSuccessPage(outcome)) stopCallbackServerSoon()
                    outcome
                }.also { it.startServer() }
            } catch (e: Exception) {
                lastError = e
                continue
            }
            started = candidate
            break
        }
        val server = started ?: throw IllegalStateException(
            "无法监听本地回调端口 $port" +
                if (LoopbackCallbackServer.isPortAvailable(port).not()) {
                    "：端口已被其它程序占用（请关闭占用该端口的应用后重试）"
                } else {
                    "：${lastError?.message ?: "未知原因"}"
                },
        )
        callbackServer = server
        log("已监听本地回调 http://127.0.0.1:$port$path")
        // 用户长时间不完成登录时释放端口，避免一直占着
        Thread {
            runCatching { Thread.sleep(CALLBACK_TIMEOUT_MS) }
            if (callbackServer === server) {
                callbackServer = null
                runCatching { server.stop() }
                log("浏览器登录超时，已释放回调端口 $port")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 结果页是否代表「登录真的成功了」。
     *
     * 只有成功才允许关端口：失败页留在浏览器里，用户刷新/重试时端口仍在监听。
     * 判据用结果页标题约定（[resultPage] 生成），避免 handler 额外改签名。
     */
fun GatewayEngine.isSuccessPage(html: String): Boolean = html.contains(SUCCESS_PAGE_MARKER)


fun GatewayEngine.stopCallbackServerSoon() {
        val server = callbackServer ?: return
        callbackServer = null
        Thread {
            runCatching { Thread.sleep(1_500) }
            runCatching { server.stop() }
        }.start()
    }

    /** 释放登录回调端口（登录完成或用户取消时调用）。 */
fun GatewayEngine.stopCallbackServer() {
        val server = callbackServer ?: return
        callbackServer = null
        runCatching { server.stop() }
    }

fun GatewayEngine.beginWebLogin(providerId: String, callbackUrl: String, region: String = ""): WebLoginTicket {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: throw IllegalStateException("该供应商不支持网页登录")
        return support.beginWebLogin(callbackUrl, region)
    }

fun GatewayEngine.completeWebLogin(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持网页登录")
        return try {
            val account = support.completeWebLogin(callbackUrl)
            adopt(providerId, account, "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    /** [ccode] 为国家码（不带 `+`）；非大陆号码必须传对，否则上游短信通道会拒发。 */
fun GatewayEngine.sendSmsCode(providerId: String, phone: String, ccode: String = SmsLoginSupport.DEFAULT_CCODE): String {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: throw IllegalStateException("该供应商不支持短信登录")
        return support.sendSmsCode(phone, ccode)
    }

    /**
     * 拉一张人机校验图（移动云盘的滑动拼图）。
     *
     * 只有实现了 [HumanCheckSupport] 的供应商才有；界面先用它判定要不要画滑块，
     * 需要时再调这里取图。
     */
fun GatewayEngine.fetchHumanCheck(providerId: String, phone: String): HumanCheckChallenge? {
        val support = registry.get(providerId) as? HumanCheckSupport ?: return null
        return support.fetchHumanCheck(phone)
    }

    /** 该供应商的短信登录是否需要先过人机校验。 */
fun GatewayEngine.requiresHumanCheck(providerId: String): Boolean =
        (registry.get(providerId) as? HumanCheckSupport)?.requiresHumanCheckBeforeSms == true

    /**
     * 通知供应商「人机校验已通过」。
     *
     * WebView 模式下必须调：校验页自己会把短信码发出去，provider 得记下这件事，
     * 后续 `sendSmsCode` 才不会重复发（上游有次数配额，多发一次少一次）。
     */
fun GatewayEngine.notifyHumanCheckPassed(providerId: String) {
        (registry.get(providerId) as? HumanCheckSupport)?.onHumanCheckPassed()
    }

fun GatewayEngine.completeSmsLogin(
        providerId: String,
        phone: String,
        code: String,
        msgid: String,
        ccode: String = SmsLoginSupport.DEFAULT_CCODE,
    ): LoginOutcome {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持短信登录")
        return try {
            adopt(providerId, support.loginBySmsCode(phone, code, msgid, ccode), "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

fun GatewayEngine.startDeviceAuth(providerId: String, region: String = ""): DeviceAuthTicket {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: throw IllegalStateException("该供应商不支持设备授权登录")
        return support.startDeviceAuth(region)
    }

fun GatewayEngine.pollDeviceAuth(providerId: String, state: String, region: String = ""): DeviceAuthPoll {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: return DeviceAuthPoll.Failed("该供应商不支持设备授权登录")
        val poll = support.pollDeviceAuth(state, region)
        if (poll is DeviceAuthPoll.Success) {
            adopt(providerId, poll.account, "登录")
        } else if (poll is DeviceAuthPoll.Failed) {
            // 授权页那边成功了、这里却失败时，先看这条日志确认轮询打的是不是同一个区域
            logWarn("设备授权轮询失败（$providerId，region=${region.ifEmpty { "默认" }}）：${poll.error}")
        }
        return poll
    }

fun GatewayEngine.completeOAuth(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? LoopbackOAuthSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持 OAuth 登录")
        // Google 在用户拒绝授权/账号被限制时会回调 ?error=access_denied，而不是给 code。
        // 不先看 error 的话，用户只会看到一句「回调里没有 code 参数」，完全不知道发生了什么。
        val error = io.synapse.core.provider.queryParam(callbackUrl, "error")
        if (!error.isNullOrEmpty()) {
            val description = io.synapse.core.provider.queryParam(callbackUrl, "error_description")
            val reason = description?.takeIf { it.isNotEmpty() } ?: when (error) {
                "access_denied" -> "你取消了授权，或该账号不允许此应用访问"
                "invalid_client" -> "客户端配置有问题"
                "redirect_uri_mismatch" -> "回调地址与注册的不一致"
                else -> ""
            }
            logWarn("OAuth 授权被拒（$providerId）：$error ${description.orEmpty()}")
            return LoginOutcome(LoginState.FAILED, error = "授权未完成（$error）${if (reason.isEmpty()) "" else "：$reason"}")
        }
        val code = io.synapse.core.provider.queryParam(callbackUrl, "code")
            ?: return LoginOutcome(LoginState.FAILED, error = "回调里没有 code 参数")
        // state 校验：Google 一定会原样回传，对不上说明这个回调不是本次登录发出的
        if (support is AntigravityProvider && !support.verifyState(callbackUrl)) {
            logWarn("OAuth 回调 state 不匹配（$providerId），已拒绝")
            return LoginOutcome(LoginState.FAILED, error = "回调校验失败（state 不匹配），请重新发起登录")
        }
        return try {
            // 走 completeCallback 让供应商拿到**完整 URL**：需要校验 state / 一次性消费 code 的
            // 供应商（如 ChatGPT）在那一层做防重放；默认实现等价于只取 code。
            adopt(providerId, support.completeCallback(callbackUrl), "登录")
        } catch (e: Exception) {
            logWarn("OAuth 登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    /** 粘贴 JSON 凭证导入（各供应商自行解析）。 */
fun GatewayEngine.importAccountJson(providerId: String, raw: String): LoginOutcome {
        val provider = registry.get(providerId)
            ?: return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return try {
            val account = provider.importCredentials(raw)
                ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持粘贴导入")
            adopt(providerId, account, "导入")
        } catch (e: Exception) {
            logWarn("导入失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "导入失败")
        }
    }

    /** 直接把账号写入账号池（自定义供应商手动添加 key 时用）。 */
fun GatewayEngine.addAccount(providerId: String, uid: String, nickname: String, secret: String): LoginOutcome {
        if (registry.get(providerId) == null) return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return adopt(providerId, ProviderAccount(providerId, uid, nickname, secret), "添加")
    }

    /**
     * 设置账号的自定义显示名（空串 = 恢复跟随上游昵称）。
     *
     * 与 [addAccount] 的区别：这里**不碰凭证**，只改账号状态里的 customName。
     * 昵称属于上游账号资料，重新登录/刷新凭证时会被上游重新下发覆盖；
     * 用户自己起的名字必须存在另一处，否则一次刷新就白改了。
     */
fun GatewayEngine.setAccountCustomName(providerId: String, uid: String, name: String): Boolean {
        if (registry.get(providerId) == null) return false
        val ok = pool.setCustomName(providerId, uid, name)
        if (ok) {
            val display = pool.displayNameOf(providerId, uid)
            log(if (name.isBlank()) "账号名已恢复为上游昵称：$display（$providerId）" else "账号名已改为：$display（$providerId）")
            onAccountsChanged?.invoke()
        }
        return ok
    }

fun GatewayEngine.adopt(providerId: String, account: ProviderAccount, verb: String): LoginOutcome {
        pool.upsert(account)
        refreshCredits(providerId, account.uid)
        log("$verb 成功：${pool.displayNameOf(account.providerId, account.uid)}（$providerId）")
        onAccountsChanged?.invoke()
        claimDesktopLoginRewardSoon(providerId, account.uid)
        checkNewAccountDailyStateSoon(providerId, account.uid)
        return LoginOutcome(LoginState.SUCCESS, account.uid, account.nickname)
    }

    /**
     * 账号加入后自动领取小浣熊的「桌面端登录奖励」。
     *
     * 该积分不是「点一下加积分」的签到，而是服务端对官方桌面客户端登录发放的奖励；
     * 因此领取请求必须带桌面客户端标识（见 RaccoonProvider 的 desktopHeaders），
     * 不主动触发就只能等用户自己去任务中心点。异步执行：失败只记日志，不影响登录结果。
     */
fun GatewayEngine.claimDesktopLoginRewardSoon(providerId: String, uid: String) {
        if (providerId != io.synapse.core.provider.raccoon.RaccoonProvider.ID) return
        ioExecutor().execute {
            val nickname = pool.displayNameOf(providerId, uid)
            runCatching { performAction(providerId, uid, ACTION_CHECKIN) }
                .onSuccess { result ->
                    if (result.ok) {
                        log("已自动领取桌面端登录奖励（$nickname）：${result.message}")
                    } else {
                        logWarn("桌面端登录奖励自动领取未成功（$nickname）：${result.message}")
                    }
                }
                .onFailure { logWarn("桌面端登录奖励自动领取异常（$nickname）：${it.message}") }
        }
    }

