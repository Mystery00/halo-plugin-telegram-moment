# Telegram Moment 插件实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 开发一个 Halo 2.x 插件，启动 Telegram Long Polling Bot，将指定频道/私聊的消息（文字、图片组、GIF、贴纸）自动发布为 Halo 瞬间，并支持编辑同步更新、删除同步删除以及 `/rm`、`/redo` 指令。

**Architecture:** Telegram Bot 运行于独立线程池（telegrambots-longpolling），回调中通过 `Schedulers.boundedElastic().block()` 调用 Halo 的 `ReactiveExtensionClient` 操作 Moment CRD；附件上传通过 WebClient 调用 Halo 内部 API；媒体组聚合使用 `ScheduledExecutorService` 每 5 秒扫描超时组。

**Tech Stack:** Java 21, Spring Boot (WebFlux), PF4J (Halo Plugin), telegrambots-longpolling 7.x, ReactiveExtensionClient, Halo 2.22

**前置条件：** Halo 已安装 `PluginMoment`（moment.halo.run CRD 提供方）插件。

---

## Task 1: 项目依赖配置与 Halo 资源文件

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/plugin.yaml`
- Create: `src/main/resources/extensions/setting.yaml`

### Step 1: 更新 build.gradle，添加 Telegram SDK 依赖

将 `build.gradle` 中的 `dependencies` 块替换为：

```groovy
dependencies {
    implementation platform('run.halo.tools.platform:plugin:2.22.0')
    compileOnly 'run.halo.app:api'

    // Telegram Bot SDK
    implementation 'org.telegram:telegrambots-longpolling:7.12.0'
    implementation 'org.telegram:telegrambots-client:7.12.0'

    testImplementation 'run.halo.app:api'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

> 注意：`jackson-databind` 已由 BOM 管理，无需单独声明。

### Step 2: 更新 plugin.yaml

```yaml
apiVersion: plugin.halo.run/v1alpha1
kind: Plugin
metadata:
  name: telegram-moment
spec:
  enabled: true
  requires: ">=2.22.0"
  author:
    name: Mystery0
    website: https://github.com/Mystery0
  logo: logo.png
  homepage: https://github.com/Mystery0/telegram-moment#readme
  repo: https://github.com/Mystery0/telegram-moment
  issues: https://github.com/Mystery0/telegram-moment/issues
  displayName: "Telegram Moment"
  description: "将 Telegram 频道/私聊消息自动发布为 Halo 瞬间"
  license:
    - name: "GPL-3.0"
      url: "https://github.com/Mystery0/telegram-moment/blob/main/LICENSE"
```

### Step 3: 创建 Setting 资源文件

```yaml
# src/main/resources/extensions/setting.yaml
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
          label: API Endpoint（留空使用官方 https://api.telegram.org）
    - group: channel
      label: 频道配置
      formSchema:
        - $formkit: checkbox
          name: channelEnabled
          label: 启用频道消息监听
          value: true
        - $formkit: text
          name: channelId
          label: 频道 Chat ID（留空监听所有频道）
        - $formkit: text
          name: channelFilter
          label: 屏蔽标签（逗号分隔的 hashtag，含该标签的消息将被跳过）
    - group: private
      label: 私聊配置
      formSchema:
        - $formkit: checkbox
          name: privateEnabled
          label: 启用私聊消息监听
          value: false
        - $formkit: text
          name: privateSenderId
          label: 允许的发送者 Chat ID（留空不限制）
    - group: storage
      label: 附件存储
      formSchema:
        - $formkit: text
          name: storagePolicy
          label: 存储策略名称
          required: true
        - $formkit: text
          name: storageGroup
          label: 附件分组名称（留空使用默认）
        - $formkit: number
          name: mediaDelaySeconds
          label: 媒体组聚合延迟秒数（默认 3）
          value: 3
```

### Step 4: 验证构建通过

```bash
cd /Users/mystery0/IdeaProjects/halo-plugin-telegram-moment
./gradlew compileJava
```

预期：BUILD SUCCESSFUL

### Step 5: Commit

```bash
git add build.gradle src/main/resources/plugin.yaml src/main/resources/extensions/setting.yaml
git commit -m "feat: 添加 Telegram SDK 依赖与插件 Setting 配置"
```

---

## Task 2: TelegramSetting 配置 POJO

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/config/TelegramSetting.java`
- Create: `src/test/java/vip/mystery0/halo/telegrammoment/config/TelegramSettingTest.java`

### Step 1: 创建 TelegramSetting.java

```java
package vip.mystery0.halo.telegrammoment.config;

import lombok.Data;

@Data
public class TelegramSetting {

    // --- bot group ---
    private String botToken = "";
    private String apiEndpoint = "";

    // --- channel group ---
    private boolean channelEnabled = true;
    private String channelId = "";
    private String channelFilter = "";

    // --- private group ---
    private boolean privateEnabled = false;
    private String privateSenderId = "";

    // --- storage group ---
    private String storagePolicy = "";
    private String storageGroup = "";
    private int mediaDelaySeconds = 3;

    public boolean hasValidToken() {
        return botToken != null && !botToken.isBlank();
    }

    /** 将逗号分隔的 channelFilter 解析为 List */
    public java.util.List<String> getChannelFilterList() {
        if (channelFilter == null || channelFilter.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(channelFilter.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
```

### Step 2: 编写测试

```java
package vip.mystery0.halo.telegrammoment.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TelegramSettingTest {

    @Test
    void defaultValues() {
        TelegramSetting s = new TelegramSetting();
        assertThat(s.isChannelEnabled()).isTrue();
        assertThat(s.isPrivateEnabled()).isFalse();
        assertThat(s.getMediaDelaySeconds()).isEqualTo(3);
        assertThat(s.hasValidToken()).isFalse();
    }

    @Test
    void hasValidToken_whenBlank_returnsFalse() {
        TelegramSetting s = new TelegramSetting();
        s.setBotToken("  ");
        assertThat(s.hasValidToken()).isFalse();
    }

    @Test
    void hasValidToken_whenSet_returnsTrue() {
        TelegramSetting s = new TelegramSetting();
        s.setBotToken("123456:ABC");
        assertThat(s.hasValidToken()).isTrue();
    }

    @Test
    void getChannelFilterList_parsesCommaList() {
        TelegramSetting s = new TelegramSetting();
        s.setChannelFilter("dev, test, ad ");
        assertThat(s.getChannelFilterList()).containsExactly("dev", "test", "ad");
    }

    @Test
    void getChannelFilterList_emptyString_returnsEmpty() {
        TelegramSetting s = new TelegramSetting();
        assertThat(s.getChannelFilterList()).isEmpty();
    }
}
```

### Step 3: 运行测试

```bash
./gradlew test --tests "vip.mystery0.halo.telegrammoment.config.TelegramSettingTest"
```

预期：5 tests passed

### Step 4: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/config/TelegramSetting.java \
        src/test/java/vip/mystery0/halo/telegrammoment/config/TelegramSettingTest.java
git commit -m "feat: 添加 TelegramSetting 配置 POJO"
```

---

## Task 3: Moment 本地扩展模型

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/model/Moment.java`

Moment 是 `moment.halo.run` 插件提供的 CRD，本插件通过本地定义同 GVK 的类来操作它，无需引入外部插件依赖。

### Step 1: 创建 Moment.java

```java
package vip.mystery0.halo.telegrammoment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "moment.halo.run",
        version = "v1alpha1",
        kind = "Moment",
        plural = "moments",
        singular = "moment")
public class Moment extends AbstractExtension {

    @JsonProperty("spec")
    private MomentSpec spec = new MomentSpec();

    @Data
    public static class MomentSpec {
        private Content content = new Content();
        /** ISO 8601 格式，例如 "2024-01-01T00:00:00.000Z" */
        private String releaseTime;
        private List<String> tags = new ArrayList<>();
        private String visible = "PUBLIC";
        private String owner = "";
    }

    @Data
    public static class Content {
        private String raw = "";
        private String html = "";
        private List<MediumItem> medium = new ArrayList<>();
    }

    @Data
    public static class MediumItem {
        /** "PHOTO" 或 "VIDEO" */
        private String type;
        /** Halo 附件的 permalink URL */
        private String url;
        /** MIME 类型，如 "image/jpeg" */
        @JsonProperty("originType")
        private String originType;
    }

    /** 注解键常量 */
    public static final String ANNOTATION_MESSAGE_ID = "messageId";
    public static final String ANNOTATION_CHAT_ID = "chatId";
    public static final String ANNOTATION_ATTACHMENT_NAMES = "attachmentNames";
}
```

### Step 2: 验证编译

```bash
./gradlew compileJava
```

预期：BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/model/Moment.java
git commit -m "feat: 定义本地 Moment CRD 扩展模型"
```

---

## Task 4: ContentBuilder（Telegram Entity → HTML 转换）

这是插件中逻辑最复杂、最值得 TDD 的纯函数模块。

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/publisher/ContentBuilder.java`
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/publisher/ContentResult.java`
- Create: `src/test/java/vip/mystery0/halo/telegrammoment/publisher/ContentBuilderTest.java`

### Step 1: 创建 ContentResult.java

```java
package vip.mystery0.halo.telegrammoment.publisher;

import lombok.Data;
import java.util.List;

/** build() 的返回结果 */
@Data
public class ContentResult {
    private final String html;
    private final List<String> tags;
}
```

### Step 2: 编写失败测试

```java
package vip.mystery0.halo.telegrammoment.publisher;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ContentBuilderTest {

    @Test
    void plainText_wrapsEachLineInParagraph() {
        ContentResult r = ContentBuilder.build("Hello\nWorld", List.of());
        assertThat(r.getHtml()).isEqualTo("<p>Hello</p><p>World</p>");
        assertThat(r.getTags()).isEmpty();
    }

    @Test
    void hashtag_extractedToTagsAndRenderedAsLink() {
        MessageEntity entity = new MessageEntity();
        entity.setType("hashtag");
        entity.setOffset(0);
        entity.setLength(5); // "#java"
        ContentResult r = ContentBuilder.build("#java 示例", List.of(entity));
        assertThat(r.getTags()).containsExactly("java");
        assertThat(r.getHtml()).contains("<a class=\"tag\"");
        assertThat(r.getHtml()).contains("#java");
    }

    @Test
    void boldEntity_wrappedInStrong() {
        MessageEntity entity = new MessageEntity();
        entity.setType("bold");
        entity.setOffset(0);
        entity.setLength(5); // "Hello"
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.getHtml()).contains("<strong>Hello</strong>");
    }

    @Test
    void italicEntity_wrappedInEm() {
        MessageEntity entity = new MessageEntity();
        entity.setType("italic");
        entity.setOffset(0);
        entity.setLength(5);
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.getHtml()).contains("<em>Hello</em>");
    }

    @Test
    void codeEntity_wrappedInCode() {
        MessageEntity entity = new MessageEntity();
        entity.setType("code");
        entity.setOffset(0);
        entity.setLength(3);
        ContentResult r = ContentBuilder.build("foo bar", List.of(entity));
        assertThat(r.getHtml()).contains("<code>foo</code>");
    }

    @Test
    void textLinkEntity_wrappedInAnchor() {
        MessageEntity entity = new MessageEntity();
        entity.setType("text_link");
        entity.setOffset(0);
        entity.setLength(4);
        entity.setUrl("https://example.com");
        ContentResult r = ContentBuilder.build("link text", List.of(entity));
        assertThat(r.getHtml()).contains("<a href=\"https://example.com\">link</a>");
    }

    @Test
    void unknownEntityType_treatedAsPlainText() {
        MessageEntity entity = new MessageEntity();
        entity.setType("mention");
        entity.setOffset(0);
        entity.setLength(5);
        ContentResult r = ContentBuilder.build("@user text", List.of(entity));
        // mention 类型不处理，整段文字按普通文本输出
        assertThat(r.getHtml()).doesNotContain("<a");
        assertThat(r.getTags()).isEmpty();
    }

    @Test
    void emptyContent_returnsEmptyHtml() {
        ContentResult r = ContentBuilder.build("", List.of());
        assertThat(r.getHtml()).isEmpty();
        assertThat(r.getTags()).isEmpty();
    }
}
```

### Step 3: 运行测试确认失败

```bash
./gradlew test --tests "vip.mystery0.halo.telegrammoment.publisher.ContentBuilderTest"
```

预期：编译失败（类不存在）

### Step 4: 实现 ContentBuilder.java

```java
package vip.mystery0.halo.telegrammoment.publisher;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class ContentBuilder {

    /** 需要处理的 Entity 类型白名单（其余类型跳过） */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "hashtag", "bold", "italic", "underline",
            "strikethrough", "code", "pre", "text_link"
    );

    /**
     * 将 Telegram 消息文本和 Entity 列表转换为 HTML 及标签列表。
     *
     * @param text     消息原始文本（Unicode）
     * @param entities MessageEntity 列表
     * @return ContentResult 包含 html 和 tags
     */
    public static ContentResult build(String text, List<MessageEntity> entities) {
        if (text == null || text.isEmpty()) {
            return new ContentResult("", List.of());
        }

        // 只保留白名单内的 entity
        List<MessageEntity> filtered = entities.stream()
                .filter(e -> ALLOWED_TYPES.contains(e.getType()))
                .sorted((a, b) -> Integer.compare(a.getOffset(), b.getOffset()))
                .toList();

        List<String> tags = new ArrayList<>();
        // 使用 rune（Unicode codepoint）数组，与 Telegram 的 offset/length 保持一致
        int[] codePoints = text.codePoints().toArray();

        StringBuilder sb = new StringBuilder();
        int cursor = 0;

        for (MessageEntity entity : filtered) {
            int start = entity.getOffset();
            int end = entity.getOffset() + entity.getLength();

            // entity 之前的普通文本
            if (cursor < start) {
                appendLines(sb, cpSubstring(codePoints, cursor, start));
            }

            String entityText = cpSubstring(codePoints, start, end);

            switch (entity.getType()) {
                case "hashtag" -> {
                    // "#java" → tag = "java"
                    String tag = entityText.startsWith("#") ? entityText.substring(1) : entityText;
                    tags.add(tag);
                    String encoded = URLEncoder.encode(tag, StandardCharsets.UTF_8);
                    sb.append("<a class=\"tag\" href=\"?tag=").append(encoded).append("\">")
                            .append(entityText).append("</a>");
                }
                case "bold" -> sb.append("<strong>").append(entityText).append("</strong>");
                case "italic" -> sb.append("<em>").append(entityText).append("</em>");
                case "underline" -> sb.append("<u>").append(entityText).append("</u>");
                case "strikethrough" -> sb.append("<s>").append(entityText).append("</s>");
                case "code" -> sb.append("<code>").append(entityText).append("</code>");
                case "pre" -> sb.append("<pre><code>").append(entityText).append("</code></pre>");
                case "text_link" -> sb.append("<a href=\"").append(entity.getUrl()).append("\">")
                        .append(entityText).append("</a>");
                default -> sb.append(entityText); // 白名单外不会走到这里
            }
            cursor = end;
        }

        // 剩余文本
        if (cursor < codePoints.length) {
            appendLines(sb, cpSubstring(codePoints, cursor, codePoints.length));
        }

        return new ContentResult(sb.toString(), List.copyOf(tags));
    }

    /** 将换行分隔的文本按 <p> 标签拼入 StringBuilder */
    private static void appendLines(StringBuilder sb, String text) {
        if (text.isEmpty()) return;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (!line.isEmpty()) {
                sb.append("<p>").append(line).append("</p>");
            }
        }
    }

    /** 从 Unicode codepoint 数组中截取子串 */
    private static String cpSubstring(int[] codePoints, int start, int end) {
        return new String(Arrays.copyOfRange(codePoints, start, end), 0, end - start);
    }
}
```

### Step 5: 运行测试确认通过

```bash
./gradlew test --tests "vip.mystery0.halo.telegrammoment.publisher.ContentBuilderTest"
```

预期：8 tests passed

### Step 6: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/publisher/ContentBuilder.java \
        src/main/java/vip/mystery0/halo/telegrammoment/publisher/ContentResult.java \
        src/test/java/vip/mystery0/halo/telegrammoment/publisher/ContentBuilderTest.java
git commit -m "feat: 实现 Telegram Entity 转 HTML 的 ContentBuilder"
```

---

## Task 5: AttachmentUploader（下载 + 上传附件）

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/publisher/AttachmentUploadResult.java`
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/publisher/AttachmentUploader.java`

### Step 1: 创建 AttachmentUploadResult.java

```java
package vip.mystery0.halo.telegrammoment.publisher;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AttachmentUploadResult {
    /** Halo 附件的 metadata.name，用于删除时关联 */
    private String attachmentName;
    /** 可公开访问的 permalink URL */
    private String permalink;
    /** MIME 类型，如 "image/jpeg" */
    private String mimeType;
    /** "PHOTO" 或 "VIDEO" */
    private String mediaType;
}
```

### Step 2: 创建 AttachmentUploader.java

```java
package vip.mystery0.halo.telegrammoment.publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;

import java.io.InputStream;
import java.time.Duration;

@Slf4j
@Component
public class AttachmentUploader {

    @Value("${server.port:8090}")
    private int serverPort;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 Telegram 下载文件，并上传到 Halo 附件库。
     *
     * @param fileId    Telegram file_id
     * @param client    已初始化的 TelegramClient
     * @param setting   当前插件配置（提供 storagePolicy / storageGroup）
     * @param mediaType "PHOTO" 或 "VIDEO"
     * @return 上传结果（attachmentName, permalink, mimeType）
     */
    public AttachmentUploadResult upload(String fileId,
                                         OkHttpTelegramClient client,
                                         TelegramSetting setting,
                                         String mediaType) {
        try {
            // 1. 获取文件路径
            GetFile getFile = GetFile.builder().fileId(fileId).build();
            File telegramFile = client.execute(getFile);
            String filePath = telegramFile.getFilePath();
            String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);

            // 2. 下载文件流
            InputStream fileStream = client.downloadFileAsStream(filePath);
            byte[] fileBytes = fileStream.readAllBytes();

            // 3. 上传到 Halo
            String mimeType = detectMimeType(fileName);
            String attachmentName = uploadToHalo(fileBytes, fileName, mimeType, setting);

            // 4. 轮询直到 permalink 可用（最多 10 秒）
            String permalink = waitForPermalink(attachmentName);

            return new AttachmentUploadResult(attachmentName, permalink, mimeType, mediaType);

        } catch (TelegramApiException e) {
            throw new RuntimeException("从 Telegram 下载文件失败: " + fileId, e);
        } catch (Exception e) {
            throw new RuntimeException("附件上传失败: " + fileId, e);
        }
    }

    private String uploadToHalo(byte[] fileBytes, String fileName,
                                  String mimeType, TelegramSetting setting) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + serverPort)
                .build();

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(MediaType.parseMediaType(mimeType));

        if (setting.getStoragePolicy() != null && !setting.getStoragePolicy().isBlank()) {
            builder.part("policyName", setting.getStoragePolicy());
        }
        if (setting.getStorageGroup() != null && !setting.getStorageGroup().isBlank()) {
            builder.part("groupName", setting.getStorageGroup());
        }

        String response = webClient.post()
                .uri("/apis/api.console.halo.run/v1alpha1/attachments/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        try {
            JsonNode node = objectMapper.readTree(response);
            return node.path("metadata").path("name").asText();
        } catch (Exception e) {
            throw new RuntimeException("解析附件上传响应失败: " + response, e);
        }
    }

    private String waitForPermalink(String attachmentName) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + serverPort)
                .build();

        for (int i = 0; i < 100; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String response = webClient.get()
                    .uri("/apis/storage.halo.run/v1alpha1/attachments/{name}", attachmentName)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            try {
                JsonNode node = objectMapper.readTree(response);
                String permalink = node.path("status").path("permalink").asText();
                if (permalink != null && !permalink.isBlank()) {
                    return permalink;
                }
            } catch (Exception ignored) {
            }
        }
        throw new RuntimeException("等待附件 permalink 超时: " + attachmentName);
    }

    private String detectMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }
}
```

### Step 3: 验证编译

```bash
./gradlew compileJava
```

预期：BUILD SUCCESSFUL

### Step 4: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/publisher/AttachmentUploadResult.java \
        src/main/java/vip/mystery0/halo/telegrammoment/publisher/AttachmentUploader.java
git commit -m "feat: 实现 AttachmentUploader（Telegram 文件下载 + Halo 附件上传）"
```

