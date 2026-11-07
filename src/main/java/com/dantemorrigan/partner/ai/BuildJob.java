package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerOrder;
import com.dantemorrigan.partner.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Analyzes what the player has already built nearby (footprint, wall height, dominant wall and floor materials)
 * and finishes the structure: fills missing floor tiles, raises unfinished perimeter walls, leaves/places a door
 * and windows, adds a roof and interior lighting. When ordered on empty ground with no existing build, constructs
 * a complete 7×7 cabin with a door, windows, roof, torches, crafting table, furnace and chest.
 */
public final class BuildJob implements Job {
	private record Step(BlockPos pos, BlockState state, boolean isDoorLower, Direction doorFacing) {
		Step(BlockPos pos, BlockState state) {
			this(pos, state, false, Direction.NORTH);
		}
	}

	private final ArrayDeque<Step> plan = new ArrayDeque<>();
	private @Nullable Job prerequisite;
	private boolean analyzed;
	private int placed, totalPlanned, ticks, stuck;

	@Override
	public String activity() {
		return prerequisite != null ? prerequisite.activity() : "build";
	}

	@Override
	public int result(PartnerBrain brain) {
		int pre = prerequisite == null ? 0 : prerequisite.result(brain);
		return placed * 50 + pre;
	}

	@Override
	public int stallTicks() {
		return 20 * 75;
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (++ticks > 20 * 60 * 5) return placed > 0 ? Status.DONE : Status.FAILED;

		if (prerequisite != null) {
			Status s = prerequisite.tick(brain, level);
			if (s == Status.RUNNING) return Status.RUNNING;
			prerequisite.stop(brain, level);
			prerequisite = null;
		}

		if (!analyzed) {
			analyzed = true;
			BlockPos center = p.anchor() != null ? p.anchor() : p.blockPosition();
			if (!analyzeExistingStructure(brain, level, center)) {
				planNewCabin(brain, level, center);
			}
			totalPlanned = plan.size();
			if (totalPlanned == 0) {
				brain.say("build_nothing");
				p.setOrderQuiet(PartnerOrder.FOLLOW);
				return Status.DONE;
			}
		}

		while (!plan.isEmpty()) {
			Step next = plan.peek();
			if (alreadySatisfied(level, next)) {
				plan.poll();
				continue;
			}
			break;
		}

		if (plan.isEmpty()) {
			brain.say("build_done", placed);
			p.setOrderQuiet(PartnerOrder.FOLLOW);
			return Status.DONE;
		}

		brain.detail = placed + "/" + totalPlanned;
		Step step = plan.peek();

		// Ensure materials in the backpack (gather wood/stone if out of blocks).
		if (!ensureMaterialFor(brain, step.state)) {
			return prerequisite != null ? Status.RUNNING : Status.FAILED;
		}

		if (p.position().distanceToSqr(Vec3.atCenterOf(step.pos)) > 4.2 * 4.2) {
			brain.approach(step.pos);
			if (++stuck > 20 * 8) {
				// Skip unreachable block or place from here if close enough.
				if (p.position().distanceToSqr(Vec3.atCenterOf(step.pos)) <= 8 * 8) {
					placeStep(brain, level, plan.poll());
				} else {
					plan.poll();
				}
				stuck = 0;
			}
			return Status.RUNNING;
		}

		stuck = 0;
		placeStep(brain, level, plan.poll());
		return Status.RUNNING;
	}

