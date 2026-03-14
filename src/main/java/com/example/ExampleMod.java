package com.example;

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
    public static final Logger LOGGER = LoggerFactory.getLogger("achievementmod");

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

    private void triggerAchievementScan(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Beregner leaderboard for 1.21.11...").withStyle(ChatFormatting.GRAY), false);

        List<String> validIds = new ArrayList<>();
        // 1.21.11 Mojang: getAdvancements().getAllAdvancements()
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isPresent()) {
                validIds.add(holder.id().toString());
            }
        }

        // 1.21.11 Mojang: PLAYER_ADVANCEMENTS_DIR is the correct field
        Path savePath = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);

        CompletableFuture.supplyAsync(() -> buildStats(server, savePath, validIds), ASYNC_IO)
            .thenAccept(results -> {
                server.execute(() -> {
                    if (results.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("Ingen data fundet.").withStyle(ChatFormatting.RED), false);
                        return;
                    }

                    source.sendSuccess(() -> Component.literal("--- Achievements Leaderboard ---").withStyle(ChatFormatting.GOLD), false);
                    int rank = 1;
                    for (PlayerResult res : results) {
                        String timeStr = res.lastTs() > 0 
                            ? OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(res.lastTs()), java.time.ZoneId.systemDefault()).format(CHAT_FORMAT)
                            : "Aldrig";

                        String line = String.format("%d. %s: %d/%d (Sidst: %s)", 
                            rank++, res.name(), res.count(), validIds.size(), timeStr);
                        
                        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.WHITE), false);
                    }
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

        List<PlayerResult> stats = new ArrayList<>();

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                String uuidStr = file.getName().replace(".json", "");
                if (uuidStr.length() < 32) continue; 

                UUID uuid = UUID.fromString(uuidStr);
                
                // 1.21.11 Mojang: getProfileCache() is the correct method
                // If this fails, it's because the server-side method is actually getProfileCache()
                String playerName = server.getProfileCache().get(uuid)
                        .map(profile -> profile.getName())
                        .orElse("Ukendt (" + uuidStr.substring(0, 4) + ")");

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
