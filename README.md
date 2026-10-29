# AI 聚合网关

![License](https://img.shields.io/badge/license-MIT%20%2B%20附加声明-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-enabled-lightblue)
![API](https://img.shields.io/badge/API-OpenAI%20Compatible-orange)
![MinSdk](https://img.shields.io/badge/minSdk-26-blue)
![TargetSdk](https://img.shields.io/badge/targetSdk-36-blue)

Android 上运行的本地 AI 模型聚合网关：把多家私有 AI 服务账号统一翻译成 OpenAI 兼容接口，暴露在 `http://127.0.0.1:8790/v1`。

任何支持 OpenAI 协议的客户端（Cline、Cherry Studio、Continue、curl 脚本……）都能直接接上手机里的所有账号额度。

`Kotlin` · `Jetpack Compose` · `NanoHTTPD` · `Gradle` · `minSdk 26 / targetSdk 36` · `MIT + 附加使用声明`

> 改动代码前请先读 **[ARCHITECTURE.md](ARCHITECTURE.md)**——完整的架构地图、模块职责、协议陷阱清单与改动守则。

---

## 目录

- [特性](#特性)
- [快速开始](#快速开始)
  - [安装](#安装)
  - [接入账号](#接入账号)
  - [客户端配置](#客户端配置)
  - [常见客户端配置示例](#常见客户端配置示例)
  - [获取模型列表](#获取模型列表)
- [架构总览](#架构总览)
  - [路由判定流程](#路由判定流程)
  - [Failover 候选路由](#failover-候选路由)
- [模块结构](#模块结构)
- [数据存储](#数据存储)
- [构建与发版](#构建与发版)
- [接口与鉴权](#接口与鉴权)
  - [接口一览](#接口一览)
  - [鉴权](#鉴权)
  - [对话补全示例](#对话补全示例)
  - [模型列表示例](#模型列表示例)
  - [额度查询示例](#额度查询示例)
  - [健康检查](#健康检查)
- [供应商](#供应商)
  - [内置供应商](#内置供应商)
  - [各供应商说明](#各供应商说明)
  - [添加自定义供应商](#添加自定义供应商)
- [路由逻辑](#路由逻辑)
  - [前缀一律用中文](#前缀一律用中文)
  - [实际使用示例](#实际使用示例)
  - [按模型归属，不猜](#按模型归属不猜)
  - [模型匹配精度](#模型匹配精度)
  - [无前缀时自动判定](#无前缀时自动判定)
  - [中文 id 的注意事项](#中文-id-的注意事项)
  - [`/v1/models` 里看到的 id](#v1models-里看到的-id)
- [贡献](#贡献)
- [使用声明](#使用声明)
- [许可](#许可)

---

## 特性

- **OpenAI 兼容网关**：`/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图。客户端只需填一个 Base URL，所有供应商的模型都能用。
- **五家内置供应商**：腾讯（CodeBuddy）、字节（Trae）、谷歌（Antigravity）、讯飞（Loomy）、商汤（Raccoon），以及任意自定义 OpenAI 兼容中转站。
- **同款模型 Failover**：首选供应商失败或无可用账号时，自动切换到拥有同款模型的其他供应商。支持手动配置优先级表，也支持启发式自动搜索。
- **智能路由**：按模型真实归属路由——模型属于哪家供应商、在哪个区域站点上线，就发给谁。不再按名称猜供应商。
- **账号管理**：同一供应商多账号按余额选号、失败自动换号、额度查询与额度包明细、跨供应商容灾、批量签到与任务中心。
- **模型开放名单**：只把勾选的模型暴露给 `/v1/models`，其余模型对客户端隐藏，防止客户端误用。
- **用量与日志**：调用记录（请求/转发/返回全链路）、今日与累计用量趋势、运行日志、按前缀统计存储占用与保留期自动清理。
- **网络代理**：HTTP / SOCKS5，支持按供应商与按地址排除；国内上游始终直连。
- **安全防护**：system 提示词零宽字符脱敏 + 账号级最小间隔限速（带抖动）。
- **后台保活**：前台服务 + WakeLock / WifiLock + 控制中心磁贴，锁屏仍可服务。

---

## 快速开始

### 安装

1. 到 [Releases](https://github.com/Alien-Et/Android-AI-gateway/releases) 下载最新的 `ai-gateway-<版本>-release.apk` 安装。
2. 打开 App，首页点「启动网关服务」；首页显示「运行中」即表示端口已在监听。

### 接入账号

3. 到「供应商」页登录要用的账号（腾讯 / 字节 / 谷歌 / 讯飞 / 商汤），或添加自定义 OpenAI 兼容中转站。
   - 腾讯、字节、谷歌、商汤支持网页登录（浏览器打开授权页，回调自动回传凭证）。
   - 讯飞 Loomy 通过「导入」粘贴 JSON 凭证。
   - 自定义供应商直接填 baseUrl + API Key。

### 客户端配置

4. 客户端里填接入信息：
   - **Base URL**：手机本机填 `http://127.0.0.1:8790/v1`；局域网内其它设备填首页复制的局域网地址（需先在「接入设置」里打开「同时监听局域网」）。
   - **模型**：可填 `供应商/模型`（如 `腾讯/glm-5.2`、`字节/qwen-max`），也可以直接填模型名，网关会自动查到它属于谁。区域前缀可选（`腾讯国内/`、`字节国外/`）用来强制指定站点。
   - **API Key**：默认开启「无 Key 调用」，随意填；关掉后需与「接入设置」里的 Key 一致。

### 常见客户端配置示例

**Cline**（VS Code 插件）：
```
Base URL: http://127.0.0.1:8790/v1
API Key:  （随意填，或留空）
Model:    腾讯/glm-5.2  或  字节/qwen-max  或  直接写 glm-5.2
```

**Cherry Studio**：
```
Provider: 自定义
Base URL:  http://127.0.0.1:8790/v1
API Key:   （随意填）
Model:     腾讯国内/glm-5.2  或  腾讯国外/gpt-5.6-sol
```

**Continue**：
```json
{
  "models": [{
    "title": "腾讯 glm-5.2",
    "provider": "openai-compatible",
    "config": {
      "baseURL": "http://127.0.0.1:8790/v1",
      "apiKey": "任意值",
      "model": "腾讯/glm-5.2"
    }
  }]
}
```

**curl**：
```sh
curl http://127.0.0.1:8790/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer 任意值" \
  -d '{"model":"腾讯国内/glm-5.2","stream":true,"messages":[{"role":"user","content":"你好"}]}'
```

### 获取模型列表

想知道当前有哪些模型可用，直接查 `/v1/models`：

```sh
curl http://127.0.0.1:8790/v1/models
```

返回的 id 可以直接复制到客户端里用，前缀已经包含了供应商和区域信息。

---

## 架构总览

一次请求的生命周期：

```
客户端 (Cline / Cherry Studio / curl)
   │  POST /v1/chat/completions  {model: "...", ...}
   ▼
GatewayHttpServer   ① 鉴权   ② 解析 model → 路由
   ▼
GatewayEngine       ③ 解析候选路由（首选 + Failover 候选）
                    ④ 选号 → 预刷新凭证 → 发起上游请求
                    ⑤ 失败则换号，或跨供应商 Failover
   ▼
Provider 实现       上游私有协议 ⇄ OpenAI 协议（双向转换封死在 Provider 内）
   ▼
上游 API
```

### 路由判定流程

```
resolveRoute(model)
  │
  ├─ ① 剥离 "models/" 前缀（Google/Gemini SDK 常见写法）
  │
  ├─ ② 有 "/" 吗？
  │     ├─ 有 → 解析前缀（中文品牌名）→ 匹配供应商
  │     │         ├─ 腾讯国内/glm-5.2  → codebuddy, region=cn
  │     │         ├─ 腾讯国外/glm-5.2  → codebuddy, region=global
  │     │         ├─ 腾讯/glm-5.2     → codebuddy, region=null（自动）
  │     │         └─ 字节/qwen-max     → trae, region=null（自动）
  │     │
  │     └─ 无 → 进入③
  │
  ├─ ③ resolveByModelOwnership(model)
  │     查各家模型目录，谁有这个模型就发给谁
  │     区域型供应商按区域分别查目录
  │     匹配精度：完全相同 → 归一化 → 去掉修饰符
  │
  ├─ ④ inferProviderByModel(model)  名称特征兜底
  │     gemini/claude → 谷歌, doubao/seed → 字节, spark → 讯飞, raccoon → 商汤
  │     deepseek/kimi/glm/qwen → 不猜，返回 null
  │
  ├─ ⑤ 单供应商接管
  │     只配了一家 → 所有请求都交给它
  │
  ├─ ⑥ 兜底
  │     defaultProvider → 第一个有账号的 → 第一个注册的
  │
  └─ ⑦ 返回 Route(providerId, model, region)
```

### Failover 候选路由

当首选路由失败时（凭证失效、额度耗尽、上游 5xx），`resolveCandidateRoutes` 会生成候选列表：

1. **显式优先级表**（用户在设置里配置的 `failoverSettings.routes`）
2. **启发式同款搜索**（遍历其他供应商的模型目录，找同名/同款模型）
3. 开关关闭时只返回首选路由

```kotlin
// 示例：配置 deepseek-v3 的 Failover 优先级
deepseek-v3 → [
  Route(trae, deepseek-v3),       // 首选：字节
  Route(codebuddy, deepseek-v3),  // 备选：腾讯
]
```

**核心不变量**：Provider 的边界是「OpenAI 进、OpenAI 出」。上游私有协议的双向转换全部封死在 Provider 内部，网关层对供应商差异零感知。任何把上游协议细节泄漏到网关层的改动，都会破坏这套可维护性根基。

---

## 模块结构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | 纯 JVM，无 Android 依赖 | 网关引擎、HTTP 服务、账号池、路由与 Failover、各供应商协议实现、存储抽象。可独立单测 |
| `:cli` | 纯 JVM 命令行 | 在本机直接起网关做端到端联调，或不打开 App 也能导入账号 / 签到 / 查用量与日志；可执行名 `aigw` |
| `:app` | Android 应用 | Compose UI、前台服务与保活、加密存储实现、控制中心磁贴 |

### 供应商协议实现

位于 `core/src/main/kotlin/dev/aigw/core/provider/`，每家的私有协议细节都封装在各自子包内，互不干扰：

| 子包 | 供应商 | 文件数 | 说明 |
|---|---|---|---|
| `trae/` | 字节 | 16 | SOLO 私有通道，协议转换最重 |
| `codebuddy/` | 腾讯 | 2 | 国内/国外双站点 |
| `antigravity/` | 谷歌 | 2 | Gemini 风格协议 |
| `loomy/` | 讯飞 | 13 | 鉴权失败为 HTTP 200 + 业务码 |
| `raccoon/` | 商汤 | 1 | OpenAI 结构被包在 `data` 里 |
| `custom/` | 自定义 | 1 | 任意 OpenAI 兼容中转站 |

每家 Provider 都实现 `Provider` 接口，负责：
- `listModels()`：拉取模型目录
- `resolveModel()`：把客户端传来的模型名归一化为上游真实名
- `openChat()`：发起对话请求
- `creditInfo()`：查询额度
- `performAction()`：供应商专属动作（签到等）
- `refreshAccount()`：凭证刷新

---

## 数据存储

账号凭证与状态**按账号独立存储**（v2.4.0 起）：

```
account/<供应商>/<账号>            # 账号凭证（ProviderAccount.toJson）
pool/state/<供应商>/<账号>.json    # 账号状态（额度 / 启用 / 禁用 / 原因）
settings/...                      # 网关、代理、安全、供应商与自定义供应商等设置
logs/calls/<id>                   # 调用记录，每条一个键
logs/requests.json                # 运行日志（环形缓冲）
usage/...                         # 用量统计
```

- **一个账号一个状态文件**：改某个账号（刷新额度、启用/停用）只重写它自己的键，不再整包重写该供应商的全部账号。
- **旧数据自动迁移**：v2.4.0 之前的状态是「一个供应商一个文件」（`pool/state/<供应商>.json`，内含该供应商全部账号）。新版首次读取时惰性迁移——迁一个账号即从旧文件摘除，旧文件清空后删除；迁移可重复执行，不会回退或丢状态，无需手工处理。
- Android 端凭证经 Android Keystore 加密后存入应用私有目录；命令行端（`aigw`）以明文存于 `--data` 目录（默认 `~/.aigw`）。

---

## 构建与发版

### 本地构建

本地构建产出 debug 包，包名与 release 一致（`dev.aigw.app`），可原地覆盖升级、配置不丢：

```sh
./gradlew :app:assembleDebug --console=plain   # 出 debug 包
./gradlew :core:test --console=plain           # 单元测试（:core 是唯一有测试的模块）
./gradlew :cli:installDist --console=plain     # 命令行工具，产出 cli/build/install/aigw/bin/aigw
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`（包名 `dev.aigw.app`，与 release 相同）。
- 版本号硬编码在 `app/build.gradle.kts`（`versionCode` / `versionName`）；改后必须重新构建。
- 本地**不产 release 包**：release 仅由云端 GitHub Actions 手动触发构建并发版，日常 push 不产生任何云端构建。
- 注意：debug 包用 AGP 默认 debug key 签名，与云端 release keystore 签名不同；在已装 release 版的设备上覆盖安装需 root（`pm install -r`）或卸载重装。

### GitHub Actions 工作流

仓库里有 2 个工作流，都是手动触发（在 Actions 页面点按钮）：

| 工作流 | 作用 | 产物 |
|---|---|---|
| `build-release.yml` | 跑测试 + 打 release 包，不创建 Release | artifact 上传，保留 14 天 |
| `android-release.yml` | 跑测试 + 打 release 包 + 自动创建 Release + 打 tag | GitHub Release + APK 附件 |

两个工作流都需要在仓库 Settings → Secrets and variables → Actions 里配置 4 个 secrets：
- `KEYSTORE_BASE64`：release keystore 的 base64 编码
- `KEYSTORE_PASSWORD`：keystore 密码
- `KEY_ALIAS`：key alias
- `KEY_PASSWORD`：key 密码

### 命令行联调

`:cli` 模块无需 Android 设备，可在本机直接起网关做端到端联调：

```sh
./gradlew :cli:run --args="serve" --console=plain       # 起网关（默认端口 8790，数据目录 ~/.aigw）
./gradlew :cli:run --args="providers" --console=plain   # 列出供应商
```

`aigw` 子命令一览：

| 命令 | 作用 |
|---|---|
| `serve` | 启动网关服务（默认端口 8790） |
| `providers` | 列出已注册的供应商 |
| `accounts` | 查看各供应商账号 |
| `credits` | 查询额度 |
| `checkin` | 执行签到 |
| `login` | 登录账号 |
| `browser-login` | 浏览器登录 |
| `sms` | 短信登录 |
| `models` | 列出模型 |
| `usage` | 查看用量统计 |
| `logs` | 查看运行日志 |

账号与设置存于 `--data` 目录（默认 `~/.aigw`，也可用 `AIGW_DATA` 环境变量改写）。

---

## 接口与鉴权

### 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/chat/completions` | 对话补全，支持流式 SSE / 非流式 / 多模态 |
| `GET` | `/v1/models` | 模型列表（并发拉取各供应商后聚合返回；按模型开放名单过滤） |
| `GET` | `/v1/credits` | 额度查询（别名 `/credits`；本机免 Key） |
| `GET` | `/healthz` | 健康检查，返回纯文本 `ok`（不带鉴权） |
| `GET` | `/authorize` | Trae 网页登录回调入口（Antigravity / 小浣熊各有独立的本地回调端口） |

### 鉴权

支持三种方式，任一即可：
- `Authorization: Bearer <key>`
- `x-goog-api-key: <key>`
- 查询参数 `?key=<key>`

可在设置中开启「无 Key 调用」（默认开启）。服务同时处理 `OPTIONS` 预检并返回 CORS 头，供浏览器端客户端使用。

默认只绑定 `127.0.0.1`；打开「接入设置」里的「同时监听局域网」后才会监听全部网卡。注意此时若仍开着「无 Key 调用」，同网段的任何设备都能直接调用网关。

### 对话补全示例

**流式 SSE**：
```sh
curl http://127.0.0.1:8790/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer 任意值" \
  -d '{"model":"腾讯国内/glm-5.2","stream":true,"messages":[{"role":"user","content":"你好"}]}'
```

**非流式**：
```sh
curl http://127.0.0.1:8790/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer 任意值" \
  -d '{"model":"字节/qwen-max","stream":false,"messages":[{"role":"user","content":"你好"}]}'
```

**多模态（识图）**：
```json
{
  "model": "谷歌/gemini-3.8-flash",
  "stream": true,
  "messages": [{
    "role": "user",
    "content": [
      {"type": "text", "text": "这张图里有什么？"},
      {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
    ]
  }]
}
```

**不写前缀（自动路由）**：
```json
{"model": "glm-5.2", "stream": true, "messages": [{"role": "user", "content": "你好"}]}
```

### 模型列表示例

```sh
curl http://127.0.0.1:8790/v1/models
```

返回格式：
```json
{
  "object": "list",
  "data": [
    {
      "id": "腾讯国内/glm-5.2",
      "object": "model",
      "created": 1753600000,
      "owned_by": "codebuddy"
    },
    {
      "id": "字节国内/doubao-pro",
      "object": "model",
      "created": 1753600000,
      "owned_by": "trae"
    }
  ]
}
```

### 额度查询示例

```sh
curl http://127.0.0.1:8790/v1/credits
```

返回各供应商各账号的额度信息，包括余额、额度包明细等。本机访问免 Key。

### 健康检查

```sh
curl http://127.0.0.1:8790/healthz
```

返回纯文本 `ok`，不带鉴权，可用于监控探活。

---

## 供应商

### 内置供应商

| id | 名称 | 上游 | 备注 |
|---|---|---|---|
| `codebuddy` | 腾讯 | 国内 `copilot.tencent.com` / 国外 `workbuddy.ai` | 上游拒绝非流式，强制 `stream:true` |
| `trae` | 字节 | SOLO 私有通道（国内 `trae.cn` / 国外 `trae.ai`） | 协议转换最重 |
| `antigravity` | 谷歌 | `cloudcode-pa.googleapis.com` | Gemini 风格协议；凭证策略有铁律，见 ARCHITECTURE.md 第 5 节 |
| `loomy` | 讯飞 | `xfinfr.com` | 鉴权失败为 HTTP 200 + 业务码，而非 4xx |
| `raccoon` | 商汤 | `xiaohuanxiong.com` | OpenAI 结构被包在 `data` 里；字段用标准名（v2 LiteLLM 层不认 `max_new_tokens`） |
| `custom:<key>` | 自定义 | 用户填写的 baseUrl | 任意 OpenAI 兼容中转站，一个 key = 一个账号 |

### 各供应商说明

**腾讯（CodeBuddy）**
- 国内站 `copilot.tencent.com`、国外站 `workbuddy.ai`，两套独立账号
- 上游**拒绝非流式请求**，网关会强制把 `stream:false` 改成 `stream:true`，客户端无需关心
- 支持签到/成长中心（仅国内账号）

**字节（Trae）**
- 国内站 `trae.cn`、国外站 `trae.ai`，两套独立账号
- 使用 SOLO 私有通道，协议转换最复杂
- 支持签到（仅国内账号）

**谷歌（Antigravity）**
- 上游 `cloudcode-pa.googleapis.com`，Gemini 风格协议
- 凭证策略有铁律：不能清空 access_token、不能改写 refresh_token
- 详见 ARCHITECTURE.md 第 5 节

**讯飞（Loomy）**
- 上游 `xfinfr.com`
- 鉴权失败返回 HTTP 200 + 业务码，而非 4xx——不判断业务码会把错误 JSON 当正常回复透传
- 通过「导入」粘贴 JSON 凭证接入

**商汤（Raccoon）**
- 上游 `xiaohuanxiong.com`
- 对话响应的真正 OpenAI 结构被包在 `data` 里（外层是 `{"status":{...}, "data":{...}}`）
- 字段用标准名（v2 LiteLLM 层不认 `max_new_tokens`）
- 网页登录回调，支持 `authorization_code` 换 token
- 每日赠送积分由服务端自动发放，「签到」按钮领取的是桌面端登录奖励

**自定义供应商**
- 任意 OpenAI 兼容中转站
- 一个 key = 一个账号，支持多个自定义供应商
- 在「供应商」页添加，填写 baseUrl + API Key 即可

### 添加自定义供应商

在「供应商」页点击「自定义供应商」→ 填写：
- **名称**：显示用的名字（如"我的中转站"）
- **Base URL**：中转站的地址（如 `https://api.example.com/v1`）
- **API Key**：中转站的密钥

保存后即可在模型页看到自定义供应商的模型（需该中转站支持 `/v1/models` 接口）。

---

## 路由逻辑

网关怎么把你写的 `model` 变成「发给哪家供应商、用哪个账号」：

```
① 写明前缀          腾讯/glm-5.2        → 一定走腾讯
② 查模型真实归属    （无前缀时）          → 看各家目录，谁有这个模型就发给谁
③ 名称特征兜底      （目录里也没有）      → gemini→谷歌、claude→谷歌、seed→字节…
④ 单供应商接管      （只配了一家）        → 所有请求都交给它
⑤ defaultProvider   （仍无法判定）        → 用设置里的默认供应商
```

### 前缀一律用中文

| 前缀 | 含义 | 区域约束 |
|---|---|---|
| `腾讯/` | 腾讯，自动选站 | 按模型实际所在站点 |
| `腾讯国内/` | 强制走腾讯国内站点 | cn |
| `腾讯国外/` | 强制走腾讯国外站点 | global |
| `字节/` | 字节，自动选站 | 按模型实际所在站点 |
| `字节国内/` | 强制走字节国内站点 | cn |
| `字节国外/` | 强制走字节国外站点 | global |
| `谷歌/`、`讯飞/`、`商汤/` | 其余三家 | 无区域概念 |

英文前缀（`codebuddy/`、`trae/` 等）不再作为路由前缀识别，写了会被当成普通模型名走自动归属判定。

### 实际使用示例

**场景一：知道模型属于谁**

```json
{"model": "腾讯/glm-5.2"}       // → codebuddy（自动判断国内/国外）
{"model": "字节/qwen-max"}      // → trae
{"model": "谷歌/gemini-3.8-flash"}  // → antigravity
{"model": "讯飞/spark-4"}       // → loomy
{"model": "商汤/raccoon-chat-ml-5-5"}  // → raccoon
```

**场景二：强制指定区域**

```json
{"model": "腾讯国内/glm-5.2"}      // → codebuddy, region=cn
{"model": "腾讯国外/gpt-5.6-sol"}  // → codebuddy, region=global
{"model": "字节国内/doubao-pro"}   // → trae, region=cn
{"model": "字节国外/qwen-max"}     // → trae, region=global
```

**场景三：不写前缀，让网关自动判断**

```json
{"model": "glm-5.2"}              // → 查目录，字节有 → trae
{"model": "qwen-max"}             // → 查目录，字节有 → trae
{"model": "gemini-3.8-flash"}     // → 查目录，谷歌有 → antigravity
{"model": "deepseek-v3"}          // → 两家都有，按余额选
{"model": "raccoon-chat-ml-5-5"}  // → 查目录，商汤有 → raccoon
```

### 按模型归属，不猜

`腾讯/glm-5.2` 里的 `glm-5.2` 具体该走国内还是国外站点，网关会**分别拉取两边的模型目录**：国内目录里有就发给国内账号，国外目录里有就发给国外账号，并带上正确的区域约束——不会出现「发给一个根本没有这个模型的站点」。

同一个模型在多家供应商都上线时（如 `deepseek-v3`），按「已启用 + 有账号 + 余额最高」排序取第一。

### 模型匹配精度

匹配分三级（从严格到宽松），优先精确匹配：

| 精度 | 规则 | 示例 |
|---|---|---|
| ① 完全相同 | 忽略大小写 | `glm-5.2` = `GLM-5.2` |
| ② 归一化 | `_`、空格统一为 `-`，忽略大小写 | `deepseek_v3` = `deepseek-v3` |
| ③ 去掉修饰符 | 去掉 `-high`、`-low`、`-preview` | `gemini-3.8-flash-high` = `gemini-3.8-flash` |

### 无前缀时自动判定

只写模型名（`glm-5.2`、`gemini-3.8`、`raccoon-chat-ml-5-5`……）时：

1. 先查各家模型目录，谁的目录里真有就发给谁（主要路径）；
2. 目录里查不到，才按名称特征猜：`gemini`/`claude` → 谷歌，`doubao`/`seed-` → 字节，`spark`/星火 → 讯飞，`raccoon`/商汤 → 商汤；
3. `deepseek`/`kimi`/`glm`/`qwen` 这类在多家都有的模型**不猜**，交给目录查询或兜底规则处理。

### 中文 id 的注意事项

> 个别客户端会把 `model` 塞进 HTTP 头（header 只允许 ASCII），中文 id 在这类客户端上会失败。遇到时改用不带前缀的写法即可，网关会自动路由。

如果你的客户端不支持中文 model 名，可以：
- 不写前缀：`{"model": "glm-5.2"}`（网关自动路由）
- 或使用英文前缀（已不再支持，见上文）

### `/v1/models` 里看到的 id

列表里的 id 与上表前缀一致，可以直接复制去客户端配置：

```json
{
  "object": "list",
  "data": [
    { "id": "腾讯国内/glm-5.2",      "owned_by": "codebuddy" },
    { "id": "腾讯国外/gpt-5.6-sol",   "owned_by": "codebuddy" },
    { "id": "字节国内/doubao-pro",    "owned_by": "trae" },
    { "id": "字节国外/qwen-max",      "owned_by": "trae" },
    { "id": "谷歌/gemini-3.8-flash",  "owned_by": "antigravity" },
    { "id": "讯飞/spark-4",           "owned_by": "loomy" },
    { "id": "商汤/raccoon-chat-ml-5-5", "owned_by": "raccoon" }
  ]
}
```

---

## 贡献

1. 改动前先读 [ARCHITECTURE.md](ARCHITECTURE.md)。
2. **新增供应商**只需三步：`core` 实现 `Provider` 接口 → 在 `ProviderBootstrap` 登记 → `app` 实现并登记 `ProviderUi`。框架自动获得账号池、Failover、代理分流、日志与保活。
3. 保持 `:core:test` 全绿。

---

## 使用声明

### 仅供学习与交流

本项目是一个用于学习 HTTP 反向代理、SSE 流式透传、多上游协议适配与 Android 应用（Jetpack Compose）打包等技术主题的实践项目，**仅供个人学习与研究使用**。它不是官方产品，与腾讯公司及 WorkBuddy、商汤及小浣熊、字节跳动及 Trae、科大讯飞及 Loomy、Google 及 Antigravity 均无任何关联，未获得其授权、认可或赞助。

### 关于反向代理行为

本项目实现的是本地反向代理：在你自己的机器上复用你自己账号的登录态，把请求转发到官方上游网关。它不破解、不绕过任何付费或权限校验，使用的额度始终来自你自己账号本就拥有的配额。但需要明确的是，这种「以非官方客户端形态复用登录态」的转发方式，**可能不符合上游服务的用户协议或使用条款**；是否使用、由此产生的一切后果（包括但不限于账号被限流、被风控、被冻结或封禁），均由使用者自行承担。

### 禁止用途

禁止将本项目用于任何商业用途、二次分发牟利、批量账号运营、绕过上游计费或配额限制、或其他违反当地法律法规的活动。如需在生产环境或商业场景中调用相关模型服务，请使用官方渠道与官方 API。

### 凭证与数据风险

本项目会把账号凭证（`accessToken` / `refreshToken` 等）落盘保存：Android 端经 Android Keystore 加密后存入应用私有目录，命令行端（`aigw`）以**明文**文件存放于 `~/.aigw`（可用 `--data` 参数或 `AIGW_DATA` 环境变量改写）。请自行妥善保管，切勿提交到公开仓库、上传到网盘或分享给他人。因凭证泄露造成的损失由使用者自行承担。

### 无担保与权利通知

本项目按「现状」提供，作者不对其可用性、稳定性、安全性或对特定用途的适用性作任何承诺。上游接口随时可能变更，本项目可能随时失效而不再维护。完整条款见 [LICENSE](./LICENSE)。本项目中的接口形态、协议字段等信息来自对各官方客户端公开网络通信的观察与整理，相关商标与服务的权利归其各自所有者；若权利方认为本项目存在不当之处，请联系作者，将及时调整或删除。

---

## 许可

本项目基于 [MIT License](./LICENSE)，可自由使用、修改与分发，须保留版权声明。

需要留意的是：LICENSE 正文之后附有一份**使用声明**，其中第 3 条在 MIT 之上**追加了限制**（禁止商业用途、禁止二次分发牟利、禁止批量账号运营）。因此本项目**不是**纯粹的 MIT 项目——**MIT 条款与使用声明共同构成完整的授权与使用约定**，两者对同一行为给出不同结论时以更严格的一方为准。这也是本项目未在构建脚本中声明 SPDX `"MIT"` 许可标识、而以 [LICENSE](./LICENSE) 共同构成授权约定的原因。
