package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class ExampleModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register the packet type first
        PayloadTypeRegistry.playS2C().register(AchievementPayload.TYPE, AchievementPayload.CODEC);
    
        // Then register the receiver
        ClientPlayNetworking.registerGlobalReceiver(AchievementPayload.TYPE, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() ->
                Minecraft.getInstance().setScreen(new AchievementScreen(json))
            );
        });
    
        // Wire the sender so ExampleMod can send packets
        ExampleMod.guiSender = (player, json) ->
            ServerPlayNetworking.send(player, new AchievementPayload(json));
    }
}
