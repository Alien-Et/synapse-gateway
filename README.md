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
> 改动代码前请先读 **[docs/](docs/README.md)**（按源码结构分类的 16 篇文档，末篇是总结）。**架构说明只有 docs 这一份**，不再维护第二份 ARCHITECTURE.md。

---

## 特性

**协议**
- OpenAI 兼容：`/v1/chat/completions` + `/v1/models`
- 流式 SSE / 非流式 / 多模态识图

**路由**
- 标签路由：`标签/模型名` 唯一确定一家供应商
- 无标签、标签失效、供应商未登录 —— 一律报错，不猜不兜底
- 失败只在同一家内换号重试，绝不跨供应商转移
- **国内外互通**（默认开启）：国内号都用不了时，自动顺延到国外号，反之亦然
- **标签支持英文别名**：中文是正名，`antigravity/`、`codebuddy-cn/`、`trae-global/` 等英文写法同样可用（见下文标签表）
- 调用记录标明这次走的是「路由」还是「故障转移」

**账号**
- **自动签到**：当天首次打开 App、或挂后台到点（默认 00:00 + 0–30 分钟随机）自动签一次，
  两条触发方式共用「今天已跑过」记录；**默认关**，开关在「我的 → 自动签到」
- 登录新账号后自动补一次签到，并检查任务列表（未完成只提示；「自动做任务」另有一个默认关的开关）
- 多账号按余额选号、失败自动换号
- **按账号级冷却**：某个号被上游限流只冷却它自己，不影响同家其它号
- **账号可自定义命名**：默认显示上游账号名，点账号名就能改；名字只存本机，重新登录/刷新凭证不会把它改掉
- 额度查询、额度包明细、批量签到
- 账号不可用（停用 / 耗尽）时分级提醒

**模型信息**
- 倍率、限时折扣、限免截止时间 —— 全部来自上游实时下发
- 谷歌模型级剩余额度与重置时刻
- 模型开放名单：只暴露勾选的模型
- **目录变更提示**：刷新时对比上一轮，报出云端新增 / 下架了哪些模型，并写进运行日志

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
| 腾讯 | 设备授权：浏览器打开授权页确认，网关轮询取回凭证 |
| 字节 / 商汤 | 网页登录，浏览器授权后自动回传凭证 |
| 谷歌 / ChatGPT | 浏览器 OAuth，回调到本机端口（谷歌 51121 / ChatGPT 1455） |
| Cline | 设备授权：浏览器打开授权页、输入授权码，网关自动取回凭证 |
| 讯飞 / 灵犀 | 手机号 + 短信验证码（讯飞要填国家码；灵犀带滑块人机校验） |
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

### 一句话版本

**你在模型名前面写哪家的标签，请求就只发给哪家。** 网关不猜、不兜底、不改道。

```
标签/模型名
```

### 标签表

| 标签（中文，正名） | 英文别名（同样可用） | 供应商 | 区域 |
|---|---|---|---|
| `腾讯国内/` | `codebuddy-cn/`、`workbuddy-cn/` | 腾讯 | cn |
| `腾讯国外/` | `codebuddy-global/`、`workbuddy-global/` | 腾讯 | global |
| `字节国内/` | `trae-cn/` | 字节 | cn |
| `字节国外/` | `trae-global/` | 字节 | global |
| `谷歌/` | `antigravity/`、`google/`、`gemini/` | 谷歌 | — |
| `讯飞/` | `loomy/`、`iflytek/`、`spark/` | 讯飞 | — |
| `商汤/` | `raccoon/`、`sensetime/` | 商汤 | — |
| `灵犀/` | `mcloud/`、`lingxi/` | 灵犀 | — |
| `ChatGPT/` | `chatgpt/`、`gpt/`、`openai/` | ChatGPT | — |
| `Cline/` | `cline/` | Cline | — |
| `<自定义标签>/` | — | 自定义供应商 | — |

**中文是正名，英文是别名，两种都能用。** 界面上、`/v1/models` 里只出中文 ——
英文只作为**输入兼容**存在，所以不存在「同一家有两个官方名字」的困惑。

英文别名**忽略大小写**（`ChatGPT` / `chatgpt` / `CHATGPT` 等价）。

腾讯、字节因为分国内外两个站点，标签**必须带区域**：裸 `腾讯/`、`字节/`、`codebuddy/`、
`trae/` 都解析不出区域，一律拒绝 —— 猜一个站等于替你做决定。

Cline 的两个额度池（免费 / 订阅）挂在**同一个账号**上，不是区域，所以只有一个标签；
池由模型 id 的前缀区分（见下文「内置供应商」）。