---

## Task 6: MomentPublisher（Moment CRUD）

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/publisher/MomentPublisher.java`

### Step 1: 创建 MomentPublisher.java

```java
package vip.mystery0.halo.telegrammoment.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import vip.mystery0.halo.telegrammoment.model.Moment;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MomentPublisher {

    private final ReactiveExtensionClient extensionClient;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter RELEASE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC);

    /**
     * 创建新 Moment。
     *
     * @param content       HTML 内容
     * @param tags          标签列表
     * @param medium        附件列表
     * @param attachNames   附件 name 列表（用于删除时关联）
     * @param messageId     Telegram 消息 ID
     * @param chatId        Telegram Chat ID
     * @param releaseTime   消息发送时间
     */
    public void publish(String content,
                        List<String> tags,
                        List<Moment.MediumItem> medium,
                        List<String> attachNames,
                        long messageId,
                        long chatId,
                        Instant releaseTime) {
        Moment moment = buildMoment(content, tags, medium, attachNames, messageId, chatId, releaseTime);
        extensionClient.create(moment)
                .subscribeOn(Schedulers.boundedElastic())
                .block();
        log.info("发布 Moment 成功，messageId={}, chatId={}", messageId, chatId);
    }

    /**
     * 编辑消息：删除旧 Moment + 创建新 Moment。
     */
    public void update(String content,
                       List<String> tags,
                       List<Moment.MediumItem> medium,
                       List<String> attachNames,
                       long messageId,
                       long chatId,
                       Instant releaseTime) {
        delete(messageId, chatId);
        publish(content, tags, medium, attachNames, messageId, chatId, releaseTime);
    }

    /**
     * 按 messageId + chatId 查找并删除对应 Moment 及其附件。
     *
     * @param messageId Telegram 消息 ID
     * @param chatId    Telegram Chat ID
     */
    public void delete(long messageId, long chatId) {
        Optional<Moment> existing = findByMessageId(messageId, chatId);
        if (existing.isEmpty()) {
            log.warn("未找到对应 Moment，跳过删除。messageId={}, chatId={}", messageId, chatId);
            return;
        }
        Moment moment = existing.get();

        // 删除关联附件
        String attachNamesJson = moment.getMetadata().getAnnotations()
                .getOrDefault(Moment.ANNOTATION_ATTACHMENT_NAMES, "[]");
        try {
            List<String> attachmentNames = objectMapper.readValue(attachNamesJson, new TypeReference<>() {});
            for (String name : attachmentNames) {
                deleteAttachment(name);
            }
        } catch (JsonProcessingException e) {
            log.warn("解析附件名称失败，跳过附件删除: {}", attachNamesJson, e);
        }

        // 删除 Moment
        extensionClient.delete(moment)
                .subscribeOn(Schedulers.boundedElastic())
                .block();
        log.info("删除 Moment 成功，messageId={}, chatId={}", messageId, chatId);
    }

    /**
     * 按 messageId + chatId 注解查找 Moment。
     */
    public Optional<Moment> findByMessageId(long messageId, long chatId) {
        String msgIdStr = String.valueOf(messageId);
        String chatIdStr = String.valueOf(chatId);

        return extensionClient.listAll(Moment.class, new ListOptions(), null)
                .filter(m -> {
                    Map<String, String> annotations = m.getMetadata().getAnnotations();
                    if (annotations == null) return false;
                    return msgIdStr.equals(annotations.get(Moment.ANNOTATION_MESSAGE_ID))
                            && chatIdStr.equals(annotations.get(Moment.ANNOTATION_CHAT_ID));
                })
                .next()
                .subscribeOn(Schedulers.boundedElastic())
                .blockOptional();
    }

    private void deleteAttachment(String attachmentName) {
        // 通过 ReactiveExtensionClient 删除 Attachment CRD
        // Attachment 定义于 storage.halo.run/v1alpha1/attachments
        // 由于 Halo api 模块包含 Attachment 类，直接操作
        try {
            extensionClient.fetch(run.halo.app.core.extension.attachment.Attachment.class, attachmentName)
                    .flatMap(extensionClient::delete)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
            log.debug("删除附件成功: {}", attachmentName);
        } catch (Exception e) {
            log.warn("删除附件失败，忽略: {}", attachmentName, e);
        }
    }

    private Moment buildMoment(String html,
                                List<String> tags,
                                List<Moment.MediumItem> medium,
                                List<String> attachNames,
                                long messageId,
                                long chatId,
                                Instant releaseTime) {
        Moment moment = new Moment();

        // metadata
        run.halo.app.extension.Metadata metadata = new run.halo.app.extension.Metadata();
        metadata.setGenerateName("telegram-moment-");
        Map<String, String> annotations = new HashMap<>();
        annotations.put(Moment.ANNOTATION_MESSAGE_ID, String.valueOf(messageId));
        annotations.put(Moment.ANNOTATION_CHAT_ID, String.valueOf(chatId));
        try {
            annotations.put(Moment.ANNOTATION_ATTACHMENT_NAMES,
                    objectMapper.writeValueAsString(attachNames));
        } catch (JsonProcessingException e) {
            annotations.put(Moment.ANNOTATION_ATTACHMENT_NAMES, "[]");
        }
        metadata.setAnnotations(annotations);
        moment.setMetadata(metadata);

        // spec
        Moment.MomentSpec spec = new Moment.MomentSpec();
        spec.setReleaseTime(RELEASE_TIME_FMT.format(releaseTime));
        spec.setTags(new ArrayList<>(tags));
        spec.setVisible("PUBLIC");

        Moment.Content content = new Moment.Content();
        content.setRaw(html); // raw 也存 html，与 Go 实现一致
        content.setHtml(html);
        content.setMedium(new ArrayList<>(medium));
        spec.setContent(content);

        moment.setSpec(spec);
        return moment;
    }
}
```

### Step 2: 验证编译

```bash
./gradlew compileJava
```

> 如果 `run.halo.app.core.extension.attachment.Attachment` 编译报错（不在 api 模块），将 `deleteAttachment` 方法改为用 WebClient 调用 `DELETE /apis/storage.halo.run/v1alpha1/attachments/{name}`，与 AttachmentUploader 同样的内部 WebClient 方式处理。

### Step 3: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/publisher/MomentPublisher.java
git commit -m "feat: 实现 MomentPublisher（Moment 创建/更新/删除）"
```