	private void placeStep(PartnerBrain brain, ServerLevel level, Step step) {
		PartnerEntity p = brain.p;
		BlockPos pos = step.pos;
		// Clear grass/snow/leaves at the target spot if needed.
		BlockState cur = level.getBlockState(pos);
		if (!cur.isAir() && (cur.canBeReplaced() || cur.is(BlockTags.LEAVES))) {
			level.destroyBlock(pos, true, p);
		}
		if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).canBeReplaced()) return;
		// Move partner slightly aside if standing inside the block being placed.
		if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) {
			p.snapTo(p.getX(), pos.getY() + 1.0, p.getZ(), p.getYRot(), p.getXRot());
		}

		BlockState actualState = resolveAvailableBlock(p, step.state);
		consumeMaterial(p, actualState);
		if (step.isDoorLower) {
			BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
					.setValue(DoorBlock.FACING, step.doorFacing)
					.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
			BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
			level.setBlock(pos, lower, 3);
			level.setBlock(pos.above(), upper, 3);
			Territory.mark(level, pos);
			Territory.mark(level, pos.above());
		} else {
			level.setBlockAndUpdate(pos, actualState);
			Territory.mark(level, pos);
		}
		placed++;
		p.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		p.swing(InteractionHand.MAIN_HAND);
		level.playSound(null, pos, SoundEvents_WOOD_PlaceSoundOrStone(step.state), SoundSource.BLOCKS, 0.8f, 1.0f);
	}

	private static net.minecraft.sounds.SoundEvent SoundEvents_WOOD_PlaceSoundOrStone(BlockState s) {
		return s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) ? SoundEvents.WOOD_PLACE : SoundEvents.STONE_PLACE;
	}

	private static boolean alreadySatisfied(ServerLevel level, Step step) {
		BlockState cur = level.getBlockState(step.pos);
		if (step.isDoorLower) return cur.is(BlockTags.DOORS);
		if (step.state.is(Blocks.TORCH)) return cur.is(Blocks.TORCH) || level.getMaxLocalRawBrightness(step.pos) > 10;
		return !cur.isAir() && !cur.canBeReplaced() && !cur.is(BlockTags.LEAVES);
	}

	/**
	 * Scans a 16-block radius around {@code center} for player-placed structural blocks (walls/foundation).
	 * If a partial building is found, computes its bounding box and queues missing floor, walls, doorway, roof and torches.
	 */
	private boolean analyzeExistingStructure(PartnerBrain brain, ServerLevel level, BlockPos center) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		Map<BlockState, Integer> wallCounts = new HashMap<>();
		Map<BlockState, Integer> floorCounts = new HashMap<>();
		int foundBlocks = 0;

		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -10; dx <= 10; dx++) {
			for (int dz = -10; dz <= 10; dz++) {
				for (int dy = -2; dy <= 6; dy++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState s = level.getBlockState(m);
					if (!isStructuralBlock(s)) continue;
					foundBlocks++;
					minX = Math.min(minX, m.getX());
					maxX = Math.max(maxX, m.getX());
					minZ = Math.min(minZ, m.getZ());
					maxZ = Math.max(maxZ, m.getZ());
					minY = Math.min(minY, m.getY());
					maxY = Math.max(maxY, m.getY());
					if (dy <= 0) floorCounts.merge(s, 1, Integer::sum);
					else wallCounts.merge(s, 1, Integer::sum);
				}
			}
		}

		int width = maxX - minX + 1, depth = maxZ - minZ + 1;
		if (foundBlocks < 6 || width < 3 || depth < 3 || width > 16 || depth > 16) return false;

		BlockState wallMat = dominantMaterial(wallCounts, dominantMaterial(floorCounts, Blocks.OAK_PLANKS.defaultBlockState()));
		BlockState floorMat = dominantMaterial(floorCounts, wallMat);
		int baseY = minY;
		int wallTopY = Math.max(baseY + 3, Math.min(baseY + 4, maxY));

		// 1. Floor inside the footprint.
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				BlockPos fp = new BlockPos(x, baseY, z);
				if (level.getBlockState(fp).isAir() || level.getBlockState(fp).canBeReplaced()) {
					plan.add(new Step(fp, floorMat));
				}
			}
		}

		// Check if there is already a door in the perimeter.
		boolean hasDoor = false;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = baseY; y <= wallTopY; y++) {
					if (level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.DOORS)) hasDoor = true;
				}
			}
		}
		int doorX = (minX + maxX) / 2, doorZ = minZ;

		// 2. Perimeter walls layer by layer.
		for (int y = baseY + 1; y <= wallTopY; y++) {
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
					if (!edge) continue;
					if (!hasDoor && x == doorX && z == doorZ && (y == baseY + 1 || y == baseY + 2)) {
						if (y == baseY + 1) plan.add(new Step(new BlockPos(x, y, z), Blocks.OAK_DOOR.defaultBlockState(), true, Direction.SOUTH));
						continue;
					}
					// Leave a window opening in the middle of side walls at eye level.
					boolean midSide = (z == (minZ + maxZ) / 2 && (x == minX || x == maxX))
							|| (x == (minX + maxX) / 2 && z == maxZ);
					if (midSide && y == baseY + 2 && width >= 5 && depth >= 5) continue;
					BlockPos wp = new BlockPos(x, y, z);
					if (level.getBlockState(wp).isAir() || level.getBlockState(wp).canBeReplaced()) {
						plan.add(new Step(wp, wallMat));
					}
				}
			}
		}

		// 3. Roof on top of the walls.
		int roofY = wallTopY + 1;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				BlockPos rp = new BlockPos(x, roofY, z);
				if (level.getBlockState(rp).isAir() || level.getBlockState(rp).canBeReplaced()) {
					plan.add(new Step(rp, wallMat));
				}
			}
		}

		// 4. Interior lighting.
		BlockPos mid = new BlockPos((minX + maxX) / 2, baseY + 1, (minZ + maxZ) / 2);
		if (level.getBlockState(mid).isAir()) plan.add(new Step(mid, Blocks.TORCH.defaultBlockState()));
		if (!plan.isEmpty()) {
			brain.say("build_analyzed", width, depth, wallTopY - baseY, plan.size());
			return true;
		}
		return false;
	}

	/** Builds a cozy 7×7 cabin from scratch near {@code center}. */
	private void planNewCabin(PartnerBrain brain, ServerLevel level, BlockPos center) {
		BlockPos origin = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.offset(4, 0, 4));
		int minX = origin.getX() - 3, maxX = origin.getX() + 3;
		int minZ = origin.getZ() - 3, maxZ = origin.getZ() + 3;
		int baseY = origin.getY() - 1;

		BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
		BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
		BlockState log = Blocks.OAK_LOG.defaultBlockState();

		// 1. 7×7 Cobblestone foundation/floor.
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				plan.add(new Step(new BlockPos(x, baseY, z), cobble));
			}
		}

		int doorX = origin.getX(), doorZ = minZ;
		// 2. Walls (3 blocks high) with oak log corners, doorway and windows.
		for (int y = baseY + 1; y <= baseY + 3; y++) {
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
					if (!edge) continue;
					if (x == doorX && z == doorZ && (y == baseY + 1 || y == baseY + 2)) {
						if (y == baseY + 1) plan.add(new Step(new BlockPos(x, y, z), Blocks.OAK_DOOR.defaultBlockState(), true, Direction.SOUTH));
						continue;
					}
					boolean window = y == baseY + 2 && ((z == origin.getZ() && (x == minX || x == maxX)) || (x == origin.getX() && z == maxZ));
					if (window) continue;
					boolean corner = (x == minX || x == maxX) && (z == minZ || z == maxZ);
					plan.add(new Step(new BlockPos(x, y, z), corner ? log : planks));
				}
			}
		}

		// 3. Roof at baseY + 4.
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				plan.add(new Step(new BlockPos(x, baseY + 4, z), planks));
			}
		}

		// 4. Interior furnishings: torch, crafting table, chest.
		plan.add(new Step(new BlockPos(origin.getX(), baseY + 1, origin.getZ()), Blocks.TORCH.defaultBlockState()));
		plan.add(new Step(new BlockPos(minX + 1, baseY + 1, maxZ - 1), Blocks.CRAFTING_TABLE.defaultBlockState()));
		plan.add(new Step(new BlockPos(maxX - 1, baseY + 1, maxZ - 1), Blocks.CHEST.defaultBlockState()));
		brain.say("build_new", plan.size());
	}

	private static boolean isStructuralBlock(BlockState s) {
		if (s.isAir() || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) return false;
		return s.is(BlockTags.PLANKS) || s.is(Blocks.COBBLESTONE) || s.is(BlockTags.STONE_BRICKS) || s.is(Blocks.BRICKS)
				|| s.is(Blocks.SANDSTONE) || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.DEEPSLATE_BRICKS)
				|| s.is(Blocks.DEEPSLATE_TILES) || s.is(Blocks.COBBLED_DEEPSLATE);
	}

	private static BlockState dominantMaterial(Map<BlockState, Integer> counts, BlockState fallback) {
		BlockState best = fallback;
		int max = 0;
		for (var e : counts.entrySet()) {
			if (e.getValue() > max) {
				max = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	private boolean ensureMaterialFor(PartnerBrain brain, BlockState wanted) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();
		int planks = Gear.count(bag, s -> s.is(ItemTags.PLANKS));
		int logs = Gear.count(bag, s -> s.is(ItemTags.LOGS));
		int stone = Gear.count(bag, s -> s.is(ItemTags.STONE_TOOL_MATERIALS));

		if (wanted.is(BlockTags.DOORS)) {
			if (Gear.count(bag, s -> s.is(ItemTags.DOORS)) > 0) return true;
			while (planks < 6 && logs > 0) {
				Crafting.makePlanks(p);
				planks = Gear.count(bag, s -> s.is(ItemTags.PLANKS));
				logs = Gear.count(bag, s -> s.is(ItemTags.LOGS));
			}
			if (planks >= 6) return true;
			prerequisite = new ChopJob(4);
			return false;
		}
		if (wanted.is(BlockTags.PLANKS) || wanted.is(Blocks.CRAFTING_TABLE) || wanted.is(Blocks.CHEST)) {
			if (planks < 4 && logs > 0) {
				Crafting.makePlanks(p);
				return true;
			}
			if (planks > 0 || stone > 0) return true;
			prerequisite = new ChopJob(6);
			return false;
		}
		if (wanted.is(BlockTags.LOGS)) {
			// Use logs if we have them, or substitute planks/stone if the player gave us those instead.
			if (logs > 0 || planks > 0 || stone > 0) return true;
			prerequisite = new ChopJob(6);
			return false;
		}
		if (wanted.is(Blocks.COBBLESTONE) || wanted.is(BlockTags.STONE_BRICKS)) {
			// Use stone if we have it, or substitute planks/logs if the player gave us wood instead!
			if (stone > 0 || planks > 0) return true;
			if (logs > 0) {
				Crafting.makePlanks(p);
				return true;
			}
			prerequisite = new ChopJob(6);
			return false;
		}
		return true;
	}

	private static BlockState resolveAvailableBlock(PartnerEntity p, BlockState preferred) {
		var bag = p.backpack();
		if (preferred.is(BlockTags.LOGS) && Gear.count(bag, s -> s.is(ItemTags.LOGS)) == 0) {
			if (Gear.count(bag, s -> s.is(ItemTags.PLANKS)) > 0) return firstBlockInBag(bag, s -> s.is(ItemTags.PLANKS), Blocks.OAK_PLANKS.defaultBlockState());
			if (Gear.count(bag, s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) > 0) return Blocks.COBBLESTONE.defaultBlockState();
		}
		if (preferred.is(Blocks.COBBLESTONE) && Gear.count(bag, s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) == 0) {
			if (Gear.count(bag, s -> s.is(ItemTags.PLANKS)) == 0 && Gear.count(bag, s -> s.is(ItemTags.LOGS)) > 0) Crafting.makePlanks(p);
			if (Gear.count(bag, s -> s.is(ItemTags.PLANKS)) > 0) return firstBlockInBag(bag, s -> s.is(ItemTags.PLANKS), Blocks.OAK_PLANKS.defaultBlockState());
		}
		if (preferred.is(BlockTags.PLANKS)) {
			if (Gear.count(bag, s -> s.is(ItemTags.PLANKS)) == 0 && Gear.count(bag, s -> s.is(ItemTags.LOGS)) > 0) Crafting.makePlanks(p);
			if (Gear.count(bag, s -> s.is(ItemTags.PLANKS)) > 0) return firstBlockInBag(bag, s -> s.is(ItemTags.PLANKS), preferred);
			if (Gear.count(bag, s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) > 0) return Blocks.COBBLESTONE.defaultBlockState();
		}
		return preferred;
	}

	private static BlockState firstBlockInBag(net.minecraft.world.SimpleContainer bag, java.util.function.Predicate<ItemStack> pred, BlockState fallback) {
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (pred.test(s) && s.getItem() instanceof BlockItem bi) return bi.getBlock().defaultBlockState();
		}
		return fallback;
	}

	private static void consumeMaterial(PartnerEntity p, BlockState state) {
		var bag = p.backpack();
		if (state.is(BlockTags.PLANKS)) {
			if (Gear.take(bag, s -> s.is(ItemTags.PLANKS), 1) == 0) {
				Crafting.makePlanks(p);
				Gear.take(bag, s -> s.is(ItemTags.PLANKS), 1);
			}
		} else if (state.is(BlockTags.LOGS)) {
			if (Gear.take(bag, s -> s.is(ItemTags.LOGS), 1) == 0) {
				Gear.take(bag, s -> s.is(ItemTags.PLANKS), 1);
			}
		} else if (state.is(Blocks.COBBLESTONE)) {
			if (Gear.take(bag, s -> s.is(ItemTags.STONE_TOOL_MATERIALS), 1) == 0) {
				Gear.take(bag, s -> s.is(ItemTags.PLANKS), 1);
			}
		} else if (state.is(Blocks.TORCH)) {
			Gear.take(bag, s -> s.is(Items.TORCH), 1);
		}
	}

	@Override
	public void stop(PartnerBrain brain, ServerLevel level) {
		if (prerequisite != null) {
			prerequisite.stop(brain, level);
			prerequisite = null;
		}
	}
}
