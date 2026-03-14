package com.example;
 
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Identifier;
 
public record AchievementPayload(String json) implements CustomPacketPayload {
 
    public static final Type<AchievementPayload> TYPE = new Type<>(
        Identifier.of("crappy_achievements", "achievement_data")
    );
 
    public static final StreamCodec<FriendlyByteBuf, AchievementPayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeUtf(payload.json(), 2097152),
        buf -> new AchievementPayload(buf.readUtf(2097152))
    );
 
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
