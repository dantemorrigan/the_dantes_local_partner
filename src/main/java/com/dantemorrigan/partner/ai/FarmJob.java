package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Farming, Livestock & Taming job:
 * <ul>
 *   <li>Tames wild wolves (with bones) and cats (with fish) for the owner.</li>
 *   <li>Harvests mature crops (wheat, carrots, potatoes, beetroots) and replants immediately.</li>
 *   <li>Tills dirt/grass near water into farmland (or creates a starter garden near anchor), plants seeds/crops, and applies bone meal.</li>
 *   <li>Builds a wooden fence pen near the anchor if livestock are nearby and no pen exists.</li>
 *   <li>Lures and herds wild cows, sheep, pigs, and chickens toward the farm, breeds adult pairs, and shears sheep.</li>
 * </ul>
 */
public final class FarmJob implements Job {
	private @Nullable Job subJob;
	private int cooldown;
	private boolean builtPen;
	private int actionTicks, results;
	private @Nullable Animal herdingTarget;
	private int herdTicks;

	@Override
	public String activity() {
		return subJob != null ? subJob.activity() : "farm";
	}

	@Override
	public int result(PartnerBrain brain) {
		if (subJob != null) return subJob.result(brain);
		return results * 20 + actionTicks / 40;
	}

	@Override
	public int stallTicks() {
		return 20 * 90;
	}

