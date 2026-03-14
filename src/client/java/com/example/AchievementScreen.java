package com.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

public class AchievementScreen extends Screen {

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    private final List<PlayerEntry>      players      = new ArrayList<>();
    private final List<AdvancementEntry> advancements = new ArrayList<>();

    record PlayerEntry(UUID uuid, String name, Set<String> completed) {}
    record AdvancementEntry(String id, String namespace, String title, String description, String iconItem) {}

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private static final int LEFT_W       = 190;   // player panel width
    private static final int ENTRY_H      = 34;    // player list row height
    private static final int ICON_SIZE    = 20;    // achievement icon size
    private static final int ICON_GAP     = 3;     // gap between icons
    private static final int HEADER_H     = 16;    // namespace header height
    private static final int PANEL_PAD    = 6;     // inner padding

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private int selectedPlayer        = 0;
    private int playerScrollOffset    = 0;   // first visible player index
    private int achievementScrollY    = 0;   // px scrolled in achievement panel

    // Hover
    private AdvancementEntry hoveredAdv = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public AchievementScreen(String json) {
        super(Component.literal("Achievements"));
        parseJson(json);
    }

    private void parseJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            for (JsonElement el : root.getAsJsonArray("players")) {
                JsonObject p = el.getAsJsonObject();
                UUID uuid = UUID.fromString(p.get("uuid").getAsString());
                String name = p.get("name").getAsString();
                Set<String> completed = new HashSet<>();
                p.getAsJsonArray("completed").forEach(c -> completed.add(c.getAsString()));
                players.add(new PlayerEntry(uuid, name, completed));
            }

