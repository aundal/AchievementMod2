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
import net.minecraft.network.chat.Component;
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

import static net.minecraft.commands.Commands.literal;

public class ExampleMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("crappy_achievements");

    private static final ExecutorService ASYNC_IO = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter CHAT_FORMAT = DateTimeFormatter.ofPattern("dd/MM-yyyy HH:mm");
    private static final DateTimeFormatter MC_JSON_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("achievements")
                .executes(context -> {
                    triggerAchievementScan(context.getSource());
                    return 1;
                }));
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
                    String uuid = obj.get("uuid").getAsString();
                    String name = obj.get("name").getAsString();
                    cache.put(uuid, name);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Kunne ikke læse usercache.json", e);
        }
        return cache;
    }

    private void triggerAchievementScan(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Beregner leaderboard for 1.21.11...").withStyle(ChatFormatting.GRAY), false);

        // Tving gem af alle spillerdata før scanning
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

                // 1. Prøv online spillere først
                // 2. Fald tilbage til usercache.json
                // 3. Sidst udvej: vis kort UUID
                String playerName = Optional.ofNullable(server.getPlayerList().getPlayer(uuid))
                        .map(p -> p.getName().getString())
                        .orElseGet(() -> userCache.getOrDefault(uuidStr, "Ukendt (" + uuidStr.substring(0, 4) + ")"));

                int count = 0;
                long latest = 0;

                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
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