---

## Task 7: 媒体组聚合（PendingMediaGroup + MediaGroupAggregator）

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/handler/PendingMediaGroup.java`
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/handler/MediaGroupAggregator.java`

### Step 1: 创建 PendingMediaGroup.java

```java
package vip.mystery0.halo.telegrammoment.handler;

import lombok.Getter;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Getter
public class PendingMediaGroup {
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong expectedExecuteTimeMs;
    private final boolean isPrivate;

    public PendingMediaGroup(long executeTimeMs, boolean isPrivate) {
        this.expectedExecuteTimeMs = new AtomicLong(executeTimeMs);
        this.isPrivate = isPrivate;
    }

    public void add(Message message, long newExecuteTimeMs) {
        messages.add(message);
        expectedExecuteTimeMs.set(newExecuteTimeMs);
    }

    public boolean isReady() {
        return System.currentTimeMillis() >= expectedExecuteTimeMs.get();
    }
}
```

### Step 2: 创建 MediaGroupAggregator.java

```java
package vip.mystery0.halo.telegrammoment.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.photos.PhotoSize;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.publisher.AttachmentUploadResult;
import vip.mystery0.halo.telegrammoment.publisher.AttachmentUploader;
import vip.mystery0.halo.telegrammoment.publisher.ContentBuilder;
import vip.mystery0.halo.telegrammoment.publisher.ContentResult;
import vip.mystery0.halo.telegrammoment.publisher.MomentPublisher;
import vip.mystery0.halo.telegrammoment.model.Moment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaGroupAggregator {

    private final MomentPublisher momentPublisher;
    private final AttachmentUploader attachmentUploader;

    private final ConcurrentHashMap<String, PendingMediaGroup> pendingGroups = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    /** 由 TelegramBotService 在 Bot 启动时调用 */
    public void start(TelegramSetting setting, OkHttpTelegramClient telegramClient) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "media-group-aggregator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> drainReady(setting, telegramClient),
                5, 5, TimeUnit.SECONDS
        );
        log.info("MediaGroupAggregator 已启动");
    }

    /** 由 TelegramBotService 在 Bot 停止时调用 */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        pendingGroups.clear();
        log.info("MediaGroupAggregator 已停止");
    }

    /**
     * 将一条图片消息加入对应的媒体组。
     *
     * @param albumId   Telegram media_group_id
     * @param message   消息对象
     * @param isPrivate 是否来自私聊
     * @param setting   当前配置（读取 mediaDelaySeconds）
     */
    public void add(String albumId, Message message, boolean isPrivate, TelegramSetting setting) {
        long executeTimeMs = System.currentTimeMillis()
                + setting.getMediaDelaySeconds() * 1000L;
        pendingGroups.compute(albumId, (key, existing) -> {
            if (existing == null) {
                PendingMediaGroup group = new PendingMediaGroup(executeTimeMs, isPrivate);
                group.add(message, executeTimeMs);
                return group;
            }
            existing.add(message, executeTimeMs);
            return existing;
        });
    }

    private void drainReady(TelegramSetting setting, OkHttpTelegramClient telegramClient) {
        pendingGroups.entrySet().removeIf(entry -> {
            PendingMediaGroup group = entry.getValue();
            if (!group.isReady()) return false;

            try {
                processGroup(group, setting, telegramClient);
            } catch (Exception e) {
                log.error("处理媒体组失败，albumId={}", entry.getKey(), e);
            }
            return true; // 无论成功失败，都从 map 移除
        });
    }

    private void processGroup(PendingMediaGroup group, TelegramSetting setting,
                               OkHttpTelegramClient telegramClient) {
        List<Message> messages = group.getMessages();
        if (messages.isEmpty()) return;

        Message firstMsg = messages.get(0);
        String captionText = firstMsg.getCaption() != null ? firstMsg.getCaption() : "";
        List<org.telegram.telegrambots.meta.api.objects.MessageEntity> captionEntities =
                firstMsg.getCaptionEntities() != null ? firstMsg.getCaptionEntities() : List.of();

        ContentResult contentResult = ContentBuilder.build(captionText, captionEntities);

        List<Moment.MediumItem> medium = new ArrayList<>();
        List<String> attachmentNames = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.getPhoto() == null || msg.getPhoto().isEmpty()) continue;
            // 选最高分辨率的图片
            PhotoSize largest = msg.getPhoto().stream()
                    .max((a, b) -> Integer.compare(a.getFileSize(), b.getFileSize()))
                    .orElse(msg.getPhoto().get(msg.getPhoto().size() - 1));

            try {
                AttachmentUploadResult result = attachmentUploader.upload(
                        largest.getFileId(), telegramClient, setting, "PHOTO");
                Moment.MediumItem item = new Moment.MediumItem();
                item.setType("PHOTO");
                item.setUrl(result.getPermalink());
                item.setOriginType(result.getMimeType());
                medium.add(item);
                attachmentNames.add(result.getAttachmentName());
            } catch (Exception e) {
                log.error("媒体组图片上传失败，跳过: {}", largest.getFileId(), e);
            }
        }

        momentPublisher.publish(
                contentResult.getHtml(),
                contentResult.getTags(),
                medium,
                attachmentNames,
                firstMsg.getMessageId(),
                firstMsg.getChatId(),
                firstMsg.getDate() != null
                        ? Instant.ofEpochSecond(firstMsg.getDate())
                        : Instant.now()
        );
    }
}
```

