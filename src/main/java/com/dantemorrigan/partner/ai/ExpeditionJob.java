package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.Territory;
import com.dantemorrigan.partner.compat.InfinityCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Long-range expedition (up to 2500 blocks):
 * <ul>
 *   <li>Scouts new territory and biomes, recording landmarks for the tactical map.</li>
 *   <li>Discovers dungeons/spawners: clears enemies, torches the spawner, and loots dungeon chests.</li>
 *   <li>Trades with villagers and wandering traders (sells surplus logs/coal/crops for emeralds, buys food/arrows/ender pearls/gear).</li>
 *   <li>Runs survival upgrades (food, wood, ore, crafting, smelting) along the journey.</li>
 *   <li>Uses {@link Travel} self-rescue to dig/pillar/bridge out of traps, and calls SOS or evacuates in emergencies.</li>
 * </ul>
 */
public final class ExpeditionJob implements Job {
	public static final int EXPEDITION_RADIUS = 2500;

	private enum Stage { PLAN, MARCH, DUNGEON, TRADE, SUBJOB }

	private Stage stage = Stage.PLAN;
	private final Travel travel = new Travel();
	private @Nullable Vec3 waypoint;
	private @Nullable BlockPos spawnerPos;
	private @Nullable BlockPos chestPos;
	private @Nullable AbstractVillager merchant;
	private @Nullable Job subJob;
	private int ticks, stageTicks, legsCompleted;
	private String lastBiome = "";

	@Override
	public String activity() {
		return switch (stage) {
			case DUNGEON -> "dungeon";
			case TRADE -> "trade";
			case SUBJOB -> subJob != null ? subJob.activity() : "expedition";
			default -> "expedition";
		};
	}

