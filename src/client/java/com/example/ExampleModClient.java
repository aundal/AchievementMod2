package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class ExampleModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(AchievementPayload.TYPE, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() ->
                Minecraft.getInstance().setScreen(new AchievementScreen(json))
            );
        });
    }
}
