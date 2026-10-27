package vip.mystery0.halo.telegrammoment.telegram;

import java.util.Locale;
import java.util.regex.Pattern;

/** 保留命令先于正文处理，指定其他 Bot 的命令也不能变成瞬间。 */
public record TelegramCommand(String kind, String hint) {
    private static final Pattern COMMAND = Pattern.compile("^/([A-Za-z][A-Za-z0-9_]*)(?:@([A-Za-z0-9_]+))?(?:\\s+(.*))?$", Pattern.DOTALL);
    public static final String HELP = "请回复需要操作的原消息：/redo 重新同步，/rm 删除对应瞬间。相册按整组处理；/help 查看帮助。";

    public static TelegramCommand parse(String text, String botUsername) {
        var matcher = COMMAND.matcher(text == null ? "" : text.trim());
        if (!matcher.matches()) return new TelegramCommand("NONE", "");
        String recipient = matcher.group(2);
        if (recipient != null && (botUsername == null || !recipient.equalsIgnoreCase(botUsername)))
            return new TelegramCommand("OTHER_BOT", "");
        String command = matcher.group(1).toLowerCase(Locale.ROOT);
        if (command.equals("help") || command.equals("start")) return new TelegramCommand("HINT", HELP);
        if (!command.equals("rm") && !command.equals("redo"))
            return new TelegramCommand("HINT", "无法识别这个命令。" + HELP);
        if (matcher.group(3) != null && !matcher.group(3).isBlank())
            return new TelegramCommand("HINT", "命令不接受参数。" + HELP);
        return new TelegramCommand(command.equals("rm") ? "DELETE" : "PUBLISH", "");
    }
}
