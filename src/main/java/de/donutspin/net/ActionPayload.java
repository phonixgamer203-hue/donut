package de.donutspin.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> Server: {"a":"bet","amt":1000000} | {"a":"cancel"} | {"a":"sync"} | {"a":"close"}. */
public record ActionPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ActionPayload> TYPE = CustomPacketPayload.createType("donutspin_action");
    public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC =
        StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ActionPayload::json, ActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
