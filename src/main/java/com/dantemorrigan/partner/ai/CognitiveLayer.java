package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerChat;
import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerOrder;
import com.dantemorrigan.partner.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * First-person Cognitive Reasoning Layer ("Слой обдумывания"):
 * Continuously analyzes where the partner and owner are (biome, dimension, underground mine, near a village),
 * their survival state (owner dying, hunger, missing armor), their equipment & progression stage
 * (diamond swords + iron armor → Dragon readiness vs. needing Golden Apples first), and turns these
 * situational thoughts into concrete autonomous actions.
 */
public final class CognitiveLayer {
	private final PartnerBrain brain;
	private final Map<String, Integer> thoughtAt = new HashMap<>();
	private int lastAnyThought = -9999;

	public CognitiveLayer(PartnerBrain brain) {
		this.brain = brain;
	}

	/**
	 * Priority 0: Called every tick in {@link PartnerBrain#tick} to detect if the owner is dying
	 * and immediately prioritize saving them (intercepting attackers + delivering Golden Apples / food).
	 */
	public void checkOwnerEmergency(ServerLevel level, ServerPlayer owner) {
		if (owner.isCreative() || owner.isSpectator() || !owner.isAlive()) return;
		if (owner.getHealth() <= 7.0f && canThink("owner_dying", 20 * 25, true)) {
			think("think_owner_dying", (int) Math.ceil(owner.getHealth()));
			// Immediately engage whoever hurt the owner
			LivingEntity attacker = owner.getLastHurtByMob();
			if (attacker != null && attacker.isAlive() && attacker != brain.p) {
				brain.engageQuestTarget(attacker);
			}
			// Reset care cooldowns so PartnerBrain.care() immediately feeds/heals the owner
			brain.prioritizeOwnerCareNow();
		}
	}

	/**
	 * Priority 1: Called when the owner issues a command ({@link PartnerBrain#onOrder}).
	 * Evaluates the command, current environment, inventory, and progression stage to formulate an execution plan.
	 */
	public void onOrderGiven(PartnerOrder order, ServerPlayer owner) {
		if (!(brain.p.level() instanceof ServerLevel level)) return;
		PartnerEntity p = brain.p;
		Component orderName = Component.translatable("partner.local_partner.order." + order.id);
		Component env = describeEnvironment(level, owner);
		Component eraName = Component.translatable("partner.local_partner.era." + brain.era());
		int armor = totalArmor(p);
		int food = Gear.foodPoints(p.backpack());
		p.speakComponent(PartnerChat.Tone.ASK,
				Component.translatable("partner.local_partner.say.think_order", orderName, env, eraName, armor, food));
		lastAnyThought = p.tickCount;
	}

	/**
	 * Periodic situational reasoning (called every 2 seconds).
	 * Evaluates the environment, inventory, and shared goals, voices the partner's reasoning, and returns a {@link Job}
	 * when the partner decides to act on its analysis right away.
	 */
	public @Nullable Job evaluate(ServerLevel level, ServerPlayer owner, boolean busyWithExplicitJob) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();

		// 1. We are in The End: priority is defeating the Ender Dragon!
		if (level.dimension() == Level.END) {
			if (PartnerSquad.canAutonomouslyFormSquad(owner, level) && canThink("squad_end", 20 * 180, false)) {
				PartnerSquad.assembleSquad(owner, p, "end", true);
			} else if (canThink("in_end", 20 * 90, false)) {
				think("think_in_end");
			}
			if (!busyWithExplicitJob && !brain.inFight()) {
				return new BeatGameJob(false);
			}
			return null;
		}

		// 2. We are in The Nether: gather everything needed at this stage (and summon the Expedition Squad to storm the Nether!)
		if (level.dimension() == Level.NETHER) {
			if (PartnerSquad.canAutonomouslyFormSquad(owner, level) && canThink("squad_nether", 20 * 180, false)) {
				PartnerSquad.assembleSquad(owner, p, "nether", true);
			} else if (canThink("in_nether", 20 * 75, false)) {
				int rods = Gear.count(bag, Items.BLAZE_ROD) + Gear.count(bag, Items.BLAZE_POWDER) / 2;
				int pearls = Gear.count(bag, Items.ENDER_PEARL) + Gear.count(bag, Items.ENDER_EYE);
				String focus = (rods < 6 || pearls < 12) ? "стержни ифрита, жемчуг Эндера и золото" : "золото и древние обломки";
				think("think_in_nether", focus);
			}
			// Craft gold armor piece against piglins if we have gold and none equipped
			if (!wearingGold(p) && Gear.count(bag, Items.GOLD_INGOT) >= 4) {
				if (Crafting.craft(p, Items.GOLDEN_BOOTS) || Crafting.craft(p, Items.GOLDEN_HELMET)) {
					Gear.equipArmor(p);
				}
			}
			if (!busyWithExplicitJob && !brain.inFight()
					&& WorldScan.findExposed(level, p.blockPosition(), 14, 8, s -> WorldScan.isOre(s, WorldScan.OreKind.ANY), brain.badBlocks) != null) {
				return new MineJob(WorldScan.OreKind.ANY, 6);
			}
			return null;
		}

