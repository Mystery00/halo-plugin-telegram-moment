package vip.mystery0.halo.telegrammoment.publisher;

import run.halo.app.extension.Unstructured;

public interface MomentGateway {
    Unstructured get(String name);
    Unstructured findLegacy(long chat, long message);
    Unstructured save(Unstructured moment);
    void delete(Unstructured moment);
}