### 判定流程

```
resolveRoute(model)
  ├─ 剥掉 "models/" 前缀
  ├─ 没有 "/"                       → 报错
  ├─ 标签不在表中                   → 报错
  ├─ 供应商未注册 / 未启用 / 无账号 → 报错
  └─ 全部通过                       → Route(供应商, 模型, 区域)
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

---

## 出故障了会怎样

这是本网关最核心的能力，值得单独讲清楚。

### 三层防线

请求失败时，网关按顺序尝试下面三层，**任何一层成功了就立刻返回**：

```
① 换号
   同一个区域里，换下一个账号重试
   ↓ 这个区域的所有号都试过了
② 跨区域（可选，默认开启）
   换到同一家供应商的另一个区域
   ↓ 两个区域都没号能用
③ 放弃，如实报错
   绝不改发别家供应商
```

### 第一层：同区域换号

上游的限流、额度耗尽、凭证失效，绝大多数是**按账号**计算的。所以同一个模型换个号往往立刻能用。

```
请求 腾讯国内/glm-5.2
  → 账号 A 报「该模型已超限」
  → 立刻换账号 B，成功 ✓
```

**关键：出问题的账号会被单独冷却**，不影响同家的其它号。

| 情况 | 网关怎么做 |
|---|---|
| 某个号撞了「模型已超限」 | 只冷却**这个号**的这个模型，换下一个号 |
| 某个号额度耗尽 | 把它标记为 0，换下一个号 |
| 某个号凭证失效 | 停用这个号，换下一个号 |
| 某个号连续失败多次 | 停用这个号（熔断），换下一个号 |

**供应商熔断不等于账号熔断。** 只有「整轮所有号都失败」才会给供应商记一次失败——某个号反复出错不会连累整家。

### 第二层：跨区域（国内外互通）

腾讯和字节各有「国内站」和「国外站」，分别是一整池账号。它们其实是同一家供应商、同样的模型、只是接入点不同。

**默认开启「国内外互通」**（设置 → 故障转移）：

```
请求 腾讯国内/glm-5.2
  → 国内号 A、B 都用不了
  → 自动顺延到国外号 C，成功 ✓
```

**优先级严格保证**：先在本区域找，本区域**一个可用账号都没有**时才跨区。绝不会因为国外号余额更高就先跑国外。

关掉这个开关后，区域就是硬约束：`腾讯国内/` 只可能用国内号，用不了就报错。

### 第三层：放弃并如实报错

两个区域都没号可用了，网关会返回明确的错误，并**列出具体是哪些账号、什么原因**：

```
该模型在所有候选账号上都不可用；codebuddy(上游限流)
｜涉及账号：账号A：该模型使用频率超限，约 19:26 后重置；
          账号B：该模型使用频率超限，约 15:28 后重置
```

**绝不改发别家供应商** —— 写 `腾讯国内/` 的请求永远只可能去腾讯。

### 在调用记录里看发生了什么

「记录 → 调用记录」每张卡片会标明这次请求的路径：

| 卡片显示 | 含义 |
|---|---|
| `[路由] [● 账号A]` | 一次就成，正常路由 |
| `[故障转移] [● 账号A]` + 下方 `[故障] [● 账号B]` | 账号 B 失败后换到账号 A |
| `[失败]` + 下方多个 `[故障] [● 账号]` | 所有账号都试过，全都不行 |

**「路由」和「故障转移」按区域判定，不看换了几个号：**

| 请求 | 最终用的账号 | 标签 |
|---|---|---|
| `腾讯国内/x` | 国内号 | 路由（哪怕中途换过几个国内号） |
| `腾讯国内/x` | 国外号 | **故障转移** |
| `腾讯国外/x` | 国外号 | 路由 |
| `腾讯国外/x` | 国内号 | **故障转移** |

失败时上方不显示路由/故障转移 —— 因为「全都不行」时没有「最终账号」可言，所有试过的账号统一在下方以 `[故障] [账号]` 列出。

### 可以调什么

「我的 → 故障转移」里可以调整：

| 参数 | 作用 |
|---|---|
| 国内外互通 | 上面说的跨区域开关，默认开 |
| 最大尝试次数 | 一次请求最多试几个号 |
| 初始退避 / 退避上限 / 倍率 / 抖动 | 换号之间的等待策略 |
| 并发上限 | 单账号在途上限、全局在途上限 |
| 熔断阈值 / 静默时长 / 半开成功数 | 供应商级熔断 |
| 模型级冷却时长 | 撞了「该模型已超限」后冷却多久 |
| 模型禁用阈值 / 时长 | 冷却解封后当天仍连续失败几次就整家停用该模型，停用多久（默认 3 次 / 24 小时，可改成 1–20 次、1 小时–30 天） |

全部参数保存后立即生效，无需重启网关。

### 模型禁用与冷却的区别

两套机制不要混：**冷却**是撞一次限流就歇一会儿，只影响单个账号的那个模型；
**禁用**是同一个模型反复失败后的「认输并记住」，按供应商+模型生效、会落盘。

```
请求 腾讯国内/glm-5.2
  → 账号 A 撞限流：只冷却 A 的这个模型（10 分钟），换 B 继续
  → 冷却解封后当天仍然连续失败 3 次
  → 该模型整家禁用 24 小时（时长可在「我的 → 并发与容错」改）
  → 禁用期间再点名它，直接跳过走故障转移，不再白打上游
