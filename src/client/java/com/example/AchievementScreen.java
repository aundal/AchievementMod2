package com.example;

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
import net.minecraft.util.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.util.*;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class AchievementScreen extends Screen {

    private final List<PlayerEntry>      players      = new ArrayList<>();
    private final List<AdvancementEntry> advancements = new ArrayList<>();

    record PlayerEntry(UUID uuid, String name, Set<String> completed) {}
    record AdvancementEntry(String id, String namespace, String title, String description, String iconItem) {}

    private static final int LEFT_W    = 190;
    private static final int ENTRY_H   = 34;
    private static final int ICON_SIZE = 20;
    private static final int ICON_GAP  = 3;
    private static final int HEADER_H  = 16;
    private static final int PANEL_PAD = 6;

    private int selectedPlayer     = 0;
    private int playerScrollOffset = 0;
    private int achievementScrollY = 0;
    private AdvancementEntry hoveredAdv  = null;
    private int              hoveredAdvX = 0;
    private int              hoveredAdvY = 0;

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

    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {}

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g, mouseX, mouseY, delta);

        int leftX = 8, leftY = 8, leftH = this.height - 16;
        int rightX = leftX + LEFT_W + 6, rightY = leftY;
        int rightW = this.width - rightX - 8, rightH = leftH;

        drawPanel(g, leftX, leftY, LEFT_W, leftH);
        g.drawString(font, Component.literal("Players").withStyle(ChatFormatting.GOLD),
            leftX + PANEL_PAD, leftY + PANEL_PAD, 0xFFFFFFFF, false);
        renderPlayerList(g, leftX, leftY + 20, LEFT_W, leftH - 20, mouseX, mouseY);

        drawPanel(g, rightX, rightY, rightW, rightH);
        if (!players.isEmpty()) {
            PlayerEntry sel = players.get(selectedPlayer);
            long done = advancements.stream().filter(a -> sel.completed().contains(a.id())).count();
            g.drawString(font,
                Component.literal(sel.name() + "  " + done + "/" + advancements.size()).withStyle(ChatFormatting.GOLD),
                rightX + PANEL_PAD, rightY + PANEL_PAD, 0xFFFFFFFF, false);
            renderAchievements(g, rightX, rightY + 20, rightW, rightH - 20, mouseX, mouseY);
        }

        if (hoveredAdv != null) renderAdvTooltip(g, hoveredAdv, hoveredAdvX, hoveredAdvY);

        super.render(g, mouseX, mouseY, delta);
    }

    private void drawPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x,         y,         x + w,     y + h,     0xDD0D1117);
        g.fill(x + 1,     y,         x + w - 1, y + 1,     0xFF2A2D3E);
        g.fill(x + 1,     y + h - 1, x + w - 1, y + h,     0xFF2A2D3E);
        g.fill(x,         y + 1,     x + 1,     y + h - 1, 0xFF2A2D3E);
        g.fill(x + w - 1, y + 1,     x + w,     y + h - 1, 0xFF2A2D3E);
    }

    private void renderPlayerList(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        g.enableScissor(x, y, x + w, y + h);
        int endIdx = Math.min(players.size(), playerScrollOffset + h / ENTRY_H + 1);
        for (int i = playerScrollOffset; i < endIdx; i++) {
            PlayerEntry player = players.get(i);
            int ey = y + (i - playerScrollOffset) * ENTRY_H;
            boolean selected = (i == selectedPlayer);
            boolean hovered  = mouseX >= x && mouseX < x + w && mouseY >= ey && mouseY < ey + ENTRY_H;
            if (selected)     g.fill(x + 1, ey, x + w - 1, ey + ENTRY_H, 0x55FFAA00);
            else if (hovered) g.fill(x + 1, ey, x + w - 1, ey + ENTRY_H, 0x33FFFFFF);
            g.fill(x + 4, ey + ENTRY_H - 1, x + w - 4, ey + ENTRY_H, 0x33FFFFFF);
            renderHead(g, player.uuid(), x + PANEL_PAD, ey + 7, 20);
            g.drawString(font, player.name(), x + 32, ey + 7, selected ? 0xFFFFAA00 : 0xFFEEEEEE, false);
            long done = advancements.stream().filter(a -> player.completed().contains(a.id())).count();
            g.drawString(font, done + "/" + advancements.size(), x + 32, ey + 18, 0xFF888888, false);
        }
        g.disableScissor();
    }

    // Use reflection to get texture from PlayerSkin — avoids version-specific accessor name
    private void renderHead(GuiGraphics g, UUID uuid, int x, int y, int size) {
        Identifier skin = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
                if (info != null) {
                    Object playerSkin = info.getSkin();
                    // Try both possible accessor names via reflection
                    for (String methodName : new String[]{"texture", "getTexture"}) {
                        try {
                            Method m = playerSkin.getClass().getMethod(methodName);
                            skin = (Identifier) m.invoke(playerSkin);
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (skin == null) {
                Object defaultSkin = DefaultPlayerSkin.get(uuid);
                for (String methodName : new String[]{"texture", "getTexture"}) {
                    try {
                        Method m = defaultSkin.getClass().getMethod(methodName);
                        skin = (Identifier) m.invoke(defaultSkin);
                        break;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        if (skin == null) skin = Identifier.ofVanilla("textures/entity/player/wide/steve.png");
        PlayerFaceRenderer.draw(g, skin, x, y, size);
    }

    private void renderAchievements(GuiGraphics g, int panelX, int panelY,
                                     int panelW, int panelH, int mouseX, int mouseY) {
        PlayerEntry player = players.get(selectedPlayer);

        Map<String, List<AdvancementEntry>> grouped = new LinkedHashMap<>();
        for (AdvancementEntry a : advancements)
            grouped.computeIfAbsent(a.namespace(), k -> new ArrayList<>()).add(a);

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
        int newHovX = 0, newHovY = 0;
        int contentY = panelY + PANEL_PAD - achievementScrollY;

        for (Map.Entry<String, List<AdvancementEntry>> group : sorted.entrySet()) {
            List<AdvancementEntry> list = group.getValue();
            long doneCount = list.stream().filter(a -> player.completed().contains(a.id())).count();

            if (contentY + HEADER_H >= panelY && contentY < panelY + panelH) {
                g.fill(contentX, contentY, contentX + panelW - PANEL_PAD * 2, contentY + HEADER_H, 0x441A2050);
                g.drawString(font,
                    Component.literal(group.getKey() + "  (" + doneCount + "/" + list.size() + ")").withStyle(ChatFormatting.YELLOW),
                    contentX + 4, contentY + 4, 0xFFFFFFFF, false);
            }
            contentY += HEADER_H + 4;

            int col = 0, rowY = contentY;
            for (AdvancementEntry adv : list) {
                int ix = contentX + col * (ICON_SIZE + ICON_GAP);
                int iy = rowY;
                if (iy + ICON_SIZE >= panelY && iy < panelY + panelH) {
                    boolean completed = player.completed().contains(adv.id());
                    g.renderItem(resolveItem(adv.iconItem()), ix + 2, iy + 2);
                    if (!completed) g.fill(ix + 2, iy + 2, ix + ICON_SIZE - 2, iy + ICON_SIZE - 2, 0xBB111111);
                    if (mouseX >= ix && mouseX < ix + ICON_SIZE && mouseY >= iy && mouseY < iy + ICON_SIZE) {
                        newHovered = adv; newHovX = mouseX; newHovY = mouseY;
                        g.fill(ix,               iy,                 ix + ICON_SIZE, iy + 1,             0xFFFFFFFF);
                        g.fill(ix,               iy + ICON_SIZE - 1, ix + ICON_SIZE, iy + ICON_SIZE,     0xFFFFFFFF);
                        g.fill(ix,               iy,                 ix + 1,         iy + ICON_SIZE,     0xFFFFFFFF);
                        g.fill(ix + ICON_SIZE - 1, iy,               ix + ICON_SIZE, iy + ICON_SIZE,     0xFFFFFFFF);
                    }
                }
                col++;
                if (col >= iconsPerRow) { col = 0; rowY += ICON_SIZE + ICON_GAP; }
            }
            if (col > 0) rowY += ICON_SIZE + ICON_GAP;
            contentY = rowY + 8;
        }

        g.disableScissor();
        hoveredAdv = newHovered;
        hoveredAdvX = newHovX;
        hoveredAdvY = newHovY;
    }

    private void renderAdvTooltip(GuiGraphics g, AdvancementEntry adv, int mouseX, int mouseY) {
        PlayerEntry player = players.get(selectedPlayer);
        boolean completed  = player.completed().contains(adv.id());

        String title  = adv.title();
        String desc   = adv.description();
        String status = completed ? "\u2714 Completed" : "\u2718 Not completed";
        int titleColor  = completed ? 0xFF55FF55 : 0xFFFF5555;
        int statusColor = completed ? 0xFF00AA00 : 0xFFAA0000;

        int maxW    = Math.max(font.width(title), Math.max(font.width(desc), font.width(status)));
        int pad     = 5;
        int lineH   = font.lineHeight + 2;
        int boxW    = maxW + pad * 2;
        int boxH    = lineH * 3 + pad * 2 + 4;
        int tx      = mouseX + 12;
        int ty      = mouseY - 12;
        if (tx + boxW > this.width  - 4) tx = mouseX - boxW - 4;
        if (ty + boxH > this.height - 4) ty = this.height - boxH - 4;
        if (ty < 4) ty = 4;

        g.fill(tx - 1,        ty - 1,        tx + boxW + 1, ty + boxH + 1, 0xFF1A1A2E);
        g.fill(tx,            ty,            tx + boxW,     ty + boxH,     0xEE0D1117);
        g.fill(tx,            ty,            tx + 1,        ty + boxH,     0xFF444466);
        g.fill(tx + boxW - 1, ty,            tx + boxW,     ty + boxH,     0xFF444466);
        g.fill(tx,            ty,            tx + boxW,     ty + 1,        0xFF444466);
        g.fill(tx,            ty + boxH - 1, tx + boxW,     ty + boxH,     0xFF444466);

        int textX = tx + pad, textY = ty + pad;
        g.drawString(font, title,  textX, textY,                  titleColor,  false);
        g.drawString(font, desc,   textX, textY + lineH,          0xFFAAAAAA,  false);
        g.fill(textX, textY + lineH * 2, textX + maxW, textY + lineH * 2 + 1, 0xFF444444);
        g.drawString(font, status, textX, textY + lineH * 2 + 3,  statusColor, false);
    }

    // -------------------------------------------------------------------------
    // Input — no @Override to avoid signature mismatch in 1.21.x
    // -------------------------------------------------------------------------

    public boolean mouseClicked(double mx, double my, int button) {
        int leftX = 8, leftY = 28;
        if (mx >= leftX && mx < leftX + LEFT_W) {
            int idx = playerScrollOffset + ((int) my - leftY) / ENTRY_H;
            if (idx >= 0 && idx < players.size()) {
                selectedPlayer = idx;
                achievementScrollY = 0;
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (mx >= 8 && mx < 8 + LEFT_W) {
            int maxScroll = Math.max(0, players.size() - (this.height - 40) / ENTRY_H);
            playerScrollOffset = Math.max(0, Math.min(maxScroll, playerScrollOffset - (int) dy));
        } else {
            achievementScrollY = Math.max(0, achievementScrollY - (int)(dy * 20));
        }
        return true;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { onClose(); return true; }
        return false;
    }

    private ItemStack resolveItem(String itemId) {
        try {
            return BuiltInRegistries.ITEM
                .getOptional(Identifier.of(itemId))
                .map(ItemStack::new)
                .orElse(new ItemStack(Items.BARRIER));
        } catch (Exception e) {
            return new ItemStack(Items.BARRIER);
        }
    }
}
