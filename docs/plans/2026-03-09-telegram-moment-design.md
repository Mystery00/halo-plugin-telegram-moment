# Telegram Moment 插件设计文档

**日期**：2026-03-09
**状态**：已确认，待实施

---

## 一、背景与目标

开发一个 Halo 2.x 插件，启动 Telegram Bot，监听指定 Telegram Channel（及可选的私聊消息），将消息内容（文字、图片组、GIF、贴纸）自动发布为 Halo 瞬间（Moment）。支持消息编辑同步更新、删除同步删除，以及频道内 `/rm`、`/redo` 指令。

---

## 二、整体架构

### 包结构

```
vip.mystery0.halo.telegrammoment/
├── TelegramMomentPlugin.java        # 插件生命周期入口
├── config/
│   └── TelegramSetting.java         # Setting 配置 POJO
├── bot/
│   ├── TelegramBotService.java      # Bot 生命周期管理（start/stop/restart）
│   └── TelegramUpdateHandler.java   # Update 路由分发
├── handler/
│   ├── MessageHandler.java          # 消息处理主逻辑
│   └── MediaGroupAggregator.java    # 媒体组聚合
├── publisher/
│   ├── MomentPublisher.java         # 操作 Moment CRD
│   └── AttachmentUploader.java      # 下载 Telegram 文件并上传到 Halo
└── model/
    └── PendingMediaGroup.java       # 内存媒体组聚合状态
```

### 依赖关系

```
TelegramMomentPlugin
    └── TelegramBotService
            └── TelegramUpdateHandler
                    └── MessageHandler
                            ├── MediaGroupAggregator
                            ├── AttachmentUploader
                            └── MomentPublisher
```

### 关键技术选型

- **Telegram SDK**：`telegrambots-longpolling`（独立线程池）
- **Halo Moment 操作**：`ReactiveExtensionClient` + `.subscribeOn(Schedulers.boundedElastic()).block()`
- **附件上传**：Halo 内部 `AttachmentService`（或本地 WebClient 调用内部端点）
- **媒体组聚合**：`ScheduledExecutorService`，每 5 秒扫描超时组
- **架构模式**：阻塞式桥接（Telegram 回调线程直接 block() 调用响应式 API）

---

## 三、配置设计

### Setting YAML

```yaml
apiVersion: v1alpha1
kind: Setting
metadata:
  name: telegram-moment-setting
spec:
  forms:
    - group: bot
      label: Bot 配置
      formSchema:
        - $formkit: text
          name: botToken
          label: Bot Token
          required: true
        - $formkit: text
          name: apiEndpoint
          label: API Endpoint（留空使用官方）
    - group: channel
      label: 频道配置
      formSchema:
        - $formkit: checkbox
          name: channelEnabled
          label: 启用频道消息
          value: true
        - $formkit: text
          name: channelId
          label: 频道 ID（留空监听所有）
        - $formkit: text
          name: channelFilter
          label: 屏蔽标签（逗号分隔，含该 # 标签的消息跳过）
    - group: private
      label: 私聊配置
      formSchema:
        - $formkit: checkbox
          name: privateEnabled
          label: 启用私聊消息
          value: false
        - $formkit: text
          name: privateSenderId
          label: 发送者 ID（留空不限制）
    - group: storage
      label: 附件配置
      formSchema:
        - $formkit: text
          name: storagePolicy
          label: 存储策略名称
          required: true
        - $formkit: text
          name: storageGroup
          label: 附件分组名称
        - $formkit: number
          name: mediaDelaySeconds
          label: 媒体组聚合延迟（秒）
          value: 3
```

### TelegramSetting 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `botToken` | String | Telegram Bot API Token，必填 |
| `apiEndpoint` | String | 自定义 API Endpoint，留空使用官方 |
| `channelEnabled` | boolean | 是否启用频道消息监听 |
| `channelId` | String | 指定频道 ID，留空不过滤 |
| `channelFilter` | String | 屏蔽含指定 # 标签的消息，逗号分隔 |
| `privateEnabled` | boolean | 是否启用私聊消息监听 |
| `privateSenderId` | String | 指定私聊发送者 ID，留空不过滤 |
| `storagePolicy` | String | Halo 附件存储策略名称 |
| `storageGroup` | String | Halo 附件分组名称 |
| `mediaDelaySeconds` | int | 媒体组聚合等待时间（秒），默认 3 |

---

## 四、Moment CRD 映射

| CRD 字段 | 来源 |
|----------|------|
| `metadata.name` | 随机生成（`moment-{uuid}`） |
| `metadata.annotations["telegram.moment/messageId"]` | Telegram 消息 ID |
| `metadata.annotations["telegram.moment/chatId"]` | Telegram Chat ID |
| `metadata.annotations["telegram.moment/attachmentNames"]` | Halo 附件 name 列表（JSON 数组） |
| `spec.content.raw` | 原始文本 |
| `spec.content.html` | 格式化 HTML（Entity 转换后） |
| `spec.releaseTime` | 消息发送时间 |
| `spec.tags` | 从 `#hashtag` 提取的标签列表 |
| `spec.visible` | 固定 `PUBLIC` |

---

## 五、核心数据流

### 5.1 消息路由