```

禁用成功后发横幅 + 常驻通知，只提醒一次；日志卡片上标红色「禁用」。

### 中文标签的限制（已内置英文别名）

少数客户端会把 `model` 放进 HTTP 头（头只允许 ASCII），中文标签在这类客户端上会失败。
**现在直接写英文别名即可**（见上面标签表第二列），不必再建一个纯英文标签的自定义供应商：

```
antigravity/gemini-3.8          →  谷歌/gemini-3.8
codebuddy-cn/glm-5.2            →  腾讯国内/glm-5.2
trae-global/qwen-max            →  字节国外/qwen-max
```

---

## 内置供应商

| id | 名称 | 上游 | 说明 |
|---|---|---|---|
| `codebuddy` | 腾讯 | 国内 `copilot.tencent.com` / 国外 `workbuddy.ai` | 上游拒绝非流式，网关强制 `stream:true` |
| `trae` | 字节 | 国内 `trae.cn` / 国外 `trae.ai` | SOLO 私有通道，协议转换最重 |
| `antigravity` | 谷歌 | `cloudcode-pa.googleapis.com` | Gemini 风格协议 |
| `loomy` | 讯飞 | `xfinfr.com` | 鉴权失败返回 HTTP 200 + 业务码 |
| `raccoon` | 商汤 | `xiaohuanxiong.com` | OpenAI 结构包在 `data` 里 |
| `mcloud` | 灵犀 | `ai.yun.139.com` / `orches.yun.139.com` | 请求体加密；短信登录带滑块人机校验 |
| `chatgpt` | ChatGPT | Codex 订阅后端 | Responses API，不是 Chat Completions |
| `cline` | Cline | `api.cline.bot` | 免费池 / 订阅池共用一份凭证 |

**各家的坑：**

- **腾讯** — 强制流式；签到仅国内账号
- **字节** — SOLO 私有通道；签到仅国内账号
- **谷歌** — 凭证有铁律（不清空 access_token、不改写 refresh_token，见 [docs/08](docs/08.上游陷阱清单.md)）；需人机验证时返回 403 `VALIDATION_REQUIRED`
- **讯飞** — 鉴权失败是 HTTP 200 + 业务码，不判业务码会把错误 JSON 当正常回复透传
- **商汤** — 响应外层是 `{"status":..., "data":...}`；字段用标准名（不认 `max_new_tokens`）
- **Cline** — 免费池靠 `X-CLIENT-TYPE: cline-sdk` 这道产品面校验（缺了 403、写成 `cline-cli` 会 500）；令牌带 `workos:` 前缀，去掉就 401；`cline-pass/` 要订阅，没订阅的账号会 403 `ENTITLEMENT_ERROR`

---

## 自定义供应商

「供应商」页 → 「自定义供应商」，填三项：

- **模型标签** — 调用时写成 `标签/模型名`
- **Base URL** — 如 `https://api.example.com/v1`。尾部已经写了 `/v1`（或智谱 `/api/paas/v4`、百炼 `/compatible-mode/v1`）时不会再重复拼一遍，也不会把你写的版本段剥掉
- **协议** — 决定对话地址、鉴权和请求体形状。拉模型与发对话用同一套路径；对不上时上游会回「接口不存在」，不再误报成「没有这个模型」
- **流式** — 可单独打开「总是向上游要流式」。有些中转站和 Codex 类后端只接受 `stream: true`，关着会直接 400；打开后客户端要非流式时，由网关在本地聚合成普通 JSON
- **API Key** — 一个 key 对应一个账号。已保存的供应商里可**统一查看所有 Key**：默认遮住，点「显示」看明文、点 Key 复制单个、或一次复制全部（一行一个）

