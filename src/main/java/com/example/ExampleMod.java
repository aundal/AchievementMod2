package com.aundal.achievementmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.advancement.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
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

import static net.minecraft.server.command.CommandManager.literal;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "achievementmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Dedicated thread pool for IO to prevent server lag
    private static final ExecutorService ASYNC_IO = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter CHAT_FORMAT = DateTimeFormatter.ofPattern("dd/MM-yyyy HH:mm");
    // Minecraft format: "2024-05-20 12:34:56 +0200"
    private static final DateTimeFormatter MC_JSON_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    @Override
    public void onInitialize() {
        LOGGER.info("AchievementMod initialized for 1.21.1");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("achievements")
                .executes(context -> {
                    triggerAchievementScan(context.getSource());
                    return 1;
                }));
        });
    }

    private void triggerAchievementScan(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        source.sendFeedback(() -> Text.literal("Beregner leaderboard...").formatted(Formatting.GRAY), false);

        // Get valid advancement IDs (those that have a display/icon) on the Main Thread
        List<String> validIds = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancementLoader().getAdvancements()) {
            if (holder.value().display().isPresent()) {
                validIds.add(holder.id().toString());
            }
        }

        Path savePath = server.getSavePath(WorldSavePath.ADVANCEMENTS);

        // Run file IO asynchronously
        CompletableFuture.supplyAsync(() -> buildStats(server, savePath, validIds), ASYNC_IO)
            .thenAccept(results -> {
                // Return to Main Thread to send chat messages
                server.execute(() -> {
                    if (results.isEmpty()) {
                        source.sendFeedback(() -> Text.literal("Ingen data fundet i world/advancements.").formatted(Formatting.RED), false);
                        return;
                    }

                    source.sendFeedback(() -> Text.literal("--- Achievements Leaderboard ---").formatted(Formatting.GOLD), false);
                    int rank = 1;
                    for (PlayerResult res : results) {
                        String timeStr = res.lastTs() > 0 
                            ? OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(res.lastTs()), java.time.ZoneId.systemDefault()).format(CHAT_FORMAT)
                            : "Aldrig";

                        String line = String.format("%d. %s: %d/%d (Sidst: %s)", 
                            rank++, res.name(), res.count(), validIds.size(), timeStr);
                        
                        source.sendFeedback(() -> Text.literal(line).formatted(Formatting.WHITE), false);
                    }
                });
            })
            .exceptionally(ex -> {
                LOGGER.error("Error scanning achievements", ex);
                server.execute(() -> source.sendError(Text.literal("Intern fejl under scanning af filer.")));
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
                String filename = file.getName().replace(".json", "");
                // Skip non-player files (like Data Packs)
                if (filename.length() < 32) continue; 

                UUID uuid = UUID.fromString(filename);
                String playerName = server.getUserCache().getByUuid(uuid)
                        .map(profile -> profile.getName())
                        .orElse("Ukendt (" + filename.substring(0, 4) + ")");

                int count = 0;
                long latest = 0;

                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    String advId = entry.getKey();
                    
                    if (validIds.contains(advId)) {
                        JsonObject data = entry.getValue().getAsJsonObject();
                        // In 1.21, "done" indicates completion
                        if (data.has("done") && data.get("done").getAsBoolean()) {
                            count++;
                            // Parse criteria to find the latest completion time
                            if (data.has("criteria")) {
                                JsonObject criteria = data.getAsJsonObject("criteria");
                                for (Map.Entry<String, JsonElement> crit : criteria.entrySet()) {
                                    try {
                                        String dateStr = crit.getValue().getAsString();
                                        long ts = OffsetDateTime.parse(dateStr, MC_JSON_FORMAT).toInstant().toEpochMilli();
                                        if (ts > latest) latest = ts;
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
                if (count > 0) {
                    stats.add(new PlayerResult(playerName, count, latest));
                }
            } catch (Exception e) {
                LOGGER.warn("Kunne ikke læse fil: " + file.getName());
            }
        }
        
        // Sort by count (descending), then by latest timestamp (descending)
        stats.sort((a, b) -> {
            if (b.count() != a.count()) return Integer.compare(b.count(), a.count());
            return Long.compare(b.lastTs(), a.lastTs());
        });
        
        return stats;
    }

    public record PlayerResult(String name, int count, long lastTs) {}
}