	@Override
	public boolean onStall(PartnerBrain brain, ServerLevel level) {
		if (subJob != null) {
			subJob.stop(brain, level);
			subJob = null;
		}
		herdingTarget = null;
		return true;
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		actionTicks++;

		if (subJob != null) {
			Status r = subJob.tick(brain, level);
			if (r == Status.RUNNING) return Status.RUNNING;
			subJob.stop(brain, level);
			subJob = null;
		}

		if (cooldown > 0) {
			cooldown--;
			return Status.RUNNING;
		}

		// Sweep any loose crop/wool/seed drops around the partner
		if (actionTicks % 15 == 0 && brain.collectDrops(level, p.position(), 5.0)) {
			return Status.RUNNING;
		}

		BlockPos center = p.anchor() != null ? p.anchor() : p.blockPosition();

		// 1. Tame nearby wild Wolves or Cats for the player!
		if (tryTamePet(p, brain, level)) {
			return Status.RUNNING;
		}

		// 2. Ensure we have a hoe (craft one if needed)
		if (ensureFarmingTools(p)) {
			return Status.RUNNING;
		}

		// 3. Harvest mature crops & replant
		BlockPos matureCrop = findMatureCrop(level, center, 20);
		if (matureCrop != null) {
			brain.detail = "🌾 " + matureCrop.getX() + "," + matureCrop.getZ();
			harvestAndReplant(p, brain, level, matureCrop);
			return Status.RUNNING;
		}

		// 4. Plant empty farmland or till new soil near water
		BlockPos emptyFarmland = findEmptyFarmland(level, center, 18);
		if (emptyFarmland != null && hasAnySeedsOrCrops(p)) {
			brain.detail = "🌱 " + emptyFarmland.getX() + "," + emptyFarmland.getZ();
			plantAt(p, brain, level, emptyFarmland);
			return Status.RUNNING;
		}

		BlockPos tillable = findTillableNearWater(level, center, 16);
		if (tillable != null && hasAnySeedsOrCrops(p)) {
			brain.detail = "🌱 " + tillable.getX() + "," + tillable.getZ();
			tillAndPlant(p, brain, level, tillable);
			return Status.RUNNING;
		}

		// If no seeds at all, break nearby tall grass to get wheat seeds
		if (!hasAnySeedsOrCrops(p)) {
			BlockPos grass = findTallGrass(level, center, 20);
			if (grass != null) {
				if (!BlockBreaker.inReach(p, grass)) {
					brain.approach(grass);
				} else {
					level.destroyBlock(grass, true, p);
					p.swing(InteractionHand.MAIN_HAND);
					if (p.getRandom().nextFloat() < 0.35f) {
						p.backpack().addItem(new ItemStack(Items.WHEAT_SEEDS, 1));
					}
					results++;
					cooldown = 8;
				}
				return Status.RUNNING;
			}
		}

		// 5. Bone-meal growing crops if we have bones or bone meal
		if (Gear.count(p.backpack(), Items.BONE) > 2 && Gear.count(p.backpack(), Items.BONE_MEAL) == 0) {
			Crafting.craft(p, Items.BONE_MEAL);
		}
		if (Gear.count(p.backpack(), Items.BONE_MEAL) > 0) {
			BlockPos growing = findGrowingCrop(level, center, 16);
			if (growing != null) {
				fertilizeCrop(p, brain, level, growing);
				return Status.RUNNING;
			}
		}

		// 6. Shear ready sheep
		Sheep woolly = findShearableSheep(level, center, 24);
		if (woolly != null) {
			if (Gear.count(p.backpack(), Items.SHEARS) == 0 && !p.getMainHandItem().is(Items.SHEARS)
					&& Gear.count(p.backpack(), Items.IRON_INGOT) >= 2) {
				Crafting.craft(p, Items.SHEARS);
			}
			if (Gear.count(p.backpack(), Items.SHEARS) > 0 || p.getMainHandItem().is(Items.SHEARS)) {
				shearSheep(p, brain, level, woolly);
				return Status.RUNNING;
			}
		}

		// 7. Build a small fence pen near anchor if animals exist nearby and no fence pen is present
		if (!builtPen && hasAnimalsNearby(level, center, 36) && !hasFenceNearby(level, center, 14)) {
			if (ensureFenceMaterials(p)) {
				buildAnimalPen(p, level, center);
				builtPen = true;
				brain.say("farm_pen_built");
				results++;
				cooldown = 20;
				return Status.RUNNING;
			}
			if (subJob != null) return Status.RUNNING;
		}

		// 8. Herd distant wild animals toward the farm center & breed pairs
		if (herdOrBreedAnimals(p, brain, level, center)) {
			return Status.RUNNING;
		}

		// 9. Create a starter garden plot if no farmland exists near center at all
		if (findAnyFarmland(level, center, 16) == null && hasAnySeedsOrCrops(p)) {
			BlockPos gardenSpot = findFlatGardenSpot(level, center);
			if (gardenSpot != null) {
				tillAndPlant(p, brain, level, gardenSpot);
				return Status.RUNNING;
			}
		}

		// Patrol around the farm peacefully
		brain.detail = "";
		if (actionTicks % 80 == 0) {
			double angle = (actionTicks / 80.0) * 1.2;
			BlockPos stroll = center.offset((int) Math.round(Math.cos(angle) * 5), 0, (int) Math.round(Math.sin(angle) * 5));
			p.getNavigation().moveTo(stroll.getX() + 0.5, stroll.getY(), stroll.getZ() + 0.5, PartnerEntity.WALK);
		}
		return Status.RUNNING;
	}