协议可选 **OpenAI 兼容**（`messages`）与 **OpenAI Responses**（`input`，`/v1/responses`）。
后者是 OpenAI 新版端点，响应是它自己的一套 SSE 事件，网关会翻译回客户端认识的
`chat.completion.chunk`，客户端不用改任何配置。

保存后按所选协议拉取模型列表，模型出现在模型页。

讯飞的新手任务、腾讯国内的任务中心：这个账号的任务全部做完（腾讯要领完奖励）后，对应入口自动收起；没做完的会一直留着。两家各看各的清单，互不影响。签到不受影响。

讯飞的「兑换邀请码 / 兑换码」是一份共享表单：多账号时用下拉栏选兑换到哪个账号，不再每个账号卡片各摆一套输入框。

### 标签规则

| 规则 | 说明 |
|---|---|
| 不能为空 | 空标签无法路由 |
| 不能含斜杠或空白，≤ 32 字符 | 它本身就是 `标签/模型名` 的前半段 |
| 不能与内置标签重名 | `腾讯国内`、`字节国外`、`谷歌` 等已被占用（**英文别名也算占用**：`google`、`antigravity`、`trae-cn` 同样不能用） |
| 不能与其它自定义供应商重名 | 一个标签只能绑定一家 |

重名会被表单和核心层双重拦截 —— 否则标签会解析到内置供应商，配起来像是能通，实际必然失败。

### 自定义请求头

部分中转站除了 `Bearer` Key 还要求额外的头（租户号、渠道号、`User-Agent` 等），不配就 401/403。
在供应商详情页的「自定义请求头」里一行一条填写，格式 `名字: 值`：

```
User-Agent: MyClient/1.0
X-Tenant: abc123
```

`Authorization` 默认由 API Key 自动生成；在这里写同名的一条会**覆盖**它（适用于要求 `x-api-key` 或裸 Key 的站点）。
只发一条、不拼接 —— 两个 Auth 头同发，服务端多半直接 400。

头名不合法（含空格等）或值里带换行的行会被忽略，界面当场提示哪几行不会发送。

### 余额查询

OpenAI 协议没有余额接口，所以余额要在供应商详情页手填一条查询：完整地址（可选）+ 相对路径 + 取值字段。

| 字段 | 说明 |
|---|---|
| 完整地址 | 填了就优先用它 |
| 相对路径 | 留空且无完整地址时，直接查 baseUrl 本身 |
| 取哪个字段 | 点分写法，支持数组下标：`data.quota`、`data.accounts.0.remain` |
| 倍数 / 除以 | 上游给最小单位时换算，如 `quota=500000` 想显示 1 元就填 500000 并勾「除以」 |
| 单位 | 显示用，如 `点`、`元`，可留空 |

点「测试余额」可以直接用当前表单值试一次，不用先保存。取不到字段时报 `known=false`（界面显示「—」），
不会显示成 0 —— 0 会让人以为额度真的用完了。

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/chat/completions` | 对话补全（流式 / 非流式 / 多模态） |
| `GET` | `/v1/models` | 模型列表（按开放名单过滤） |
| `GET` | `/v1/credits` | 额度查询（别名 `/credits`） |
| `GET` | `/v1/dashboard/billing/subscription` | 余额（OpenAI 账单格式，`hard_limit_usd`） |
| `GET` | `/v1/dashboard/billing/credit_grants` | 余额（OpenAI 格式，`total_available`） |
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

### 模型测试按端点分流

「测试」按钮会按模型类型打对应端点，不再一律发对话请求：

| 模型类型 | 端点 |
|---|---|
| 对话 | `/v1/chat/completions` |
| 图片生成 | `/v1/images/generations` |
| 视频生成 | `/v1/videos/generations` |
| 文本向量 | `/v1/embeddings` |
| 重排 | `/v1/rerank` |
| 语音合成 | `/v1/audio/speech` |

类型**自动识别**，不靠硬编码名单：先看上游给的 `tags`/`category`/`ability`/`endpoint`
字段，再看模型 id 词根（`embed`、`rerank`、`whisper`、`tts`、`image`、`veo` 等）。
识别时识图模型（`vision`/`vl`/`vlm`）会先被排掉，不会被 `image` 兜底词根误判成生图。

语音转写要上传音频，无法用轻量探针测，界面会直接说明而不是给一个假的失败。

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

### 界面文案里不出现英文原文

网络异常一律翻成人话，原始报错只进日志：

```
界面上：连接「xiaohuanxiong.com」超时：上游在规定时间内没有响应。
        请检查网络，境外供应商可能需要开启代理

