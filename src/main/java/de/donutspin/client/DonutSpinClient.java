package de.donutspin.client;

import de.donutspin.net.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class DonutSpinClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> {
            Minecraft mc = context.client();
            boolean open = ClientState.update(payload.json());
            if (open && !(mc.screen instanceof SpinScreen)) mc.setScreen(new SpinScreen());
        });
    }
}
