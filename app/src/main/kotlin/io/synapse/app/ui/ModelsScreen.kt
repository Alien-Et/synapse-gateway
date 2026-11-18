package io.synapse.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.synapse.core.provider.RoutedModel

/** 模型页：汇总所有供应商的模型（id 带 `provider/` 前缀），按供应商分组展示与流式更新。 */
@Composable
fun ModelsScreen(state: AppUiState, viewModel: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 按 routePrefix 分组：区域型供应商（如 WorkBuddy 国内与国外）各成一个分组
    val grouped = remember(state.models) { state.models.groupBy { it.routePrefix } }
    // 折叠状态：记「被折叠」的分组（默认全展开）。
    //
    // 用不可变 Set + rememberSaveable，而不是 SnapshotStateList：
    // 后者在保存时机（onPause / 进程回收）被 SaveableStateRegistry 读取时，
    // 若正好与 LazyColumn 的组合读快照交叠，会抛「Reading a state that was created
    // after the snapshot was taken」——折叠的分组越多越容易撞上，表现为点折叠直接闪退。
    // 换成纯值 Set 后 save/restore 不碰快照，问题消失；读取仍在组合作用域，
    // 折叠变化会正常触发列表重建。
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // 隐藏被屏蔽状态来自 AppUiState，并由 ViewModel 写入加密本地设置，跨进程重启持久保留。
    // 测试失败原文可能很长，行内只展示截断提示，全文点开弹窗查看
    var failureDetail by remember { mutableStateOf<String?>(null) }

    // 刷新按钮旋转动效：仅在加载中才创建无限过渡，停止后不空转每帧插值
    val spinAngle = if (state.modelsLoading) {
        val transition = rememberInfiniteTransition(label = "refreshSpin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spinAngle",
        )
        angle
    } else {
        0f
    }
    val iconRotation by animateFloatAsState(
        targetValue = spinAngle,
        label = "refreshIconRotation",
    )

    // 动态副标题：流式加载中展示进度
    val totalProviders = state.modelLoadStates.size
    val doneProviders = state.modelLoadStates.values.count { it !is ModelLoadState.Loading }
    val subtitleText = if (state.modelsLoading) {
        if (totalProviders > 0) "共 ${state.models.size} 个 · 载入中 ($doneProviders/$totalProviders)" else "共 ${state.models.size} 个 · 更新中..."
    } else {
        "共 ${state.models.size} 个"
    }

    // 还在加载中的供应商列表
    val pendingProviders = state.modelLoadStates.filter { it.value is ModelLoadState.Loading }.keys

    // 本页自带骨架（不走 PageScaffold）：模型量大，LazyColumn 懒加载避免整页一次性组合；
    // PageScaffold 的 verticalScroll 与 LazyColumn 不兼容（无限高度约束会崩）。
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(
            title = "模型",
            subtitle = subtitleText,
            actions = {
                // 一键测速：并发上限 4，避免自己把自己限流（见 AppViewModel.testAllModels）
                Icon(
                    Icons.Rounded.Speed,
                    contentDescription = "一键测速",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .bouncyClick { viewModel.testAllModels() }
                        .padding(6.dp),
                )
                // 一键屏蔽不可用：只在真有失败结果时出现，避免一个永远点不动的按钮
                val failedCount = state.modelTestLatencies.values.count { it is ModelTestStatus.Failed }
                if (failedCount > 0) {
                    Icon(
                        Icons.Rounded.Block,
                        contentDescription = "屏蔽 $failedCount 个不可用模型",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .bouncyClick { viewModel.blockFailedModels() }
                            .padding(6.dp),
                    )
                }
                // 一键隐藏/显示被屏蔽模型：只在真有屏蔽项时出现（没有屏蔽项时这个按钮没有意义）
                val blockedTotal = state.models.count { state.modelBlocklist.blocks(it.fullId) }
                if (blockedTotal > 0) {
                    Icon(
                        imageVector = if (state.hideBlockedModels) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (state.hideBlockedModels) {
                            "显示被屏蔽的模型（$blockedTotal 个）"
                        } else {
                            "隐藏被屏蔽的模型（$blockedTotal 个）"
                        },
                        tint = if (state.hideBlockedModels) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .bouncyClick { viewModel.setHideBlockedModels(!state.hideBlockedModels) }
                            .padding(6.dp),
                    )
                }
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .bouncyClick { viewModel.refreshModels(forceRefresh = true) }
                        .padding(6.dp)
                        .graphicsLayer { rotationZ = iconRotation },
                )
            },
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = "测速结果是最近一次测试记录（重启后保留），不是实时可用性；失败和未测均计入待测。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // 顶部细条流式进度指示
            AnimatedVisibility(
                visible = state.modelsLoading,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                )
            }

            if (state.modelsError.isNotEmpty()) {
                SectionCard(enterIndex = 0, modifier = Modifier.padding(top = 14.dp)) {
                    Text(
                        text = "拉取模型目录时有报错：${state.modelsError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 本轮刷新带来的目录变更（新增 / 移除）：只在一轮刷完之后出现，下次刷新被新结果覆盖。
            // 用户最需要知道的是「我配的模型怎么不见了」，所以移除要单独点出来。
            if (!state.modelsLoading && !state.modelCatalogChanges.isEmpty) {
                SectionCard(enterIndex = 0, modifier = Modifier.padding(top = 14.dp)) {
                    val changes = state.modelCatalogChanges
                    Text(
                        text = "云端目录有变化",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    changes.added.forEach { (providerId, ids) ->
                        Text(
                            text = "新增 ${ids.size} 个：" + ids.joinToString("、") { it.substringAfter('/') },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    changes.removed.forEach { (providerId, ids) ->
                        Text(
                            text = "移除 ${ids.size} 个（上游已下架）：" +
                                ids.joinToString("、") { it.substringAfter('/') },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (grouped.isEmpty()) {
                if (state.modelsLoading) {
                    // 首屏流式载入中骨架屏
                    val loadingList = pendingProviders.toList().ifEmpty { listOf("loading_1", "loading_2") }
                    items(loadingList, key = { "skeleton-$it" }) { id ->
                        val name = state.providerOf(id)?.displayName ?: "正在拉取模型目录"
                        ModelSkeletonCard(providerName = name)
                    }
                } else {
                    item(key = "empty") {
                        SectionCard(enterIndex = 1) {
                            EmptyHint(
                                title = "还没有模型",
                                message = "无可用账号的供应商不会返回动态模型；可以先刷新试试。",
                                actionLabel = "重新拉取",
                                onAction = { viewModel.refreshModels(forceRefresh = true) },
                            )
                        }
                    }
                }
            } else {
                // 已加载的模型按组展示
                for ((routePrefix, models) in grouped) {
                    val title = models.firstOrNull()?.providerName ?: routePrefix
                    // 这一组本轮没拉到上游、用的是上次成功的清单：明确标注，不能让旧清单冒充实时结果
                    val cachedGroup = models.isNotEmpty() && models.all { it.cached }
                    item(key = "header-$routePrefix") {
                        // 标题与统计分两行；测速成功数、待测数、屏蔽数三个数相加正好等于总数。
                        val groupBlocked = models.count { state.modelBlocklist.blocks(it.fullId) }
                        val groupUsable = models.count {
                            !state.modelBlocklist.blocks(it.fullId) &&
                                state.modelTestLatencies[it.fullId] is ModelTestStatus.Success
                        }
                        val groupUntested = models.size - groupBlocked - groupUsable
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    collapsed = if (routePrefix in collapsed) {
                                        collapsed - routePrefix
                                    } else {
                                        collapsed + routePrefix
                                    }
                                }
                                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (routePrefix in collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                    contentDescription = if (routePrefix in collapsed) "展开" else "折叠",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                SectionLabel(title)
                                if (cachedGroup) {
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                                            .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Box(
                                            Modifier.breathingDot(MaterialTheme.colorScheme.error, size = 6.dp),
                                        )
                                        Text(
                                            text = "缓存",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                } else {
                                    Box(Modifier.breathingDot(MaterialTheme.colorScheme.tertiary, size = 6.dp))
                                }
                                Box(Modifier.weight(1f))
                            }
                            // 三个计数口径统一：测速成功 + 待测 + 屏蔽 = 该组模型总数，不存在分子分母错配。
                            GroupStats(
                                tested = groupUsable,
                                untested = groupUntested,
                                total = models.size,
                                blocked = groupBlocked,
                            )
                        }
                    }
                    // 折叠的分组跳过其模型行：只渲染分组头，模型行即时收起
                    if (routePrefix in collapsed) continue
                    // 一键隐藏被屏蔽：只影响列表展示，屏蔽状态本身不动
                    val visibleModels = if (state.hideBlockedModels) {
                        models.filterNot { state.modelBlocklist.blocks(it.fullId) }
                    } else {
                        models
                    }
                    if (visibleModels.isEmpty()) {
                        item(key = "hidden-all-$routePrefix") {
                            Text(
                                text = "该分组的模型全部被屏蔽，点右上角眼睛图标可恢复显示",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(visibleModels, key = { it.fullId }) { model ->
                        val testStatus = state.modelTestLatencies[model.fullId]
                        ModelRow(
                            model = model,
                            testStatus = testStatus,
                            blocked = state.modelBlocklist.blocks(model.fullId),
                            onToggleBlocked = { viewModel.toggleModelBlocked(model.fullId) },
                            onCopy = {
                                clipboard.setText(AnnotatedString(model.fullId))
                                SystemPrompt.toast("已复制模型名称：${model.fullId}")
                            },
                            onTest = { viewModel.testModel(model) },
                            onShowFailure = { failureDetail = it },
                        )
                    }
                }

                // 若已有部分模型展示，而剩余供应商仍在加载中，在下方继续展示该供应商的骨架占位
                if (state.modelsLoading && pendingProviders.isNotEmpty()) {
                    items(pendingProviders.toList(), key = { "skeleton-$it" }) { id ->
                        val name = state.providerOf(id)?.displayName ?: id
                        ModelSkeletonCard(providerName = name)
                    }
                }
            }
        }

        failureDetail?.let { reason ->
            // 纯提示（无输入、无选择）→ 系统原生对话框
            LaunchedEffect(reason) {
                SystemPrompt.alert("测试失败原因", reason) { failureDetail = null }
            }
        }
    }
}

/**
 * 分组统计第二行：`3/10 测速成功 · 待测 5 · 屏蔽 2`。
 *
 * 三个计数口径统一：测速成功数 + 待测数 + 屏蔽数 = 该组模型总数，不存在分子分母错配。
 * 待测数用减法得出（总数 − 测速成功 − 屏蔽），不替用户下「未测即可用」的结论；
 * 「测速成功」也刻意不写成「可用」，避免把未测模型误读成可用。
 */
@Composable
private fun GroupStats(tested: Int, untested: Int, total: Int, blocked: Int) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                append("$tested/$total 测速成功")
            }
            append(" · ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                append("待测 $untested")
            }
            append(" · ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append("屏蔽 $blocked")
            }
        },
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 单个模型行：模型信息（点击复制）+ 延迟状态 + 屏蔽/测试按钮，失败原因单独占一行。
 *
 * 失败原因**必须显示在模型下方**、占整行宽度，不能塞进上面那行 Row：
 * 它是个长句子（上游原文经常几十字），塞进 Row 后会把左侧信息列挤到极窄宽度，
 * 长模型名/副标题随之逐字换行 —— 整行变成一条竖着的字母柱，页面直接没法看。
 */
@Composable
private fun ModelRow(
    model: RoutedModel,
    testStatus: ModelTestStatus?,
    blocked: Boolean,
    onToggleBlocked: () -> Unit,
    onCopy: () -> Unit,
    onTest: () -> Unit,
    onShowFailure: (String) -> Unit,
) {
    Column(
        // 被屏蔽的模型整行降透明度：一眼能看出它不对外暴露、也不参与路由；
        // 只降视觉不拦截事件，屏蔽按钮仍然可点（否则没法取消屏蔽）。
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (blocked) 0.55f else 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 左侧模型名与信息：点击复制模型名称
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onCopy)
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 列表里只显示模型本名，不显示「供应商/」前缀——那是给下游客户端
                    // 路由用的标签（`讯飞/xxx模型`），自己看列表时前缀纯属噪音，
                    // 且分组标题已经标明供应商。复制时仍给完整 fullId，路由不受影响。
                    val displayName = model.model.name.ifBlank { model.model.id }
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        // fill = false：名字短时紧贴复制图标，不把后面的「已屏蔽」标签推到行尾。
                        // maxLines + Ellipsis 必须有：只给 weight 不给 maxLines 时，
                        // 宽度不够的 Text 会用换行去适应，长名字被压到一两个字符宽就竖排。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(13.dp),
                    )
                    if (blocked) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "已屏蔽",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                // 副标题：模型本名已上移为标题，这里只补附加信息（上下文窗口等）。
                // 同样必须单行截断——上游给的值长度不可控，不截断会逐字竖排。
                // 没有附加信息时整行不渲染，保持列表紧凑（「简化显示」）。
                if (model.model.contextWindow > 0) {
                    Text(
                        text = "上下文 ${model.model.contextWindow}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 能力标记：只有上游/供应商明确给了才显示，缺就不渲染（不猜）。
                // 各家写 extra 的口径不统一（CodeBuddy 写 tools/vision="true"，Loomy 写
                // tool_call/attachment="1"），这里把同义 key 与两种真假写法都认下，
                // 否则能力存在却显示不出来。
                val extra = model.model.extra
                fun flag(vararg keys: String): Boolean = keys.any { key ->
                    val v = extra[key]?.trim()?.lowercase()
                    v == "true" || v == "1"
                }
                val tags = buildList {
                    if (flag("reasoning")) add("思考")
                    if (flag("search", "web_search", "online")) add("联网")
                    if (flag("vision", "image", "attachment")) add("识图")
                    if (flag("tools", "tool_call", "toolcall")) add("工具")
                    extra["maxOutput"]?.takeIf { it.isNotBlank() }?.let { add("输出上限 $it") }
                    extra["vendor"]?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                if (tags.isNotEmpty()) {
                    Text(
                        text = tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 倍率与限时折扣：统一字段名 rate / rateDiscount / rateDiscountTag / rateDiscountUntil，
                // 腾讯（credits + tags badge）、字节（本地参考表）、商汤（billing_*）都往这里填。
                val rateLabel = extra["rate"]?.takeIf { it.isNotBlank() }
                val discount = extra["rateDiscount"]?.takeIf { it.isNotBlank() }
                if (rateLabel != null || discount != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (rateLabel != null) {
                            Text(
                                text = "倍率 $rateLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (discount != null) {
                            // 文案优先级：上游给的 tag（如「会员5折」「限时免费」）> 默认「限时」。
                            //
                            // ⚠️ tag 本身可能已经带日期（商汤的 `billing_discounts[].name`
                            // 就叫「限免至10月31日」），这时再拼 rateDiscountUntil 会变成
                            // 「限免至10月31日 0×（至 10月31日 23:59）」——同一日期出现两遍。
                            // 所以 tag 里已经含日期就只用 tag，不再补时间；否则用「限时 X×（至 …）」。
                            val tag = extra["rateDiscountTag"]?.takeIf { it.isNotBlank() }
                            val until = extra["rateDiscountUntil"]?.takeIf { it.isNotBlank() }
                            val tagHasDate = tag != null && DATE_IN_TEXT.containsMatchIn(tag)
                            Text(
                                text = when {
                                    // tag 已含日期：直接「限免至10月31日 0×」，最紧凑
                                    tagHasDate -> "$tag $discount"
                                    // 有独立截止时间：补到括号里
                                    until != null -> "${tag ?: "限时"} $discount（至 $until）"
                                    // 只有 tag
                                    tag != null -> "$tag $discount"
                                    // 什么都没有
                                    else -> "限时 $discount"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // 谷歌的模型级剩余额度（quotaInfo.remainingFraction），有才显示
                val quotaRemaining = extra["quotaRemaining"]?.takeIf { it.isNotBlank() }
                if (quotaRemaining != null) {
                    val resetAt = extra["quotaResetAt"]?.takeIf { it.isNotBlank() }
                    Text(
                        text = if (resetAt != null) "额度 $quotaRemaining（$resetAt 重置）" else "额度 $quotaRemaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 延迟：<5000ms 绿色，>=5000ms 橙色。失败原因不在这里显示（见行下方整行提示），
            // 否则一句上游原文会把左侧信息列挤成一条竖线。
            when (testStatus) {
                is ModelTestStatus.Testing -> {
                    Text(
                        text = "测试中...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                is ModelTestStatus.Success -> {
                    val isFast = testStatus.latencyMs < 5000
                    // 上下两层：上层延迟数字（一眼可比），下层测速时间（判断这结果有多旧）。
                    // 时间来自持久化的 testedAtMs，跨重启仍有值；老数据为 0 时不显示下层。
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = "${testStatus.latencyMs}ms",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isFast) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                            maxLines = 1,
                        )
                        val ago = relativeTestedAgo(testStatus.testedAtMs)
                        if (ago.isNotEmpty()) {
                            Text(
                                text = ago,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                }
                is ModelTestStatus.Failed, null -> Unit
            }

            // 屏蔽 / 取消屏蔽：屏蔽后该模型不对外暴露、不参与路由，也不进同名匹配快照。
            // 放在「测试」左侧，方便测速失败后就地屏蔽；图标态区分当前状态。
            Box(
                modifier = Modifier
                    .bouncyClick(onClick = onToggleBlocked)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (blocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Block,
                    contentDescription = if (blocked) "取消屏蔽" else "屏蔽",
                    tint = if (blocked) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            // 右侧 Q 弹测试按钮
            Box(
                modifier = Modifier
                    .bouncyClick(enabled = testStatus !is ModelTestStatus.Testing, onClick = onTest)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "测试",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // 失败原因：整行宽度、最多两行、超出省略，点开弹窗看完整原文。
        (testStatus as? ModelTestStatus.Failed)?.let { failed ->
            Text(
                text = "最近一次测速失败：${failed.reason}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onShowFailure(failed.reason) }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }
    }
}

/** 骨架屏占位卡片：配合微光动效呈现正在流式拉取的状态。 */
@Composable
private fun ModelSkeletonCard(
    providerName: String,
    modifier: Modifier = Modifier,
) {
    // 整卡共用一个动画进度：每个占位各建一套无限过渡会让加载期间成倍触发重组
    val shimmerProgress = rememberShimmerProgress()
    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = providerName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "加载中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        repeat(2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(16.dp)
                            .shimmer(shimmerProgress, RoundedCornerShape(4.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(12.dp)
                            .shimmer(shimmerProgress, RoundedCornerShape(3.dp)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 26.dp)
                        .shimmer(shimmerProgress, MaterialTheme.shapes.small),
                )
            }
        }
    }
}

/**
 * 判断一段文案里是否已经包含日期（如「限免至10月31日」「至 2026-10-31」）。
 *
 * 用于避免重复展示：商汤的折扣 tag 本身就带日期，若再拼一次 `rateDiscountUntil`
 * 会变成「限免至10月31日 0×（至 10月31日 23:59）」，同一日期出现两遍。
 */
private val DATE_IN_TEXT = Regex("""\d{1,4}\s*[月/-]\s*\d{1,2}\s*[日号]?|\d{4}-\d{2}-\d{2}""")

/**
 * 测速时间的相对文案（`刚刚` / `12分钟前` / `3小时前` / `2天前`）。
 *
 * 传 0 或未来时间返回空串——老数据没有测试时间，不显示比瞎猜「刚刚」诚实。
 * 只是展示层换算，不参与任何可用性判断。
 */
private fun relativeTestedAgo(testedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (testedAtMs <= 0L) return ""
    val delta = nowMs - testedAtMs
    if (delta < 0L) return "刚刚"
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L}分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L}小时前"
        else -> "${delta / 86_400_000L}天前"
    }
}
