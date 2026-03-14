package com.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import static net.minecraft.commands.Commands.literal;

public class ExampleMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("crappy_achievements");

    private static final ExecutorService ASYNC_IO = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter CHAT_FORMAT = DateTimeFormatter.ofPattern("dd/MM-yyyy HH:mm");
    private static final DateTimeFormatter MC_JSON_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
    private static final int PAGE_SIZE = 15;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("achievements")
                .executes(context -> {
                    triggerAchievementScan(context.getSource());
                    return 1;
                })
                .then(Commands.argument("username", StringArgumentType.word())
                    .executes(context -> {
                        String username = StringArgumentType.getString(context, "username");
                        triggerPlayerAchievements(context.getSource(), username, 1);
                        return 1;
                    })
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> {
                            String username = StringArgumentType.getString(context, "username");
                            int page = IntegerArgumentType.getInteger(context, "page");
                            triggerPlayerAchievements(context.getSource(), username, page);
                            return 1;
                        })))
            );
        });
    }

    private Map<String, String> loadUserCache(MinecraftServer server) {
        Map<String, String> cache = new HashMap<>();
        try {
            File cacheFile = server.getServerDirectory().resolve("usercache.json").toFile();
            if (!cacheFile.exists()) return cache;
            try (FileReader reader = new FileReader(cacheFile)) {
                JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
                for (JsonElement el : array) {
                    JsonObject obj = el.getAsJsonObject();
                    cache.put(obj.get("uuid").getAsString(), obj.get("name").getAsString());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Kunne ikke læse usercache.json", e);
        }
        return cache;
    }

    private String findUuidByName(MinecraftServer server, String username) {
        Map<String, String> cache = loadUserCache(server);
        for (Map.Entry<String, String> entry : cache.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(username)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void triggerPlayerAchievements(CommandSourceStack source, String username, int page) {
        MinecraftServer server = source.getServer();
        server.getPlayerList().saveAll();

        List<AdvancementHolder> allAdvancements = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isPresent()) {
                allAdvancements.add(holder);
            }
        }

        String uuid = findUuidByName(server, username);
        if (uuid == null) {
            source.sendSuccess(() -> Component.literal("Spiller '" + username + "' ikke fundet i usercache.").withStyle(ChatFormatting.RED), false);
            return;
        }

        Path savePath = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
        File advFile = savePath.resolve(uuid + ".json").toFile();

        if (!advFile.exists()) {
            source.sendSuccess(() -> Component.literal("Ingen advancement-data fundet for " + username + ".").withStyle(ChatFormatting.RED), false);
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            Set<String> done = new HashSet<>();
            try (FileReader reader = new FileReader(advFile)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject data = entry.getValue().getAsJsonObject();
                    if (data.has("done") && data.get("done").getAsBoolean()) {
                        done.add(entry.getKey());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Fejl ved læsning af advancement-fil", e);
            }
            return done;
        }, ASYNC_IO).thenAccept(done -> {
            server.execute(() -> {
                // Group by namespace, minecraft first then rest alphabetically
                Map<String, List<AdvancementHolder>> grouped = new LinkedHashMap<>();
                for (AdvancementHolder holder : allAdvancements) {
                    grouped.computeIfAbsent(holder.id().getNamespace(), k -> new ArrayList<>()).add(holder);
                }

                Map<String, List<AdvancementHolder>> sorted = new LinkedHashMap<>();
                if (grouped.containsKey("minecraft")) sorted.put("minecraft", grouped.get("minecraft"));
                grouped.entrySet().stream()
                    .filter(e -> !e.getKey().equals("minecraft"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));

                // Flatten into rows
                record Row(String text, String hover, ChatFormatting color, boolean isHeader) {}
                List<Row> rows = new ArrayList<>();

                for (Map.Entry<String, List<AdvancementHolder>> group : sorted.entrySet()) {
                    long groupDone = group.getValue().stream().filter(h -> done.contains(h.id().toString())).count();
                    rows.add(new Row(group.getKey() + " (" + groupDone + "/" + group.getValue().size() + "):", null, ChatFormatting.YELLOW, true));

                    for (AdvancementHolder holder : group.getValue()) {
                        boolean completed = done.contains(holder.id().toString());
                        String displayName = holder.value().display().map(d -> d.getTitle().getString()).orElse(holder.id().toString());
                        String description = holder.value().display().map(d -> d.getDescription().getString()).orElse("Ingen beskrivelse.");
                        rows.add(new Row(
                            (completed ? "  [+] " : "  [-] ") + displayName,
                            description,
                            completed ? ChatFormatting.GREEN : ChatFormatting.RED,
                            false
                        ));
                    }
                }

                int totalPages = (int) Math.ceil((double) rows.size() / PAGE_SIZE);
                int clampedPage = Math.min(page, totalPages);
                int from = (clampedPage - 1) * PAGE_SIZE;
                int to = Math.min(from + PAGE_SIZE, rows.size());

                // Header
                source.sendSuccess(() -> Component.literal(
                    "--- " + username + "'s achievements (" + done.size() + "/" + allAdvancements.size() + ") ---"
                ).withStyle(ChatFormatting.GOLD), false);

                // Rows for this page
                for (int i = from; i < to; i++) {
                    Row row = rows.get(i);
                    Component line;
                    if (row.isHeader() || row.hover() == null) {
                        line = Component.literal(row.text()).withStyle(row.color());
                    } else {
                        line = Component.literal(row.text())
                            .withStyle(style -> style
                                .withColor(row.color())
                                .withHoverEvent(new HoverEvent.ShowText(
                                    Component.literal(row.hover()).withStyle(ChatFormatting.GRAY)
                                ))
                            );
                    }
                    source.sendSuccess(() -> line, false);
                }

                // Clickable prev / page counter / next
                if (totalPages > 1) {
                    MutableComponent nav = Component.literal("");

                    if (clampedPage > 1) {
                        nav.append(Component.literal("[◀]")
                            .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent.RunCommand("/achievements " + username + " " + (clampedPage - 1)))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Forrige side").withStyle(ChatFormatting.GRAY)))
                            ));
                    } else {
                        nav.append(Component.literal("[◀]").withStyle(ChatFormatting.DARK_GRAY));
                    }

                    nav.append(Component.literal("  side " + clampedPage + "/" + totalPages + "  ").withStyle(ChatFormatting.DARK_GRAY));

                    if (clampedPage < totalPages) {
                        nav.append(Component.literal("[▶]")
                            .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent.RunCommand("/achievements " + username + " " + (clampedPage + 1)))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Næste side").withStyle(ChatFormatting.GRAY)))
                            ));
                    } else {
                        nav.append(Component.literal("[▶]").withStyle(ChatFormatting.DARK_GRAY));
                    }

                    source.sendSuccess(() -> nav, false);
                }
            });
        }).exceptionally(ex -> {
            LOGGER.error("Fejl under achievement-liste", ex);
            server.execute(() -> source.sendFailure(Component.literal("Intern fejl.")));
            return null;
        });
    }

    private void triggerAchievementScan(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Beregner leaderboard...").withStyle(ChatFormatting.GRAY), false);

        server.getPlayerList().saveAll();

        List<String> validIds = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isPresent()) {
                validIds.add(holder.id().toString());
            }
        }

        Path savePath = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);

        CompletableFuture.supplyAsync(() -> buildStats(server, savePath, validIds), ASYNC_IO)
            .thenAccept(results -> {
                server.execute(() -> {
                    if (results.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("Ingen data fundet.").withStyle(ChatFormatting.RED), false);
                        return;
                    }

                    int total = validIds.size();
                    String header = String.format("%-3s %-16s %8s  %s", "#", "Spiller", "Adv", "Sidst");
                    String separator = "-".repeat(44);

                    source.sendSuccess(() -> Component.literal("--- Achievements Leaderboard ---").withStyle(ChatFormatting.GOLD), false);
                    source.sendSuccess(() -> Component.literal(header).withStyle(ChatFormatting.YELLOW), false);
                    source.sendSuccess(() -> Component.literal(separator).withStyle(ChatFormatting.DARK_GRAY), false);

                    int rank = 1;
                    for (PlayerResult res : results) {
                        String timeStr = res.lastTs() > 0
                            ? OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(res.lastTs()), java.time.ZoneId.systemDefault()).format(CHAT_FORMAT)
                            : "Aldrig";

                        String score = res.count() + "/" + total;
                        String line = String.format("%-3d %-16s %8s  %s", rank++, res.name(), score, timeStr);
                        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.WHITE), false);
                    }

                    source.sendSuccess(() -> Component.literal(separator).withStyle(ChatFormatting.DARK_GRAY), false);
                });
            })
            .exceptionally(ex -> {
                LOGGER.error("Fejl under scanning", ex);
                server.execute(() -> source.sendFailure(Component.literal("Intern fejl under scanning.")));
                return null;
            });
    }

    private List<PlayerResult> buildStats(MinecraftServer server, Path path, List<String> validIds) {
        File folder = path.toFile();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return Collections.emptyList();

        Map<String, String> userCache = loadUserCache(server);
        List<PlayerResult> stats = new ArrayList<>();

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                String uuidStr = file.getName().replace(".json", "");
                if (uuidStr.length() < 32) continue;

                UUID uuid = UUID.fromString(uuidStr);

                String playerName = Optional.ofNullable(server.getPlayerList().getPlayer(uuid))
                        .map(p -> p.getName().getString())
                        .orElseGet(() -> userCache.getOrDefault(uuidStr, "Ukendt (" + uuidStr.substring(0, 4) + ")"));

                int count = 0;
                long latest = 0;

                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    if (validIds.contains(entry.getKey())) {
                        JsonObject data = entry.getValue().getAsJsonObject();
                        if (data.has("done") && data.get("done").getAsBoolean()) {
                            count++;
                            if (data.has("criteria")) {
                                for (Map.Entry<String, JsonElement> crit : data.getAsJsonObject("criteria").entrySet()) {
                                    try {
                                        long ts = OffsetDateTime.parse(crit.getValue().getAsString(), MC_JSON_FORMAT).toInstant().toEpochMilli();
                                        if (ts > latest) latest = ts;
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
                if (count > 0) stats.add(new PlayerResult(playerName, count, latest));
            } catch (Exception ignored) {}
        }

        stats.sort((a, b) -> b.count() != a.count() ? Integer.compare(b.count(), a.count()) : Long.compare(b.lastTs(), a.lastTs()));
        return stats;
    }

    public record PlayerResult(String name, int count, long lastTs) {}
}