            for (JsonElement el : root.getAsJsonArray("advancements")) {
                JsonObject a = el.getAsJsonObject();
                advancements.add(new AdvancementEntry(
                    a.get("id").getAsString(),
                    a.get("namespace").getAsString(),
                    a.get("title").getAsString(),
                    a.get("description").getAsString(),
                    a.get("icon").getAsString()
                ));
            }
        } catch (Exception e) {
            ExampleMod.LOGGER.error("Failed to parse achievement GUI data", e);
        }
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        // No widgets needed — all rendering is manual
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g, mouseX, mouseY, delta);

        int leftX  = 8;
        int leftY  = 8;
        int leftH  = this.height - 16;

        int rightX = leftX + LEFT_W + 6;
        int rightY = leftY;
        int rightW = this.width - rightX - 8;
        int rightH = leftH;

        // ---- Left panel ----
        drawPanel(g, leftX, leftY, LEFT_W, leftH);
        g.drawString(font, Component.literal("Players").withStyle(ChatFormatting.GOLD),
            leftX + PANEL_PAD, leftY + PANEL_PAD, 0xFFFFFFFF, false);

        renderPlayerList(g, leftX, leftY + 20, LEFT_W, leftH - 20, mouseX, mouseY);

        // ---- Right panel ----
        drawPanel(g, rightX, rightY, rightW, rightH);

        if (!players.isEmpty()) {
            PlayerEntry sel = players.get(selectedPlayer);
            long done = advancements.stream().filter(a -> sel.completed().contains(a.id())).count();
            String title = sel.name() + "  " + done + "/" + advancements.size();
            g.drawString(font, Component.literal(title).withStyle(ChatFormatting.GOLD),
                rightX + PANEL_PAD, rightY + PANEL_PAD, 0xFFFFFFFF, false);

            renderAchievements(g, rightX, rightY + 20, rightW, rightH - 20, mouseX, mouseY);
        }

        // Tooltip — rendered last so it appears on top
        if (hoveredAdv != null) {
            renderAdvTooltip(g, hoveredAdv, mouseX, mouseY);
        }

        super.render(g, mouseX, mouseY, delta);
    }

    // Draws a dark rounded panel
    private void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x,     y,     x + w,     y + h,     0xDD0D1117);
        g.fill(x + 1, y,     x + w - 1, y + 1,     0xFF2A2D3E); // top border
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, 0xFF2A2D3E); // bottom
        g.fill(x,     y + 1, x + 1, y + h - 1,     0xFF2A2D3E); // left
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, 0xFF2A2D3E); // right
    }

    // ---- Player list ----

    private void renderPlayerList(GuiGraphics g, int x, int y, int w, int h,
                                   int mouseX, int mouseY) {
        g.enableScissor(x, y, x + w, y + h);

        int visibleRows = h / ENTRY_H;
        int endIdx = Math.min(players.size(), playerScrollOffset + visibleRows + 1);

        for (int i = playerScrollOffset; i < endIdx; i++) {
            PlayerEntry player = players.get(i);
            int ey = y + (i - playerScrollOffset) * ENTRY_H;

            boolean selected = (i == selectedPlayer);
            boolean hovered  = mouseX >= x && mouseX < x + w && mouseY >= ey && mouseY < ey + ENTRY_H;

            if (selected) {
                g.fill(x + 1, ey, x + w - 1, ey + ENTRY_H, 0x55FFAA00);
            } else if (hovered) {
                g.fill(x + 1, ey, x + w - 1, ey + ENTRY_H, 0x33FFFFFF);
            }

            // Divider
            g.fill(x + 4, ey + ENTRY_H - 1, x + w - 4, ey + ENTRY_H, 0x33FFFFFF);

            // Player head (20x20)
            renderHead(g, player.uuid(), x + PANEL_PAD, ey + 7, 20);

            // Name
            g.drawString(font, player.name(), x + 32, ey + 7,
                selected ? 0xFFFFAA00 : 0xFFEEEEEE, false);

            // Count
            long done = advancements.stream().filter(a -> player.completed().contains(a.id())).count();
            String count = done + "/" + advancements.size();
            g.drawString(font, count, x + 32, ey + 18, 0xFF888888, false);
        }

        g.disableScissor();
    }

    private void renderHead(GuiGraphics g, UUID uuid, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation skin = null;

        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
            if (info != null) skin = info.getSkin().texture();
        }
        if (skin == null) skin = DefaultPlayerSkin.get(uuid).texture();

        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }

    // ---- Achievement grid ----

    private void renderAchievements(GuiGraphics g, int panelX, int panelY,
                                     int panelW, int panelH,
                                     int mouseX, int mouseY) {
        PlayerEntry player = players.get(selectedPlayer);

        // Group by namespace
        Map<String, List<AdvancementEntry>> grouped = new LinkedHashMap<>();
        for (AdvancementEntry a : advancements) {
            grouped.computeIfAbsent(a.namespace(), k -> new ArrayList<>()).add(a);
        }
        Map<String, List<AdvancementEntry>> sorted = new LinkedHashMap<>();
        if (grouped.containsKey("minecraft")) sorted.put("minecraft", grouped.get("minecraft"));
        grouped.entrySet().stream()
            .filter(e -> !e.getKey().equals("minecraft"))
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sorted.put(e.getKey(), e.getValue()));

        int contentX    = panelX + PANEL_PAD;
        int iconsPerRow = Math.max(1, (panelW - PANEL_PAD * 2) / (ICON_SIZE + ICON_GAP));

        g.enableScissor(panelX, panelY, panelX + panelW, panelY + panelH);

        AdvancementEntry newHovered = null;
        int contentY = panelY + PANEL_PAD - achievementScrollY;

        for (Map.Entry<String, List<AdvancementEntry>> group : sorted.entrySet()) {
            List<AdvancementEntry> list = group.getValue();
            long doneCount = list.stream().filter(a -> player.completed().contains(a.id())).count();

            // Namespace header
            if (contentY + HEADER_H >= panelY && contentY < panelY + panelH) {
                g.fill(contentX, contentY, contentX + panelW - PANEL_PAD * 2, contentY + HEADER_H, 0x441A2050);
                String hdr = group.getKey() + "  (" + doneCount + "/" + list.size() + ")";
                g.drawString(font, Component.literal(hdr).withStyle(ChatFormatting.YELLOW),
                    contentX + 4, contentY + 4, 0xFFFFFFFF, false);
            }
            contentY += HEADER_H + 4;

            // Icons
            int col = 0;
            int rowY = contentY;
            for (AdvancementEntry adv : list) {
                int ix = contentX + col * (ICON_SIZE + ICON_GAP);
                int iy = rowY;

                if (iy + ICON_SIZE >= panelY && iy < panelY + panelH) {
                    boolean completed = player.completed().contains(adv.id());
                    renderAdvIcon(g, adv, ix, iy, completed);

                    // Hover detection
                    if (mouseX >= ix && mouseX < ix + ICON_SIZE
                            && mouseY >= iy && mouseY < iy + ICON_SIZE) {
                        newHovered = adv;
                        // Highlight border
                        g.fill(ix,              iy,              ix + ICON_SIZE, iy + 1,          0xFFFFFFFF);
                        g.fill(ix,              iy + ICON_SIZE - 1, ix + ICON_SIZE, iy + ICON_SIZE, 0xFFFFFFFF);
                        g.fill(ix,              iy,              ix + 1,         iy + ICON_SIZE,  0xFFFFFFFF);
                        g.fill(ix + ICON_SIZE - 1, iy,          ix + ICON_SIZE, iy + ICON_SIZE,  0xFFFFFFFF);
                    }
                }

                col++;
                if (col >= iconsPerRow) {
                    col = 0;
                    rowY += ICON_SIZE + ICON_GAP;
                }
            }
            if (col > 0) rowY += ICON_SIZE + ICON_GAP;
            contentY = rowY + 8;
        }

        g.disableScissor();
        hoveredAdv = newHovered;
    }

    private void renderAdvIcon(GuiGraphics g, AdvancementEntry adv, int x, int y, boolean completed) {
        ItemStack stack = resolveItem(adv.iconItem());

        if (completed) {
            // Full color
            g.renderItem(stack, x + 2, y + 2);
        } else {
            // Grayscale: render item then overlay with a dark semi-transparent fill
            g.renderItem(stack, x + 2, y + 2);
            g.fill(x + 2, y + 2, x + ICON_SIZE - 2, y + ICON_SIZE - 2, 0xBB111111);
        }
    }

    private void renderAdvTooltip(GuiGraphics g, AdvancementEntry adv, int mouseX, int mouseY) {
        PlayerEntry player = players.get(selectedPlayer);
        boolean completed = player.completed().contains(adv.id());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(adv.title()).withStyle(completed ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Component.literal(adv.description()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.empty());
        lines.add(Component.literal(completed ? "✔ Completed" : "✘ Not completed")
            .withStyle(completed ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED));

        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int leftX = 8;
        int leftY = 28;  // after header

        if (mx >= leftX && mx < leftX + LEFT_W) {
            int relY = (int) my - leftY;
            if (relY >= 0) {
                int idx = playerScrollOffset + relY / ENTRY_H;
                if (idx >= 0 && idx < players.size()) {
                    selectedPlayer = idx;
                    achievementScrollY = 0;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int leftX = 8;
        if (mx >= leftX && mx < leftX + LEFT_W) {
            // Scroll player list
            int maxScroll = Math.max(0, players.size() - (this.height - 40) / ENTRY_H);
            playerScrollOffset = Math.max(0, Math.min(maxScroll, playerScrollOffset - (int) dy));
        } else {
            // Scroll achievement panel
            achievementScrollY = Math.max(0, achievementScrollY - (int)(dy * 20));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { onClose(); return true; } // ESC
        return super.keyPressed(key, scan, mods);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ItemStack resolveItem(String itemId) {
        try {
            return BuiltInRegistries.ITEM
                .getOptional(ResourceLocation.parse(itemId))
                .map(ItemStack::new)
                .orElse(new ItemStack(Items.BARRIER));
        } catch (Exception e) {
            return new ItemStack(Items.BARRIER);
        }
    }
}
