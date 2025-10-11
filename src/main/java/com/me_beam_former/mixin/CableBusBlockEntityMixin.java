package com.me_beam_former.mixin;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.blockentity.AEBaseBlockEntity;
import com.mebeamformer.part.BeamFormerPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AEBaseBlockEntity.class, remap = false)
public abstract class CableBusBlockEntityMixin {

    @Inject(method = "getRenderBoundingBox", at = @At("RETURN"), cancellable = true, remap = false)
    @OnlyIn(Dist.CLIENT)
    private void expandRenderBoundingBoxForBeamFormer(CallbackInfoReturnable<AABB> cir) {
        if (!(this instanceof IPartHost partHost)) {
            return;
        }
        
        AABB originalBox = cir.getReturnValue();
        AABB expandedBox = originalBox;

        for (Direction dir : Direction.values()) {
            IPart part = partHost.getPart(dir);

            if (part instanceof BeamFormerPart beamPart) {
                AABB beamBox = beamPart.getExtendedRenderBoundingBox(originalBox);
                expandedBox = expandedBox.minmax(beamBox);
            }
        }

        if (expandedBox != originalBox) {
            cir.setReturnValue(expandedBox);
        }
    }
}

