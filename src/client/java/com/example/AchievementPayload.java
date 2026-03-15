package com.example;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AchievementPayload(String json) implements CustomPacketPayload {

    // First, define the ID as a standalone constant
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("crappy_achievements", "achievement_data");

    // Second, initialize the TYPE using that ID
    public static final Type<AchievementPayload> TYPE = new Type<>(ID);

    // StreamCodec for serialization/deserialization
    // 2097152 is the max string length (2MB), which is standard for large JSON payloads
    public static final StreamCodec<FriendlyByteBuf, AchievementPayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeUtf(payload.json(), 2097152),
        buf -> new AchievementPayload(buf.readUtf(2097152))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
