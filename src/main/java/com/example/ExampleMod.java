package com.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import static net.minecraft.commands.Commands.literal;

public class ExampleMod implements ModInitializer {
    public static GuiSender guiSender = null;
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
                    CommandSourceStack source = context.getSource();
                    if (source.getEntity() instanceof ServerPlayer player) {
                        triggerGui(player, source.getServer());
                    } else {
                        triggerAchievementScan(source);
                    }
                    return 1;
                })
                .then(Commands.argument("username", StringArgumentType.word())
                    .executes(context -> {
                        String username = StringArgumentType.getString(context, "username");
                        triggerAchievementScan(context.getSource());
                        return 1;
                    })
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> {
                            triggerAchievementScan(context.getSource());
                            return 1;
                        })))
            );
        });
    }

    // -------------------------------------------------------------------------
    // GUI path — sends all data to the client
    // -------------------------------------------------------------------------

    private void triggerGui(ServerPlayer requestingPlayer, MinecraftServer server) {
        server.getPlayerList().saveAll();

        // Collect all displayable advancements
        List<AdvancementHolder> displayable = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isPresent()) {
                displayable.add(holder);
            }
        }

        Path savePath = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
        Map<String, String> userCache = loadUserCache(server);

        CompletableFuture.supplyAsync(() -> buildGuiJson(server, savePath, displayable, userCache), ASYNC_IO)
            .thenAccept(json -> server.execute(() -> {
                if (guiSender != null) guiSender.send(requestingPlayer, json);
            }))
            .exceptionally(ex -> {
                LOGGER.error("Error building GUI data", ex);
                return null;
            });
    }

    private String buildGuiJson(MinecraftServer server, Path savePath,
                                List<AdvancementHolder> displayable, Map<String, String> userCache) {
        JsonObject root = new JsonObject();

        // Build advancements array
        JsonArray advsArr = new JsonArray();
        for (AdvancementHolder holder : displayable) {
            holder.value().display().ifPresent(display -> {
                JsonObject a = new JsonObject();
                a.addProperty("id", holder.id().toString());
                a.addProperty("namespace", holder.id().getNamespace());
                a.addProperty("title", display.getTitle().getString());
                a.addProperty("description", display.getDescription().getString());
                // Icon item
                ItemStack icon = display.getIcon();
                String iconStr = Objects.toString(BuiltInRegistries.ITEM.getKey(icon.getItem()), "minecraft:barrier");
                a.addProperty("icon", iconStr);
                advsArr.add(a);
            });
        }
        root.add("advancements", advsArr);

        // Build players array
        Set<String> validIds = new HashSet<>();
        for (AdvancementHolder holder : displayable) validIds.add(holder.id().toString());

        JsonArray playersArr = new JsonArray();
        File folder = savePath.toFile();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try (FileReader reader = new FileReader(file)) {
                    String uuidStr = file.getName().replace(".json", "");
                    if (uuidStr.length() < 32) continue;
                    UUID uuid = UUID.fromString(uuidStr);

                    String name = Optional.ofNullable(server.getPlayerList().getPlayer(uuid))
                        .map(p -> p.getName().getString())
                        .orElseGet(() -> userCache.getOrDefault(uuidStr, "Ukendt (" + uuidStr.substring(0, 4) + ")"));

                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonArray completedArr = new JsonArray();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                        if (!entry.getValue().isJsonObject()) continue;
                        com.google.gson.JsonObject data = entry.getValue().getAsJsonObject();
                        if (validIds.contains(entry.getKey()) && data.has("done") && data.get("done").getAsBoolean()) {
                            completedArr.add(entry.getKey());
                        }
                    }

                    JsonObject player = new JsonObject();
                    player.addProperty("uuid", uuidStr);
                    player.addProperty("name", name);
                    player.add("completed", completedArr);
                    playersArr.add(player);
                } catch (Exception ignored) {}
            }
        }

        // Sort players by completion count descending
        List<com.google.gson.JsonElement> playerList = new ArrayList<>();
        playersArr.forEach(playerList::add);
        playerList.sort((a, b) -> {
            int ca = a.getAsJsonObject().getAsJsonArray("completed").size();
            int cb = b.getAsJsonObject().getAsJsonArray("completed").size();
            return Integer.compare(cb, ca);
        });
        JsonArray sortedPlayers = new JsonArray();
        playerList.forEach(sortedPlayers::add);
        root.add("players", sortedPlayers);

        return root.toString();
    }

    // -------------------------------------------------------------------------
    // Chat-based leaderboard (console fallback / /achievements <username>)
    // -------------------------------------------------------------------------

    private Map<String, String> loadUserCache(MinecraftServer server) {
        Map<String, String> cache = new HashMap<>();
        try {
            File cacheFile = server.getServerDirectory().resolve("usercache.json").toFile();
            if (!cacheFile.exists()) return cache;
            try (FileReader reader = new FileReader(cacheFile)) {
                com.google.gson.JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
                for (com.google.gson.JsonElement el : array) {
                    com.google.gson.JsonObject obj = el.getAsJsonObject();
                    cache.put(obj.get("uuid").getAsString(), obj.get("name").getAsString());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read usercache.json", e);
        }
        return cache;
    }

    private String findUuidByName(MinecraftServer server, String username) {
        Map<String, String> cache = loadUserCache(server);
        for (Map.Entry<String, String> entry : cache.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(username)) return entry.getKey();
        }
        return null;
    }

    private void triggerAchievementScan(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Beregner leaderboard...").withStyle(ChatFormatting.GRAY), false);
        server.getPlayerList().saveAll();

        List<String> validIds = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isPresent()) validIds.add(holder.id().toString());
        }

        Path savePath = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);

        CompletableFuture.supplyAsync(() -> buildStats(server, savePath, validIds), ASYNC_IO)
            .thenAccept(results -> server.execute(() -> {
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
                    String line = String.format("%-3d %-16s %8s  %s", rank++, res.name(), res.count() + "/" + total, timeStr);
                    source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.WHITE), false);
                }
                source.sendSuccess(() -> Component.literal(separator).withStyle(ChatFormatting.DARK_GRAY), false);
            }))
            .exceptionally(ex -> {
                LOGGER.error("Error during scan", ex);
                server.execute(() -> source.sendFailure(Component.literal("Intern fejl.")));
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
                com.google.gson.JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    if (validIds.contains(entry.getKey())) {
                        com.google.gson.JsonObject data = entry.getValue().getAsJsonObject();
                        if (data.has("done") && data.get("done").getAsBoolean()) {
                            count++;
                            if (data.has("criteria")) {
                                for (Map.Entry<String, com.google.gson.JsonElement> crit : data.getAsJsonObject("criteria").entrySet()) {
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