### Step 3: 验证编译

```bash
./gradlew compileJava
```

### Step 4: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/handler/PendingMediaGroup.java \
        src/main/java/vip/mystery0/halo/telegrammoment/handler/MediaGroupAggregator.java
git commit -m "feat: 实现媒体组聚合器 MediaGroupAggregator"
```

---

## Task 8: MessageHandler（消息处理主逻辑）

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/handler/MessageHandler.java`

### Step 1: 创建 MessageHandler.java

```java
package vip.mystery0.halo.telegrammoment.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.photos.PhotoSize;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.model.Moment;
import vip.mystery0.halo.telegrammoment.publisher.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageHandler {

    private final MomentPublisher momentPublisher;
    private final AttachmentUploader attachmentUploader;
    private final MediaGroupAggregator mediaGroupAggregator;

    /**
     * 处理一条消息（新建或编辑）。
     *
     * @param message         Telegram 消息对象
     * @param isEdit          true = 编辑已有消息，false = 新消息
     * @param isChannel       true = 来自频道，false = 来自私聊
     * @param setting         当前插件配置
     * @param telegramClient  已初始化的 TelegramClient（用于下载文件）
     */
    public void handleMessage(Message message,
                               boolean isEdit,
                               boolean isChannel,
                               TelegramSetting setting,
                               OkHttpTelegramClient telegramClient) {
        // 过滤检查
        if (!checkEnabled(message, isChannel, setting)) {
            return;
        }

        // 媒体组处理：图片组直接交给聚合器，提前返回
        if (message.getPhoto() != null
                && message.getMediaGroupId() != null
                && !isEdit) {
            mediaGroupAggregator.add(message.getMediaGroupId(), message,
                    !isChannel, setting);
            return;
        }

        // 构建 Post 数据
        String rawText;
        List<MessageEntity> entities;
        List<Moment.MediumItem> medium = new ArrayList<>();
        List<String> attachmentNames = new ArrayList<>();

        if (message.getPhoto() != null && !message.getPhoto().isEmpty()) {
            // 单张图片
            rawText = message.getCaption() != null ? message.getCaption() : "";
            entities = message.getCaptionEntities() != null
                    ? message.getCaptionEntities() : List.of();
            uploadSingleMedia(message.getPhoto().stream()
                            .max((a, b) -> Integer.compare(a.getFileSize(), b.getFileSize()))
                            .orElse(message.getPhoto().get(message.getPhoto().size() - 1))
                            .getFileId(),
                    "PHOTO", setting, telegramClient, medium, attachmentNames);

        } else if (message.getAnimation() != null) {
            // GIF
            rawText = message.getCaption() != null ? message.getCaption() : "";
            entities = message.getCaptionEntities() != null
                    ? message.getCaptionEntities() : List.of();
            uploadSingleMedia(message.getAnimation().getFileId(),
                    "VIDEO", setting, telegramClient, medium, attachmentNames);

        } else if (message.getSticker() != null) {
            // 贴纸
            rawText = "";
            entities = List.of();
            uploadSingleMedia(message.getSticker().getFileId(),
                    "PHOTO", setting, telegramClient, medium, attachmentNames);

        } else if (message.getText() != null && !message.getText().isBlank()) {
            // 纯文字
            rawText = message.getText();
            entities = message.getEntities() != null ? message.getEntities() : List.of();

        } else {
            log.debug("忽略不支持的消息类型，messageId={}", message.getMessageId());
            return;
        }

        ContentResult contentResult = ContentBuilder.build(rawText, entities);
        Instant releaseTime = message.getDate() != null
                ? Instant.ofEpochSecond(message.getDate())
                : Instant.now();

        if (isEdit) {
            momentPublisher.update(contentResult.getHtml(), contentResult.getTags(),
                    medium, attachmentNames,
                    message.getMessageId(), message.getChatId(), releaseTime);
        } else {
            momentPublisher.publish(contentResult.getHtml(), contentResult.getTags(),
                    medium, attachmentNames,
                    message.getMessageId(), message.getChatId(), releaseTime);
        }
    }

    /**
     * 处理删除消息请求。
     *
     * @param targetMessage 要删除的消息（通过 /rm 或 replyTo 指定）
     */
    public void handleDelete(Message targetMessage) {
        if (targetMessage == null) {
            log.warn("删除目标消息为空，跳过");
            return;
        }
        log.info("删除 Moment，messageId={}, chatId={}",
                targetMessage.getMessageId(), targetMessage.getChatId());
        momentPublisher.delete(targetMessage.getMessageId(), targetMessage.getChatId());
    }

    // ---- 私有辅助方法 ----

    private boolean checkEnabled(Message message, boolean isChannel, TelegramSetting setting) {
        if (isChannel) {
            if (!setting.isChannelEnabled()) {
                log.debug("频道消息监听已关闭，跳过");
                return false;
            }
            // 指定频道 ID 过滤
            if (setting.getChannelId() != null && !setting.getChannelId().isBlank()) {
                try {
                    long expectedId = Long.parseLong(setting.getChannelId());
                    if (message.getChatId() != expectedId) {
                        log.debug("非目标频道 {}，跳过", message.getChatId());
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            // Hashtag 过滤
            String text = message.getText() != null ? message.getText()
                    : (message.getCaption() != null ? message.getCaption() : "");
            for (String filterTag : setting.getChannelFilterList()) {
                if (text.contains("#" + filterTag)) {
                    log.info("消息含屏蔽标签 #{}，跳过", filterTag);
                    return false;
                }
            }
        } else {
            if (!setting.isPrivateEnabled()) {
                log.debug("私聊消息监听已关闭，跳过");
                return false;
            }
            if (setting.getPrivateSenderId() != null && !setting.getPrivateSenderId().isBlank()) {
                try {
                    long expectedSender = Long.parseLong(setting.getPrivateSenderId());
                    if (message.getChatId() != expectedSender) {
                        log.debug("非允许的私聊发送者 {}，跳过", message.getChatId());
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return true;
    }

    private void uploadSingleMedia(String fileId, String mediaType,
                                    TelegramSetting setting,
                                    OkHttpTelegramClient telegramClient,
                                    List<Moment.MediumItem> medium,
                                    List<String> attachmentNames) {
        try {
            AttachmentUploadResult result = attachmentUploader.upload(
                    fileId, telegramClient, setting, mediaType);
            Moment.MediumItem item = new Moment.MediumItem();
            item.setType(mediaType);
            item.setUrl(result.getPermalink());
            item.setOriginType(result.getMimeType());
            medium.add(item);
            attachmentNames.add(result.getAttachmentName());
        } catch (Exception e) {
            log.error("单媒体文件上传失败，fileId={}，继续发布文字内容", fileId, e);
        }
    }
}
```

