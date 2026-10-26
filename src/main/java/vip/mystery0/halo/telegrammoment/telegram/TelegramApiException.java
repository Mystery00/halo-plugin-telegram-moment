package vip.mystery0.halo.telegrammoment.telegram;

/** 不保留 Telegram 原始响应或请求 URL，避免异常链泄露 Token。 */
public class TelegramApiException extends RuntimeException {
    private final int code;
    private final int retryAfter;
    public TelegramApiException(int code, int retryAfter) {
        this(code, retryAfter, hint(code));
    }
    private TelegramApiException(int code, int retryAfter, String detail) {
        super("Telegram request failed (code " + code + "). " + detail);
        this.code = code; this.retryAfter = Math.max(0, Math.min(retryAfter, 3600));
    }
    public int code() { return code; }
    public int retryAfter() { return retryAfter; }
    public boolean retryable() { return code == 0 || code == 429 || code >= 500; }
    public static TelegramApiException transportFailure(Throwable failure) {
        // 只依据异常类型分类，不保留异常对象、原始信息或可能包含 Token 的 URL。
        for (int depth = 0; failure != null && depth < 10; depth++, failure = failure.getCause()) {
            String detail = null;
            if (failure instanceof EndpointAddressRejectedException)
                return new TelegramApiException(0, 0, failure.getMessage());
            if (failure instanceof java.net.http.HttpTimeoutException || failure instanceof java.util.concurrent.TimeoutException
                || failure instanceof java.net.SocketTimeoutException) detail = "Request timed out; check endpoint reachability and proxy latency";
            else if (failure instanceof java.net.UnknownHostException || failure instanceof java.nio.channels.UnresolvedAddressException)
                detail = "DNS resolution failed; check container DNS and endpoint hostname";
            else if (failure instanceof javax.net.ssl.SSLException) detail = "TLS handshake failed; check endpoint certificate and proxy TLS settings";
            else if (failure instanceof java.net.ConnectException) {
                if (failure.getCause() != null) continue;
                detail = "Connection could not be established; check endpoint port, proxy and firewall";
            }
            else if (failure instanceof InterruptedException || failure instanceof java.util.concurrent.CancellationException)
                detail = "Request was interrupted or cancelled";
            else if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException)
                detail = "Invalid API response; check that the endpoint returns Telegram Bot API JSON";
            if (detail != null) return new TelegramApiException(0, 0, detail);
        }
        return new TelegramApiException(0, 0);
    }
    private static String hint(int code) {
        return switch (code) {
            case 0 -> "Check network, DNS, TLS and API endpoint connectivity";
            case 400 -> "Check request parameters and target chat permissions";
            case 401 -> "Check bot token validity";
            case 403 -> "Check bot membership and permissions in the target chat";
            case 404 -> "Check API endpoint and proxy routing";
            case 409 -> "Check for another getUpdates consumer or an active webhook";
            case 413 -> "Media or response exceeds the supported size limit";
            case 429 -> "Rate limited by Telegram; respect retry delay";
            default -> code >= 500 ? "Telegram or proxy service is unavailable" : "Check API endpoint configuration";
        };
    }
}
