package com.vortexmc.banish;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VortexMC extends JavaPlugin implements Listener, CommandExecutor {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd - HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Map<UUID, Punishment> mutedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> frozenPlayers = new ConcurrentHashMap<>();

    private static class Punishment {
        final long expiresAt;
        final String reason;
        final String staff;

        Punishment(long expiresAt, String reason, String staff) {
            this.expiresAt = expiresAt;
            this.reason = reason;
            this.staff = staff;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPunishments();
        Bukkit.getPluginManager().registerEvents(this, this);

        for (String name : new String[]{"ban", "mute", "freeze", "unban"}) {
            if (getCommand(name) != null) getCommand(name).setExecutor(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::expirePunishments, 20L, 20L);
        getLogger().info("VortexMC-banish enabled for Purpur/Paper 1.20.4.");
    }

    @Override
    public void onDisable() {
        savePunishments();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase();
        if (args.length < 1 || (cmd.equals("ban") && args.length < 2)) {
            sender.sendMessage(color("&cالاستخدام: /" + cmd + " <player> " +
                    (cmd.equals("ban") ? "<duration|perm> [reason]" : "[duration] [reason]")));
            return true;
        }

        String permission = switch (cmd) {
            case "ban", "unban" -> "vortexmc.admin.ban";
            case "mute" -> "vortexmc.staff.mute";
            case "freeze" -> "vortexmc.staff.freeze";
            default -> "";
        };
        if (!permission.isEmpty() && !sender.hasPermission(permission)) {
            sender.sendMessage(color("&cليس لديك صلاحية لاستخدام هذا الأمر."));
            return true;
        }

        String playerName = args[0];
        String staff = sender.getName();

        if (cmd.equals("unban")) {
            Bukkit.getBanList(BanList.Type.NAME).pardon(playerName);
            sender.sendMessage(color("&aتم فك حظر &f" + playerName + "&a."));
            sendDiscord("ban", "✅ فك الحظر (UNBAN)", staff, playerName, "N/A", "تم فك الحظر");
            return true;
        }

        if (cmd.equals("ban")) {
            String durationInput = args[1];
            long durationMillis = parseDuration(durationInput);
            if (durationMillis == Long.MIN_VALUE) {
                sender.sendMessage(color("&cمدة غير صحيحة. أمثلة: 30m أو 2h أو 7d أو perm"));
                return true;
            }
            String reason = joinArgs(args, 2, "بدون سبب محدد");
            Date expires = durationMillis < 0 ? null : new Date(System.currentTimeMillis() + durationMillis);
            Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, reason, expires, staff);

            Player online = Bukkit.getPlayerExact(playerName);
            if (online != null) {
                online.kickPlayer(color("&cتم حظرك من VortexMC\n&eالمدة: &f" + displayDuration(durationInput)
                        + "\n&eالسبب: &f" + reason));
            }
            sender.sendMessage(color("&aتم حظر &f" + playerName + " &aلمدة &f" + displayDuration(durationInput)));
            sendDiscord("ban", "🔨 عقوبة حظر (BAN)", staff, playerName, displayDuration(durationInput), reason);
            return true;
        }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(color("&cاللاعب غير متصل. أمر " + cmd + " يتطلب أن يكون اللاعب داخل السيرفر."));
            return true;
        }

        UUID uuid = target.getUniqueId();
        Map<UUID, Punishment> map = cmd.equals("mute") ? mutedPlayers : frozenPlayers;

        // كتابة الاسم فقط تفك العقوبة إذا كانت فعالة.
        if (args.length == 1 && map.containsKey(uuid)) {
            map.remove(uuid);
            savePunishments();
            String action = cmd.equals("mute") ? "UNMUTE" : "UNFREEZE";
            sender.sendMessage(color("&aتم " + (cmd.equals("mute") ? "فك الكتم عن " : "فك التجميد عن ") + target.getName()));
            target.sendMessage(color("&aتم " + (cmd.equals("mute") ? "فك الكتم عنك." : "فك التجميد عنك.")));
            sendDiscord(cmd, cmd.equals("mute") ? "🔊 فك الكتم (UNMUTE)" : "❄️ فك التجميد (UNFREEZE)",
                    staff, target.getName(), "N/A", "تم فك العقوبة");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(color("&cاكتب المدة. مثال: /" + cmd + " " + playerName + " 30m السبب"));
            return true;
        }

        long durationMillis = parseDuration(args[1]);
        if (durationMillis <= 0) {
            sender.sendMessage(color("&cالمدة لازم تكون أكبر من صفر، مثل 30s أو 10m أو 2h أو 1d."));
            return true;
        }
        String reason = joinArgs(args, 2, "بدون سبب محدد");
        long expiresAt = System.currentTimeMillis() + durationMillis;
        map.put(uuid, new Punishment(expiresAt, reason, staff));
        savePunishments();

        if (cmd.equals("mute")) {
            sender.sendMessage(color("&aتم كتم &f" + target.getName() + " &aلمدة &f" + displayDuration(args[1])));
            target.sendMessage(color("&cتم كتمك!\n&eالمدة: &f" + displayDuration(args[1]) + "\n&eالسبب: &f" + reason));
            sendDiscord("mute", "🔇 عقوبة كتم (MUTE)", staff, target.getName(), displayDuration(args[1]), reason);
        } else {
            sender.sendMessage(color("&aتم تجميد &f" + target.getName() + " &aلمدة &f" + displayDuration(args[1])));
            target.sendMessage(color("&cتم تجميدك!\n&eالمدة: &f" + displayDuration(args[1]) + "\n&eالسبب: &f" + reason));
            sendDiscord("freeze", "🧊 عقوبة تجميد (FREEZE)", staff, target.getName(), displayDuration(args[1]), reason);
        }
        return true;
    }

    // Accepts combined durations like 1d2h30m, and perm for permanent bans.
    private long parseDuration(String input) {
        if (input.equalsIgnoreCase("perm") || input.equalsIgnoreCase("permanent")) return -1L;
        Matcher matcher = DURATION_PART.matcher(input);
        int end = 0;
        long total = 0;
        try {
            while (matcher.find()) {
                if (matcher.start() != end) return Long.MIN_VALUE;
                long amount = Long.parseLong(matcher.group(1));
                long multiplier = switch (matcher.group(2).toLowerCase()) {
                    case "s" -> 1_000L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    default -> 0L;
                };
                total = Math.addExact(total, Math.multiplyExact(amount, multiplier));
                end = matcher.end();
            }
        } catch (ArithmeticException ex) {
            return Long.MIN_VALUE;
        }
        return end == input.length() && end > 0 ? total : Long.MIN_VALUE;
    }

    private String displayDuration(String raw) {
        if (raw.equalsIgnoreCase("perm") || raw.equalsIgnoreCase("permanent")) return "دائم (Permanent)";
        return raw.toLowerCase();
    }

    private String joinArgs(String[] args, int start, String fallback) {
        if (args.length <= start) return fallback;
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !frozenPlayers.containsKey(event.getPlayer().getUniqueId())) return;
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Punishment punishment = mutedPlayers.get(event.getPlayer().getUniqueId());
        if (punishment != null && punishment.expiresAt > System.currentTimeMillis()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&cلا يمكنك الكتابة لأنك مكتوم."));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Punishment mute = mutedPlayers.get(id);
        if (mute != null && mute.expiresAt <= System.currentTimeMillis()) mutedPlayers.remove(id);
        Punishment freeze = frozenPlayers.get(id);
        if (freeze != null && freeze.expiresAt <= System.currentTimeMillis()) frozenPlayers.remove(id);
        savePunishments();
    }

    private void expirePunishments() {
        long now = System.currentTimeMillis();
        boolean changed = mutedPlayers.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        changed |= frozenPlayers.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        if (changed) savePunishments();
    }

    private void loadPunishments() {
        var data = getDataFolder().toPath().resolve("punishments.yml").toFile();
        if (!data.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(data);
        loadMap(yaml, "mute", mutedPlayers);
        loadMap(yaml, "freeze", frozenPlayers);
        expirePunishments();
    }

    private void loadMap(org.bukkit.configuration.file.YamlConfiguration yaml, String path, Map<UUID, Punishment> map) {
        if (!yaml.isConfigurationSection(path)) return;
        for (String key : yaml.getConfigurationSection(path).getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String base = path + "." + key + ".";
                long expiry = yaml.getLong(base + "expiresAt");
                String reason = yaml.getString(base + "reason", "بدون سبب محدد");
                String staff = yaml.getString(base + "staff", "Unknown");
                if (expiry > System.currentTimeMillis()) map.put(uuid, new Punishment(expiry, reason, staff));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid UUID in punishments.yml: " + key);
            }
        }
    }

    private void savePunishments() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        saveMap(yaml, "mute", mutedPlayers);
        saveMap(yaml, "freeze", frozenPlayers);
        try {
            yaml.save(getDataFolder().toPath().resolve("punishments.yml").toFile());
        } catch (java.io.IOException ex) {
            getLogger().warning("Could not save punishments.yml: " + ex.getMessage());
        }
    }

    private void saveMap(org.bukkit.configuration.file.YamlConfiguration yaml, String path, Map<UUID, Punishment> map) {
        for (var entry : map.entrySet()) {
            String base = path + "." + entry.getKey() + ".";
            yaml.set(base + "expiresAt", entry.getValue().expiresAt);
            yaml.set(base + "reason", entry.getValue().reason);
            yaml.set(base + "staff", entry.getValue().staff);
        }
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void sendDiscord(String type, String title, String staff, String target, String duration, String reason) {
        String webhook = getConfig().getString("webhooks." + type, "").trim();
        if (webhook.isEmpty()) return;
        if (!webhook.startsWith("https://discord.com/api/webhooks/")) {
            getLogger().warning("Webhook URL is missing or invalid for " + type + ".");
            return;
        }

        String currentTime = TIME_FORMAT.format(Instant.now());
        String payload = "{\"embeds\":[{\"title\":\"" + json(title) + "\",\"color\":"
                + (type.equals("ban") ? 16711680 : type.equals("mute") ? 16753920 : 65535)
                + ",\"fields\":["
                + field("👤 اللاعب", target, true) + ","
                + field("🛡️ المسؤول", staff, true) + ","
                + field("⏳ مدة العقوبة", duration, false) + ","
                + field("📝 السبب", reason, false) + ","
                + field("📅 الوقت والتاريخ", currentTime, false)
                + "],\"footer\":{\"text\":\"VortexMC Punishment System\"}}]}";

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(webhook).openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    getLogger().warning("Discord webhook returned HTTP " + status + " for " + type + ".");
                }
            } catch (Exception ex) {
                getLogger().warning("Discord webhook failed for " + type + ": " + ex.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + json(name) + "\",\"value\":\"" + json(value) + "\",\"inline\":" + inline + "}";
    }

    private String json(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
