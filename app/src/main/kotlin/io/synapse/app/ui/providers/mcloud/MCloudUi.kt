package io.synapse.app.ui.providers.mcloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
                    text = "用中国移动云盘绑定的手机号登录，登录后额度跟随该账号的会员权益。",
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

                    // 第二段：校验进行中
                    !puzzleSolved && current != null && current.webUrl.isNotEmpty() -> {
                        // WebView 模式（移动云盘走这条）：
                        // 校验页自己会把短信码发出去，所以 onPassed 里**不能再调 sendCode()**，
                        // 否则会白耗一次上游的发码次数配额。
                        Text(
                            text = "请拖动滑块完成校验，通过后验证码会自动发送",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MCloudCaptchaWebView(
                            url = current.webUrl,
                            userAgent = current.userAgent,
                            onPassed = {
                                puzzleSolved = true
                                challenge = null
                                smsSent = true
                                usedWebView = true
                                countdown = RESEND_SECONDS
                                // 告诉 provider：码已经发出去了，别再发一次
                                actions.onHumanCheckPassed(id)
                            },
                            onFailed = { reason ->
                                sendError = reason
                                // 失败就重新拉一次校验页，让用户能重试
                                challenge = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
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
                            Text(if (loading) "正在加载…" else "重新加载校验页")
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
                                actions.onSmsLogin(id, phone, code, "86") { error ->
                                    if (error.isNotEmpty()) sendError = error
                                }
                            },
                            enabled = code.length >= 4,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("登录")
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
