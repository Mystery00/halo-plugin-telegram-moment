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
import run.halo.moments.Moment;

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
                                         Moment.MomentMediaType mediaType) {
        try {
            // 1. 获取文件路径
            GetFile getFile = GetFile.builder().fileId(fileId).build();
            File telegramFile = client.execute(getFile);
            String filePath = telegramFile.getFilePath();
            String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);

            // 2. 下载文件流
            byte[] fileBytes;
            try (InputStream fileStream = client.downloadFileAsStream(filePath)) {
                fileBytes = fileStream.readAllBytes();
            }

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