	@Override
	public int result(PartnerBrain brain) {
		if (stage == Stage.SUBJOB && subJob != null) return subJob.result(brain);
		return legsCompleted * 100 + stageTicks / 20;
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
		stage = Stage.PLAN;
		stageTicks = 0;
		return true;
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		ServerPlayer owner = p.getOwnerPlayer();
		if (owner == null) return Status.FAILED;
		ticks++;
		stageTicks++;

		// Check biome discovery every few seconds.
		if (p.tickCount % 60 == 0) {
			checkBiomeAndLandmarks(brain, level);
		}

		// Survival & gear check along the journey: if starving or missing basic gear, run a quick survival step.
		if (stage == Stage.MARCH && p.tickCount % 40 == 0) {
			AutoJob.craftUpgrades(brain);
			var crafts = AutoJob.plannedCrafts(brain);
			if (!crafts.isEmpty()) {
				startSubJob(new CraftJob(crafts, true));
				return Status.RUNNING;
			}
			if (Gear.foodPoints(p.backpack()) < 10) {
				startSubJob(new HuntJob(24));
				return Status.RUNNING;
			}
			// Look for nearby villagers/traders or dungeons while marching.
			if (scanForMerchant(brain, level) || scanForDungeon(brain, level)) {
				return Status.RUNNING;
			}
		}

		switch (stage) {
			case PLAN -> {
				stageTicks = 0;
				AutoJob.craftUpgrades(brain);
				var crafts = AutoJob.plannedCrafts(brain);
				if (!crafts.isEmpty()) {
					startSubJob(new CraftJob(crafts, true));
					return Status.RUNNING;
				}
				if (scanForDungeon(brain, level) || scanForMerchant(brain, level)) return Status.RUNNING;
				// Every few legs of the expedition, gather wood/ore if needed to grow stronger.
				if (legsCompleted % 2 == 1 && !AutoJob.nothingToDo(brain)) {
					startSubJob(new AutoJob());
					legsCompleted++;
					return Status.RUNNING;
				}
				waypoint = pickNextWaypoint(p, owner);
				double dist = Math.sqrt(PartnerBrain.horizontalDistSqr(p.position(), waypoint));
				brain.report("expedition_step", (int) waypoint.x, (int) waypoint.z, (int) dist);
				stage = Stage.MARCH;
			}
			case MARCH -> {
				if (waypoint == null) {
					stage = Stage.PLAN;
					return Status.RUNNING;
				}
				double dist = Math.sqrt(PartnerBrain.horizontalDistSqr(p.position(), waypoint));
				int distToOwner = (int) Math.sqrt(PartnerBrain.horizontalDistSqr(p.position(), owner.position()));
				brain.detail = (int) dist + "m · ⚑ " + distToOwner + "m";
				if (dist < 8 || stageTicks > 20 * 90) {
					legsCompleted++;
					stage = Stage.PLAN;
					return Status.RUNNING;
				}
				if (!travel.tick(brain, level, waypoint, PartnerEntity.SPRINT)) {
					legsCompleted++;
					stage = Stage.PLAN;
				}
			}
			case DUNGEON -> {
				if (stageTicks > 20 * 45) {
					spawnerPos = null;
					chestPos = null;
					stage = Stage.PLAN;
					return Status.RUNNING;
				}
				if (spawnerPos != null) {
					if (!level.getBlockState(spawnerPos).is(Blocks.SPAWNER)) {
						spawnerPos = null;
						return Status.RUNNING;
					}
					brain.detail = "⚑ " + spawnerPos.getX() + "," + spawnerPos.getZ();
					if (!BlockBreaker.inReach(p, spawnerPos)) {
						brain.approach(spawnerPos);
						return Status.RUNNING;
					}
					// Neutralize the spawner with torches!
					lightSpawner(p, level, spawnerPos);
					brain.badBlocks.add(spawnerPos);
					brain.say("dungeon_torched", spawnerPos.getX(), spawnerPos.getY(), spawnerPos.getZ());
					spawnerPos = null;
					return Status.RUNNING;
				}
				if (chestPos != null) {
					Container c = StoreJob.getContainer(level, chestPos);
					if (c == null) {
						chestPos = null;
						stage = Stage.PLAN;
						return Status.RUNNING;
					}
					if (!BlockBreaker.inReach(p, chestPos)) {
						brain.approach(chestPos);
						return Status.RUNNING;
					}
					lootDungeonChest(brain, level, chestPos, c);
					brain.badBlocks.add(chestPos);
					chestPos = null;
				}
				stage = Stage.PLAN;
			}
			case TRADE -> {
				if (merchant == null || !merchant.isAlive() || stageTicks > 20 * 30) {
					merchant = null;
					stage = Stage.PLAN;
					return Status.RUNNING;
				}
				if (p.distanceTo(merchant) > 3.0) {
					if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(merchant, PartnerEntity.WALK);
					brain.jumpIfBlocked(merchant.position());
					return Status.RUNNING;
				}
				p.getNavigation().stop();
				p.getLookControl().setLookAt(merchant, 30f, 30f);
				if (stageTicks % 20 == 0) {
					performTrades(brain, level, merchant);
					brain.ignoredEntity(merchant.getId(), 20 * 180);
					merchant = null;
					stage = Stage.PLAN;
				}
			}
			case SUBJOB -> {
				if (subJob == null) {
					stage = Stage.PLAN;
					return Status.RUNNING;
				}
				Status s = subJob.tick(brain, level);
				if (s != Status.RUNNING || stageTicks > 20 * 120) {
					subJob.stop(brain, level);
					subJob = null;
					stage = Stage.PLAN;
				}
			}
		}
		return Status.RUNNING;
	}

	private void startSubJob(Job job) {
		this.subJob = job;
		this.stage = Stage.SUBJOB;
		this.stageTicks = 0;
	}

	private static Vec3 pickNextWaypoint(PartnerEntity p, ServerPlayer owner) {
		// Bias outward in a steady direction with slight turns so it explores a real trail across the map.
		double baseAngle = Math.atan2(p.getZ() - owner.getZ(), p.getX() - owner.getX());
		if ( PartnerBrain.horizontalDistSqr(p.position(), owner.position()) < 24 * 24) {
			baseAngle = p.getRandom().nextDouble() * Math.PI * 2;
		}
		for (int i = 0; i < 10; i++) {
			double angle = baseAngle + (p.getRandom().nextDouble() - 0.5) * 1.4;
			double step = 80 + p.getRandom().nextDouble() * 90;
			Vec3 candidate = p.position().add(Math.cos(angle) * step, 0, Math.sin(angle) * step);
			if (PartnerBrain.horizontalDistSqr(candidate, owner.position()) < (double) (EXPEDITION_RADIUS - 60) * (EXPEDITION_RADIUS - 60)) {
				return candidate;
			}
			baseAngle += Math.PI * 0.5;
		}
		return owner.position();
	}

