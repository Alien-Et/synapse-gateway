package io.synapse.app.ui.providers.mcloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.synapse.app.ui.AppDialog
import io.synapse.app.ui.AppDialogButton
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.SectionLabel
import io.synapse.app.ui.providers.ProviderUi
import io.synapse.app.ui.providers.ProviderUiActions
import io.synapse.core.pool.AccountStatus
import io.synapse.core.provider.HumanCheckChallenge
import io.synapse.core.provider.mcloud.MCloudProvider
import kotlinx.coroutines.delay

/**
 * 中国移动云盘（139 云）供应商界面。
 *
 * ## 登录三步（顺序不能乱）
 * 1. 填手机号
 * 2. 在内嵌 WebView 里拖阿里云滑块（人机校验）
 * 3. **校验页自己把短信码发出去**，界面收起 WebView，只剩验证码输入框
 *
 * ## 第 3 步为什么不能自己发码
 *
 * 上游的滑块是阿里云验证码 3.0，跑在 `yun.139.com/fe/captcha/` 这个 H5 页面里。
 * 页面验证通过后会**立刻自己调 `/user/sms/getSmsCode`**（用它自己的 AES-256 密钥 +
 * `puzzleVerfycode` 凭证），实测响应就是 `{"success":true,"code":"0000",...}`。
 *
 * 所以我们只需要监听到这个成功信号，然后**什么也不做**。要是再自己发一次码，
 * 一来白耗上游的次数配额（响应里的 `remainGetTimes`），二来我们手上没有新鲜的
 * `puzzleVerfycode`，必然被回 `200059554 请先获取滑块拼图验证码`。
 *
 * ## 「重新发送」为什么是重开校验页
 *
 * 同理：重发也需要一个新 token，而 token 只能由用户在滑块上现拖出来。
 * 所以 WebView 模式下这个按钮的语义是「重新校验」，点了会回到第 2 步。
 */
object MCloudUi : ProviderUi {

    override val id: String = MCloudProvider.ID

    /** 验证码重发间隔（秒）。 */
    private const val RESEND_SECONDS = 60

    override fun description(): String = "手机号登录，可用 qwen / GLM / DeepSeek / Seed 四家模型"

