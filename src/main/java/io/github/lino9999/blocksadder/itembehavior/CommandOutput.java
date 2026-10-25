package io.github.lino9999.blocksadder.itembehavior;

import net.md_5.bungee.api.chat.BaseComponent;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class CommandOutput {
    private CommandOutput() {}

    static boolean dispatch(String command, boolean silent, String expected) {
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        List<String> messages = new ArrayList<>();
        CommandSender.Spigot chat =
                new CommandSender.Spigot() {
                    private void capture(BaseComponent... components) {
                        String message = BaseComponent.toPlainText(components);
                        append(messages, message);
                        if (!silent) console.spigot().sendMessage(components);
                    }

                    @Override
                    public void sendMessage(BaseComponent component) {
                        capture(component);
                    }

                    @Override
                    public void sendMessage(BaseComponent... components) {
                        capture(components);
                    }

                    @Override
                    public void sendMessage(UUID source, BaseComponent component) {
                        capture(component);
                    }

                    @Override
                    public void sendMessage(UUID source, BaseComponent... components) {
                        capture(components);
                    }
                };
        ConsoleCommandSender sender =
                (ConsoleCommandSender)
                        Proxy.newProxyInstance(
                                CommandOutput.class.getClassLoader(),
                                new Class<?>[] {ConsoleCommandSender.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("sendMessage")) {
                                        for (Object argument : arguments) {
                                            if (argument instanceof String text)
                                                append(messages, text);
                                            else if (argument instanceof String[] texts)
                                                for (String text : texts) append(messages, text);
                                        }
                                        if (silent) return null;
                                    }
                                    if (method.getName().equals("spigot")) return chat;
                                    try {
                                        return method.invoke(console, arguments);
                                    } catch (InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                });
        String[] words = command.split("\\s+");
        org.bukkit.command.PluginCommand plugin = Bukkit.getPluginCommand(words[0]);
        if (plugin == null && (silent || expected != null)) return false;
        boolean success =
                plugin == null
                        ? Bukkit.dispatchCommand(console, command)
                        : plugin.execute(
                                sender,
                                words[0],
                                java.util.Arrays.copyOfRange(words, 1, words.length));
        return expected == null
                ? success
                : messages.stream().anyMatch(message -> message.contains(expected));
    }

    private static void append(List<String> messages, String text) {
        if (messages.size() < 128)
            messages.add(text.length() <= 4096 ? text : text.substring(0, 4096));
    }
}
