package de.donutspin.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> Client: kompletter Spielzustand als JSON (kleiner als 8 KB). */
public record StatePayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StatePayload> TYPE = CustomPacketPayload.createType("donutspin_state");
    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC =
        StreamCodec.composite(ByteBufCodecs.STRING_UTF8, StatePayload::json, StatePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