日志里：连接「xiaohuanxiong.com」超时：… ← failed to connect to
        xiaohuanxiong.com/180.184.181.237 (port 443) after 30000ms: ETIMEDOUT
```

覆盖超时、连接被拒、网络不可达、DNS 解析失败、TLS 握手失败、连接中断六类。
已经是中文可读文案的异常（如「登录状态已失效」）直接原样显示，不再套一层壳。

### 上游错误也带中文语义

有些失败不是网络问题，而是上游用英文如实告诉你账号状态（余额不足、没订阅这个模型）。
这类由各 Provider 的 `classify` 补一句中文结论，**中文在前、上游原文在后**：

```
Cline 账户余额不足（已欠费或额度用完），当前余额 -0.002877 credit；
充值地址 https://app.cline.bot/credits
｜上游原文：Insufficient balance. Your Cline Credits balance is $-0.00
```

原文必须保留 —— 排查时它是唯一能搜的线索。上游文案本身已是中文时不再套壳。

### 提示统一走系统接口

短句用系统 Toast，长句用系统对话框（跟随系统深浅色），不用底部 Snackbar —— 后者会压在内容底部盖住按钮。

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
                    ④ 选号（跳过已冷却 / 已停用的号）
                    ⑤ 失败换号 → 本区域试完才跨区域 → 仍不行就如实报错
                       全程不跨供应商
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
| `trae/` | 字节 | 15 |
| `loomy/` | 讯飞 | 14 |
| `mcloud/` | 灵犀 | 12 |
| `chatgpt/` | ChatGPT | 5 |
| `codebuddy/` | 腾讯 | 3 |
| `cline/` | Cline | 3 |
| `antigravity/` | 谷歌 | 2 |
| `raccoon/` | 商汤 | 1 |
| `custom/` | 自定义 | 1 |

`provider/` 顶层还有 13 个公共文件：`Provider` 接口、`RoutedModel` / `ProviderModel` 数据模型、
端点目录、`ChatCall`、SSE 逐行转换工具、错误分类等。

每个 Provider 实现 `Provider` 接口：`listModels()` / `resolveModel()` / `openChat()` / `creditInfo()` / `performAction()` / `refreshAccount()`。

---

## 数据存储

账号凭证与状态按**账号独立存储**：

```
account/<供应商>/<账号>              # 凭证
pool/state/<供应商>/<账号>.json      # 状态（额度 / 启用 / 禁用原因）
settings/gateway.json              # 网关设置
settings/provider/<id>.json        # 供应商设置
settings/proxy.json                # 代理设置
settings/custom.json               # 自定义供应商配置
settings/model_exposure.json       # 模型开放名单
logs/calls/<id>                    # 调用记录
logs/requests.json                 # 运行日志（环形缓冲）
usage/...                          # 用量统计
```

一个账号一个状态文件，改单个账号只重写它自己的键。

调用记录里**超出摘要的完整原文不在加密存储里**，而是溢出到应用缓存目录
（单字段 ≤512K 字符、总量 ≤64MB）；缓存被系统回收或记录被清理后，详情页回落展示摘要。

### 账号导入导出

导出是**快照**，导入按快照原样恢复（`.agw` 文件，口令可选）。

| 内容 | 是否随文件走 |
|---|---|
| 凭证 | ✅ |
| 手动启用/停用、使用优先级 | ✅ |
| 自定义账号名 | ✅（v4 起；v3 老文件按「未起名」恢复） |
| 自定义供应商的标签/地址/协议/请求头/余额配置 | ✅ |
| 运行时探测出的额度、凭证失效状态 | ❌（导入后重新探，存下来只会过期） |

文件格式只认 v3 与 v4：v1/v2 缺启用开关与优先级，补默认值会悄悄改掉你的策略，
所以直接报错让你重新导出一份；v3 只缺自定义名，缺失含义唯一，继续可读。

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
# 标签按**仓库已有序列**递增，打之前先查一遍：
#   git ls-remote --tags origin
git tag -a v3.0.9 -m "v3.0.9"
git push origin v3.0.9
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
| `help` | 打印命令帮助（等价 `--help`） |

数据存于 `--data` 目录（默认 `~/.aigw`，可用 `AIGW_DATA` 环境变量覆盖）。

---

## 贡献

1. 改动前先读 [docs/](docs/README.md)（架构说明只有这一份）
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
