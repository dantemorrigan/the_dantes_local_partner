package com.dantemorrigan.partner;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The seven effects of the design pack (summon, recall, accepted, guard, assist, patrol, dismiss).
 * Each is a one-shot sprite-sheet particle: 48 × 48 frames, one frame per tick (50 ms), played once.
 * The position sent is the effect's anchor point; the client provider places the frame around it.
 */
public final class PartnerFx {
	public static final SimpleParticleType SUMMON = register("summon");
	public static final SimpleParticleType RECALL = register("recall");
	public static final SimpleParticleType ACCEPTED = register("accepted");
	public static final SimpleParticleType GUARD = register("guard");
	public static final SimpleParticleType ASSIST = register("assist");
	public static final SimpleParticleType PATROL = register("patrol");
	public static final SimpleParticleType DISMISS = register("dismiss");

	private static SimpleParticleType register(String name) {
		return Registry.register(BuiltInRegistries.PARTICLE_TYPE, LocalPartner.id(name), FabricParticleTypes.simple(true));
	}

	public static void init() {}

	private static void at(Entity e, SimpleParticleType type, double x, double y, double z) {
		if (e.level() instanceof ServerLevel level) level.sendParticles(type, true, true, x, y, z, 1, 0, 0, 0, 0);
	}

	/** At the partner's feet. */
	public static void summon(PartnerEntity p) { at(p, SUMMON, p.getX(), p.getY(), p.getZ()); }
	public static void dismiss(PartnerEntity p) { at(p, DISMISS, p.getX(), p.getY(), p.getZ()); }
	/** At the spot next to the player where the partner reappears. */
	public static void recall(PartnerEntity p) { at(p, RECALL, p.getX(), p.getY(), p.getZ()); }
	/** Above the partner's head. */
	public static void accepted(PartnerEntity p) { at(p, ACCEPTED, p.getX(), p.getY() + p.getBbHeight() + 0.45, p.getZ()); }
	/** Body height. */
	public static void guard(PartnerEntity p) { at(p, GUARD, p.getX(), p.getY() + p.getBbHeight() * 0.55, p.getZ()); }

	/** Where the food changes hands, between the partner and the owner. */
	public static void assist(PartnerEntity p, ServerPlayer owner) {
		at(p, ASSIST, (p.getX() + owner.getX()) / 2, Math.max(p.getY(), owner.getY()) + 1.1, (p.getZ() + owner.getZ()) / 2);
	}

	/** On top of the block under the patrol point. */
	public static void patrolMark(PartnerEntity p, BlockPos point) {
		at(p, PATROL, point.getX() + 0.5, point.getY() + 0.05, point.getZ() + 0.5);
	}

	/** HUD toast for the owner. */
	public static void toast(PartnerEntity p, String key) {
		ServerPlayer owner = p.getOwnerPlayer();
		if (owner != null) PartnerNetwork.send(owner, new PartnerNetwork.Toast(key));
	}

	private PartnerFx() {}
}