### Step 2: 验证编译

```bash
./gradlew compileJava
```

### Step 3: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/handler/MessageHandler.java
git commit -m "feat: 实现 MessageHandler 消息处理主逻辑"
```

---

## Task 9: TelegramUpdateHandler（Update 路由分发）

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/bot/TelegramUpdateHandler.java`

### Step 1: 创建 TelegramUpdateHandler.java

```java
package vip.mystery0.halo.telegrammoment.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.handler.MessageHandler;

@Slf4j
@RequiredArgsConstructor
public class TelegramUpdateHandler implements LongPollingSingleThreadUpdateConsumer {

    private static final String CMD_RM = "/rm";
    private static final String CMD_REDO = "/redo";

    private final MessageHandler messageHandler;
    private final TelegramSetting setting;
    private final OkHttpTelegramClient telegramClient;

    @Override
    public void consume(Update update) {
        try {
            if (update.hasChannelPost()) {
                handleChannelPost(update.getChannelPost());
            } else if (update.hasEditedChannelPost()) {
                messageHandler.handleMessage(update.getEditedChannelPost(),
                        true, true, setting, telegramClient);
            } else if (update.hasMessage()) {
                messageHandler.handleMessage(update.getMessage(),
                        false, false, setting, telegramClient);
            } else if (update.hasEditedMessage()) {
                messageHandler.handleMessage(update.getEditedMessage(),
                        true, false, setting, telegramClient);
            }
        } catch (Exception e) {
            log.error("处理 Telegram Update 异常，updateId={}", update.getUpdateId(), e);
        }
    }

    private void handleChannelPost(Message message) {
        String text = message.getText();

        if (CMD_RM.equals(text)) {
            // 删除当前消息本身（通常是频道管理员发送的指令，消息本身即为被删目标）
            // 若消息有 replyTo，删除 replyTo；否则视为删除当前消息
            Message target = message.isReply() ? message.getReplyToMessage() : null;
            if (target != null) {
                messageHandler.handleDelete(target);
            } else {
                log.warn("/rm 指令无回复目标，忽略");
            }
        } else if (CMD_REDO.equals(text)) {
            // 重新发布 replyTo 消息
            Message target = message.getReplyToMessage();
            if (target != null) {
                messageHandler.handleMessage(target, false, true, setting, telegramClient);
            } else {
                log.warn("/redo 指令无回复目标，忽略");
            }
        } else {
            messageHandler.handleMessage(message, false, true, setting, telegramClient);
        }
    }
}
```

