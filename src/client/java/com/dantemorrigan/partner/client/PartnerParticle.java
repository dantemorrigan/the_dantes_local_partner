package com.dantemorrigan.partner.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.LightCoordsUtil;

/**
 * One design-pack effect: a 48 × 48 frame strip played once, one frame per tick (50 ms), facing the camera,
 * full bright. The spawn position is the effect's anchor point; the quad is shifted so the anchor pixel lands on it.
 */
public final class PartnerParticle extends SingleQuadParticle {
	/** World size of a 48 px frame. */
	public static final float SIZE = 1.5f;
	private final SpriteSet sprites;

	public PartnerParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites, int frames, int anchorY) {
		super(level, x, y + (anchorY - 24) / 48.0 * SIZE, z, sprites.get(0, frames - 1));
		this.sprites = sprites;
		this.lifetime = frames;
		this.quadSize = SIZE / 2f;
		this.gravity = 0;
		this.hasPhysics = false;
		this.xd = this.yd = this.zd = 0;
	}

	@Override
	public void tick() {
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		if (this.age++ >= this.lifetime) {
			this.remove();
			return;
		}
		this.setSprite(this.sprites.get(Math.min(this.age, this.lifetime - 1), this.lifetime - 1));
	}

	@Override
	protected Layer getLayer() {
		return Layer.TRANSLUCENT;
	}

	@Override
	public int getLightCoords(float partialTick) {
		return LightCoordsUtil.FULL_BRIGHT;
	}
}
