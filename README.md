# 智能中枢

> **原作者**：[xibai6668](https://github.com/xibai6668) · 原仓库：[Android-AI-gateway](https://github.com/xibai6668/Android-AI-gateway)
>
> 本项目基于原作者代码进行路由改造（**标签路由：模型标签唯一绑定供应商**），感谢原作者的开源贡献喵～

![License](https://img.shields.io/badge/license-MIT%20%2B%20附加声明-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-enabled-lightblue)
![API](https://img.shields.io/badge/API-OpenAI%20Compatible-orange)
![MinSdk](https://img.shields.io/badge/minSdk-26-blue)
![TargetSdk](https://img.shields.io/badge/targetSdk-36-blue)

Android 上运行的本地 AI 模型聚合网关：把多家私有 AI 服务账号统一翻译成 OpenAI 兼容接口，暴露在 `http://127.0.0.1:8790/v1`。

> **包名变更**：从 2.23.3 起应用包名由 `dev.aigw.app` 改为 `io.synapse.app`（显示名「智能中枢」）。
> 两者在系统中视为不同应用，**旧版的配置与账号不会自动迁移**，需重新导入。

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
  - [标签表](#标签表)
  - [实际使用示例](#实际使用示例)
  - [按模型归属，不猜](#按模型归属不猜)
  - [模型匹配精度](#模型匹配精度)
  - [同一标签下换号重试](#同一标签下换号重试)
  - [中文 id 的注意事项](#中文-id-的注意事项)
  - [`/v1/models` 里看到的 id](#v1models-里看到的-id)
- [模型信息展示](#模型信息展示)
  - [倍率](#倍率)
  - [限时折扣--限免](#限时折扣--限免)
  - [模型级剩余额度谷歌](#模型级剩余额度谷歌)
  - [其他标签](#其他标签)
- [错误提示](#错误提示)
- [贡献](#贡献)
- [使用声明](#使用声明)
- [许可](#许可)

---

## 特性

- **OpenAI 兼容网关**：`/v1/chat/completions` + `/v1/models`，流式 SSE / 非流式 / 多模态识图。客户端只需填一个 Base URL，所有供应商的模型都能用。
- **五家内置供应商**：腾讯（CodeBuddy）、字节（Trae）、谷歌（Antigravity）、讯飞（Loomy）、商汤（Raccoon），以及任意自定义 OpenAI 兼容中转站。
- **标签路由**：调用模型时写成「标签/模型名」，标签唯一地绑定一家供应商。选了哪家就只去哪家，**不做任何跨供应商转移**。
- **标签即路由**：没有标签、或标签指向的供应商没登录/不存在，一律直接报错，不猜、不兜底。
- **账号管理**：同一供应商多账号按余额选号、失败自动换号、额度查询与额度包明细、跨供应商容灾、批量签到与任务中心。
- **模型开放名单**：只把勾选的模型暴露给 `/v1/models`，其余模型对客户端隐藏，防止客户端误用。
- **模型倍率与折扣**：模型页显示每个模型的积分消耗倍率——**腾讯、字节、讯飞、商汤四家全部解析**，数据直接来自上游下发（不是本地硬编码）。限时折扣/限免与截止时间单独标注，随官方活动实时变化，一眼看出哪个模型最省额度。
- **模型级剩余额度**：谷歌的每个模型带 `quotaInfo`，展示剩余百分比与重置时刻（如「额度 100%（11月5日 16:42 重置）」）。
- **友好的错误提示**：上游拒绝时按原因分类给出可操作指引（如 Google 账号需要人机验证 → 分步告诉你去哪、做什么），而不是把整段 JSON 甩给用户。
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
   - **模型**：**必须**写成 `标签/模型名`（如 `腾讯国内/glm-5.2`、`字节国外/qwen-max`）。腾讯、字节是双站点的，标签里必须带区域（`腾讯国内/`、`腾讯国外/`、`字节国内/`、`字节国外/`）。不写标签会直接报错——网关不再按模型名自动猜测归属。
   - **API Key**：默认开启「无 Key 调用」，随意填；关掉后需与「接入设置」里的 Key 一致。

### 常见客户端配置示例

**Cline**（VS Code 插件）：
```
Base URL: http://127.0.0.1:8790/v1
API Key:  （随意填，或留空）
Model:    腾讯国内/glm-5.2  或  字节国外/qwen-max  或  直接写 glm-5.2
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
      "model": "腾讯国内/glm-5.2"
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
GatewayEngine       ③ 按标签解析路由（唯一一家，无候选）
                    ④ 选号 → 预刷新凭证 → 发起上游请求
                    ⑤ 失败只在同一家内换号重试，绝不换家
   ▼
Provider 实现       上游私有协议 ⇄ OpenAI 协议（双向转换封死在 Provider 内）
   ▼
上游 API
```

### 路由判定流程

**标签是路由的唯一依据。** 整个流程只有两步，没有任何猜测：

```
resolveRoute(model)
  │
  ├─ ① 剥 "models/" 前缀（Google/Gemini SDK 常见写法）
  │
  ├─ ② 没有 "/"？
  │     └─ 是 → 返回 null（报「模型无法路由」）
  │          网关不做目录归属查询、不做名称推断
  │
  ├─ ③ routeByLabel(标签, 模型名)
  │     ├─ 标签不在表里？              → null
  │     ├─ 该供应商没注册？            → null
  │     ├─ 该供应商没启用？            → null
  │     ├─ 该供应商没账号（没登录）？  → null
  │     └─ 全过 → Route(那一家, 模型名, 区域)
  │
  └─ ④ 返回 Route(providerId, model, region)
```

区域由标签后缀决定：`腾讯国内/` → `cn`，`腾讯国外/` → `global`，无区域后缀的（谷歌/讯飞/商汤/自定义）→ `null`。

**路由之后**：失败只会在**同一个供应商内换号重试**（可配置退避参数），**不存在跨供应商转移**。

**核心不变量**：Provider 的边界是「OpenAI 进、OpenAI 出」。上游私有协议的双向转换全部封死在 Provider 内部，网关层对供应商差异零感知。任何把上游协议细节泄漏到网关层的改动，都会破坏这套可维护性根基。

---

## 模块结构

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | 纯 JVM，无 Android 依赖 | 网关引擎、HTTP 服务、账号池、标签路由、各供应商协议实现、存储抽象。可独立单测 |
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

本地构建产出 debug 包，包名与 release 一致（`io.synapse.app`），可原地覆盖升级、配置不丢：

```sh
./gradlew :app:assembleDebug --console=plain   # 出 debug 包
./gradlew :core:test --console=plain           # 单元测试（:core 是唯一有测试的模块）
./gradlew :cli:installDist --console=plain     # 命令行工具，产出 cli/build/install/aigw/bin/aigw
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`（包名 `io.synapse.app`，与 release 相同）。
- 版本号硬编码在 `app/build.gradle.kts`（`versionCode` / `versionName`）；改后必须重新构建。
- 本地**不产 release 包**：release 由云端 GitHub Actions 在**推送 `v*` 标签时**构建并发版。日常 push 分支不会触发任何云端构建。
- 注意：debug 包用 AGP 默认 debug key 签名，与云端 release keystore 签名不同；在已装 release 版的设备上覆盖安装需 root（`pm install -r`）或卸载重装。

### GitHub Actions 工作流

仓库里有 2 个工作流：

| 工作流 | 触发方式 | 作用 |
|---|---|---|
| `android-release.yml` | **push `v*` 标签** 或手动 | 跑测试 → 打 release 包 → 创建 Release + 上传 APK → **自动递增版本号并提交回 main** |
| `build-release.yml` | 手动 | 只跑测试 + 打包，产物作为 artifact（不创建 Release），用于验证构建 |

#### 发版流程：打标签即发版

```sh
# 1. 确认当前版本号（上一次发版后已自动递增好）
grep -m1 'versionName' app/build.gradle.kts     # 例如 2.23.1

# 2. 直接打标签并推送
git tag -a v2.23.1 -m "v2.23.1"
git push origin v2.23.1
```

推送标签后自动完成：
1. 校验标签与 `build.gradle.kts` 的 `versionName` 一致
2. 跑单元测试 → 打签名 release 包
3. 创建 GitHub Release 并上传 APK
4. **把 `versionCode` +1、`versionName` 的修订号 +1 提交回 main**

所以下一次发版时，`build.gradle.kts` 里已经是新版本号了，直接打标签即可。

#### 版本号规则

| 场景 | 结果 |
|---|---|
| 当前 `2.23.0 (200)` 发布 `v2.23.0` | 自动提交 `2.23.1 (201)` |
| 再发布 `v2.23.1` | 自动提交 `2.23.2 (202)` |
| 想跳次版本（如 `2.24.0`） | 手动改 `build.gradle.kts` 后打 `v2.24.0` 标签 |

> ⚠️ 标签必须是纯 `v<主>.<次>.<修订>` 三段数字。`v2.23.0-beta`、`v2.23` 会被拒绝。
> ⚠️ 标签版本必须与 `build.gradle.kts` 一致，不一致会直接失败并提示当前版本号是多少。

#### Secrets 配置

两个工作流都需要在仓库 Settings → Secrets and variables → Actions 里配置 4 个 secrets：
- `KEYSTORE_BASE64`：release keystore 的 base64 编码
- `KEYSTORE_PASSWORD`：keystore 密码
- `KEY_ALIAS`：key alias
- `KEY_PASSWORD`：key 密码

生成 base64：
```sh
base64 -w0 release.keystore > keystore.b64
```

> ⚠️ 工作流里安装的 build-tools 版本必须与 `app/build.gradle.kts` 的 `buildToolsVersion` 一致（当前都是 `36.0.0`），否则云端构建会失败。

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
  -d '{"model":"字节国内/qwen-max","stream":false,"messages":[{"role":"user","content":"你好"}]}'
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

**不写标签（会直接报错）**：
```json
{"model": "glm-5.2", "stream": true, "messages": [{"role": "user", "content": "你好"}]}
```
返回 `400 model_not_routable`，提示需要写成「标签/模型名」。

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
| `<自定义标签>` | 自定义 | 用户填写的 baseUrl | 任意 OpenAI 兼容中转站，一个 key = 一个账号 |

### 各供应商说明

**腾讯（CodeBuddy）**
- 国内站 `copilot.tencent.com`、国外站 `workbuddy.ai`，两套独立账号
- 上游**拒绝非流式请求**，网关会强制把 `stream:false` 改成 `stream:true`，客户端无需关心
- 支持签到/成长中心（仅国内账号）
- **倍率**：上游 `credits` 字段（如 `"x0.79 credits"`）；限时活动在 `tags[]` 里以 `badge:限时免费` 下发

**字节（Trae）**
- 国内站 `trae.cn`、国外站 `trae.ai`，两套独立账号
- 使用 SOLO 私有通道，协议转换最复杂
- 支持签到（仅国内账号）
- **倍率**：上游 `display_contact_config.consumption_rate.rate`（该字段是被转义的 JSON 字符串）；上游没给时回退到本地参考表

**谷歌（Antigravity）**
- 上游 `cloudcode-pa.googleapis.com`，Gemini 风格协议
- 凭证策略有铁律：不能清空 access_token、不能改写 refresh_token
- 详见 ARCHITECTURE.md 第 5 节
- **无积分倍率概念**，但每个模型带 `quotaInfo`（剩余额度百分比 + 重置时刻），界面单独展示
- 账号需要人机验证时上游返回 403 `VALIDATION_REQUIRED`，界面会给出分步操作指引

**讯飞（Loomy）**
- 上游 `xfinfr.com`
- 鉴权失败返回 HTTP 200 + 业务码，而非 4xx——不判断业务码会把错误 JSON 当正常回复透传
- 通过「导入」粘贴 JSON 凭证接入
- **倍率内嵌在模型名里**：`"Kimi k2.6 （x6.5）"`、`"GLM 5.3 Flash(x0.8)"`，网关解析后显示为 `6.5×` / `0.8×`，名字里的「限时免费」也会单独标出

**商汤（Raccoon）**
- 上游 `xiaohuanxiong.com`
- 对话响应的真正 OpenAI 结构被包在 `data` 里（外层是 `{"status":{...}, "data":{...}}`）
- 字段用标准名（v2 LiteLLM 层不认 `max_new_tokens`）
- 网页登录回调，支持 `authorization_code` 换 token
- 每日赠送积分由服务端自动发放，「签到」按钮领取的是桌面端登录奖励
- **倍率**：`billing_multiplier`（基准）与 `billing_effective_multiplier`（生效）；限免/折扣在 `billing_discounts[]` 里，含文案与起止时间（服务器实时下发）

**自定义供应商**
- 任意 OpenAI 兼容中转站
- 一个 key = 一个账号，支持多个自定义供应商
- 在「供应商」页添加，填写 baseUrl + API Key 即可

### 添加自定义供应商

在「供应商」页点击「自定义供应商」→ 填写：

- **模型标签🏷️**：调用时写成「标签/模型名」，比如标签填 `mysite` 就用 `mysite/gpt-4o`
- **名称**：界面上显示的名字（如"我的中转站"）
- **Base URL**：中转站的地址（如 `https://api.example.com/v1`）
- **API Key**：中转站的密钥（可留空，稍后在详情页「添加账号」里填）

保存后即可在模型页看到自定义供应商的模型（需该中转站支持 `/v1/models` 接口）。

### 模型标签的规则

标签就是路由前缀本身，所以有硬性约束：

| 规则 | 说明 |
|---|---|
| 不能为空 | 空标签会让模型无法路由 |
| 不能带斜杠、空白字符，最多 32 字符 | 它本身就是 `标签/模型名` 里的前半段 |
| **不能与内置标签重名** | `腾讯国内`、`字节国外`、`谷歌`、`讯飞`、`商汤` 这些名字被内置供应商占着 |
| **不能与另一个自定义供应商重名** | 一个标签只能绑定一家 |

⚠️ **为什么禁止重名**：如果自定义供应商把标签起成 `腾讯国内`，
系统会真的把这个标签解析到**内置的腾讯国内**供应商，然后发现腾讯那边没这个模型——
看起来能配、实际必然失败，这种坑必须从源头堵住。

表单会在保存前拦下来，而且核心层在写盘前会再校一次。

---

## 路由逻辑

网关怎么把你写的 `model` 变成「发给哪家供应商、用哪个账号」：

```
① 写标签           腾讯国内/glm-5.2    → 一定走腾讯国内站
② 没写标签                             → 直接报「模型无法路由」
③ 标签指向的供应商不可用               → 直接报「模型无法路由」

标签是路由的**唯一依据**。没有标签、标签不存在、或标签指向的供应商没登录/没启用，
一律直接报错——不查目录、不猜名称、不兜底给别家。
```

> **设计取舍**：早期版本会根据模型名猜供应商（`gemini`→谷歌、`doubao`→字节），
> 也会在标签失效时退到「第一个有账号的供应商」。这些全部已删除：
> 猜错的代价比报错大得多——请求会静悄悄跑到别家，得到一个与真实原因无关的上游错误，
> 排查时根本看不出问题在「标签写错了」。

### 标签表

调用模型时写成「**标签/模型名**」。标签只认下表这些，**不支持英文标签**：

| 前缀 | 供应商 | 区域约束 |
|---|---|---|
| `腾讯国内/` | 腾讯 | cn |
| `腾讯国外/` | 腾讯 | global |
| `字节国内/` | 字节 | cn |
| `字节国外/` | 字节 | global |
| `谷歌/` | 谷歌 | 无区域概念 |
| `讯飞/` | 讯飞 | 无区域概念 |
| `商汤/` | 商汤 | 无区域概念 |
| `<自定义标签>/` | 自定义供应商 | 无区域概念 |

腾讯、字节只保留「国内 / 国外」两种写法：上游模型列表的标签本身就长这样，
不存在「腾讯」「字节」裸品牌标签，也没有「中国 / 国际」这种同区别名。

**已删除的写法**（写了不会被识别，会直接报错）：

| 写法 | 为什么删 |
|---|---|
| `腾讯/`、`字节/` | 区域标签本身就带国内/国外，裸品牌无意义 |
| `腾讯中国/`、`字节国际/` | 同义别名只会让人以为有区别 |
| `小浣熊/` | 已全面改名为「商汤」 |
| `codebuddy/`、`trae/`、`google/` 等英文 | 避免中英两套写法并存 |

### 实际使用示例

**场景一：写标签（唯一正确写法）**

```json
{"model": "腾讯国内/glm-5.2"}    // → codebuddy, region=cn
{"model": "腾讯国外/glm-5.2"}    // → codebuddy, region=global
{"model": "字节国内/qwen-max"}   // → trae, region=cn
{"model": "字节国外/qwen-max"}   // → trae, region=global
{"model": "谷歌/gemini-3.8-flash"}  // → antigravity
{"model": "讯飞/spark-4"}       // → loomy
{"model": "商汤/raccoon-chat-ml-5-5"}  // → raccoon
{"model": "mysite/gpt-4o"}       // → custom:mysite
```

**场景二：标签只绑定自己那家**

```json
{"model": "腾讯国内/qwen-max"}   // → 腾讯国内（即使 qwen 实际在字节）
{"model": "字节国内/glm-5.2"}    // → 字节国内（即使 glm 实际在腾讯）
```

写了哪家就去哪家。网关**不会**因为你写错而帮你改道——上游会如实报「没这个模型」，
这时应当是改标签，而不是期待网关猜。

**场景三：不写标签 → 直接报错**

```json
{"model": "glm-5.2"}       // ✗ 400 model_not_routable
{"model": "gemini-3.8"}    // ✗ 400 model_not_routable
{"model": "auto"}          // ✗ 400 model_not_routable
```

错误信息会告诉你正确写法：

```
模型「glm-5.2」无法路由。请写成「标签/模型名」，例如 腾讯国内/glm-5.2、字节国外/doubao-pro。
若标签已经写了，请到「供应商」页确认该供应商已登录且已启用。
```

**场景四：标签指向的供应商不可用**

```json
{"model": "腾讯国内/glm-5.2"}   // 腾讯没登录 → ✗ 400 model_not_routable
{"model": "火星/glm-5.2"}       // 标签不存在 → ✗ 400 model_not_routable
```

### 同一标签下换号重试

路由定下某家后，如果这个账号失败（额度耗尽、凭证失效、上游 5xx），
只会在**同一个供应商内**换下一个账号重试，参数在「我的 → 并发与容错」里可调：

- 最大尝试次数（含首次）
- 初始退避 / 退避上限 / 退避倍率 / 随机抖动

**绝不会切换到其它供应商。** 这是标签语义的保证：你写了哪家，请求就只会在那家。

### 账号不可用时的行为

同一供应商下通常有多个账号（区域型还会分国内/国外），路由选号时会自动跳过不可用的：

| 情况 | 行为 |
|---|---|
| 部分账号被停用（手动关 / 凭证失效） | **静默换号**，请求照常。这是设计意图，不弹提醒 |
| 同家账号**全部**停用 | 发**强提醒**（两段震动 + 高优先级横幅），然后如实报错 |

全部停用时的横幅会告诉你是哪种情况：

```
腾讯国内 的账号全部不可用
共 2 个账号，全部被手动停用，去「供应商」页启用即可恢复

腾讯国内 的账号全部不可用
共 2 个账号：1 个凭证失效（需重新登录），1 个被手动停用（去启用）
```

注意：**这不是钱的问题**，所以文案指向「启用账号 / 重新登录 / 换模型」，
不会误导你去充值。

### 额度耗尽时的行为

1. 上游报额度不足时，本地立刻把这个账号的额度记为 0，同一请求内不再重复试它；
2. 同家还有别的账号 → 换号继续，同时发一条**轻提醒**（短震动 + 通知）；
3. 同家账号都耗尽了 → 发**强提醒**（两段震动 + 高优先级横幅），然后如实报错。

选号时会跳过「已知额度为 0」的账号；但若全部为 0，会退回不过滤的一轮——
因为池里的 0 可能已经过时（刚充值、或今日刚重置），需要让上游给出真实答案。

### 中文标签的注意事项

> 个别客户端会把 `model` 塞进 HTTP 头（header 只允许 ASCII），中文标签在这类客户端上会失败。

这是标签路由的代价：**标签是路由依据，不能用别的写法代替**。
遇到这类客户端，可以选择：
- 给那个供应商新建一个**自定义供应商**，标签用纯英文（如 `mysite`）；
- 或换一个支持中文 model 名的客户端。

### `/v1/models` 里看到的 id

列表里的 id 与上表标签一致，可以直接复制去客户端配置：

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

## 模型信息展示

模型页每个模型下面会显示这些信息（有才显示，没有就不画那一行）：

### 倍率

**四家供应商的倍率全部来自上游下发**，不是本地硬编码：

| 供应商 | 上游字段 | 示例 |
|---|---|---|
| 腾讯 | `credits`（`"x0.79 credits"`） | 倍率 0.79× |
| 字节 | `display_config.display_contact_config.consumption_rate.rate` | 倍率 0.7× |
| 讯飞 | 模型名内嵌 `(x6.5)` | 倍率 6.5× |
| 商汤 | `billing_multiplier` | 倍率 0.75× |

### 限时折扣 / 限免

折扣同样来自上游，随官方活动实时变化：

| 供应商 | 折扣信息 | 展示效果 |
|---|---|---|
| 商汤 | `billing_discounts[]`（含文案 + 起止时间） | 倍率 0.25× **限免至10月31日 0×** |
| 腾讯 | `tags[]` 里的 `badge:限时免费` | 倍率 0× **限时免费 0×** |
| 字节 | 本地参考表的 `discounted` / `discountTag` | 倍率 0.78× **会员5折 0.39×** |
| 讯飞 | 模型名里的「限时免费」文案 | 倍率 0× **限时免费 0×** |

> 日期不重复：若上游给的折扣文案本身已带日期（如「限免至10月31日」），就不再额外拼接截止时间。

### 模型级剩余额度（谷歌）

```json
"quotaInfo": { "remainingFraction": 1, "resetTime": "2026-11-05T08:42:21Z" }
```

展示为「额度 100%（11月5日 16:42 重置）」——这是**剩余额度**，不是倍率。

### 其他标签

- 上下文窗口、输出上限
- 能力标签：思考 / 联网 / 识图 / 工具

---

## 错误提示

上游拒绝时，界面优先给出**可操作指引**而不是技术细节：

```
user@example.com
需要先在 Google 官网验证这个账号：
1. 用浏览器打开 accounts.google.com 并登录该账号
2. 按页面提示完成验证（可能需要手机号或辅助邮箱）
3. 验证通过后回到这里点「刷新额度」
账号密码没问题，不需要在网关里重新登录。
```

实现上分两层：`actionHint`（给用户看，界面高亮）+ `detail`（技术描述，供排查）。

---

## 贡献

1. 改动前先读 [ARCHITECTURE.md](ARCHITECTURE.md)。
2. **新增供应商**只需三步：`core` 实现 `Provider` 接口 → 在 `ProviderBootstrap` 登记 → `app` 实现并登记 `ProviderUi`。别忘了在 `chineseRoutePrefix` 里给它加一条**模型标签**。框架自动获得账号池、换号重试、代理分流、日志与保活。
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
