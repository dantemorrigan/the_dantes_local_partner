package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.PartnerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** The partner looks like a player: player model, one of the default skins, armor and items in hand. */
public final class PartnerRenderer extends LivingEntityRenderer<PartnerEntity, AvatarRenderState, PlayerModel> {
	private static final PlayerSkin[] SKINS = new PlayerSkin[PartnerEntity.SKINS.length];

	static {
		for (int i = 0; i < SKINS.length; i++) {
			SKINS[i] = new PlayerSkin(new ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("entity/player/wide/" + PartnerEntity.SKINS[i])),
					null, null, PlayerModelType.WIDE, true);
		}
	}

	public PartnerRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(this,
				ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), part -> new PlayerModel(part, false)),
				context.getEquipmentRenderer()));
		this.addLayer(new PlayerItemInHandLayer<>(this));
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AvatarRenderState();
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return state.skin.body().texturePath();
	}

	@Override
	protected void scale(AvatarRenderState state, PoseStack poseStack) {
		poseStack.scale(0.9375F, 0.9375F, 0.9375F);
	}

	@Override
	public void extractRenderState(PartnerEntity partner, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(partner, state, partialTicks);
		HumanoidMobRenderer.extractHumanoidRenderState(partner, state, partialTicks, this.itemModelResolver);
		state.skin = SKINS[partner.skin()];
		boolean drawingBow = partner.isUsingItem() && com.dantemorrigan.partner.ai.Gear.isBow(partner.getUseItem());
		boolean blockingShield = partner.isUsingItem() && com.dantemorrigan.partner.ai.Gear.isShield(partner.getUseItem());
		state.rightArmPose = drawingBow ? HumanoidModel.ArmPose.BOW_AND_ARROW
				: partner.getMainHandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
		state.leftArmPose = blockingShield ? HumanoidModel.ArmPose.BLOCK
				: partner.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
		state.id = partner.getId();
		state.isSpectator = false;
	}
}
