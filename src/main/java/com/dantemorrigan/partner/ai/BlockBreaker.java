package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Breaks one block the way a player does: the right tool from the backpack, the same breaking time
 * (tool speed / hardness / 30, or /100 without the right tool), crack animation, tool wear and real drops.
 */
public final class BlockBreaker {
	public enum Result { WORKING, DONE, FAILED }

	public static final double REACH = 4.5;
	private @Nullable BlockPos pos;
	private float progress;
	private int lastStage = -1;

	public static boolean inReach(PartnerEntity p, BlockPos pos) {
		return p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH;
	}

	/** Blocks the partner refuses to break: unbreakable, containers, and anything a player clearly built with. */
	public static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getFluidState().isSource()) return false;
		float hardness = state.getDestroySpeed(level, pos);
		return hardness >= 0 && hardness < 40 && level.getBlockEntity(pos) == null;
	}

	public Result tick(PartnerEntity p, ServerLevel level, BlockPos target) {
		if (!target.equals(pos)) {
			cancel(level, p);
			pos = target.immutable();
			progress = 0;
		}
		BlockState state = level.getBlockState(target);
		if (state.isAir()) {
			cancel(level, p);
			return Result.DONE;
		}
		if (!breakable(level, target, state) || !inReach(p, target) || !level.mayInteract(p, target)) {
			cancel(level, p);
			return Result.FAILED;
		}
		if (progress == 0) Gear.holdToolFor(p, state);
		// Face the block with the whole body, like a player mining it.
		Vec3 center = Vec3.atCenterOf(target);
		p.getLookControl().setLookAt(center);
		float yaw = (float) (net.minecraft.util.Mth.atan2(center.z - p.getZ(), center.x - p.getX()) * net.minecraft.util.Mth.RAD_TO_DEG) - 90f;
		p.setYBodyRot(yaw);
		p.setYHeadRot(yaw);
		float hardness = state.getDestroySpeed(level, target);
		ItemStack tool = p.getMainHandItem();
		if (hardness <= 0) {
			p.swing(InteractionHand.MAIN_HAND);
			return finish(p, level, target, state, tool);
		}
		boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		float speed = tool.getDestroySpeed(state);
		if (p.isInWater()) speed /= 5f;
		if (!p.onGround()) speed /= 5f;
		progress += speed / hardness / (correct ? 30f : 100f);
		if (p.tickCount % 4 == 0) {
			// Each hit: arm swing with the tool, the block's hit sound and chips flying off the face we hit.
			p.swing(InteractionHand.MAIN_HAND);
			var sound = state.getSoundType();
			level.playSound(null, target, sound.getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, (sound.getVolume() + 1f) / 8f, sound.getPitch() * 0.5f);
			Vec3 face = center.add(p.getEyePosition().subtract(center).normalize().scale(0.55));
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, state),
					face.x, face.y, face.z, 4, 0.15, 0.15, 0.15, 0.05);
		}
		int stage = (int) (progress * 10f);
		if (stage != lastStage) {
			level.destroyBlockProgress(p.getId(), target, Math.min(stage, 9));
			lastStage = stage;
		}
		if (progress >= 1f) return finish(p, level, target, state, tool);
		return Result.WORKING;
	}

	private Result finish(PartnerEntity p, ServerLevel level, BlockPos target, BlockState state, ItemStack tool) {
		boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		BlockEntity be = level.getBlockEntity(target);
		boolean instant = state.getDestroySpeed(level, target) == 0;
		level.destroyBlockProgress(p.getId(), target, -1);
		level.levelEvent(2001, target, Block.getId(state)); // break particles + sound
		if (correct) Block.dropResources(state, level, target, be, p, tool);
		level.removeBlock(target, false);
		if (!tool.isEmpty() && !instant) tool.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
		p.causeExhaustion(0.005f);
		pos = null;
		progress = 0;
		lastStage = -1;
		return Result.DONE;
	}

	public void cancel(ServerLevel level, PartnerEntity p) {
		if (pos != null) level.destroyBlockProgress(p.getId(), pos, -1);
		pos = null;
		progress = 0;
		lastStage = -1;
	}

	public boolean active() {
		return pos != null;
	}
}