	private void checkBiomeAndLandmarks(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		var holder = level.getBiome(p.blockPosition());
		String biomeKey = holder.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		if (!biomeKey.isEmpty() && !biomeKey.equals(lastBiome)) {
			lastBiome = biomeKey;
			brain.recordPoi("biome", p.blockPosition(), biomeKey);
		}
	}

	private boolean scanForDungeon(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		BlockPos center = p.blockPosition();
		BlockPos foundSpawner = null;
		BlockPos foundChest = null;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -16; dx <= 16; dx++) {
			for (int dz = -16; dz <= 16; dz++) {
				for (int dy = -10; dy <= 8; dy++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.hasChunkAt(m) || brain.badBlocks.contains(m)) continue;
					var s = level.getBlockState(m);
					if (s.is(Blocks.SPAWNER) && !hasTorchAround(level, m)) {
						foundSpawner = m.immutable();
						break;
					}
					if ((s.is(Blocks.CHEST) || s.is(Blocks.BARREL)) && !Territory.isProtected(level, m)) {
						foundChest = m.immutable();
					}
				}
			}
		}
		if (foundSpawner != null || foundChest != null) {
			spawnerPos = foundSpawner;
			chestPos = foundChest;
			BlockPos poi = foundSpawner != null ? foundSpawner : foundChest;
			brain.recordPoi("dungeon", poi, "Dungeon");
			stage = Stage.DUNGEON;
			stageTicks = 0;
			return true;
		}
		return false;
	}

	private static boolean hasTorchAround(ServerLevel level, BlockPos spawner) {
		for (Direction d : Direction.values()) {
			BlockPos n = spawner.relative(d);
			if (level.getBlockState(n).is(Blocks.TORCH) || level.getBlockState(n).is(Blocks.WALL_TORCH)) return true;
		}
		return false;
	}

	private static void lightSpawner(PartnerEntity p, ServerLevel level, BlockPos spawner) {
		if (Gear.count(p.backpack(), Items.TORCH) == 0) Crafting.craft(p, Items.TORCH);
		BlockPos top = spawner.above();
		if (level.getBlockState(top).isAir() && Blocks.TORCH.defaultBlockState().canSurvive(level, top)) {
			Gear.take(p.backpack(), s -> s.is(Items.TORCH), 1);
			level.setBlockAndUpdate(top, Blocks.TORCH.defaultBlockState());
			p.swing(InteractionHand.MAIN_HAND);
			level.playSound(null, top, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
		}
	}

	private static void lootDungeonChest(PartnerBrain brain, ServerLevel level, BlockPos pos, Container chest) {
		PartnerEntity p = brain.p;
		Map<String, int[]> looted = new LinkedHashMap<>();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack s = chest.getItem(i);
			if (s.isEmpty() || InfinityCompat.isProtectedItem(s)) continue;
			ItemStack copy = s.copy();
			ItemStack rest = p.backpack().addItem(copy);
			int taken = s.getCount() - rest.getCount();
			if (taken > 0) {
				looted.computeIfAbsent(s.getItem().getDescriptionId(), k -> new int[1])[0] += taken;
				s.shrink(taken);
				if (s.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
			}
		}
		chest.setChanged();
		p.swing(InteractionHand.MAIN_HAND);
		level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6f, 1.0f);
		if (!looted.isEmpty()) {
			brain.onPickedUp();
			p.speak("dungeon_looted", pos.getX(), pos.getY(), pos.getZ(), PartnerEntity.summary(looted));
		}
	}

	private boolean scanForMerchant(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		for (AbstractVillager v : level.getEntitiesOfClass(AbstractVillager.class, p.getBoundingBox().inflate(18, 6, 18),
				v -> v.isAlive() && !v.isBaby() && !brain.isIgnoredEntity(v.getId()))) {
			merchant = v;
			brain.recordPoi("village", v.blockPosition(), "Trader");
			stage = Stage.TRADE;
			stageTicks = 0;
			return true;
		}
		return false;
	}

	private static void performTrades(PartnerBrain brain, ServerLevel level, AbstractVillager v) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();
		Map<String, int[]> gained = new LinkedHashMap<>();

		// 1. Try real MerchantOffers that match what the partner carries.
		for (MerchantOffer offer : v.getOffers()) {
			if (offer.isOutOfStock()) continue;
			ItemStack costA = offer.getCostA();
			ItemStack costB = offer.getCostB();
			ItemStack result = offer.getResult();
			if (InfinityCompat.isProtectedItem(result) || !bag.canAddItem(result)) continue;
			// Only spend surplus resources or emeralds, never the partner's own tools/armor.
			if (!canSpend(bag, costA) || (!costB.isEmpty() && !canSpend(bag, costB))) continue;
			Gear.take(bag, s -> ItemStack.isSameItemSameComponents(s, costA), costA.getCount());
			if (!costB.isEmpty()) Gear.take(bag, s -> ItemStack.isSameItemSameComponents(s, costB), costB.getCount());
			ItemStack out = offer.assemble();
			bag.addItem(out.copy());
			offer.increaseUses();
			v.notifyTrade(offer);
			gained.computeIfAbsent(out.getItem().getDescriptionId(), k -> new int[1])[0] += out.getCount();
		}

		// 2. Fair barter when the villager's random level-1 offers don't match: sell surplus coal/logs/wheat/rotten flesh for emeralds,
		// and spend surplus emeralds on bread, arrows, or ender pearls.
		if (gained.isEmpty()) {
			if (Gear.count(bag, Items.ROTTEN_FLESH) >= 16) {
				Gear.take(bag, s -> s.is(Items.ROTTEN_FLESH), 16);
				bag.addItem(new ItemStack(Items.EMERALD, 1));
				gained.computeIfAbsent(Items.EMERALD.getDescriptionId(), k -> new int[1])[0] += 1;
			} else if (Gear.count(bag, s -> s.is(ItemTags.COALS)) >= 20) {
				Gear.take(bag, s -> s.is(ItemTags.COALS), 15);
				bag.addItem(new ItemStack(Items.EMERALD, 1));
				gained.computeIfAbsent(Items.EMERALD.getDescriptionId(), k -> new int[1])[0] += 1;
			} else if (Gear.count(bag, s -> s.is(ItemTags.LOGS)) >= 24) {
				Gear.take(bag, s -> s.is(ItemTags.LOGS), 16);
				bag.addItem(new ItemStack(Items.EMERALD, 1));
				gained.computeIfAbsent(Items.EMERALD.getDescriptionId(), k -> new int[1])[0] += 1;
			}
			if (Gear.count(bag, Items.EMERALD) >= 2 && Gear.foodPoints(bag) < 24) {
				Gear.take(bag, s -> s.is(Items.EMERALD), 1);
				bag.addItem(new ItemStack(Items.BREAD, 6));
				gained.computeIfAbsent(Items.BREAD.getDescriptionId(), k -> new int[1])[0] += 6;
			} else if (Gear.count(bag, Items.EMERALD) >= 2 && Gear.arrows(p) < 24) {
				Gear.take(bag, s -> s.is(Items.EMERALD), 1);
				bag.addItem(new ItemStack(Items.ARROW, 16));
				gained.computeIfAbsent(Items.ARROW.getDescriptionId(), k -> new int[1])[0] += 16;
			} else if (Gear.count(bag, Items.EMERALD) >= 5 && Gear.count(bag, Items.ENDER_PEARL) < 4) {
				Gear.take(bag, s -> s.is(Items.EMERALD), 4);
				bag.addItem(new ItemStack(Items.ENDER_PEARL, 1));
				gained.computeIfAbsent(Items.ENDER_PEARL.getDescriptionId(), k -> new int[1])[0] += 1;
			}
		}

		if (!gained.isEmpty()) {
			p.swing(InteractionHand.MAIN_HAND);
			level.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.8f, 1.0f);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 1.5, v.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
			brain.onPickedUp();
			p.speak("traded", v.getDisplayName(), PartnerEntity.summary(gained));
		}
	}

	private static boolean canSpend(net.minecraft.world.SimpleContainer bag, ItemStack cost) {
		if (Gear.isTool(cost) || Gear.isArmor(cost) || cost.is(Items.DIAMOND)) return false;
		return Gear.count(bag, s -> ItemStack.isSameItemSameComponents(s, cost)) >= cost.getCount();
	}

	@Override
	public void stop(PartnerBrain brain, ServerLevel level) {
		if (subJob != null) {
			subJob.stop(brain, level);
			subJob = null;
		}
	}
}