	private boolean tryTamePet(PartnerEntity p, PartnerBrain brain, ServerLevel level) {
		ServerPlayer owner = p.getOwnerPlayer();
		if (owner == null) return false;

		// Look for untamed Wolf
		List<Wolf> wolves = level.getEntitiesOfClass(Wolf.class, p.getBoundingBox().inflate(24.0),
				w -> w.isAlive() && !w.isTame() && !w.isAngry() && !brain.isIgnoredEntity(w.getId()));
		if (!wolves.isEmpty() && (Gear.count(p.backpack(), Items.BONE) > 0 || p.getMainHandItem().is(Items.BONE))) {
			Wolf wolf = wolves.getFirst();
			equipInMainHand(p, Items.BONE);
			if (p.distanceToSqr(wolf) > 6.5) {
				if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(wolf, PartnerEntity.SPRINT);
				brain.jumpIfBlocked(wolf.position());
				return true;
			}
			p.getNavigation().stop();
			p.getLookControl().setLookAt(wolf, 30, 30);
			p.swing(InteractionHand.MAIN_HAND);
			consumeFromHandOrBag(p, Items.BONE, 1);
			if (p.getRandom().nextInt(2) == 0) {
				wolf.tame(owner);
				wolf.setOrderedToSit(false);
				level.sendParticles(ParticleTypes.HEART, wolf.getX(), wolf.getY() + 0.6, wolf.getZ(), 7, 0.3, 0.3, 0.3, 0.02);
				level.playSound(null, wolf.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.8f, 1.2f);
				brain.say("tamed_pet", wolf.getName());
				brain.ignoredEntity(wolf.getId(), 1200);
				results++;
			} else {
				level.sendParticles(ParticleTypes.SMOKE, wolf.getX(), wolf.getY() + 0.5, wolf.getZ(), 4, 0.2, 0.2, 0.2, 0.01);
			}
			cooldown = 15;
			return true;
		}

		// Look for untamed Cat
		List<Cat> cats = level.getEntitiesOfClass(Cat.class, p.getBoundingBox().inflate(24.0),
				c -> c.isAlive() && !c.isTame() && !brain.isIgnoredEntity(c.getId()));
		if (!cats.isEmpty() && (Gear.count(p.backpack(), s -> s.is(Items.COD) || s.is(Items.SALMON)) > 0
				|| p.getMainHandItem().is(Items.COD) || p.getMainHandItem().is(Items.SALMON))) {
			Cat cat = cats.getFirst();
			Item fish = (Gear.count(p.backpack(), Items.COD) > 0 || p.getMainHandItem().is(Items.COD)) ? Items.COD : Items.SALMON;
			equipInMainHand(p, fish);
			if (p.distanceToSqr(cat) > 6.5) {
				if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(cat, PartnerEntity.WALK);
				brain.jumpIfBlocked(cat.position());
				return true;
			}
			p.getNavigation().stop();
			p.getLookControl().setLookAt(cat, 30, 30);
			p.swing(InteractionHand.MAIN_HAND);
			consumeFromHandOrBag(p, fish, 1);
			cat.tame(owner);
			cat.setOrderedToSit(false);
			level.sendParticles(ParticleTypes.HEART, cat.getX(), cat.getY() + 0.5, cat.getZ(), 7, 0.3, 0.3, 0.3, 0.02);
			brain.say("tamed_pet", cat.getName());
			brain.ignoredEntity(cat.getId(), 1200);
			results++;
			cooldown = 15;
			return true;
		}
		return false;
	}

	private boolean ensureFarmingTools(PartnerEntity p) {
		boolean hasHoe = Gear.count(p.backpack(), s -> s.is(ItemTags.HOES)) > 0 || p.getMainHandItem().is(ItemTags.HOES);
		if (!hasHoe) {
			for (Item hoe : new Item[]{Items.IRON_HOE, Items.STONE_HOE, Items.WOODEN_HOE}) {
				if (Crafting.canMake(p, hoe)) {
					subJob = new CraftJob(List.of(hoe), true);
					return true;
				}
			}
		}
		return false;
	}