### Step 2: 验证编译

```bash
./gradlew compileJava
```

### Step 3: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/bot/TelegramUpdateHandler.java
git commit -m "feat: 实现 TelegramUpdateHandler Update 路由分发"
```

---

## Task 10: TelegramBotService（Bot 生命周期管理）

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/bot/TelegramBotService.java`

### Step 1: 创建 TelegramBotService.java

```java
package vip.mystery0.halo.telegrammoment.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.handler.MediaGroupAggregator;
import vip.mystery0.halo.telegrammoment.handler.MessageHandler;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBotService {

    /** Halo 存储插件设置的 ConfigMap 名称，遵循 plugin-{pluginName} 约定 */
    private static final String CONFIG_MAP_NAME = "telegram-moment-configmap";

    private final ReactiveExtensionClient extensionClient;
    private final MessageHandler messageHandler;
    private final MediaGroupAggregator mediaGroupAggregator;
    private final ObjectMapper objectMapper;

    private TelegramBotsLongPollingApplication botApplication;
    private OkHttpTelegramClient telegramClient;
    private TelegramSetting currentSetting;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 返回 Bot 当前是否运行中 */
    public boolean isRunning() {
        return running.get();
    }

    /** 返回 Bot 用户名（未运行时返回 null） */
    public String getBotUsername() {
        if (telegramClient == null) return null;
        try {
            return telegramClient.execute(
                    org.telegram.telegrambots.meta.api.methods.GetMe.builder().build()
            ).getUserName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 启动 Bot。由 Plugin.start() 或 restart 接口调用。
     */
    public synchronized void startBot() {
        TelegramSetting setting = readSetting();
        if (setting == null || !setting.hasValidToken()) {
            log.warn("Bot Token 未配置，跳过 Bot 启动。请在插件配置页面设置 Bot Token 后点击「重启 Bot」。");
            return;
        }

        log.info("正在启动 Telegram Bot...");
        currentSetting = setting;

        String apiUrl = (setting.getApiEndpoint() == null || setting.getApiEndpoint().isBlank())
                ? "https://api.telegram.org"
                : setting.getApiEndpoint();

        telegramClient = new OkHttpTelegramClient(
                new okhttp3.OkHttpClient(),
                setting.getBotToken(),
                apiUrl + "/bot{token}/{method}"
        );

        TelegramUpdateHandler handler = new TelegramUpdateHandler(
                messageHandler, setting, telegramClient);

        botApplication = new TelegramBotsLongPollingApplication();
        try {
            botApplication.registerBot(setting.getBotToken(), handler);
            mediaGroupAggregator.start(setting, telegramClient);
            running.set(true);
            log.info("Telegram Bot 启动成功");
        } catch (Exception e) {
            running.set(false);
            log.error("Telegram Bot 启动失败", e);
        }
    }

    /**
     * 停止 Bot。由 Plugin.stop() 或 restart 接口调用。
     */
    public synchronized void stopBot() {
        running.set(false);
        mediaGroupAggregator.stop();
        if (botApplication != null) {
            try {
                botApplication.close();
            } catch (Exception e) {
                log.warn("关闭 Bot Application 时出现异常（通常可忽略）", e);
            }
            botApplication = null;
        }
        telegramClient = null;
        log.info("Telegram Bot 已停止");
    }

    /**
     * 重启 Bot（停止后用最新配置重新启动）。
     */
    public void restartBot() {
        log.info("正在重启 Telegram Bot...");
        stopBot();
        startBot();
    }

    // ---- 私有辅助 ----

    private TelegramSetting readSetting() {
        try {
            ConfigMap configMap = extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
            if (configMap == null || configMap.getData() == null) {
                log.warn("插件配置 ConfigMap 不存在或为空: {}", CONFIG_MAP_NAME);
                return new TelegramSetting();
            }

            TelegramSetting setting = new TelegramSetting();
            Map<String, String> data = configMap.getData();

            // 每个 group 的值是 JSON 字符串
            parseGroup(data.get("bot"), setting, "bot");
            parseGroup(data.get("channel"), setting, "channel");
            parseGroup(data.get("private"), setting, "private");
            parseGroup(data.get("storage"), setting, "storage");

            return setting;
        } catch (Exception e) {
            log.error("读取插件配置失败", e);
            return new TelegramSetting();
        }
    }

    private void parseGroup(String json, TelegramSetting setting, String group) {
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            switch (group) {
                case "bot" -> {
                    setting.setBotToken(str(map, "botToken"));
                    setting.setApiEndpoint(str(map, "apiEndpoint"));
                }
                case "channel" -> {
                    setting.setChannelEnabled(bool(map, "channelEnabled", true));
                    setting.setChannelId(str(map, "channelId"));
                    setting.setChannelFilter(str(map, "channelFilter"));
                }
                case "private" -> {
                    setting.setPrivateEnabled(bool(map, "privateEnabled", false));
                    setting.setPrivateSenderId(str(map, "privateSenderId"));
                }
                case "storage" -> {
                    setting.setStoragePolicy(str(map, "storagePolicy"));
                    setting.setStorageGroup(str(map, "storageGroup"));
                    Object delay = map.get("mediaDelaySeconds");
                    if (delay != null) {
                        setting.setMediaDelaySeconds(Integer.parseInt(delay.toString()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析配置 group={} 失败: {}", group, json, e);
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v.toString());
    }
}
```