    @Composable
    override fun Icon(modifier: Modifier) {
        MaterialIcon(
            imageVector = Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }
        var challenge by remember { mutableStateOf<HumanCheckChallenge?>(null) }
        var loading by remember { mutableStateOf(false) }
        /** 拼图已通过：收起拼图、进入「等短信」阶段。 */
        var puzzleSolved by remember { mutableStateOf(false) }
        /**
         * 本次校验走的是 WebView（阿里云）而不是自绘滑块。
         *
         * 决定「重新发送」按钮的行为：WebView 模式下重发必须重新拖一次滑块
         * （token 只能现拖出来），所以点了要退回校验阶段。
         */
        var usedWebView by remember { mutableStateOf(false) }
        /** 短信是否已发出（决定提示文案）。 */
        var smsSent by remember { mutableStateOf(false) }
        var sendError by remember { mutableStateOf("") }
        /**
         * 验证码弹窗是否展开。
         *
         * 为什么要独立弹窗：内嵌在页面里时，WebView 的触摸事件会被外层
         * LazyColumn/Column 的纵向滚动抢走，表现为「手指一滑页面就上下滚，
         * 滑块完全拖不动」。全屏 Dialog 是独立 window，不参与外层滚动，
         * 触摸事件能完整落在 WebView 里。
         */
        var captchaDialogVisible by remember { mutableStateOf(false) }

        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        /** 发码；成功后进入倒计时并提示已发送。 */
        fun sendCode() {
            countdown = RESEND_SECONDS
            sendError = ""
            actions.onSmsLogin(id, phone, "", "86") { error ->
                if (error.isEmpty()) {
                    smsSent = true
                } else {
                    countdown = 0
                    sendError = error
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("登录")
                Text(
                    text = "用中国移动绑定的手机号登录，登录后额度跟随该账号的会员权益。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { value ->
                        phone = value.filter(Char::isDigit).take(11)
                        // 换号码后旧拼图与发送状态全部作废
                        if (challenge != null || puzzleSolved || smsSent) {
                            challenge = null
                            puzzleSolved = false
                            smsSent = false
                            usedWebView = false
                            countdown = 0
                            sendError = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    label = { Text("手机号") },
                )

                val current = challenge
                when {
                    // 第一段：还没拿到拼图
                    //
                    // 手机号没填满 11 位时**不显示按钮**：验证页要把手机号编进 URL 才能打开，
                    // 号码不完整时打开的页面必然走不下去，摆一个点了只会报错的按钮纯属误导。
                    // （之前是常驻 + 点击提示位数不够，等于让用户先点错一次才知道不能点。）
                    !puzzleSolved && current == null && phone.length == 11 -> {
                        Button(
                            onClick = {
                                loading = true
                                sendError = ""
                                actions.onFetchHumanCheck(id, phone) { result ->
                                    loading = false
                                    challenge = result
                                    if (result == null) sendError = "人机校验加载失败，请重试"
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (loading) "正在加载…" else "获取人机校验")
                        }
                    }

                    // 号码还没填完：只给一行提示，不摆按钮
                    !puzzleSolved && current == null && phone.isNotEmpty() -> {
                        Text(
                            text = "手机号填满 11 位后会出现「获取人机校验」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 第二段：校验进行中
                    //
                    // WebView 模式（灵犀走这条）：校验页自己会把短信码发出去，
                    // 所以 onPassed 里**不能再调 sendCode()**，否则白耗一次发码配额。
                    //
                    // 只保留**一个**按钮。之前「重新打开验证窗口」+「重新加载校验页」两个按钮
                    // 功能几乎一样，而且后者只是重新拉链接、不会打开弹窗，看起来像点了没反应。
                    !puzzleSolved && current != null && current.webUrl.isNotEmpty() -> {
                        // 拿到校验链接后自动弹出浮层对话框，不需要用户再点一次
                        LaunchedEffect(current.webUrl) {
                            captchaDialogVisible = true
                        }
                        Text(
                            text = "在弹出的窗口里拖动滑块，通过后验证码会自动发送",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { captchaDialogVisible = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("打开人机验证")
                        }
                    }

                    // 第二段（备选）：自绘滑块 —— 上游直接给两张 base64 图时才走这条
                    !puzzleSolved && current != null -> {
                        SlidePuzzle(
                            sliderImage = current.sliderImage,
                            backgroundImage = current.backgroundImage,
                            sliderWidth = current.sliderWidth,
                            onCompleted = {
                                // 通过 → 立刻收起拼图 + 自动发短信
                                puzzleSolved = true
                                challenge = null
                                sendCode()
                            },
                            onRefresh = {
                                loading = true
                                actions.onFetchHumanCheck(id, phone) { result ->
                                    loading = false
                                    challenge = result
                                }
                            },
                        )
                    }
                }

                // 第三段：拼图通过后才出现验证码表单
                if (puzzleSolved) {
                    if (smsSent) {
                        Text(
                            text = "验证码已发送到 ${phone.take(3)}****${phone.takeLast(4)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(8) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("短信验证码") },
                    )
                    if (sendError.isNotEmpty()) {
                        Text(
                            text = sendError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (usedWebView) {
                                    // WebView 模式：token 只能由用户现拖出来，退回校验阶段
                                    puzzleSolved = false
                                    smsSent = false
                                    sendError = ""
                                    countdown = 0
                                    loading = true
                                    actions.onFetchHumanCheck(id, phone) { result ->
                                        loading = false
                                        challenge = result
                                        if (result == null) sendError = "人机校验加载失败，请重试"
                                    }
                                } else {
                                    sendCode()
                                }
                            },
                            enabled = countdown == 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                when {
                                    countdown > 0 -> "${countdown} 秒后重发"
                                    usedWebView -> "重新校验"
                                    else -> "重新发送"
                                },
                            )
                        }
                        Button(
                            onClick = {
                                loading = true
                                sendError = ""
                                actions.onSmsLogin(id, phone, code, "86") { error ->
                                    loading = false
                                    if (error.isEmpty()) {
                                        // 登录成功后立刻收起表单、回到最初状态：
                                        // 否则会停在验证码界面，用户会以为没成功又点一次
                                        // （验证码是一次性的，重复提交必然回 9441）。
                                        phone = ""
                                        code = ""
                                        challenge = null
                                        puzzleSolved = false
                                        smsSent = false
                                        usedWebView = false
                                        countdown = 0
                                        sendError = ""
                                    } else {
                                        sendError = error
                                    }
                                }
                            },
                            enabled = code.length >= 4 && !loading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (loading) "登录中…" else "登录")
                        }
                    }
                } else if (sendError.isNotEmpty()) {
                    // 不依赖 current：WebView 校验失败时会把 challenge 清空以便重载，
                    // 若这里要求 current != null，用户就看不到失败原因了
                    Text(
                        text = sendError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

            // 人机验证：**同界面浮层对话框**（不是跳页面，也不是铺满全屏）
            //
            // 为什么必须浮层：内嵌在页面里时，WebView 的触摸会被外层滚动抢走，
            // 表现为「手指一滑页面就上下滚、滑块拖不动」。Dialog 是独立 window，
            // 不参与外层滚动；这里再用 usePlatformDefaultWidth=false 让宽度可控，
            // 外观仍然是常规的居中卡片对话框。
            val pendingChallenge = challenge
            if (captchaDialogVisible && pendingChallenge != null) {
                // 走统一弹窗外观（AppDialog）：从前这里是自绘 Surface，圆角/内边距/标题
                // 与其它页面各不相同。宽度给到 0.96（比默认 0.92 略宽）是因为里面嵌了
                // WebView 滑块，太窄会拖不动。
                AppDialog(
                    title = "人机验证",
                    subtitle = "在下方滑动验证，通过后验证码会自动发送",
                    onDismiss = { captchaDialogVisible = false },
                    modifier = Modifier.fillMaxWidth(0.96f),
                    // ★ 必须关掉内层滚动：AppDialog 默认给内容套 verticalScroll，
                    // 而 verticalScroll 会先抢走拖动手势，WebView 里的滑块就拖不动了
                    // （表现是「手指一滑整个弹窗内容在滚，滑块纹丝不动」）。
                    scrollable = false,
                    buttons = {
                        AppDialogButton("关闭", { captchaDialogVisible = false })
                    },
                ) {
                            MCloudCaptchaWebView(
                                url = pendingChallenge.webUrl,
                                userAgent = pendingChallenge.userAgent,
                                onPassed = {
                                    puzzleSolved = true
                                    challenge = null
                                    smsSent = true
                                    usedWebView = true
                                    countdown = RESEND_SECONDS
                                    captchaDialogVisible = false
                                    actions.onHumanCheckPassed(id)
                                },
                                onFailed = { reason ->
                                    sendError = reason
                                    challenge = null
                                    captchaDialogVisible = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                }
            }

            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
