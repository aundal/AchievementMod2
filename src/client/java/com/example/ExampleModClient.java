package com.example;
 
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
 
public class ExampleModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register the packet type
        PayloadTypeRegistry.playS2C().register(AchievementPayload.TYPE, AchievementPayload.CODEC);
 
        // Register the client receiver — opens the GUI when packet arrives
        ClientPlayNetworking.registerGlobalReceiver(AchievementPayload.TYPE, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() ->
                Minecraft.getInstance().setScreen(new AchievementScreen(json))
            );
        });
 
        // Wire the sender so ExampleMod (server side) can send packets to players
        ExampleMod.guiSender = (player, json) ->
            ServerPlayNetworking.send(player, new AchievementPayload(json));
    }
}
 
