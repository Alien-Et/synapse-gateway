package io.synapse.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.synapse.app.service.GatewayTileService
import io.synapse.app.ui.SystemPrompt
import io.synapse.app.service.QuotaAlertNotifier
import io.synapse.app.ui.pushPopSpec
import io.synapse.app.ui.tabContentSpec
import io.synapse.app.ui.AppUiState
import io.synapse.app.ui.ApiSettingsScreen
import io.synapse.app.ui.AppViewModel
import io.synapse.app.ui.AppearanceThemeScreen
import io.synapse.app.ui.CustomProviderScreen
import io.synapse.app.ui.CreditCenterScreen
import io.synapse.app.ui.HomeScreen
import io.synapse.app.ui.KeepAliveScreen
import io.synapse.app.ui.LogsScreen
import io.synapse.app.ui.MeScreen
import io.synapse.app.ui.ModelsScreen
import io.synapse.app.ui.ProviderDetailScreen
import io.synapse.app.ui.ProviderListScreen
import io.synapse.app.ui.ProviderCreditsScreen
import io.synapse.app.ui.ProxyScreen
import io.synapse.app.ui.ResilienceScreen
import io.synapse.app.ui.SecurityProtectionScreen
import io.synapse.app.ui.SubPage
import io.synapse.app.ui.AccountTransferScreen
import io.synapse.app.ui.TaskCenterScreen
import io.synapse.app.ui.UsageScreen
import io.synapse.app.ui.formatBytes
import io.synapse.app.ui.formatSpeed
import io.synapse.app.ui.UpdateDownload
import io.synapse.app.ui.providers.ProviderUiRegistry
import io.synapse.app.ui.theme.AiGatewayTheme
import io.synapse.app.ui.theme.AppColors
import io.synapse.app.ui.theme.LocalAppColors

class MainActivity : ComponentActivity() {

    private var viewModel: AppViewModel? = null

    /**
     * 启动时主动申请通知权限（Android 13+ 是运行时权限，不申请就默认关）。
     *
     * 为什么放 Activity 而不是 HomeScreen：额度提醒、常驻通知、实时状态
     * 都依赖这个权限，而用户不一定从首页进入。放 Activity 层才能保证一打开就申请。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            /* 结果无需处理：拒绝后提醒降级为仅震动 */
        }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (QuotaAlertNotifier.permissionGranted(this)) return
        // 已经弹过一次且用户明确拒绝（系统不再弹窗）时不再骚扰，
        // 之后想开可以从「保活」页的「去开启」进去。
        if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            getPreferences(MODE_PRIVATE).getBoolean(PREF_ASKED_NOTIFICATION, false)
        ) {
            return
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_ASKED_NOTIFICATION, true).apply()
        runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    private val gatewayToggledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 磁贴启停后通知界面刷新（磁贴无法直接触达 ViewModel）
            viewModel?.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        registerGatewayToggledReceiver()
        // 额度提醒 / 常驻通知 / 实时状态都靠通知权限：打开应用就主动申请
        requestNotificationPermissionIfNeeded()
        setContent {
            val vm: AppViewModel = viewModel(factory = appViewModelFactory())
            viewModel = vm
            val state by vm.state.collectAsStateWithLifecycle()
            AiGatewayTheme(
                dynamicColor = state.dynamicColor,
                themeMode = state.themeMode,
                appColors = AppColors(state.goldStart, state.goldEnd),
                backgroundOverride = state.backgroundOverride,
            ) {
                AppRoot(vm, state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 系统级提示（Toast / 原生对话框）需要一个前台 Activity 才能弹；
        // App 在后台时弹窗会抛 BadTokenException，所以这里登记/注销。
        SystemPrompt.attach(this)
        // 用户可能刚从 Custom Tabs 的验证页返回：若有待验证账号，自动刷新一次额度。
        // 放 onResume 而不是 onStart，是为了覆盖页面真正可见的时刻（刷新结果能直接看到）。
        viewModel?.onAppForeground()
    }

    override fun onPause() {
        SystemPrompt.detach(this)
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(gatewayToggledReceiver) }
        viewModel = null
        super.onDestroy()
    }

    private fun registerGatewayToggledReceiver() {
        val filter = IntentFilter(GatewayTileService.ACTION_GATEWAY_TOGGLED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gatewayToggledReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gatewayToggledReceiver, filter)
        }
    }

    private companion object {
        /** 是否已经弹过一次权限申请（避免反复骚扰）。 */
        const val PREF_ASKED_NOTIFICATION = "asked_notification_permission"
    }

    private fun appViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(application) as T
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Providers("供应商", Icons.Filled.Person),
    Models("模型", Icons.Filled.Storage),
    Logs("记录", Icons.Filled.History),
    Me("我的", Icons.Filled.Settings),
}

