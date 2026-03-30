package com.mebeamformer.network;

import com.mebeamformer.ME_Beam_Former;
import com.mebeamformer.item.LaserBindingTool;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public final class ToggleRangeBindingModePacket {
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ME_Beam_Former.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextMessageId = 0;

    public static void register() {
        CHANNEL.messageBuilder(ToggleRangeBindingModePacket.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ToggleRangeBindingModePacket::encode)
                .decoder(ToggleRangeBindingModePacket::decode)
                .consumerMainThread(ToggleRangeBindingModePacket::handle)
                .add();
    }

    public static void sendToServer() {
        CHANNEL.sendToServer(new ToggleRangeBindingModePacket());
    }

    public static ToggleRangeBindingModePacket decode(FriendlyByteBuf buf) {
        return new ToggleRangeBindingModePacket();
    }

    public static void encode(ToggleRangeBindingModePacket packet, FriendlyByteBuf buf) {
    }

    private static void handle(ToggleRangeBindingModePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            LaserBindingTool.toggleRangeMode(player);
        }
    }
}