		// 3. No armor in the field + cows nearby (or leather in bag) -> hunt cows and craft leather tunics for us!
		boolean partnerNoChest = p.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
		boolean ownerNoChest = !owner.isCreative() && owner.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
		if ((partnerNoChest || ownerNoChest) && level.canSeeSky(p.blockPosition())) {
			if (Gear.count(bag, Items.LEATHER) >= 8) {
				HuntJob.craftLeatherArmorForBoth(brain);
			} else if (!busyWithExplicitJob && HuntJob.hasCowsNearby(level, p) && canThink("no_armor_cows", 20 * 90, false)) {
				think("think_no_armor_cows");
				return new HuntJob(16, true);
			}
		}

		// 4. Low food in the desert or current biome -> go get food!
		int partnerFood = Gear.foodPoints(bag);
		int ownerFoodPts = countOwnerFoodPoints(owner);
		boolean lowFood = (partnerFood < 10 && p.foodLevel() <= 15)
				|| (!owner.isCreative() && owner.getFoodData().getFoodLevel() <= 13 && ownerFoodPts < 8 && partnerFood < 12);
		if (lowFood && !busyWithExplicitJob) {
			if (Gear.count(bag, Items.WHEAT) >= 3 && Crafting.craft(p, Items.BREAD)) {
				return null;
			}
			String biomePath = level.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
			boolean isDesert = biomePath.contains("desert") || biomePath.contains("badlands");
			if ((HuntJob.hasPreyNearby(level, p) || isDesert) && canThink("low_food_biome", 20 * 75, false)) {
				if (isDesert) {
					think("think_desert_food");
				} else {
					think("think_low_food_biome", describeEnvironment(level, owner));
				}
				if (HuntJob.hasPreyNearby(level, p)) {
					return new HuntJob(20);
				}
				return new FarmJob();
			}
		}

		// 5. Underground in a mine/cave -> gather valuable visible ores and protect from monsters!
		boolean inMine = !level.canSeeSky(owner.blockPosition()) && owner.getY() < 54;
		if (inMine) {
			BlockPos exposedOre = WorldScan.findExposed(level, p.blockPosition(), 16, 8, s -> WorldScan.isOre(s, WorldScan.OreKind.ANY), brain.badBlocks);
			if (canThink("in_mine", 20 * 80, false)) {
				think("think_in_mine");
			}
			if (!busyWithExplicitJob && exposedOre != null && brain.pickTier() >= 1) {
				return new MineJob(WorldScan.OreKind.ANY, 8);
			}
		}

		// 6. Near a village -> visit, trade with villagers, and check for valuable loot!
		AbstractVillager villager = findNearbyVillager(level, p);
		if (villager != null && canThink("near_village", 20 * 90, false)) {
			think("think_near_village");
			if (p.distanceTo(villager) <= 6.0) {
				ExpeditionJob.performTrades(brain, level, villager);
			} else if (!busyWithExplicitJob) {
				p.getNavigation().moveTo(villager, PartnerEntity.WALK);
			}
		}

		// 7. Diamond swords + Iron/Diamond armor check -> Ready for The End? Wait, first get Golden Apples!
		if (hasDiamondSword(p, owner) && totalArmor(p) >= 12) {
			int gapples = countGoldenApples(p, owner);
			if (gapples == 0) {
				// If we already have gold + apple in backpack, craft a Golden Apple right now!
				if (Gear.count(bag, Items.GOLD_INGOT) >= 8 && Gear.count(bag, Items.APPLE) >= 1) {
					Crafting.craft(p, Items.GOLDEN_APPLE);
				}
				if (canThink("dragon_wait_gapples", 20 * 110, false)) {
					think("think_dragon_wait_gapples");
					if (!busyWithExplicitJob) {
						// Harvest nearby oak leaves for apples or mine gold so we can make Golden Apples!
						if (Gear.count(bag, Items.APPLE) == 0) {
							tryHarvestAppleFromLeaves(level, p);
						}
						if (Gear.count(bag, Items.GOLD_INGOT) < 8) {
							return new MineJob(WorldScan.OreKind.GOLD, 8);
						}
					}
				}
			} else if (canThink("dragon_ready", 20 * 180, false)) {
				PartnerChat.ask(owner, p,
						Component.translatable("partner.local_partner.say.think_dragon_ready", gapples),
						List.of(
								PartnerChat.orderButton("speedrun", PartnerChat.GUARD),
								PartnerChat.laterButton()
						));
				lastAnyThought = p.tickCount;
			}
		}

