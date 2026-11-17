package com.dantemorrigan.kingdoms.client;

import com.dantemorrigan.kingdoms.world.CitizenEntity;
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

/** Citizens look like people: player model with a default skin, armor in the kingdom's colors, tools in hand. */
public final class CitizenRenderer extends LivingEntityRenderer<CitizenEntity, AvatarRenderState, PlayerModel> {
	private static final PlayerSkin[] SKINS = new PlayerSkin[CitizenEntity.SKINS.length];

	static {
		for (int i = 0; i < SKINS.length; i++) {
			SKINS[i] = new PlayerSkin(new ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("entity/player/wide/" + CitizenEntity.SKINS[i])),
					null, null, PlayerModelType.WIDE, true);
		}
	}

	public CitizenRenderer(EntityRendererProvider.Context context) {
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
	public void extractRenderState(CitizenEntity c, AvatarRenderState state, float partialTicks) {
		super.extractRenderState(c, state, partialTicks);
		HumanoidMobRenderer.extractHumanoidRenderState(c, state, partialTicks, this.itemModelResolver);
		state.skin = SKINS[c.skin()];
		state.rightArmPose = c.getMainHandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
		state.leftArmPose = c.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
		state.id = c.getId();
		state.isSpectator = false;
	}
}
