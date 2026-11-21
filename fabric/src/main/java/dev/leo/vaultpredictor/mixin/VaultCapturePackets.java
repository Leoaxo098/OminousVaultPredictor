package dev.leo.vaultpredictor.mixin;

import dev.leo.vaultpredictor.VaultPredictorClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.LevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class VaultCapturePackets {
    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void ovp$itemData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        var listener = (ClientPacketListener) (Object) this;
        if (listener.getLevel() != null && listener.getLevel().getEntity(packet.id()) instanceof ItemEntity item) {
            VaultPredictorClient.itemData(listener.getLevel(), item);
        }
    }

    @Inject(method = "handleLevelEvent", at = @At("TAIL"))
    private void ovp$levelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        if (packet.getType() == LevelEvent.ANIMATION_VAULT_EJECT_ITEM) {
            var listener = (ClientPacketListener) (Object) this;
            VaultPredictorClient.ejection(listener.getLevel(), packet.getPos());
        }
    }
}