		// 8. Autonomous "ЭКСПЕДИЦИЯ" (Squad) Initiative: when ready to mine Diamond Armor for the whole team or on a major expedition!
		if (PartnerSquad.canAutonomouslyFormSquad(owner, level)
				&& (p.order() == PartnerOrder.EXPEDITION || p.order() == PartnerOrder.SPEEDRUN
				|| (brain.era() >= 2 && !PartnerSquad.isPlayerInFullDiamond(owner) && p.tickCount > 20 * 90))) {
			if (canThink("auto_squad", 20 * 240, false)) {
				String mission = p.order() == PartnerOrder.SPEEDRUN ? "nether" : "diamond";
				PartnerSquad.assembleSquad(owner, p, mission, true);
			}
		}

		return null;
	}

	private boolean canThink(String key, int cooldownTicks, boolean urgent) {
		int now = brain.p.tickCount;
		if (!urgent && now - lastAnyThought < 20 * 18) return false;
		Integer prev = thoughtAt.get(key);
		if (prev != null && now - prev < cooldownTicks) return false;
		thoughtAt.put(key, now);
		lastAnyThought = now;
		return true;
	}

	private void think(String key, Object... args) {
		brain.p.speakComponent(PartnerChat.Tone.ASK,
				Component.translatable("partner.local_partner.say." + key, args));
	}

	public static Component describeEnvironment(ServerLevel level, ServerPlayer owner) {
		if (level.dimension() == Level.NETHER) {
			return Component.translatable("partner.local_partner.env.nether");
		}
		if (level.dimension() == Level.END) {
			return Component.translatable("partner.local_partner.env.end");
		}
		if (!level.canSeeSky(owner.blockPosition()) && owner.getY() < 54) {
			return Component.translatable("partner.local_partner.env.mine");
		}
		String biome = level.getBiome(owner.blockPosition()).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		if (biome.contains("desert") || biome.contains("badlands")) {
			return Component.translatable("partner.local_partner.env.desert");
		}
		if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle") || biome.contains("grove")) {
			return Component.translatable("partner.local_partner.env.forest");
		}
		if (biome.contains("peaks") || biome.contains("mountain") || biome.contains("hills") || biome.contains("slopes")) {
			return Component.translatable("partner.local_partner.env.mountains");
		}
		if (biome.contains("ocean") || biome.contains("river") || biome.contains("beach") || biome.contains("swamp")) {
			return Component.translatable("partner.local_partner.env.water");
		}
		return Component.translatable("partner.local_partner.env.plains");
	}

	private static boolean wearingGold(PartnerEntity p) {
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (Gear.id(p.getItemBySlot(slot)).getPath().startsWith("golden_")) return true;
		}
		return false;
	}

	private static int totalArmor(PartnerEntity p) {
		int a = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			a += Gear.armorValue(p.getItemBySlot(slot));
		}
		return a;
	}

	private static int countOwnerFoodPoints(ServerPlayer owner) {
		int pts = 0;
		for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
			ItemStack s = owner.getInventory().getItem(i);
			if (Gear.isFood(s)) pts += Gear.nutrition(s) * s.getCount();
		}
		return pts;
	}

	private static boolean hasDiamondSword(PartnerEntity p, ServerPlayer owner) {
		if (p.getMainHandItem().is(Items.DIAMOND_SWORD) || p.getMainHandItem().is(Items.NETHERITE_SWORD)) return true;
		if (Gear.count(p.backpack(), s -> s.is(Items.DIAMOND_SWORD) || s.is(Items.NETHERITE_SWORD)) > 0) return true;
		for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
			ItemStack s = owner.getInventory().getItem(i);
			if (s.is(Items.DIAMOND_SWORD) || s.is(Items.NETHERITE_SWORD)) return true;
		}
		return false;
	}

	private static int countGoldenApples(PartnerEntity p, ServerPlayer owner) {
		int count = Gear.count(p.backpack(), s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE));
		for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
			ItemStack s = owner.getInventory().getItem(i);
			if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) count += s.getCount();
		}
		return count;
	}

	private static @Nullable AbstractVillager findNearbyVillager(ServerLevel level, PartnerEntity p) {
		for (AbstractVillager v : level.getEntitiesOfClass(AbstractVillager.class, p.getBoundingBox().inflate(24, 6, 24),
				v -> v.isAlive() && !v.isBaby())) {
			return v;
		}
		return null;
	}

	private static void tryHarvestAppleFromLeaves(ServerLevel level, PartnerEntity p) {
		BlockPos center = p.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -1, -5), center.offset(5, 5, 5))) {
			var s = level.getBlockState(pos);
			if (s.is(BlockTags.LEAVES) && !Territory.isProtected(level, pos)) {
				level.destroyBlock(pos, true, p);
				p.backpack().addItem(new ItemStack(Items.APPLE, 1));
				return;
			}
		}
	}
}