### Step 2: 验证编译

```bash
./gradlew compileJava
```

### Step 3: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/bot/TelegramBotService.java
git commit -m "feat: 实现 TelegramBotService Bot 生命周期管理"
```

---

## Task 11: BotController（REST API 端点）

**Files:**
- Create: `src/main/java/vip/mystery0/halo/telegrammoment/bot/BotController.java`

### Step 1: 创建 BotController.java

```java
package vip.mystery0.halo.telegrammoment.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/apis/telegram-moment/v1alpha1/bot")
@RequiredArgsConstructor
public class BotController {

    private final TelegramBotService botService;

    /**
     * 查询 Bot 运行状态。
     * GET /apis/telegram-moment/v1alpha1/bot/status
     */
    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        return Mono.fromCallable(() -> Map.of(
                "running", botService.isRunning(),
                "username", botService.getBotUsername() != null
                        ? botService.getBotUsername() : ""
        )).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重启 Bot（使用最新配置）。
     * POST /apis/telegram-moment/v1alpha1/bot/restart
     */
    @PostMapping("/restart")
    public Mono<Map<String, String>> restart() {
        return Mono.fromRunnable(botService::restartBot)
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(Map.of("message", "Bot 正在重启，请稍后刷新状态"));
    }
}
```

### Step 2: 验证编译

```bash
./gradlew compileJava
```

### Step 3: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/bot/BotController.java
git commit -m "feat: 添加 Bot 状态查询与重启 REST 接口"
```