	private void harvestAndReplant(PartnerEntity p, PartnerBrain brain, ServerLevel level, BlockPos pos) {
		if (!BlockBreaker.inReach(p, pos)) {
			brain.approach(pos);
			return;
		}
		p.getNavigation().stop();
		BlockState state = level.getBlockState(pos);
		Block cropBlock = state.getBlock();
		level.destroyBlock(pos, true, p);
		p.swing(InteractionHand.MAIN_HAND);
		brain.collectDrops(level, Vec3.atCenterOf(pos), 4.0);

		// Replant immediately if we have the matching seed/crop
		BlockState replant = replantStateFor(p, cropBlock);
		if (replant != null && level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
			level.setBlockAndUpdate(pos, replant);
			Territory.mark(level, pos);
			level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8f, 1.0f);
		}
		if (Gear.count(p.backpack(), Items.WHEAT) >= 9 && Crafting.tableNearby(p)) {
			Crafting.craft(p, Items.BREAD);
		}
		results++;
		cooldown = 6;
	}

	private void plantAt(PartnerEntity p, PartnerBrain brain, ServerLevel level, BlockPos farmland) {
		BlockPos cropPos = farmland.above();
		if (!BlockBreaker.inReach(p, cropPos)) {
			brain.approach(farmland);
			return;
		}
		BlockState toPlant = consumeNextSeedAndGetCrop(p);
		if (toPlant != null) {
			level.setBlockAndUpdate(cropPos, toPlant);
			Territory.mark(level, cropPos);
			p.swing(InteractionHand.MAIN_HAND);
			level.playSound(null, cropPos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8f, 1.0f);
			results++;
			cooldown = 6;
		}
	}

	private void tillAndPlant(PartnerEntity p, PartnerBrain brain, ServerLevel level, BlockPos dirtPos) {
		if (!BlockBreaker.inReach(p, dirtPos)) {
			brain.approach(dirtPos);
			return;
		}
		p.getNavigation().stop();
		equipHoe(p);
		BlockPos above = dirtPos.above();
		if (!level.getBlockState(above).isAir() && level.getBlockState(above).canBeReplaced()) {
			level.destroyBlock(above, true, p);
		}
		level.setBlockAndUpdate(dirtPos, Blocks.FARMLAND.defaultBlockState());
		Territory.mark(level, dirtPos);
		p.swing(InteractionHand.MAIN_HAND);
		level.playSound(null, dirtPos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.9f, 1.0f);

		BlockState toPlant = consumeNextSeedAndGetCrop(p);
		if (toPlant != null && level.getBlockState(above).isAir()) {
			level.setBlockAndUpdate(above, toPlant);
			Territory.mark(level, above);
		}
		results++;
		cooldown = 8;
	}

	private void fertilizeCrop(PartnerEntity p, PartnerBrain brain, ServerLevel level, BlockPos cropPos) {
		if (!BlockBreaker.inReach(p, cropPos)) {
			brain.approach(cropPos);
			return;
		}
		BlockState s = level.getBlockState(cropPos);
		if (s.getBlock() instanceof CropBlock crop && !crop.isMaxAge(s)) {
			if (Gear.take(p.backpack(), st -> st.is(Items.BONE_MEAL), 1) > 0) {
				int nextAge = Math.min(crop.getMaxAge(), crop.getAge(s) + 3);
				level.setBlockAndUpdate(cropPos, crop.getStateForAge(nextAge));
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, cropPos.getX() + 0.5, cropPos.getY() + 0.5, cropPos.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0.02);
				p.swing(InteractionHand.MAIN_HAND);
				results++;
				cooldown = 8;
			}
		}
	}

	private void shearSheep(PartnerEntity p, PartnerBrain brain, ServerLevel level, Sheep sheep) {
		if (p.distanceToSqr(sheep) > 6.0) {
			if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(sheep, PartnerEntity.SPRINT);
			brain.jumpIfBlocked(sheep.position());
			return;
		}
		equipInMainHand(p, Items.SHEARS);
		p.getLookControl().setLookAt(sheep, 30, 30);
		p.swing(InteractionHand.MAIN_HAND);
		sheep.shear(level, SoundSource.PLAYERS, new ItemStack(Items.SHEARS));
		brain.collectDrops(level, sheep.position(), 4.0);
		results++;
		cooldown = 12;
	}

	private boolean herdOrBreedAnimals(PartnerEntity p, PartnerBrain brain, ServerLevel level, BlockPos center) {
		// 1. Breed any adult animals near the farm center!
		List<Animal> nearbyAdults = level.getEntitiesOfClass(Animal.class, new AABB(center).inflate(18.0),
				a -> a.isAlive() && isFarmAnimal(a) && a.getAge() == 0 && a.canFallInLove());
		for (Animal a : nearbyAdults) {
			Item breedFood = breedingItemFor(p, a);
			if (breedFood == null) continue;
			boolean hasMate = nearbyAdults.stream().anyMatch(other -> other != a && other.getClass() == a.getClass());
			if (hasMate || countInHandOrBag(p, breedFood) >= 2) {
				if (p.distanceToSqr(a) > 6.0) {
					equipInMainHand(p, breedFood);
					if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(a, PartnerEntity.WALK);
					brain.jumpIfBlocked(a.position());
					return true;
				}
				if (consumeFromHandOrBag(p, breedFood, 1)) {
					a.setInLove(p.getOwnerPlayer());
					p.swing(InteractionHand.MAIN_HAND);
					level.sendParticles(ParticleTypes.HEART, a.getX(), a.getY() + 0.6, a.getZ(), 5, 0.25, 0.25, 0.25, 0.02);
					results++;
					cooldown = 10;
					return true;
				}
			}
		}

		// 2. Look for wild livestock further away (14..42 blocks from center) and lure/bring them to center!
		if (herdingTarget != null && (!herdingTarget.isAlive() || herdingTarget.blockPosition().distSqr(center) <= 100 || herdTicks > 240)) {
			herdingTarget = null;
			herdTicks = 0;
		}
		if (herdingTarget == null) {
			List<Animal> distant = level.getEntitiesOfClass(Animal.class, new AABB(center).inflate(42.0),
					a -> a.isAlive() && isFarmAnimal(a) && a.blockPosition().distSqr(center) > 196);
			for (Animal cand : distant) {
				if (breedingItemFor(p, cand) != null) {
					herdingTarget = cand;
					herdTicks = 0;
					brain.report("farm_herding", cand.getName());
					break;
				}
			}
		}

		if (herdingTarget != null) {
			herdTicks++;
			Item lure = breedingItemFor(p, herdingTarget);
			if (lure != null) equipInMainHand(p, lure);

			double distToAnimal = p.distanceTo(herdingTarget);
			if (distToAnimal > 6.5) {
				if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(herdingTarget, PartnerEntity.SPRINT);
				brain.jumpIfBlocked(herdingTarget.position());
			} else {
				// Walk slowly toward the farm center while leading the animal!
				if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) {
					p.getNavigation().moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, PartnerEntity.WALK);
				}
				herdingTarget.getLookControl().setLookAt(p, 30, 30);
				herdingTarget.getNavigation().moveTo(p, 1.05);
			}
			return true;
		}
		return false;
	}

	private boolean ensureFenceMaterials(PartnerEntity p) {
		int fences = Gear.count(p.backpack(), Items.OAK_FENCE);
		if (fences >= 12) return true;
		if (brain_logsCount(p) < 4 && Gear.count(p.backpack(), s -> s.is(ItemTags.PLANKS)) < 12) {
			subJob = new ChopJob(6);
			return false;
		}
		if (Crafting.canMake(p, Items.OAK_FENCE)) {
			subJob = new CraftJob(List.of(Items.OAK_FENCE, Items.OAK_FENCE, Items.OAK_FENCE, Items.OAK_FENCE, Items.OAK_FENCE_GATE), true);
			return false;
		}
		return false;
	}

	private static int brain_logsCount(PartnerEntity p) {
		return Gear.count(p.backpack(), s -> s.is(ItemTags.LOGS));
	}

	private void buildAnimalPen(PartnerEntity p, ServerLevel level, BlockPos center) {
		BlockPos penCenter = center.offset(5, 0, 5);
		int r = 2; // 5x5 fence pen
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
				BlockPos col = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, penCenter.offset(dx, 0, dz));
				BlockState cur = level.getBlockState(col);
				if (!cur.isAir() && !cur.canBeReplaced()) continue;
				if (dx == 0 && dz == -r) {
					level.setBlockAndUpdate(col, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH));
				} else {
					level.setBlockAndUpdate(col, Blocks.OAK_FENCE.defaultBlockState());
				}
				Territory.mark(level, col);
			}
		}
		Gear.take(p.backpack(), s -> s.is(Items.OAK_FENCE), 12);
		Gear.take(p.backpack(), s -> s.is(Items.OAK_FENCE_GATE), 1);
	}

	private static boolean isFarmAnimal(Animal a) {
		return a instanceof Cow || a instanceof Sheep || a instanceof Pig || a instanceof Chicken;
	}

	private static @Nullable Item breedingItemFor(PartnerEntity p, Animal a) {
		if (a instanceof Cow || a instanceof Sheep) {
			return countInHandOrBag(p, Items.WHEAT) > 0 ? Items.WHEAT : null;
		}
		if (a instanceof Pig) {
			if (countInHandOrBag(p, Items.CARROT) > 0) return Items.CARROT;
			if (countInHandOrBag(p, Items.POTATO) > 0) return Items.POTATO;
			if (countInHandOrBag(p, Items.BEETROOT) > 0) return Items.BEETROOT;
			return null;
		}
		if (a instanceof Chicken) {
			if (countInHandOrBag(p, Items.WHEAT_SEEDS) > 0) return Items.WHEAT_SEEDS;
			if (countInHandOrBag(p, Items.BEETROOT_SEEDS) > 0) return Items.BEETROOT_SEEDS;
		}
		return null;
	}

	private static int countInHandOrBag(PartnerEntity p, Item item) {
		return Gear.count(p.backpack(), item) + (p.getMainHandItem().is(item) ? p.getMainHandItem().getCount() : 0);
	}

	private static boolean consumeFromHandOrBag(PartnerEntity p, Item item, int amount) {
		if (Gear.take(p.backpack(), s -> s.is(item), amount) >= amount) return true;
		ItemStack main = p.getMainHandItem();
		if (main.is(item) && main.getCount() >= amount) {
			main.shrink(amount);
			return true;
		}
		return false;
	}

	private static boolean hasAnySeedsOrCrops(PartnerEntity p) {
		return Gear.count(p.backpack(), s ->
				s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT_SEEDS)) > 0;
	}

	private static @Nullable BlockState consumeNextSeedAndGetCrop(PartnerEntity p) {
		if (Gear.take(p.backpack(), s -> s.is(Items.WHEAT_SEEDS), 1) > 0) return Blocks.WHEAT.defaultBlockState();
		if (Gear.take(p.backpack(), s -> s.is(Items.CARROT), 1) > 0) return Blocks.CARROTS.defaultBlockState();
		if (Gear.take(p.backpack(), s -> s.is(Items.POTATO), 1) > 0) return Blocks.POTATOES.defaultBlockState();
		if (Gear.take(p.backpack(), s -> s.is(Items.BEETROOT_SEEDS), 1) > 0) return Blocks.BEETROOTS.defaultBlockState();
		return null;
	}

	private static @Nullable BlockState replantStateFor(PartnerEntity p, Block harvested) {
		if (harvested == Blocks.WHEAT && Gear.take(p.backpack(), s -> s.is(Items.WHEAT_SEEDS), 1) > 0) {
			return Blocks.WHEAT.defaultBlockState();
		}
		if (harvested == Blocks.CARROTS && Gear.take(p.backpack(), s -> s.is(Items.CARROT), 1) > 0) {
			return Blocks.CARROTS.defaultBlockState();
		}
		if (harvested == Blocks.POTATOES && Gear.take(p.backpack(), s -> s.is(Items.POTATO), 1) > 0) {
			return Blocks.POTATOES.defaultBlockState();
		}
		if (harvested == Blocks.BEETROOTS && Gear.take(p.backpack(), s -> s.is(Items.BEETROOT_SEEDS), 1) > 0) {
			return Blocks.BEETROOTS.defaultBlockState();
		}
		return consumeNextSeedAndGetCrop(p);
	}

	private static void equipInMainHand(PartnerEntity p, Item item) {
		if (p.getMainHandItem().is(item)) return;
		var bag = p.backpack();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (s.is(item)) {
				ItemStack old = p.getMainHandItem();
				p.setItemSlot(EquipmentSlot.MAINHAND, s.split(1));
				if (!old.isEmpty()) bag.addItem(old);
				bag.setChanged();
				return;
			}
		}
	}

	private static void equipHoe(PartnerEntity p) {
		if (p.getMainHandItem().is(ItemTags.HOES)) return;
		var bag = p.backpack();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (s.is(ItemTags.HOES)) {
				ItemStack old = p.getMainHandItem();
				p.setItemSlot(EquipmentSlot.MAINHAND, s.copy());
				bag.setItem(i, old);
				bag.setChanged();
				return;
			}
		}
	}

	private static @Nullable BlockPos findMatureCrop(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 4, r)) {
			BlockState s = level.getBlockState(pos);
			if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static @Nullable BlockPos findGrowingCrop(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 4, r)) {
			BlockState s = level.getBlockState(pos);
			if (s.getBlock() instanceof CropBlock crop && !crop.isMaxAge(s)) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static @Nullable BlockPos findEmptyFarmland(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 4, r)) {
			if (level.getBlockState(pos).is(Blocks.FARMLAND) && level.getBlockState(pos.above()).isAir()) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static @Nullable BlockPos findAnyFarmland(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 4, r)) {
			if (level.getBlockState(pos).is(Blocks.FARMLAND)) return pos.immutable();
		}
		return null;
	}

	private static @Nullable BlockPos findTillableNearWater(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 4, r)) {
			if (!level.getFluidState(pos).is(FluidTags.WATER)) continue;
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockPos soil = pos.offset(dx, 0, dz);
					BlockState s = level.getBlockState(soil);
					if ((s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK)) && level.getBlockState(soil.above()).isAir()) {
						return soil.immutable();
					}
				}
			}
		}
		return null;
	}

	private static @Nullable BlockPos findFlatGardenSpot(ServerLevel level, BlockPos center) {
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
				BlockPos pos = center.offset(dx, -1, dz);
				BlockState s = level.getBlockState(pos);
				if ((s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK)) && level.getBlockState(pos.above()).isAir()) {
					return pos.immutable();
				}
			}
		}
		return null;
	}

	private static @Nullable BlockPos findTallGrass(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 4, r)) {
			BlockState s = level.getBlockState(pos);
			if (s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN)) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static @Nullable Sheep findShearableSheep(ServerLevel level, BlockPos center, int r) {
		List<Sheep> list = level.getEntitiesOfClass(Sheep.class, new AABB(center).inflate(r),
				s -> s.isAlive() && s.readyForShearing());
		return list.isEmpty() ? null : list.getFirst();
	}

	private static boolean hasAnimalsNearby(ServerLevel level, BlockPos center, int r) {
		return !level.getEntitiesOfClass(Animal.class, new AABB(center).inflate(r), FarmJob::isFarmAnimal).isEmpty();
	}

	private static boolean hasFenceNearby(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 4, r)) {
			if (level.getBlockState(pos).is(BlockTags.FENCES)) return true;
		}
		return false;
	}

	@Override
	public void stop(PartnerBrain brain, ServerLevel level) {
		if (subJob != null) {
			subJob.stop(brain, level);
			subJob = null;
		}
	}
}
