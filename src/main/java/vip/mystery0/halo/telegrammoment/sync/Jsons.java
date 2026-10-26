package vip.mystery0.halo.telegrammoment.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

public final class Jsons {
    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private Jsons() { }
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize sync state"); }
    }
    public static <T> T read(String value, TypeReference<T> type) {
        try { return MAPPER.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Invalid persisted sync state"); }
    }
}
