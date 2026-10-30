# 智能中枢

把手机里多个 AI 服务账号，统一成一个 OpenAI 兼容接口。

在本地起一个网关（默认 `http://127.0.0.1:8790/v1`），任何支持 OpenAI 协议的客户端 —— Cline、Cherry Studio、Continue、curl —— 都能直接调用你所有账号的模型。

![License](https://img.shields.io/badge/license-MIT%20%2B%20附加声明-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-purple)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-enabled-lightblue)
![API](https://img.shields.io/badge/API-OpenAI%20Compatible-orange)
![MinSdk](https://img.shields.io/badge/minSdk-26-blue)
![TargetSdk](https://img.shields.io/badge/targetSdk-36-blue)

> **原作者**：[xibai6668](https://github.com/xibai6668) · 原仓库：[Android-AI-gateway](https://github.com/xibai6668/Android-AI-gateway)
>
> 本项目在其基础上重构了路由体系（标签路由：模型标签唯一绑定供应商）。感谢原作者的开源贡献。
>
> 改动代码前请先读 **[ARCHITECTURE.md](ARCHITECTURE.md)**。

---

## 目录

- [特性](#特性)
- [快速开始](#快速开始)
- [核心概念：标签路由](#核心概念标签路由)
- [内置供应商](#内置供应商)
- [自定义供应商](#自定义供应商)
- [接口](#接口)
- [模型信息](#模型信息)
- [错误提示](#错误提示)
- [架构](#架构)
- [数据存储](#数据存储)
- [构建与发版](#构建与发版)
- [命令行工具](#命令行工具)
- [许可与声明](#许可与声明)

---

## 特性

**协议**
- OpenAI 兼容：`/v1/chat/completions` + `/v1/models`
- 流式 SSE / 非流式 / 多模态识图

**路由**
- 标签路由：`标签/模型名` 唯一确定一家供应商
- 无标签、标签失效、供应商未登录 —— 一律报错，不猜不兜底
- 失败只在同一家内换号重试，绝不跨供应商转移

**账号**
- 多账号按余额选号、失败自动换号
- 额度查询、额度包明细、批量签到
- 账号不可用（停用 / 耗尽）时分级提醒

**模型信息**
- 倍率、限时折扣、限免截止时间 —— 全部来自上游实时下发
- 谷歌模型级剩余额度与重置时刻
- 模型开放名单：只暴露勾选的模型

**其他**
- 用量趋势、调用记录、运行日志、存储占用自动清理
- HTTP / SOCKS5 代理，支持按供应商与地址排除
- system 提示词零宽字符脱敏、账号级最小间隔限速
- 前台服务保活 + 控制中心磁贴，锁屏仍可服务

---

## 快速开始

### 1. 安装

到 [Releases](https://github.com/Alien-Et/synapse-gateway/releases) 下载 `synapse-<版本>-release.apk`。

包名 `io.synapse.app`，minSdk 26。

### 2. 启动网关

打开 App，首页点「启动网关服务」，显示「运行中」即已监听 `8790` 端口。

### 3. 接入账号

到「供应商」页登录账号：

| 供应商 | 接入方式 |
|---|---|
| 腾讯 / 字节 / 谷歌 / 商汤 | 网页登录，浏览器授权后自动回传凭证 |
| 讯飞 | 「导入」粘贴 JSON 凭证 |
| 自定义 | 填 Base URL + API Key |

### 4. 配置客户端

```
Base URL:  http://127.0.0.1:8790/v1
API Key:   任意值（默认开启「无 Key 调用」）
Model:     标签/模型名，例如 腾讯国内/glm-5.2
```

**Model 必须带标签**，不写会返回 `400 model_not_routable`。

局域网内其它设备访问：先在「接入设置」打开「同时监听局域网」，然后填首页显示的局域网地址。

### 客户端示例

**Cline**
```
Base URL: http://127.0.0.1:8790/v1
API Key:  任意值
Model:    腾讯国内/glm-5.2
```

**Continue**
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

**curl**
```sh
curl http://127.0.0.1:8790/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer 任意值" \
  -d '{"model":"腾讯国内/glm-5.2","stream":true,"messages":[{"role":"user","content":"你好"}]}'
```

### 查可用模型

```sh
curl http://127.0.0.1:8790/v1/models
```

返回的 `id` 可直接复制到客户端，前缀已包含供应商与区域。

---

## 核心概念：标签路由

### 写法

```
标签/模型名
```

| 标签 | 供应商 | 区域 |
|---|---|---|
| `腾讯国内/` | 腾讯 | cn |
| `腾讯国外/` | 腾讯 | global |
| `字节国内/` | 字节 | cn |
| `字节国外/` | 字节 | global |
| `谷歌/` | 谷歌 | — |
| `讯飞/` | 讯飞 | — |
| `商汤/` | 商汤 | — |
| `<自定义标签>/` | 自定义供应商 | — |

标签只认中文，不支持英文标签。腾讯、字节因为双站点，标签必须带区域。

### 判定流程

```
resolveRoute(model)
  ├─ 剥掉 "models/" 前缀
  ├─ 没有 "/"                    → 报错
  ├─ 标签不在表中                → 报错
  ├─ 供应商未注册 / 未启用 / 无账号 → 报错
  └─ 全部通过                     → Route(供应商, 模型, 区域)
```

**标签是唯一依据。** 不查目录、不推断名称、不兜底给别家。

### 示例

```json
{"model": "腾讯国内/glm-5.2"}        // → 腾讯国内站
{"model": "字节国外/qwen-max"}       // → 字节国外站
{"model": "谷歌/gemini-3.8-flash"}   // → 谷歌
{"model": "mysite/gpt-4o"}           // → 自定义 mysite

{"model": "glm-5.2"}                 // ✗ 400 model_not_routable
{"model": "腾讯国内/qwen-max"}       // → 腾讯（即使 qwen 实际在字节，也不会改道）
```

写错标签时，上游会如实报「没这个模型」—— 该改标签，别期待网关猜。

### 换号重试

路由定下某家后，账号失败（额度耗尽、凭证失效、上游 5xx）只在**同一家内**换号。参数在「我的 → 并发与容错」可调：最大尝试次数、初始退避、退避上限、倍率、抖动。

### 账号不可用

| 情况 | 行为 |
|---|---|
| 部分账号停用 | 静默换号，请求照常 |
| 同家全部停用 | 强提醒（震动 + 横幅）+ 报错 |
| 额度耗尽，仍有其它账号 | 换号 + 轻提醒 |
| 额度全部耗尽 | 强提醒 + 报错 |

同家全停时的横幅会区分「手动停用」和「凭证失效」，文案指向启用 / 重新登录，不会误导你去充值。

### 中文标签的限制

少数客户端会把 `model` 放进 HTTP 头（头只允许 ASCII），中文标签在这类客户端上会失败。解决办法：给那家建一个纯英文标签的自定义供应商，或换客户端。

---

## 内置供应商

| id | 名称 | 上游 | 说明 |
|---|---|---|---|
| `codebuddy` | 腾讯 | 国内 `copilot.tencent.com` / 国外 `workbuddy.ai` | 上游拒绝非流式，网关强制 `stream:true` |
| `trae` | 字节 | 国内 `trae.cn` / 国外 `trae.ai` | SOLO 私有通道，协议转换最重 |
| `antigravity` | 谷歌 | `cloudcode-pa.googleapis.com` | Gemini 风格协议 |
| `loomy` | 讯飞 | `xfinfr.com` | 鉴权失败返回 HTTP 200 + 业务码 |
| `raccoon` | 商汤 | `xiaohuanxiong.com` | OpenAI 结构包在 `data` 里 |

**各家的坑：**

- **腾讯** — 强制流式；签到仅国内账号
- **字节** — SOLO 私有通道；签到仅国内账号
- **谷歌** — 凭证有铁律（不清空 access_token、不改写 refresh_token，见 ARCHITECTURE.md 第 5 节）；需人机验证时返回 403 `VALIDATION_REQUIRED`
- **讯飞** — 鉴权失败是 HTTP 200 + 业务码，不判业务码会把错误 JSON 当正常回复透传
- **商汤** — 响应外层是 `{"status":..., "data":...}`；字段用标准名（不认 `max_new_tokens`）

---

## 自定义供应商

「供应商」页 → 「自定义供应商」，填三项：

- **模型标签** — 调用时写成 `标签/模型名`
- **Base URL** — 如 `https://api.example.com/v1`
- **API Key** — 一个 key 对应一个账号

保存后自动拉取 `/v1/models`，模型出现在模型页。

### 标签规则

| 规则 | 说明 |
|---|---|
| 不能为空 | 空标签无法路由 |
| 不能含斜杠或空白，≤ 32 字符 | 它本身就是 `标签/模型名` 的前半段 |
| 不能与内置标签重名 | `腾讯国内`、`字节国外`、`谷歌` 等已被占用 |
| 不能与其它自定义供应商重名 | 一个标签只能绑定一家 |

重名会被表单和核心层双重拦截 —— 否则标签会解析到内置供应商，配起来像是能通，实际必然失败。

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/chat/completions` | 对话补全（流式 / 非流式 / 多模态） |
| `GET` | `/v1/models` | 模型列表（按开放名单过滤） |
| `GET` | `/v1/credits` | 额度查询（别名 `/credits`） |
| `GET` | `/healthz` | 健康检查，返回纯文本 `ok`，免鉴权 |

### 鉴权

三种方式任一即可：

```
Authorization: Bearer <key>
x-goog-api-key: <key>
?key=<key>
```

默认开启「无 Key 调用」。服务处理 `OPTIONS` 预检并返回 CORS 头，供浏览器端使用。

默认只绑 `127.0.0.1`；打开「同时监听局域网」后监听全部网卡 —— 此时若仍开着无 Key 调用，同网段任何设备都能直接调用。

### 多模态示例

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

### 模型列表返回

```json
{
  "object": "list",
  "data": [
    {"id": "腾讯国内/glm-5.2",     "object": "model", "created": 1753600000, "owned_by": "codebuddy"},
    {"id": "字节国内/doubao-pro",  "object": "model", "created": 1753600000, "owned_by": "trae"},
    {"id": "谷歌/gemini-3.8-flash","object": "model", "created": 1753600000, "owned_by": "antigravity"}
  ]
}
```

---

## 模型信息

模型页每个模型下方显示（有数据才显示）：

### 倍率

四家供应商的倍率**全部来自上游下发**，非本地硬编码：

| 供应商 | 上游字段 |
|---|---|
| 腾讯 | `credits`（如 `"x0.79 credits"`） |
| 字节 | `display_config.display_contact_config.consumption_rate.rate` |
| 讯飞 | 模型名内嵌 `(x6.5)` |
| 商汤 | `billing_multiplier` |

### 限时折扣

| 供应商 | 来源 | 展示 |
|---|---|---|
| 商汤 | `billing_discounts[]`（含文案 + 起止时间） | 倍率 0.25× **限免至10月31日 0×** |
| 腾讯 | `tags[]` 的 `badge:限时免费` | 倍率 0× **限时免费 0×** |
| 字节 | 本地参考表 | 倍率 0.78× **会员5折 0.39×** |
| 讯飞 | 模型名里的「限时免费」 | 倍率 0× **限时免费 0×** |

上游文案本身带日期时不再重复拼接截止时间。

### 谷歌剩余额度

```json
"quotaInfo": { "remainingFraction": 1, "resetTime": "2026-11-05T08:42:21Z" }
```

展示为「额度 100%（11月5日 16:42 重置）」。这是剩余额度，不是倍率。

### 其他

上下文窗口、输出上限、能力标签（思考 / 联网 / 识图 / 工具）。

---

## 错误提示

上游拒绝时给可操作指引，而非技术细节：

```
user@example.com
需要先在 Google 官网验证这个账号：
1. 用浏览器打开 accounts.google.com 并登录该账号
2. 按页面提示完成验证（可能需要手机号或辅助邮箱）
3. 验证通过后回到这里点「刷新额度」
账号密码没问题，不需要在网关里重新登录。
```

实现上是两层：`actionHint`（界面高亮给用户看）+ `detail`（技术描述，供排查）。

---

## 架构

一次请求的生命周期：

```
客户端 (Cline / Cherry Studio / curl)
   │  POST /v1/chat/completions  {model: "标签/模型名", ...}
   ▼
GatewayHttpServer   ① 鉴权  ② 解析 model
   ▼
GatewayEngine       ③ 按标签解析路由（唯一一家）
                    ④ 选号 → 预刷新凭证 → 请求上游
                    ⑤ 失败仅在同一家内换号重试
   ▼
Provider 实现       上游私有协议 ⇄ OpenAI 协议（转换全封在 Provider 内）
   ▼
上游 API
```

**核心不变量**：Provider 的边界是「OpenAI 进、OpenAI 出」。上游协议细节不得泄漏到网关层 —— 这是整个项目可维护性的根基。

### 模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | 纯 JVM | 网关引擎、HTTP 服务、账号池、标签路由、各供应商协议、存储抽象 |
| `:cli` | 纯 JVM | 命令行工具，可不开 App 直接联调 |
| `:app` | Android | Compose UI、前台服务保活、加密存储、控制中心磁贴 |

### Provider 协议实现

位于 `core/src/main/kotlin/io/synapse/core/provider/`：

| 子包 | 供应商 | 文件数 |
|---|---|---|
| `trae/` | 字节 | 16 |
| `codebuddy/` | 腾讯 | 2 |
| `antigravity/` | 谷歌 | 2 |
| `loomy/` | 讯飞 | 13 |
| `raccoon/` | 商汤 | 1 |
| `custom/` | 自定义 | 1 |

每个 Provider 实现 `Provider` 接口：`listModels()` / `resolveModel()` / `openChat()` / `creditInfo()` / `performAction()` / `refreshAccount()`。

---

## 数据存储

账号凭证与状态按**账号独立存储**：

```
account/<供应商>/<账号>            # 凭证
pool/state/<供应商>/<账号>.json    # 状态（额度 / 启用 / 禁用原因）
settings/...                      # 网关、代理、安全、供应商设置
logs/calls/<id>                   # 调用记录
logs/requests.json                # 运行日志（环形缓冲）
usage/...                         # 用量统计
```

一个账号一个状态文件，改单个账号只重写它自己的键。

Android 端凭证经 Android Keystore 加密后存入应用私有目录；命令行端以明文存放于 `--data` 目录（默认 `~/.aigw`）。

---

## 构建与发版

### 本地构建

```sh
./gradlew :app:assembleDebug --console=plain   # debug 包，包名与 release 一致
./gradlew :core:test --console=plain           # 单元测试
./gradlew :cli:installDist --console=plain     # 命令行工具
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

本地 debug 与云端 release 使用**同一套 keystore**，可互相覆盖升级，配置不丢。

### 发版：打标签即完成

版本号由标签决定，**不需要先改文件**：

```sh
git tag -a v3.0.1 -m "v3.0.1"
git push origin v3.0.1
```

工作流自动完成：

1. 把标签版本写进 `build.gradle.kts`（`versionName` 取标签，`versionCode` 自动递增）
2. 跑单元测试
3. 打签名 release 包
4. 创建 GitHub Release，上传 `synapse-<版本>-release.apk`
5. 把版本号同步回 main

标签必须是纯 `v<主>.<次>.<修订>` 三段数字，`v3.0.1-beta` 会被拒绝。

### 工作流

| 工作流 | 触发 | 作用 |
|---|---|---|
| `android-release.yml` | push `v*` 标签 / 手动 | 测试 → 打包 → 发 Release → 同步版本号 |
| `build-release.yml` | 手动 | 只测试 + 打包，产物作为 artifact |

### Secrets

需配置 4 个：

- `KEYSTORE_BASE64`：release keystore 的 base64
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

```sh
base64 -w0 release.keystore > keystore.b64
```

工作流的 build-tools 版本需与 `app/build.gradle.kts` 的 `buildToolsVersion` 一致（当前 `36.0.0`）。

---

## 命令行工具

`:cli` 无需 Android 设备，可在本机直接起网关联调：

```sh
./gradlew :cli:run --args="serve" --console=plain
```

| 命令 | 作用 |
|---|---|
| `serve` | 启动网关（默认端口 8790） |
| `providers` | 列出已注册供应商 |
| `accounts` | 查看账号 |
| `credits` | 查询额度 |
| `checkin` | 执行签到 |
| `login` | 登录账号 |
| `browser-login` | 浏览器登录 |
| `sms` | 短信登录 |
| `models` | 列出模型 |
| `usage` | 用量统计 |
| `logs` | 运行日志 |

数据存于 `--data` 目录（默认 `~/.aigw`，可用 `AIGW_DATA` 环境变量覆盖）。

---

## 贡献

1. 改动前先读 [ARCHITECTURE.md](ARCHITECTURE.md)
2. **新增供应商**三步：`core` 实现 `Provider` → `ProviderBootstrap` 登记 → `app` 实现并登记 `ProviderUi`，再在标签表加一条模型标签。账号池、换号重试、代理分流、日志、保活自动获得
3. 保持 `:core:test` 全绿

---

## 许可与声明

本项目基于 [MIT License](./LICENSE)，须保留版权声明。

LICENSE 正文后附有一份**使用声明**，其中第 3 条在 MIT 之上追加了限制（禁止商业用途、禁止二次分发牟利、禁止批量账号运营）。因此本项目**不是**纯粹的 MIT 项目 —— **MIT 条款与使用声明共同构成完整授权约定**，冲突时以更严格的一方为准。

### 使用须知

**仅供学习与研究。** 本项目用于学习 HTTP 反向代理、SSE 流式透传、多上游协议适配与 Android Compose 打包等技术主题。它不是官方产品，与腾讯、字节跳动、科大讯飞、Google 及其相关产品均无关联，未获授权或赞助。

**关于转发行为。** 本项目在你自己的设备上复用你自己的账号登录态，把请求转发到官方上游。它不破解、不绕过任何付费或权限校验，额度始终来自你账号本有的配额。但这种「以非官方客户端形态复用登录态」的转发方式**可能不符合上游服务条款**；是否使用及由此产生的后果（限流、风控、冻结、封禁等）由使用者自行承担。

**禁止用途。** 禁止商业用途、二次分发牟利、批量账号运营、绕过上游计费或配额限制，以及其它违反当地法律法规的活动。生产或商业场景请使用官方渠道与官方 API。

**凭证保管。** 账号凭证会落盘保存：Android 端经 Keystore 加密，命令行端为**明文**（`~/.aigw`）。请勿提交到公开仓库或分享给他人，因泄露造成的损失由使用者自行承担。

**无担保。** 按「现状」提供，不对可用性、稳定性、安全性作任何承诺。上游接口随时可能变更，本项目可能随时失效。完整条款见 [LICENSE](./LICENSE)。文中接口形态与协议字段来自对官方客户端公开网络通信的观察整理，相关商标权利归其各自所有者；若权利方认为存在不当之处，请联系作者调整或删除。