---

## Task 12: 更新 TelegramMomentPlugin 生命周期

**Files:**
- Modify: `src/main/java/vip/mystery0/halo/telegrammoment/TelegramMomentPlugin.java`

### Step 1: 修改 TelegramMomentPlugin.java

```java
package vip.mystery0.halo.telegrammoment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import vip.mystery0.halo.telegrammoment.bot.TelegramBotService;

@Slf4j
@Component
public class TelegramMomentPlugin extends BasePlugin {

    @Autowired
    private TelegramBotService botService;

    public TelegramMomentPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("Telegram Moment 插件启动");
        botService.startBot();
    }

    @Override
    public void stop() {
        log.info("Telegram Moment 插件停止");
        botService.stopBot();
    }
}
```

### Step 2: 验证全量构建

```bash
./gradlew build
```

预期：BUILD SUCCESSFUL，`build/libs/` 下生成插件 JAR

### Step 3: Commit

```bash
git add src/main/java/vip/mystery0/halo/telegrammoment/TelegramMomentPlugin.java
git commit -m "feat: 将 Bot 生命周期接入插件 start/stop"
```

---

## Task 13: 前端配置页面

**Files:**
- Modify: `ui/src/index.ts`
- Modify: `ui/src/views/HomeView.vue`（重写为配置页面）

### Step 1: 更新 ui/src/index.ts

```typescript
import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'
import HomeView from './views/HomeView.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/telegram-moment',
        name: 'TelegramMoment',
        component: HomeView,
        meta: {
          title: 'Telegram Moment',
          searchable: true,
          menu: {
            name: 'Telegram Moment',
            group: '工具',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
  ],
})
```

### Step 2: 重写 ui/src/views/HomeView.vue

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  VButton,
  VCard,
  VTag,
  Toast,
} from '@halo-dev/components'
import { PluginSetting } from '@halo-dev/ui-shared'
import axios from 'axios'

const BASE_URL = '/apis/telegram-moment/v1alpha1/bot'

interface BotStatus {
  running: boolean
  username: string
}

const status = ref<BotStatus>({ running: false, username: '' })
const restarting = ref(false)
const showSavedHint = ref(false)

async function fetchStatus() {
  try {
    const { data } = await axios.get<BotStatus>(`${BASE_URL}/status`)
    status.value = data
  } catch {
    status.value = { running: false, username: '' }
  }
}

async function restartBot() {
  restarting.value = true
  try {
    await axios.post(`${BASE_URL}/restart`)
    Toast.success('Bot 重启指令已发送，请稍后刷新状态')
    setTimeout(fetchStatus, 3000)
  } catch {
    Toast.error('重启失败，请检查配置后重试')
  } finally {
    restarting.value = false
  }
}

function onSettingSaved() {
  showSavedHint.value = true
}

onMounted(fetchStatus)
</script>

<template>
  <div class="p-4 flex flex-col gap-4">
    <!-- Bot 状态卡片 -->
    <VCard title="Bot 状态">
      <div class="flex items-center gap-3 px-4 py-3">
        <VTag :type="status.running ? 'success' : 'danger'">
          {{ status.running ? '运行中' : '已停止' }}
        </VTag>
        <span v-if="status.username" class="text-sm text-gray-500">
          @{{ status.username }}
        </span>
        <div class="ml-auto flex gap-2">
          <VButton size="sm" @click="fetchStatus">刷新状态</VButton>
          <VButton
            size="sm"
            type="primary"
            :loading="restarting"
            @click="restartBot"
          >
            重启 Bot
          </VButton>
        </div>
      </div>
    </VCard>

    <!-- 配置保存提示 -->
    <div
      v-if="showSavedHint"
      class="rounded-md bg-yellow-50 border border-yellow-200 px-4 py-3 text-sm text-yellow-800"
    >
      配置已保存，请点击上方「重启 Bot」按钮使新配置生效。
    </div>

    <!-- 插件配置表单（自动渲染 Setting YAML 定义的表单） -->
    <PluginSetting
      plugin-name="telegram-moment"
      @saved="onSettingSaved"
    />
  </div>
</template>
```

### Step 3: 构建前端并验证

```bash
cd ui && pnpm build
```

预期：`build/dist/` 下生成 `main.js` 和 `style.css`

### Step 4: Commit

```bash
cd ..
git add ui/src/index.ts ui/src/views/HomeView.vue
git commit -m "feat: 实现前端配置页面（Bot 状态 + 重启按钮 + Setting 表单）"
```

---

## Task 14: 全量集成验证

### Step 1: 全量构建

```bash
./gradlew build
```

预期：BUILD SUCCESSFUL，`build/libs/` 生成 `.jar` 文件

### Step 2: 启动 haloServer 验证

```bash
./gradlew haloServer
```

访问 `http://localhost:8090`，登录后进入插件管理页面，确认插件已加载：
- 侧边栏出现「Telegram Moment」菜单
- 配置页面正常展示（状态卡片 + 表单）
- Bot Token 填写后点击「保存」→ 提示横幅出现
- 点击「重启 Bot」→ 状态变为「运行中」

### Step 3: 最终 Commit

```bash
git add .
git commit -m "feat: Telegram Moment 插件 v1.0 初始实现完成"
```

---

## 注意事项 / 实现中可能遇到的问题

| 问题 | 解决方案 |
|------|---------|
| `Attachment` 类不在 Halo api 模块 | 用 WebClient 调用 `DELETE /apis/storage.halo.run/v1alpha1/attachments/{name}` 代替 |
| `ReactiveExtensionClient.listAll()` 签名不同 | 查阅 Halo 2.22 API，可能需改用 `list(Class, Predicate, Comparator)` |
| ConfigMap 名称约定与实际不符 | 在 Halo 后台查看插件实际创建的 ConfigMap 名称并更新 `CONFIG_MAP_NAME` |
| TelegramBotsLongPollingApplication API 与 7.x 不符 | 参考 [telegrambots README](https://github.com/rubenlagus/TelegramBots) 对应版本示例 |
| WebClient 内部调用需要认证 | 若 Halo 要求认证，需在请求头中添加 Bearer token（从 SecurityContext 或固定的系统 token 获取） |
| `PluginSetting` 组件名称不对 | 查阅 `@halo-dev/ui-shared` 2.22 导出的实际组件名称 |