@Composable
private fun AppRoot(viewModel: AppViewModel, state: AppUiState) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    // 提示统一走系统接口（Toast / 原生对话框），不再用底部 Snackbar。
    //
    // 为什么不用 Snackbar：它会压在内容底部、盖住快捷操作按钮，长文案还会撑成好几行。
    // 而且系统 Toast 与原生对话框本来就与用户习惯的系统提示一致。
    LaunchedEffect(state.notice) {
        if (state.notice.isNotEmpty()) {
            SystemPrompt.show(state.notice)
            viewModel.consumeNotice()
        }
    }

    // 系统返回与页内返回按钮同层级：二级页回它的上级，其余回主 tab
    BackHandler(enabled = subPage != null) {
        subPage = when (val page = subPage) {
            is SubPage.ProviderCredits -> SubPage.CreditCenter
            is SubPage.CustomProviderEdit ->
                page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
            else -> null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val selectedGold = LocalAppColors.current.goldStart
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = subPage == null && tab == item,
                        onClick = {
                            subPage = null
                            tab = item
                            if (item == Tab.Models) viewModel.ensureModelsLoaded()
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = selectedGold,
                            selectedTextColor = selectedGold,
                            unselectedIconColor = Color(0xFF777777),
                            unselectedTextColor = Color(0xFF777777),
                        ),
                    )
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(bottom = insets.calculateBottomPadding())) {
            // 二级页推拉过渡：推入从右滑入，返回向右滑出
            AnimatedContent(
                targetState = subPage,
                transitionSpec = { pushPopSpec() },
                label = "subPage",
            ) { page ->
                when (page) {
                is SubPage.ProviderDetail -> ProviderDetailScreen(
                    providerId = page.providerId,
                    state = state,
                    viewModel = viewModel,
                    onBack = { subPage = null },
                    onEditCustom = { subPage = SubPage.CustomProviderEdit(it.removePrefix("custom:")) },
                )

                                is SubPage.CustomProviderEdit -> CustomProviderScreen(
                    existing = state.customProviders.firstOrNull { it.key == page.key },
                    // 标签校验交给核心层：它才知道内置标签表与其它自定义供应商的名字
                    validateLabel = { viewModel.validateCustomLabel(it, page.key) },
                    onSave = { config, apiKey ->
                        // 一次到位：落盘 + 落账号 + 后台自动拉模型。
                        // 拉模型可能要几百毫秒到几秒，不阻塞返回：先跳去详情页，
                        // 拉完会把结果写回配置并在那页看到列表。
                        viewModel.saveCustomProviderAndSync(config, apiKey) { ok, message ->
                            viewModel.notice(message)
                            if (ok) subPage = SubPage.ProviderDetail(config.providerId)
                        }
                    },
                    onDelete = {
                        viewModel.removeCustomProvider(it)
                        subPage = null
                    },
                    onBack = {
                        subPage = page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
                    },
                )

                SubPage.Usage -> UsageScreen(state, viewModel) { subPage = null }
                SubPage.TaskCenter -> TaskCenterScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenProviders = {
                        // 空态里的「去供应商页」：切回供应商 tab，并关掉这个二级页
                        tab = Tab.Providers
                        subPage = null
                    },
                    onBack = { subPage = null },
                )
                SubPage.CreditCenter -> CreditCenterScreen(
                    state = state,
                    onOpen = { subPage = SubPage.ProviderCredits(it) },
                    onBack = { subPage = null },
                )
                is SubPage.ProviderCredits -> ProviderCreditsScreen(
                    providerId = page.providerId,
                    state = state,
                    viewModel = viewModel,
                    onBack = { subPage = SubPage.CreditCenter },
                )
                SubPage.KeepAlive -> KeepAliveScreen(state, viewModel) { subPage = null }
                SubPage.Proxy -> ProxyScreen(state, viewModel) { subPage = null }
                SubPage.ApiSettings -> ApiSettingsScreen(state, viewModel) { subPage = null }
                SubPage.SecurityProtection -> SecurityProtectionScreen(state, viewModel) { subPage = null }
                SubPage.Resilience -> ResilienceScreen(state, viewModel) { subPage = null }
                SubPage.AppearanceTheme -> AppearanceThemeScreen(state, viewModel) { subPage = null }
                SubPage.AccountTransfer -> AccountTransferScreen(state, viewModel) { subPage = null }
                null -> MainTabs(
                    viewModel = viewModel,
                    state = state,
                    tab = tab,
                    onTab = { newTab ->
                        subPage = null
                        tab = newTab
                        if (newTab == Tab.Models) viewModel.ensureModelsLoaded()
                    },
                    onSubPage = { subPage = it },
                )
                }
            }
        }
    }

    // 「稍后」只在本次会话内生效：关掉后仍可用「检查更新」重新拉出更新弹窗
    var updatePromptDismissed by remember(state.updateInfo?.version) { mutableStateOf(false) }
    val download = state.updateDownload
    val updateBusy = download is UpdateDownload.Probing || download is UpdateDownload.Downloading
    // 当前安装包能否自己装 APK（旧版本含 v2.13.1 缺 REQUEST_INSTALL_PACKAGES，必然装不了）
    val canSelfInstall = viewModel.canInstallUpdate()
    state.updateInfo?.takeIf { !updatePromptDismissed }?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!updateBusy) updatePromptDismissed = true },
            title = { Text("发现新版本 ${info.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!canSelfInstall) {
                        // 旧版本（含 v2.13.1）缺少 REQUEST_INSTALL_PACKAGES，应用内安装必然失败
                        Text(
                            "已更新至 v2.13.1 版本的用户需点击项目仓库下载更新，因为应用内更新有 bug",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "更新日志",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (info.notes.isEmpty()) {
                        Text(
                            "该版本没有提供更新说明。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    // 应用内更新的进度就摆在更新日志下方，不另开页面
                    when (download) {
                        is UpdateDownload.Probing -> Text(
                            "正在测速直连与国内镜像…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        is UpdateDownload.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${download.source} · ${formatBytes(download.downloaded)} / ${formatBytes(download.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            LinearProgressIndicator(
                                progress = {
                                    if (download.total <= 0) 0f
                                    else (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Text(
                                "${formatSpeed(download.bytesPerSecond)} · ${download.threads} 线程并发",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is UpdateDownload.Ready -> Text(
                            if (canSelfInstall) {
                                "安装包已就绪（${download.source}），点「安装」完成更新。"
                            } else {
                                "本版本无法应用内安装，请点下方「到仓库下载」手动更新。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        is UpdateDownload.Failed -> Text(
                            "下载失败：${download.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        null -> Unit
                    }
                }
            },
            confirmButton = {
                when (download) {
                    is UpdateDownload.Ready -> TextButton(onClick = {
                        // 自身包装不了时系统安装器根本不会被唤起，直接给用户可行的出路
                        if (canSelfInstall) viewModel.installUpdate() else viewModel.openReleasePage(info.url)
                    }) { Text(if (canSelfInstall) "安装" else "到仓库下载") }
                    is UpdateDownload.Failed, null -> TextButton(onClick = {
                        if (info.apkUrl == null) viewModel.openReleasePage(info.url) else viewModel.downloadUpdate(info)
                    }) { Text(if (info.apkUrl == null) "前往下载" else "应用内更新") }
                    else -> TextButton(onClick = { viewModel.cancelUpdateDownload() }) { Text("取消下载") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (updateBusy) viewModel.cancelUpdateDownload()
                    updatePromptDismissed = true
                }) { Text("稍后") }
            },
        )
    }
}

/** 主 Tab 页面：切换时按索引方向做小幅滑移 + 淡入淡出。 */
@Composable
private fun MainTabs(
    viewModel: AppViewModel,
    state: AppUiState,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onSubPage: (SubPage?) -> Unit,
) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = { tabContentSpec { it.ordinal } },
        label = "tab",
    ) { page ->
        when (page) {
            Tab.Home -> HomeScreen(
                state = state,
                viewModel = viewModel,
                openTaskCenter = { onSubPage(SubPage.TaskCenter) },
            )
            Tab.Providers -> ProviderListScreen(
                state = state,
                onOpen = { onSubPage(SubPage.ProviderDetail(it)) },
                onAddCustom = { onSubPage(SubPage.CustomProviderEdit(null)) },
            )
            Tab.Models -> ModelsScreen(state, viewModel)
            Tab.Logs -> LogsScreen(state, viewModel)
            Tab.Me -> MeScreen(state, viewModel, open = { onSubPage(it) })
        }
    }
}
