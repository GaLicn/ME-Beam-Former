package com.mebeamformer.client;

import com.mebeamformer.item.LaserBindingTool;
import com.mebeamformer.network.ToggleRangeBindingModePacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

public final class LaserBindingToolKeyMappings {
    private static final String KEY_CATEGORY = "key.categories.me_beam_former";
    private static final KeyMapping TOGGLE_RANGE_BINDING = new KeyMapping(
            "key.me_beam_former.toggle_range_binding",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            KEY_CATEGORY
    );

    private LaserBindingToolKeyMappings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_RANGE_BINDING);
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }

        while (TOGGLE_RANGE_BINDING.consumeClick()) {
            if (isHoldingBindingTool(minecraft.player)) {
                ToggleRangeBindingModePacket.sendToServer();
            }
        }
    }

    private static boolean isHoldingBindingTool(Player player) {
        return player.getMainHandItem().getItem() instanceof LaserBindingTool
                || player.getOffhandItem().getItem() instanceof LaserBindingTool;
    }
}
