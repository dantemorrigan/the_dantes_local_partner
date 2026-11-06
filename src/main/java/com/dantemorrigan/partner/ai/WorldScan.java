package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.compat.InfinityCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.function.Predicate;

/** Looks around for work: natural trees, ores a player could see, stone to dig. */
public final class WorldScan {
	/** Which ore kinds are wanted. */
	public enum OreKind { ANY, COAL, IRON, DIAMOND, STONE }

	private WorldScan() {}

	/** A log of a natural tree: next to non-persistent leaves somewhere above (player-placed leaves are persistent). */
	public static boolean isNaturalLog(ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos).is(BlockTags.LOGS)) return false;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy <= 8; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
					BlockState s = level.getBlockState(m);
					if (s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) return true;
				}
			}
		}
		return false;
	}

	/** The lowest log of the nearest natural tree. */
	public static @Nullable BlockPos findTree(ServerLevel level, BlockPos from, int radius, Set<BlockPos> skip) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -6; dy <= 10; dy++) {
					m.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
					if (!level.hasChunkAt(m)) continue;
					BlockState s = level.getBlockState(m);
					if (!s.is(BlockTags.LOGS)) continue;
					BlockPos below = m.below();
					if (level.getBlockState(below).is(BlockTags.LOGS)) continue; // want the trunk base
					if (!level.getBlockState(below).is(BlockTags.DIRT) && !level.getBlockState(below).is(Blocks.MUD)) continue;
					BlockPos base = m.immutable();
					if (skip.contains(base)) continue;
					double d = base.distSqr(from);
					if (d < bestD && isNaturalLog(level, base)) {
						bestD = d;
						best = base;
					}
				}
			}
		}
		return best;
	}

	public static boolean isOre(BlockState s, OreKind kind) {
		return switch (kind) {
			case COAL -> s.is(BlockTags.COAL_ORES);
			case IRON -> s.is(BlockTags.IRON_ORES);
			case DIAMOND -> s.is(BlockTags.DIAMOND_ORES);
			case STONE -> s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE);
			case ANY -> s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
					|| s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
					|| s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES) || s.is(Blocks.NETHER_QUARTZ_ORE)
					|| s.is(Blocks.ANCIENT_DEBRIS) || InfinityCompat.isUruOre(s);
		};
	}

	/** True when some face of the block touches air: a player standing nearby could see and reach it. */
	public static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (n.isAir() || n.is(Blocks.CAVE_AIR)) return true;
		}
		return false;
	}

	/** Nearest exposed block matching {@code what}, within a box around {@code from}. */
	public static @Nullable BlockPos findExposed(ServerLevel level, BlockPos from, int radius, int height, Predicate<BlockState> what, Set<BlockPos> skip) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -height; dy <= height; dy++) {
					m.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
					if (!level.hasChunkAt(m)) continue;
					BlockState s = level.getBlockState(m);
					if (!what.test(s)) continue;
					double d = m.distSqr(from);
					if (d >= bestD || skip.contains(m) || !exposed(level, m)) continue;
					bestD = d;
					best = m.immutable();
				}
			}
		}
		return best;
	}

	/** Natural ground the staircase miner may dig through (never player builds, never containers). */
	public static boolean diggable(BlockState s) {
		return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL) || s.is(Blocks.SAND)
				|| s.is(Blocks.RED_SAND) || s.is(Blocks.SANDSTONE) || s.is(Blocks.CLAY) || s.is(Blocks.COBBLESTONE)
				|| s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CALCITE) || s.is(Blocks.SMOOTH_BASALT)
				|| s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.TERRACOTTA)
				|| isOre(s, OreKind.ANY) || s.isAir();
	}

	public static boolean dangerousFluidNear(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (!level.getFluidState(pos.relative(d)).isEmpty()) return true;
		}
		return !level.getFluidState(pos).isEmpty();
	}
}