```
onUpdateReceived(update)
    ├─ channel_post
    │       ├─ text == "/rm"   → handleDelete(message)
    │       ├─ text == "/redo" → handlePublish(message.replyTo)
    │       └─ 其他            → handleMessage(isChannel=true, isEdit=false)
    ├─ edited_channel_post     → handleMessage(isChannel=true, isEdit=true)
    ├─ message                 → handleMessage(isChannel=false, isEdit=false)
    └─ edited_message          → handleMessage(isChannel=false, isEdit=true)
```

### 5.2 消息处理流

```
handleMessage(message, isEdit)
    ├─ checkEnabled()（频道 ID / 私聊发送者过滤）
    ├─ 媒体类型分支：
    │       ├─ Photo + AlbumID → MediaGroupAggregator.add() → 返回
    │       ├─ Photo（单图）  → 下载 → buildPost()
    │       ├─ Animation(GIF) → 下载 → buildPost()
    │       ├─ Sticker        → 下载 → buildPost()
    │       ├─ Text           → buildPost()
    │       └─ 其他           → 跳过
    └─ isEdit → update() / publish()
```

### 5.3 媒体组聚合

```
ScheduledExecutorService（每 5 秒）
    └─ 遍历 ConcurrentHashMap<albumId, PendingMediaGroup>
            └─ now >= expectedExecuteTime
                    ├─ 批量下载所有图片
                    ├─ MomentPublisher.publish()
                    └─ 从 Map 移除
```

每次新消息加入时刷新 `expectedExecuteTime = now + delaySeconds`。

### 5.4 Moment 操作流

**发布：**
```
publish(post, message)
    ├─ AttachmentUploader.upload(files) → [attachmentName, permalink, mimeType]
    ├─ buildMoment()（Entity → HTML，提取 tags）
    └─ ReactiveExtensionClient.create(moment).block()
```

**更新：**
```
update(post, message)
    ├─ 按 annotations[messageId+chatId] 查询 Moment
    ├─ 删除旧附件 → 上传新附件
    └─ ReactiveExtensionClient.update(moment).block()
```

**删除：**
```
delete(message, replyTo)
    ├─ 按 annotations[messageId+chatId] 查询 Moment
    ├─ 读取 annotations[attachmentNames] → 批量删除附件
    └─ ReactiveExtensionClient.delete(moment).block()
```

### 5.5 Entity → HTML 转换规则

| EntityType | 输出 |
|---|---|
| `hashtag` | `<a class="tag" href="?tag=xxx">#xxx</a>`，提取到 tags |
| `bold` | `<strong>…</strong>` |
| `italic` | `<em>…</em>` |
| `underline` | `<u>…</u>` |
| `strikethrough` | `<s>…</s>` |
| `code` | `<code>…</code>` |
| `pre` | `<pre><code>…</code></pre>` |
| `text_link` | `<a href="url">…</a>` |
| 换行 | 逐行包裹 `<p>…</p>` |

---

## 六、Bot 生命周期

```
Plugin.start()  → TelegramBotService.startBot()
                        ├─ 读取 Setting
                        ├─ 校验 botToken（空则 WARN，跳过）
                        ├─ 创建 TelegramBotsLongPollingApplication
                        ├─ 注册 TelegramUpdateHandler
                        └─ 启动 MediaGroupAggregator

Plugin.stop()   → TelegramBotService.stopBot()
                        ├─ Application.stop()
                        ├─ MediaGroupAggregator.shutdown()
                        └─ 清空 pendingGroups

POST /apis/telegram-moment/v1alpha1/bot/restart
                → stopBot() → startBot()

GET  /apis/telegram-moment/v1alpha1/bot/status
                → { "running": true/false, "username": "@bot_name" }
```

---

## 七、错误处理策略

| 场景 | 策略 |
|------|------|
| botToken 未配置 | WARN + 跳过启动，不影响插件加载 |
| Telegram 连接失败 | ERROR + Bot 停止，不影响 Halo 主进程 |
| 附件下载失败 | ERROR + 跳过该附件，继续发布其余内容 |
| 附件上传轮询超时（10s）| ERROR + 跳过该附件 |
| Moment 创建/更新失败 | ERROR + 记录完整消息内容，不重试 |
| Moment 查找不存在（edit/delete）| WARN + 跳过操作 |
| 媒体组处理异常 | try-catch + ERROR + 从 pendingGroups 移除 |

---

## 八、前端界面

**路由**：`/telegram-moment`，菜单名"Telegram 配置"

**页面组成（从上到下）：**
1. **Bot 状态横幅**：Running（绿）/ Stopped（红）+ Bot 用户名
2. **提示横幅**（配置保存后显示）："配置已保存，请点击「重启 Bot」使其生效"
3. **操作按钮**："重启 Bot"（调用 restart 接口）
4. **Setting 表单**：使用 Halo `PluginSetting` 组件渲染，含保存按钮

---

## 九、build.gradle 新增依赖

```groovy
// Telegram Bot SDK
implementation 'org.telegram:telegrambots-longpolling:7.x'
implementation 'org.telegram:telegrambots-client:7.x'
// JSON 处理（AttachmentNames 序列化）
implementation 'com.fasterxml.jackson.core:jackson-databind'  // 通过 BOM 管理版本
```
