package vip.mystery0.halo.telegrammoment.publisher;

import java.nio.file.Path;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 将有界下载后的临时文件交给 Halo 的存储策略处理器。 */
public record LocalFilePart(Path path, String filename, MediaType mediaType) implements FilePart {
    public String name() { return "file"; }
    public HttpHeaders headers() {
        var headers = new HttpHeaders(); headers.setContentType(mediaType); headers.setContentDispositionFormData("file", filename); return headers;
    }
    public Flux<DataBuffer> content() { return DataBufferUtils.read(path, DefaultDataBufferFactory.sharedInstance, 65536); }
    public Mono<Void> transferTo(Path destination) { return DataBufferUtils.write(content(), destination); }
}
