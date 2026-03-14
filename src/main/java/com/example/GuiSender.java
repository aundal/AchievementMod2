package com.example;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public interface GuiSender {
    void send(ServerPlayer player, String json);
}
