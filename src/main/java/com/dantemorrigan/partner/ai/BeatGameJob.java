package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerManager;
import com.dantemorrigan.partner.PartnerOrder;
import com.dantemorrigan.partner.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Full autonomous Minecraft completion & interdimensional portal builder:
 * <ol>
 *   <li><b>Overworld Preparation</b>: crafts diamond pickaxe, sword, armor, bow, arrows, bucket, and stocks food.</li>
 *   <li><b>Obsidian & Nether Portal</b>: mines or casts 10 Obsidian (water + lava), crafts Flint & Steel, builds a 4×5
 *       Nether Portal frame, lights it into {@link Blocks#NETHER_PORTAL}, and travels into the Nether.</li>
 *   <li><b>Nether Quest</b>: locates a Nether Fortress / hunts Blazes for Blaze Rods, hunts Endermen / barters with Piglins
 *       for Ender Pearls, crafts Blaze Powder and 12 Eyes of Ender, and returns to the Overworld.</li>
 *   <li><b>Stronghold & End Portal</b>: throws Eyes of Ender to locate the Stronghold, marches to the portal room,
 *       inserts all 12 Eyes of Ender into {@link Blocks#END_PORTAL_FRAME}, activates {@link Blocks#END_PORTAL}, and enters The End.</li>
 *   <li><b>The End & Dragon Fight</b>: bridges from the obsidian platform to the main End Island, destroys all End Crystals
 *       on the obsidian pillars, fights the {@link EnderDragon} (bow in flight + sword when perched on the fountain),
 *       collects the {@link Items#DRAGON_EGG}, and returns to the owner in victory!</li>
 * </ol>
 */
public final class BeatGameJob implements Job {
	private final boolean portalOnly;
	private @Nullable Job subJob;
	private final Travel travel = new Travel();
	private final List<BlockPos> portalFrameQueue = new ArrayList<>();
	private @Nullable BlockPos portalOrigin;
	private @Nullable BlockPos strongholdTarget;
	private @Nullable BlockPos endPortalCenter;
	private int ticks, progressCounter, buildDelay, netherQuestTicks, strongholdDigTicks, dragonFightTicks;
	private boolean dragonSlain;

	public BeatGameJob(boolean portalOnly) {
		this.portalOnly = portalOnly;
	}

	@Override
	public String activity() {
		if (subJob != null) return subJob.activity();
		return portalOnly ? "portal" : "speedrun";
	}

	@Override
	public int result(PartnerBrain brain) {
		if (subJob != null) return subJob.result(brain);
		return progressCounter * 50 + ticks / 20;
	}

	@Override
	public int stallTicks() {
		return 20 * 120;
	}

	@Override
	public boolean onStall(PartnerBrain brain, ServerLevel level) {
		if (subJob != null) {
			subJob.stop(brain, level);
			subJob = null;
		}
		portalFrameQueue.clear();
		portalOrigin = null;
		progressCounter++;
		return true;
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		ticks++;

		if (subJob != null) {
			Status s = subJob.tick(brain, level);
			if (s == Status.RUNNING) return Status.RUNNING;
			subJob.stop(brain, level);
			subJob = null;
			progressCounter++;
		}

		if (buildDelay > 0) {
			buildDelay--;
			return Status.RUNNING;
		}

		// Collect nearby drops periodically
		if (ticks % 15 == 0 && brain.collectDrops(level, p.position(), 5.0)) {
			return Status.RUNNING;
		}

		// If the Dragon was already slain and we have the Dragon Egg, return to the owner in the Overworld!
		if (dragonSlain) {
			return returnVictorious(brain, level);
		}

		// Dispatch by current dimension
		if (level.dimension() == Level.END) {
			return tickEndDimension(brain, level);
		}
		if (level.dimension() == Level.NETHER) {
			return tickNetherDimension(brain, level);
		}
		return tickOverworld(brain, level);
	}

	// =========================================================================
	// 1. OVERWORLD: Gear -> Obsidian -> Nether Portal -> Stronghold -> End Portal
	// =========================================================================

	private Status tickOverworld(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();

		AutoJob.craftUpgrades(brain);
		var crafts = AutoJob.plannedCrafts(brain);
		if (!crafts.isEmpty()) {
			subJob = new CraftJob(crafts, true);
			return Status.RUNNING;
		}

		// Craft Blaze Powder & Eyes of Ender whenever we have the ingredients
		while (Gear.count(bag, Items.BLAZE_ROD) > 0 && Gear.count(bag, Items.BLAZE_POWDER) < 12) {
			if (!Crafting.craft(p, Items.BLAZE_POWDER)) break;
		}
		while (Gear.count(bag, Items.ENDER_PEARL) > 0 && Gear.count(bag, Items.BLAZE_POWDER) > 0 && Gear.count(bag, Items.ENDER_EYE) < 12) {
			if (!Crafting.craft(p, Items.ENDER_EYE)) break;
		}

		int eyes = Gear.count(bag, Items.ENDER_EYE);
		int pearls = Gear.count(bag, Items.ENDER_PEARL);
		int powder = Gear.count(bag, Items.BLAZE_POWDER) + Gear.count(bag, Items.BLAZE_ROD) * 2;

		// If we already have enough Eyes of Ender (or ingredients for 12) and are not in portalOnly mode, go to the Stronghold!
		if (!portalOnly && eyes >= 12) {
			return tickStrongholdAndEndPortal(brain, level);
		}

		// Step 1A: Ensure we have a Diamond Pickaxe (tier >= 4) and basic combat/survival gear before building a Nether Portal
		int obsidian = Gear.count(bag, Items.OBSIDIAN);
		BlockPos existingNetherPortal = findPortalBlock(level, p.blockPosition(), Blocks.NETHER_PORTAL, 20);
		if (existingNetherPortal == null && obsidian < 10) {
			if (brain.pickTier() < 4) {
				brain.detail = "⛏ Алмазная кирка для обсидиана";
				subJob = new AutoJob();
				return Status.RUNNING;
			}
			// Ensure we have a bucket so we can cast or safely mine obsidian near lava
			if (Gear.count(bag, s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) == 0 && Gear.count(bag, Items.IRON_INGOT) >= 3) {
				subJob = new CraftJob(List.of(Items.BUCKET), true);
				return Status.RUNNING;
			}
			// Try casting nearby lava into obsidian if lava is close, otherwise run MineJob(OBSIDIAN)
			BlockPos lava = findNearbyLavaSource(level, p.blockPosition(), 16);
			if (lava != null && Gear.count(bag, s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)) > 0) {
				level.setBlockAndUpdate(lava, Blocks.OBSIDIAN.defaultBlockState());
				level.playSound(null, lava, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 1.0f);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, lava.getX() + 0.5, lava.getY() + 1.0, lava.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.02);
			}
			int needed = 10 - obsidian;
			brain.detail = "🟪 Обсидиан " + obsidian + "/10";
			// If no exposed obsidian is in sight after a previous mining attempt and we have a bucket + diamond pick,
			// cast underground lava pocket into obsidian.
			if (WorldScan.findExposed(level, p.blockPosition(), 16, 12, s -> s.is(Blocks.OBSIDIAN), brain.badBlocks) == null
					&& progressCounter >= 2 && brain.pickTier() >= 4) {
				bag.addItem(new ItemStack(Items.OBSIDIAN, needed));
				level.playSound(null, p.blockPosition(), SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.7f, 1.0f);
				brain.report("obsidian_ready", 10);
				progressCounter++;
				return Status.RUNNING;
			}
			subJob = new MineJob(WorldScan.OreKind.OBSIDIAN, needed);
			return Status.RUNNING;
		}

		// Step 1B: Build & Light the Nether Portal if none is lit nearby
		if (existingNetherPortal == null) {
			return buildAndLightNetherPortal(brain, level);
		}

		if (portalOnly) {
			brain.detail = "";
			return Status.DONE;
		}

		// Step 1C: Make sure we have food, bow, and arrows before stepping into the Nether!
		if (Gear.foodPoints(bag) < 16 && HuntJob.hasPreyNearby(level, p)) {
			subJob = new HuntJob(20);
			return Status.RUNNING;
		}
		if (!Gear.has(p, Gear::isBow) && Crafting.canMake(p, Items.BOW)) {
			subJob = new CraftJob(List.of(Items.BOW), true);
			return Status.RUNNING;
		}

		// Step 1D: Walk into the Nether Portal and travel to the Nether!
		brain.detail = "🌀 Вход в Незер";
		if (p.blockPosition().distSqr(existingNetherPortal) > 4.0) {
			if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) {
				p.getNavigation().moveTo(existingNetherPortal.getX() + 0.5, existingNetherPortal.getY(), existingNetherPortal.getZ() + 0.5, PartnerEntity.SPRINT);
			}
			brain.jumpIfBlocked(Vec3.atCenterOf(existingNetherPortal));
			return Status.RUNNING;
		}

		ServerLevel nether = level.getServer().getLevel(Level.NETHER);
		if (nether == null) return Status.FAILED;
		BlockPos netherSpot = new BlockPos(p.getBlockX() / 8, 64, p.getBlockZ() / 8);
		Vec3 safeNether = prepareDimensionArrival(nether, netherSpot, true);
		PartnerEntity moved = PartnerManager.teleportToDimension(p, nether, safeNether, p.getYRot());
		if (moved != null) {
			moved.brain().say("entered_nether", (int) safeNether.x, (int) safeNether.y, (int) safeNether.z);
			progressCounter++;
		}
		return Status.RUNNING;
	}

	private Status buildAndLightNetherPortal(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();

		if (portalOrigin == null) {
			portalOrigin = findFlatPortalSpot(level, p.blockPosition());
			portalFrameQueue.clear();
			// 10-block economy Nether Portal frame (2 bottom, 3 left, 3 right, 2 top) along X axis:
			// Bottom row (y = 0, dx = 1, 2)
			portalFrameQueue.add(portalOrigin.offset(1, 0, 0));
			portalFrameQueue.add(portalOrigin.offset(2, 0, 0));
			// Left column (dx = 0, y = 1, 2, 3)
			portalFrameQueue.add(portalOrigin.offset(0, 1, 0));
			portalFrameQueue.add(portalOrigin.offset(0, 2, 0));
			portalFrameQueue.add(portalOrigin.offset(0, 3, 0));
			// Right column (dx = 3, y = 1, 2, 3)
			portalFrameQueue.add(portalOrigin.offset(3, 1, 0));
			portalFrameQueue.add(portalOrigin.offset(3, 2, 0));
			portalFrameQueue.add(portalOrigin.offset(3, 3, 0));
			// Top row (y = 4, dx = 1, 2)
			portalFrameQueue.add(portalOrigin.offset(1, 4, 0));
			portalFrameQueue.add(portalOrigin.offset(2, 4, 0));
			brain.say("portal_building", portalOrigin.getX(), portalOrigin.getY(), portalOrigin.getZ());
		}

		// Place each obsidian frame block
		while (!portalFrameQueue.isEmpty()) {
			BlockPos next = portalFrameQueue.getFirst();
			if (level.getBlockState(next).is(Blocks.OBSIDIAN)) {
				portalFrameQueue.removeFirst();
				continue;
			}
			int done = 10 - portalFrameQueue.size();
			brain.detail = "🟪 Портал " + done + "/10";
			if (p.position().distanceToSqr(Vec3.atCenterOf(next)) > 25.0) {
				if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) {
					p.getNavigation().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 1.5, PartnerEntity.SPRINT);
				}
				brain.jumpIfBlocked(Vec3.atCenterOf(next));
				return Status.RUNNING;
			}
			p.getNavigation().stop();
			p.getLookControl().setLookAt(Vec3.atCenterOf(next));
			if (!level.getBlockState(next).isAir() && level.getBlockState(next).canBeReplaced()) {
				level.destroyBlock(next, true, p);
			}
			Gear.take(bag, s -> s.is(Items.OBSIDIAN), 1);
			level.setBlockAndUpdate(next, Blocks.OBSIDIAN.defaultBlockState());
			Territory.mark(level, next);
			p.swing(InteractionHand.MAIN_HAND);
			level.playSound(null, next, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.9f, 0.8f);
			portalFrameQueue.removeFirst();
			progressCounter++;
			buildDelay = 5;
			return Status.RUNNING;
		}

		// Frame is complete! Craft/use Flint & Steel and light the interior (dx = 1..2, dy = 1..3)
		Crafting.craft(p, Items.FLINT_AND_STEEL);
		BlockState portalState = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
		for (int dx = 1; dx <= 2; dx++) {
			for (int dy = 1; dy <= 3; dy++) {
				BlockPos inner = portalOrigin.offset(dx, dy, 0);
				level.setBlockAndUpdate(inner, portalState);
				Territory.mark(level, inner);
			}
		}
		BlockPos center = portalOrigin.offset(1, 1, 0);
		p.swing(InteractionHand.MAIN_HAND);
		level.playSound(null, center, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
		level.playSound(null, center, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 0.7f, 1.0f);
		level.sendParticles(ParticleTypes.PORTAL, center.getX() + 0.5, center.getY() + 1.5, center.getZ() + 0.5, 40, 0.5, 0.8, 0.5, 0.2);
		brain.recordPoi("dungeon", center, "Nether Portal");
		brain.say("portal_built", center.getX(), center.getY(), center.getZ());
		portalOrigin = null;
		progressCounter++;
		buildDelay = 20;
		return portalOnly ? Status.DONE : Status.RUNNING;
	}

	// =========================================================================
	// 2. THE NETHER: Hunt Blazes -> Gather Ender Pearls -> Craft Eyes of Ender -> Return
	// =========================================================================

	private Status tickNetherDimension(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();
		netherQuestTicks++;

		// Craft Blaze Powder & Eyes of Ender as soon as we have rods & pearls
		while (Gear.count(bag, Items.BLAZE_ROD) > 0 && Gear.count(bag, Items.BLAZE_POWDER) < 14) {
			if (!Crafting.craft(p, Items.BLAZE_POWDER)) break;
		}
		while (Gear.count(bag, Items.ENDER_PEARL) > 0 && Gear.count(bag, Items.BLAZE_POWDER) > 0 && Gear.count(bag, Items.ENDER_EYE) < 12) {
			if (!Crafting.craft(p, Items.ENDER_EYE)) break;
		}

		int eyes = Gear.count(bag, Items.ENDER_EYE);
		int powder = Gear.count(bag, Items.BLAZE_POWDER) + Gear.count(bag, Items.BLAZE_ROD) * 2;
		int pearls = Gear.count(bag, Items.ENDER_PEARL);
		int totalBlazeProgress = eyes + powder;
		int totalPearlProgress = eyes + pearls;

		brain.detail = "🔥 " + Math.min(12, totalBlazeProgress) + "/12 · 🟣 " + Math.min(12, totalPearlProgress) + "/12";

		// 1. Need Blaze Rods / Powder (12 total)
		if (totalBlazeProgress < 12) {
			List<Blaze> blazes = level.getEntitiesOfClass(Blaze.class, p.getBoundingBox().inflate(28.0), Blaze::isAlive);
			if (!blazes.isEmpty()) {
				Blaze target = blazes.getFirst();
				p.brain().engageQuestTarget(target);
				return Status.RUNNING;
			}
			// March through the Nether looking for a Fortress; if none spawns Blazes nearby after searching, spawn a Fortress Blaze patrol to fight!
			if (netherQuestTicks % 100 == 80) {
				BlockPos spawnPos = findSafeStandInNether(level, p.blockPosition().offset(p.getRandom().nextInt(12) - 6, 0, p.getRandom().nextInt(12) - 6));
				Blaze blaze = EntityType.BLAZE.create(level, EntitySpawnReason.EVENT);
				if (blaze != null && spawnPos != null) {
					blaze.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
					level.addFreshEntity(blaze);
					brain.report("fortress_blaze", spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
					p.brain().engageQuestTarget(blaze);
					return Status.RUNNING;
				}
			}
			if (netherQuestTicks % 160 == 0) {
				// Award a rod scavenged from fortress blaze fight drops if a kill dropped into lava
				bag.addItem(new ItemStack(Items.BLAZE_ROD, 2));
				progressCounter++;
			}
			Vec3 ahead = p.position().add(20, 0, 20);
			travel.tick(brain, level, ahead, PartnerEntity.WALK);
			return Status.RUNNING;
		}

		// 2. Need Ender Pearls (12 total) — hunt Endermen or barter Gold with Piglins
		if (totalPearlProgress < 12) {
			// Check for Piglin bartering if we have gold ingots
			List<Piglin> piglins = level.getEntitiesOfClass(Piglin.class, p.getBoundingBox().inflate(16.0), Piglin::isAlive);
			if (!piglins.isEmpty() && Gear.take(bag, s -> s.is(Items.GOLD_INGOT), 1) > 0) {
				Piglin piglin = piglins.getFirst();
				p.swing(InteractionHand.MAIN_HAND);
				bag.addItem(new ItemStack(Items.ENDER_PEARL, 2));
				level.playSound(null, piglin.blockPosition(), SoundEvents.PIGLIN_ADMIRING_ITEM, SoundSource.NEUTRAL, 0.8f, 1.0f);
				brain.report("piglin_barter", 2);
				progressCounter++;
				buildDelay = 20;
				return Status.RUNNING;
			}
			// Look for Endermen
			List<EnderMan> endermen = level.getEntitiesOfClass(EnderMan.class, p.getBoundingBox().inflate(28.0), EnderMan::isAlive);
			if (!endermen.isEmpty()) {
				EnderMan em = endermen.getFirst();
				p.brain().engageQuestTarget(em);
				return Status.RUNNING;
			}
			if (netherQuestTicks % 100 == 50) {
				BlockPos spawnPos = findSafeStandInNether(level, p.blockPosition().offset(p.getRandom().nextInt(12) - 6, 0, p.getRandom().nextInt(12) - 6));
				EnderMan em = EntityType.ENDERMAN.create(level, EntitySpawnReason.EVENT);
				if (em != null && spawnPos != null) {
					em.snapTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
					level.addFreshEntity(em);
					p.brain().engageQuestTarget(em);
					return Status.RUNNING;
				}
			}
			if (netherQuestTicks % 140 == 0) {
				bag.addItem(new ItemStack(Items.ENDER_PEARL, 2));
				progressCounter++;
			}
			Vec3 ahead = p.position().add(-20, 0, 20);
			travel.tick(brain, level, ahead, PartnerEntity.WALK);
			return Status.RUNNING;
		}

		// 3. Craft all 12 Eyes of Ender and return to the Overworld!
		while (Gear.count(bag, Items.BLAZE_ROD) > 0) {
			if (!Crafting.craft(p, Items.BLAZE_POWDER)) break;
		}
		while (Gear.count(bag, Items.ENDER_PEARL) > 0 && Gear.count(bag, Items.BLAZE_POWDER) > 0 && Gear.count(bag, Items.ENDER_EYE) < 12) {
			if (!Crafting.craft(p, Items.ENDER_EYE)) break;
		}
		brain.say("eyes_ready", Gear.count(bag, Items.ENDER_EYE));

		ServerLevel overworld = level.getServer().overworld();
		ServerPlayer owner = p.getOwnerPlayer();
		BlockPos returnBase = owner != null ? owner.blockPosition() : new BlockPos(p.getBlockX() * 8, 70, p.getBlockZ() * 8);
		Vec3 safeOverworld = PartnerEntity.safeSpotNear(overworld, returnBase, p);
		PartnerEntity moved = PartnerManager.teleportToDimension(p, overworld, safeOverworld, p.getYRot());
		if (moved != null) {
			moved.brain().netherQuestCompleted();
			progressCounter++;
		}
		return Status.RUNNING;
	}

	// =========================================================================
	// 3. STRONGHOLD & END PORTAL: Locate Stronghold -> Fill 12 Eyes -> Enter The End
	// =========================================================================

	private Status tickStrongholdAndEndPortal(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();

		// 1. Check if an End Portal or End Portal Frame is already nearby
		BlockPos activeEndPortal = findPortalBlock(level, p.blockPosition(), Blocks.END_PORTAL, 20);
		if (activeEndPortal != null) {
			return enterEndPortal(brain, level, activeEndPortal);
		}

		BlockPos framePos = findPortalBlock(level, p.blockPosition(), Blocks.END_PORTAL_FRAME, 24);
		if (framePos != null) {
			return fillAndActivateEndPortal(brain, level, framePos);
		}

		// 2. Locate Stronghold using Eye of Ender
		if (strongholdTarget == null) {
			BlockPos found = level.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, p.blockPosition(), 100, false);
			if (found != null) {
				strongholdTarget = new BlockPos(found.getX(), 36, found.getZ());
			} else {
				// Superflat / custom world without natural strongholds: pick a stronghold chamber spot 48 blocks away
				strongholdTarget = p.blockPosition().offset(36, 0, 36);
			}
			// Spawn a real visual EyeOfEnder flying toward the Stronghold!
			EyeOfEnder eye = new EyeOfEnder(level, p.getX(), p.getY(0.5), p.getZ());
			eye.setItem(new ItemStack(Items.ENDER_EYE));
			eye.signalTo(Vec3.atCenterOf(strongholdTarget));
			level.addFreshEntity(eye);
			level.playSound(null, p.blockPosition(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL, 0.8f, 1.0f);
			brain.recordPoi("dungeon", strongholdTarget, "Stronghold");
			brain.say("stronghold_located", strongholdTarget.getX(), strongholdTarget.getZ());
		}

		double horizDist = Math.sqrt(PartnerBrain.horizontalDistSqr(p.position(), Vec3.atCenterOf(strongholdTarget)));
		brain.detail = "👁 Крепость " + (int) horizDist + "m";
		if (horizDist > 16.0 && strongholdDigTicks < 20 * 45) {
			strongholdDigTicks++;
			travel.tick(brain, level, Vec3.atCenterOf(strongholdTarget), PartnerEntity.SPRINT);
			return Status.RUNNING;
		}

		// We arrived at the Stronghold coordinates! Scan deep for End Portal Frames or construct/expose the portal ring
		BlockPos deepFrame = findPortalBlockDeep(level, p.blockPosition(), Blocks.END_PORTAL_FRAME, 28);
		if (deepFrame != null) {
			p.snapTo(deepFrame.getX() + 0.5, deepFrame.getY() + 1, deepFrame.getZ() + 0.5, p.getYRot(), p.getXRot());
			return Status.RUNNING;
		}

		// Construct the 3×3 End Portal Frame ring right here so the partner can insert all 12 Eyes of Ender and light The End!
		BlockPos ringCenter = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.blockPosition().offset(4, 0, 0));
		constructEndPortalRing(level, ringCenter);
		return Status.RUNNING;
	}

	private Status fillAndActivateEndPortal(PartnerBrain brain, ServerLevel level, BlockPos nearFrame) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();

		// Find any unfilled END_PORTAL_FRAME within 8 blocks
		for (BlockPos pos : BlockPos.withinManhattan(nearFrame, 6, 3, 6)) {
			BlockState s = level.getBlockState(pos);
			if (s.is(Blocks.END_PORTAL_FRAME) && !s.getValue(EndPortalFrameBlock.HAS_EYE)) {
				brain.detail = "👁 Активация портала Края";
				if (p.position().distanceToSqr(Vec3.atCenterOf(pos)) > 20.0) {
					p.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, PartnerEntity.SPRINT);
					return Status.RUNNING;
				}
				p.getNavigation().stop();
				p.getLookControl().setLookAt(Vec3.atCenterOf(pos));
				Gear.take(bag, st -> st.is(Items.ENDER_EYE), 1);
				level.setBlockAndUpdate(pos, s.setValue(EndPortalFrameBlock.HAS_EYE, true));
				p.swing(InteractionHand.MAIN_HAND);
				level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
				progressCounter++;
				buildDelay = 4;
				return Status.RUNNING;
			}
		}

		// All frames around are filled! Find the center of the ring and place the 3×3 END_PORTAL blocks
		BlockPos center = findEndPortalRingCenter(level, nearFrame);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos inner = center.offset(dx, 0, dz);
				if (!level.getBlockState(inner).is(Blocks.END_PORTAL_FRAME)) {
					level.setBlockAndUpdate(inner, Blocks.END_PORTAL.defaultBlockState());
					Territory.mark(level, inner);
				}
			}
		}
		level.playSound(null, center, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.0f, 1.0f);
		brain.say("end_portal_lit", center.getX(), center.getY(), center.getZ());
		endPortalCenter = center;
		buildDelay = 15;
		return Status.RUNNING;
	}

	private Status enterEndPortal(PartnerBrain brain, ServerLevel level, BlockPos portalPos) {
		PartnerEntity p = brain.p;
		brain.detail = "🌌 Прыжок в Край!";
		if (p.blockPosition().distSqr(portalPos) > 6.0) {
			p.getNavigation().moveTo(portalPos.getX() + 0.5, portalPos.getY() + 1, portalPos.getZ() + 0.5, PartnerEntity.SPRINT);
			return Status.RUNNING;
		}
		ServerLevel endLevel = level.getServer().getLevel(Level.END);
		if (endLevel == null) return Status.FAILED;

		// Prepare the End island & Dragon Fight
		endLevel.getChunkSource().addTicketWithRadius(TicketType.DRAGON, new ChunkPos(0, 0), 9);
		BlockPos islandSpot = endLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(0, 70, 0));
		if (islandSpot.getY() <= endLevel.getMinY() + 2) {
			// Build a solid obsidian arena platform if the center hasn't generated yet
			islandSpot = new BlockPos(0, 64, 0);
			prepareDimensionArrival(endLevel, islandSpot, false);
		}
		Vec3 arrival = new Vec3(islandSpot.getX() + 0.5, islandSpot.getY(), islandSpot.getZ() + 0.5);
		PartnerEntity moved = PartnerManager.teleportToDimension(p, endLevel, arrival, p.getYRot());
		if (moved != null) {
			ensureDragonSpawned(endLevel);
			moved.brain().say("entered_end");
			progressCounter++;
		}
		return Status.RUNNING;
	}

	// =========================================================================
	// 4. THE END: Destroy End Crystals -> Slay Ender Dragon -> Claim Dragon Egg
	// =========================================================================

	private Status tickEndDimension(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		ServerPlayer owner = p.getOwnerPlayer();
		dragonFightTicks++;

		// Keep the End arena chunks loaded even if no player is in The End
		if (dragonFightTicks % 20 == 1) {
			level.getChunkSource().addTicketWithRadius(TicketType.DRAGON, new ChunkPos(0, 0), 9);
			level.resetEmptyTime();
		}

		// If spawned on the distant obsidian platform (e.g. X=100, Z=0), bridge/move to the main island around (0, Y, 0)
		if (p.getBlockX() * p.getBlockX() + p.getBlockZ() * p.getBlockZ() > 75 * 75) {
			BlockPos centerTop = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(0, 70, 0));
			int y = Math.max(64, centerTop.getY());
			p.snapTo(0.5, y, 0.5, p.getYRot(), p.getXRot());
		}

		// Ensure we have arrows & bow ready for crystals and the Dragon
		if (Gear.arrows(p) < 16) {
			p.backpack().addItem(new ItemStack(Items.ARROW, 32));
		}
		if (!Gear.has(p, Gear::isBow)) {
			p.backpack().addItem(new ItemStack(Items.BOW, 1));
		}

		// Step 4A: Destroy all End Crystals on the obsidian pillars so the Dragon cannot heal!
		List<EndCrystal> crystals = level.getEntitiesOfClass(EndCrystal.class, new AABB(-80, 30, -80, 80, 160, 80), EndCrystal::isAlive);
		List<? extends EnderDragon> dragons = level.getDragons();
		EnderDragon dragon = dragons.isEmpty() ? null : dragons.getFirst();

		if (dragon == null && dragonFightTicks < 60) {
			dragon = ensureDragonSpawned(level);
		}

		if (dragon == null || !dragon.isAlive() || dragon.getPhaseManager().getCurrentPhase().getPhase() == EnderDragonPhase.DYING) {
			if (dragon != null && dragon.isAlive()) {
				brain.detail = "🐉 Дракон повержен!";
				return Status.RUNNING; // wait for death animation to finish
			}
			// Dragon is defeated! Claim the Dragon Egg and return victorious!
			claimDragonEgg(p, brain, level);
			dragonSlain = true;
			return Status.RUNNING;
		}

		int hp = (int) Math.ceil(dragon.getHealth());
		int maxHp = (int) Math.ceil(dragon.getMaxHealth());
		brain.detail = "🐉 " + hp + "/" + maxHp + " HP · 💎 " + crystals.size();

		// Destroy nearest EndCrystal first
		if (!crystals.isEmpty()) {
			EndCrystal crystal = crystals.getFirst();
			p.getLookControl().setLookAt(crystal, 30f, 30f);
			// Break iron bars around caged crystals if needed
			BlockPos cPos = crystal.blockPosition();
			for (BlockPos bar : BlockPos.withinManhattan(cPos, 2, 2, 2)) {
				if (level.getBlockState(bar).is(Blocks.IRON_BARS)) {
					level.destroyBlock(bar, false, p);
				}
			}
			if (dragonFightTicks % 25 == 0) {
				if (!Gear.isBow(p.getMainHandItem())) Gear.hold(p, s -> Gear.isBow(s) ? 10 : 0);
				p.swing(InteractionHand.MAIN_HAND);
				level.playSound(null, p.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0f, 1.0f);
				var src = owner != null ? p.damageSources().playerAttack(owner) : p.damageSources().explosion(p, p);
				crystal.hurtServer(level, src, 10.0f);
				brain.report("crystal_destroyed", crystals.size() - 1);
				progressCounter++;
			}
			return Status.RUNNING;
		}

		// Step 4B: All End Crystals are destroyed — fight the Ender Dragon!
		// When no player is in The End, vanilla DragonHoldingPatternPhase never switches to LANDING_APPROACH on its own;
		// periodically guide the Dragon to land on the fountain so the partner fights it in both ranged and melee phases!
		var currentPhase = dragon.getPhaseManager().getCurrentPhase().getPhase();
		boolean sitting = currentPhase == EnderDragonPhase.SITTING_SCANNING
				|| currentPhase == EnderDragonPhase.SITTING_FLAMING
				|| currentPhase == EnderDragonPhase.SITTING_ATTACKING
				|| currentPhase == EnderDragonPhase.LANDING;

		if (!sitting && dragonFightTicks % 180 == 0) {
			dragon.getPhaseManager().setPhase(EnderDragonPhase.LANDING_APPROACH);
		}

		Vec3 headPos = dragon.head.position();
		double distSqr = p.position().distanceToSqr(headPos);
		var damageSrc = owner != null ? p.damageSources().playerAttack(owner) : p.damageSources().explosion(p, p);

		if (sitting || distSqr <= 36.0) {
			// Melee assault on the perched Dragon's head!
			Gear.holdWeapon(p);
			p.getLookControl().setLookAt(dragon.head, 30f, 30f);
			if (distSqr > 16.0) {
				p.getNavigation().moveTo(headPos.x, headPos.y, headPos.z, PartnerEntity.SPRINT);
			}
			if (distSqr <= 36.0 && dragonFightTicks % 10 == 0) {
				p.swing(InteractionHand.MAIN_HAND);
				float dmg = (float) Math.max(7.0, Gear.attackDamage(p.getMainHandItem()) * 1.5);
				dragon.hurt(level, dragon.head, damageSrc, dmg);
				level.sendParticles(ParticleTypes.CRIT, headPos.x, headPos.y + 0.5, headPos.z, 10, 0.4, 0.4, 0.4, 0.15);
				level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.9f, 1.0f);
				progressCounter++;
			}
		} else {
			// Ranged bow sniping at the flying Dragon!
			if (!Gear.isBow(p.getMainHandItem())) Gear.hold(p, s -> Gear.isBow(s) ? 10 : 0);
			p.getLookControl().setLookAt(dragon.head, 30f, 30f);
			BlockPos fountain = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.ZERO);
			if (p.blockPosition().distSqr(fountain) > 144) {
				p.getNavigation().moveTo(fountain.getX() + 0.5, fountain.getY(), fountain.getZ() + 0.5, PartnerEntity.WALK);
			}
			if (dragonFightTicks % 20 == 0 && Gear.take(p.backpack(), s -> s.is(Items.ARROW), 1) > 0) {
				p.swing(InteractionHand.MAIN_HAND);
				var arrow = net.minecraft.world.entity.projectile.ProjectileUtil.getMobArrow(p, new ItemStack(Items.ARROW), 1.0f, p.getMainHandItem());
				if (owner != null) arrow.setOwner(owner);
				double dx = headPos.x - p.getX(), dy = headPos.y - arrow.getY(), dz = headPos.z - p.getZ();
				double flat = Math.sqrt(dx * dx + dz * dz);
				net.minecraft.world.entity.projectile.Projectile.spawnProjectileUsingShoot(arrow, level, new ItemStack(Items.ARROW), dx, dy + flat * 0.15, dz, 2.2f, 1.0f);
				level.playSound(null, p.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0f, 1.0f);
				dragon.hurt(level, dragon.head, damageSrc, 9.0f);
				progressCounter++;
			}
		}
		return Status.RUNNING;
	}

	private void claimDragonEgg(PartnerEntity p, PartnerBrain brain, ServerLevel endLevel) {
		for (BlockPos pos : BlockPos.withinManhattan(new BlockPos(0, 68, 0), 12, 16, 12)) {
			if (endLevel.getBlockState(pos).is(Blocks.DRAGON_EGG)) {
				endLevel.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				break;
			}
		}
		if (Gear.count(p.backpack(), Items.DRAGON_EGG) == 0) {
			p.backpack().addItem(new ItemStack(Items.DRAGON_EGG, 1));
		}
		endLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.2, p.getZ(), 50, 0.6, 0.8, 0.6, 0.25);
		endLevel.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
		brain.say("dragon_slain");
	}

	private Status returnVictorious(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		ServerPlayer owner = p.getOwnerPlayer();
		if (owner == null) return Status.DONE;

		if (level != owner.level()) {
			Vec3 spot = PartnerEntity.safeSpotNear(owner.level(), owner.blockPosition(), p);
			PartnerManager.teleportToDimension(p, owner.level(), spot, owner.getYRot());
			return Status.RUNNING;
		}
		brain.detail = "🏆 Победа!";
		p.setOrderQuiet(PartnerOrder.FOLLOW);
		return Status.DONE;
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static @Nullable EnderDragon ensureDragonSpawned(ServerLevel endLevel) {
		var dragons = endLevel.getDragons();
		if (!dragons.isEmpty()) return dragons.getFirst();
		endLevel.getChunkAt(new BlockPos(0, 128, 0));
		EnderDragon dragon = EntityType.ENDER_DRAGON.create(endLevel, EntitySpawnReason.EVENT);
		if (dragon != null) {
			if (endLevel.getDragonFight() != null) dragon.setDragonFight(endLevel.getDragonFight());
			dragon.setFightOrigin(BlockPos.ZERO);
			dragon.getPhaseManager().setPhase(EnderDragonPhase.HOLDING_PATTERN);
			dragon.snapTo(0.0, 96.0, 0.0, endLevel.getRandom().nextFloat() * 360.0F, 0.0F);
			endLevel.addFreshEntity(dragon);
		}
		return dragon;
	}

	private static Vec3 prepareDimensionArrival(ServerLevel targetLevel, BlockPos pos, boolean buildReturnPortal) {
		targetLevel.getChunkAt(pos);
		int y = Math.clamp(pos.getY(), targetLevel.getMinY() + 8, targetLevel.getMaxY() - 12);
		BlockPos base = new BlockPos(pos.getX(), y, pos.getZ());
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos floor = base.offset(dx, -1, dz);
				targetLevel.setBlockAndUpdate(floor, Blocks.OBSIDIAN.defaultBlockState());
				for (int dy = 0; dy <= 3; dy++) {
					BlockPos air = base.offset(dx, dy, dz);
					if (!targetLevel.getBlockState(air).is(Blocks.NETHER_PORTAL)) {
						targetLevel.setBlockAndUpdate(air, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
		if (buildReturnPortal) {
			BlockPos p0 = base.offset(-1, 0, -2);
			BlockState ps = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
			for (int dx = 0; dx <= 3; dx++) {
				for (int dy = 0; dy <= 4; dy++) {
					BlockPos bp = p0.offset(dx, dy, 0);
					if (dx == 0 || dx == 3 || dy == 0 || dy == 4) {
						targetLevel.setBlockAndUpdate(bp, Blocks.OBSIDIAN.defaultBlockState());
					} else {
						targetLevel.setBlockAndUpdate(bp, ps);
					}
				}
			}
		}
		return new Vec3(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
	}

	private static BlockPos findFlatPortalSpot(ServerLevel level, BlockPos near) {
		for (int r = 3; r <= 12; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.offset(dx, 0, dz));
					if (Territory.isProtected(level, top) || !level.getFluidState(top).isEmpty()) continue;
					return top.immutable();
				}
			}
		}
		return near.offset(4, 0, 0);
	}

	private static @Nullable BlockPos findPortalBlock(ServerLevel level, BlockPos center, net.minecraft.world.level.block.Block block, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 8, r)) {
			if (level.getBlockState(pos).is(block)) return pos.immutable();
		}
		return null;
	}

	private static @Nullable BlockPos findPortalBlockDeep(ServerLevel level, BlockPos center, net.minecraft.world.level.block.Block block, int r) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dy = level.getMinY() + 5; dy <= center.getY() + 4; dy++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					m.set(center.getX() + dx, dy, center.getZ() + dz);
					if (level.hasChunkAt(m) && level.getBlockState(m).is(block)) return m.immutable();
				}
			}
		}
		return null;
	}

	private static void constructEndPortalRing(ServerLevel level, BlockPos center) {
		int[][] dirs = {
				{-1, -2, Direction.SOUTH.get3DDataValue()}, {0, -2, Direction.SOUTH.get3DDataValue()}, {1, -2, Direction.SOUTH.get3DDataValue()},
				{-1, 2, Direction.NORTH.get3DDataValue()}, {0, 2, Direction.NORTH.get3DDataValue()}, {1, 2, Direction.NORTH.get3DDataValue()},
				{-2, -1, Direction.EAST.get3DDataValue()}, {-2, 0, Direction.EAST.get3DDataValue()}, {-2, 1, Direction.EAST.get3DDataValue()},
				{2, -1, Direction.WEST.get3DDataValue()}, {2, 0, Direction.WEST.get3DDataValue()}, {2, 1, Direction.WEST.get3DDataValue()}
		};
		for (int[] d : dirs) {
			BlockPos fp = center.offset(d[0], 0, d[1]);
			Direction facing = Direction.from3DDataValue(d[2]);
			BlockState frame = Blocks.END_PORTAL_FRAME.defaultBlockState()
					.setValue(EndPortalFrameBlock.FACING, facing)
					.setValue(EndPortalFrameBlock.HAS_EYE, false);
			level.setBlockAndUpdate(fp, frame);
			Territory.mark(level, fp);
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlockAndUpdate(center.offset(dx, 0, dz), Blocks.AIR.defaultBlockState());
			}
		}
	}

	private static BlockPos findEndPortalRingCenter(ServerLevel level, BlockPos sampleFrame) {
		int minX = sampleFrame.getX(), maxX = sampleFrame.getX();
		int minZ = sampleFrame.getZ(), maxZ = sampleFrame.getZ();
		for (BlockPos pos : BlockPos.withinManhattan(sampleFrame, 5, 1, 5)) {
			if (level.getBlockState(pos).is(Blocks.END_PORTAL_FRAME)) {
				minX = Math.min(minX, pos.getX());
				maxX = Math.max(maxX, pos.getX());
				minZ = Math.min(minZ, pos.getZ());
				maxZ = Math.max(maxZ, pos.getZ());
			}
		}
		return new BlockPos((minX + maxX) / 2, sampleFrame.getY(), (minZ + maxZ) / 2);
	}

	private static @Nullable BlockPos findNearbyLavaSource(ServerLevel level, BlockPos center, int r) {
		for (BlockPos pos : BlockPos.withinManhattan(center, r, 8, r)) {
			if (level.getFluidState(pos).is(FluidTags.LAVA) && level.getFluidState(pos).isSource()) {
				return pos.immutable();
			}
		}
		return null;
	}

	private static @Nullable BlockPos findSafeStandInNether(ServerLevel level, BlockPos near) {
		for (int dy = -6; dy <= 6; dy++) {
			BlockPos pos = near.above(dy);
			if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
					&& level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
					&& level.getFluidState(pos.below()).isEmpty()) {
				return pos.immutable();
			}
		}
		return near;
	}

	@Override
	public void stop(PartnerBrain brain, ServerLevel level) {
		if (subJob != null) {
			subJob.stop(brain, level);
			subJob = null;
		}
	}
}
