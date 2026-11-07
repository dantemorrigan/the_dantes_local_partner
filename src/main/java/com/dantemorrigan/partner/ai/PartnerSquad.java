package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.LocalPartner;
import com.dantemorrigan.partner.PartnerChat;
import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerFx;
import com.dantemorrigan.partner.PartnerManager;
import com.dantemorrigan.partner.PartnerOrder;
import com.dantemorrigan.partner.compat.InfinityCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the Partner's "ЭКСПЕДИЦИЯ" (Expedition Squad) initiative:
 * <ul>
 *   <li>The main partner can autonomously summon a 3-bot buddy team (<b>Авангард</b>, <b>Рудокоп</b>, <b>Следопыт</b>)
 *       at any convenient moment to tackle major objectives together.</li>
 *   <li>Supported squad missions:
 *       <ul>
 *         <li>{@code "diamond"} — Mine diamonds &amp; forge full Diamond Armor sets for the player and the entire team.</li>
 *         <li>{@code "nether"} — Storm the Nether together (build/enter portal, fight Blazes &amp; Wither Skeletons, gather Rods, Pearls, Gold &amp; Ancient Debris).</li>
 *         <li>{@code "end"} — Raid The End together, destroy End Crystals, and slay the Ender Dragon as a party.</li>
 *         <li>{@code "harvest"} — Large-scale coordinated resource gathering (mining, lumberjacking, hunting &amp; trading).</li>
 *       </ul>
 *   </li>
 *   <li>The squad can stay in the game permanently ({@code /partner squad keep}) or be disbanded by the player
 *       at any time ({@code /partner squad disband}), handing all gathered loot to the player before departing.</li>
 * </ul>
 */
public final class PartnerSquad {
	private static final String[] ROLES = {"vanguard", "miner", "scout"};
	private static final Map<UUID, List<PartnerEntity>> SQUADS = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> PERMANENT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> DISBAND_COOLDOWN = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> ANNOUNCED_DIAMOND_DONE = new ConcurrentHashMap<>();

	public static boolean isActiveMember(PartnerEntity entity) {
		if (!entity.isSquadMember() || entity.getOwnerUUID() == null) return false;
		List<PartnerEntity> list = SQUADS.computeIfAbsent(entity.getOwnerUUID(), k -> new CopyOnWriteArrayList<>());
		for (PartnerEntity e : list) {
			if (e.getUUID().equals(entity.getUUID())) return true;
		}
		if (list.size() < 3) {
			list.add(entity);
			return true;
		}
		return false;
	}

	public static List<PartnerEntity> getSquad(UUID ownerId) {
		List<PartnerEntity> list = SQUADS.get(ownerId);
		if (list == null) return List.of();
		list.removeIf(e -> e == null || e.isRemoved() || !e.isAlive());
		return list;
	}

	public static boolean hasActiveSquad(ServerPlayer owner) {
		return !getSquad(owner.getUUID()).isEmpty();
	}

	public static boolean canAutonomouslyFormSquad(ServerPlayer owner, ServerLevel level) {
		if (hasActiveSquad(owner)) return false;
		Long until = DISBAND_COOLDOWN.get(owner.getUUID());
		return until == null || level.getGameTime() >= until;
	}

	/**
	 * Summons the 3-member Expedition Squad around the leader/owner, or updates their mission if already active.
	 */
	public static void assembleSquad(ServerPlayer owner, PartnerEntity leader, String missionId, boolean autonomous) {
		ServerLevel level = (ServerLevel) leader.level();
		List<PartnerEntity> existing = getSquad(owner.getUUID());
		Component missionTitle = Component.translatable("partner.local_partner.squad.mission." + missionId);

		if (!existing.isEmpty()) {
			for (PartnerEntity m : existing) {
				m.setSquadMission(missionId);
				m.brain().reset();
			}
			leader.speakComponent(PartnerChat.Tone.GOOD,
					Component.translatable("partner.local_partner.say.squad_mission_changed", missionTitle));
			return;
		}

		List<PartnerEntity> squad = SQUADS.computeIfAbsent(owner.getUUID(), k -> new CopyOnWriteArrayList<>());
		ANNOUNCED_DIAMOND_DONE.remove(owner.getUUID());
		int baseSkin = leader.skin();

		for (int i = 0; i < ROLES.length; i++) {
			String role = ROLES[i];
			PartnerEntity buddy = new PartnerEntity(LocalPartner.PARTNER, level);
			buddy.setupSquadMember(owner, baseSkin + i + 1, role, missionId);
			equipStarterLoadout(buddy, role, level);

			Vec3 spot = PartnerEntity.safeSpotNear(level, leader.blockPosition(), buddy);
			buddy.snapTo(spot.x, spot.y, spot.z, leader.getYRot(), 0f);
			if (level.addFreshEntity(buddy)) {
				buddy.arrivalFx();
				squad.add(buddy);
			}
		}

		if (autonomous) {
			leader.speakComponent(PartnerChat.Tone.ASK,
					Component.translatable("partner.local_partner.say.think_squad_formed", missionTitle));
		}

		PartnerChat.ask(owner, leader,
				Component.translatable("partner.local_partner.say.squad_assembled", squad.size(), missionTitle),
				List.of(
						PartnerChat.button(
								Component.translatable("partner.local_partner.btn.squad_keep"),
								"/partner squad keep",
								Component.translatable("partner.local_partner.btn.squad_keep.hover"),
								PartnerChat.FOLLOW),
						PartnerChat.button(
								Component.translatable("partner.local_partner.btn.squad_diamond"),
								"/partner squad diamond",
								Component.translatable("partner.local_partner.btn.squad_diamond.hover"),
								PartnerChat.ORE),
						PartnerChat.button(
								Component.translatable("partner.local_partner.btn.squad_nether"),
								"/partner squad nether",
								Component.translatable("partner.local_partner.btn.squad_nether.hover"),
								PartnerChat.WOOD),
						PartnerChat.button(
								Component.translatable("partner.local_partner.btn.squad_disband"),
								"/partner squad disband",
								Component.translatable("partner.local_partner.btn.squad_disband.hover"),
								PartnerChat.GUARD)
				));
	}

	private static void equipStarterLoadout(PartnerEntity buddy, String role, ServerLevel level) {
		var bag = buddy.backpack();
		bag.addItem(new ItemStack(Items.BREAD, 12));
		bag.addItem(new ItemStack(Items.TORCH, 24));
		bag.addItem(new ItemStack(Items.COBBLESTONE, 32));
		bag.addItem(new ItemStack(Items.OAK_PLANKS, 16));
		bag.addItem(new ItemStack(Items.CRAFTING_TABLE, 1));

		switch (role) {
			case "vanguard" -> {
				buddy.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
				buddy.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
				buddy.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
				bag.addItem(new ItemStack(Items.BOW, 1));
				bag.addItem(new ItemStack(Items.ARROW, 32));
				bag.addItem(new ItemStack(Items.STONE_PICKAXE, 1));
			}
			case "miner" -> {
				buddy.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
				buddy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
				bag.addItem(new ItemStack(Items.IRON_SWORD, 1));
				bag.addItem(new ItemStack(Items.FURNACE, 1));
				bag.addItem(new ItemStack(Items.COAL, 16));
				bag.addItem(new ItemStack(Items.WATER_BUCKET, 1));
			}
			case "scout" -> {
				buddy.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
				buddy.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
				bag.addItem(new ItemStack(Items.IRON_SWORD, 1));
				bag.addItem(new ItemStack(Items.IRON_PICKAXE, 1));
				bag.addItem(new ItemStack(Items.BOW, 1));
				bag.addItem(new ItemStack(Items.ARROW, 24));
			}
		}
		if (level.dimension() == Level.NETHER) {
			buddy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
		}
		Gear.equipArmor(buddy);
	}

	/** Marks the squad to stay in the world permanently. */
	public static void keepForever(ServerPlayer owner) {
		PERMANENT.put(owner.getUUID(), true);
		PartnerEntity leader = PartnerManager.find(owner);
		if (leader != null) {
			leader.speakComponent(PartnerChat.Tone.GOOD,
					Component.translatable("partner.local_partner.say.squad_kept"));
		}
	}

	/** Disbands the squad: every member transfers their gathered resources & gear to the owner and departs. */
	public static void disband(ServerPlayer owner) {
		List<PartnerEntity> squad = getSquad(owner.getUUID());
		PartnerEntity leader = PartnerManager.find(owner);
		if (squad.isEmpty()) {
			if (leader != null) {
				leader.speakComponent(PartnerChat.Tone.INFO,
						Component.translatable("partner.local_partner.say.squad_none"));
			}
			return;
		}
		for (PartnerEntity m : new ArrayList<>(squad)) {
			if (m.level() instanceof ServerLevel sl) {
				m.brain().suspend();
			}
			m.handEverythingTo(owner, false);
			m.leaveFx();
			m.discard();
		}
		squad.clear();
		SQUADS.remove(owner.getUUID());
		PERMANENT.remove(owner.getUUID());
		DISBAND_COOLDOWN.put(owner.getUUID(), owner.level().getGameTime() + 20L * 60 * 5);
		if (leader != null) {
			leader.speakComponent(PartnerChat.Tone.GOOD,
					Component.translatable("partner.local_partner.say.squad_disbanded"));
		}
	}

	/**
	 * Called every tick from {@link PartnerBrain#tick} for squad members.
	 * Coordinates dimension travel, team combat focus, diamond armor forging & gifting, and mission jobs.
	 */
	public static void tickMember(PartnerBrain brain, ServerLevel level, ServerPlayer owner) {
		PartnerEntity p = brain.p;
		isActiveMember(p);

		PartnerEntity leader = PartnerManager.find(owner);
		ServerLevel targetLevel = (leader != null && leader.isAlive()) ? (ServerLevel) leader.level() : owner.level();

		// 1. Follow leader/owner across dimensions automatically
		if (p.level() != targetLevel && !InfinityCompat.partnerWaitsOutside(targetLevel.dimension())) {
			Vec3 anchorPos = (leader != null && leader.level() == targetLevel) ? leader.position() : owner.position();
			Vec3 safe = PartnerEntity.safeSpotNear(targetLevel, net.minecraft.core.BlockPos.containing(anchorPos), p);
			Entity moved = p.teleport(new TeleportTransition(targetLevel, safe, Vec3.ZERO, p.getYRot(), 0f, TeleportTransition.DO_NOTHING));
			if (moved instanceof PartnerEntity mp) {
				List<PartnerEntity> list = SQUADS.computeIfAbsent(owner.getUUID(), k -> new CopyOnWriteArrayList<>());
				list.remove(p);
				list.add(mp);
				PartnerFx.recall(mp);
			}
			return;
		}

		// 2. Share combat targets with the leader and owner
		if (!brain.inFight() && p.tickCount % 10 == 0) {
			LivingEntity sharedTarget = null;
			if (owner.getLastHurtByMob() != null && owner.getLastHurtByMob().isAlive() && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100) {
				sharedTarget = owner.getLastHurtByMob();
			} else if (owner.getLastHurtMob() != null && owner.getLastHurtMob().isAlive() && owner.tickCount - owner.getLastHurtMobTimestamp() < 100) {
				sharedTarget = owner.getLastHurtMob();
			}
			if (sharedTarget != null && !(sharedTarget instanceof PartnerEntity) && !(sharedTarget instanceof ServerPlayer)
					&& sharedTarget.distanceTo(p) <= 28) {
				brain.engageQuestTarget(sharedTarget);
			}
		}

		// 3. Periodically forge & distribute Diamond Armor (or Nether loot) to the player and leader!
		if (p.tickCount % 40 == 0) {
			if (level.dimension() == Level.NETHER && ! hasGoldArmor(p)) {
				p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
			}
			forgeAndShareTeamGear(brain, owner, leader);
		}
	}

	/** Chooses the next autonomous job for a squad member based on its mission and role. */
	public static Job createMissionJob(PartnerBrain brain, ServerLevel level, ServerPlayer owner) {
		PartnerEntity p = brain.p;
		String mission = p.squadMission().isEmpty() ? "diamond" : p.squadMission();
		String role = p.squadRole();

		if (level.dimension() == Level.END || mission.equals("end")) {
			return new BeatGameJob(false);
		}
		if (level.dimension() == Level.NETHER || mission.equals("nether")) {
			if (level.dimension() == Level.OVERWORLD && role.equals("miner")) {
				return new BeatGameJob(true);
			}
			if (role.equals("miner")) {
				return new MineJob(WorldScan.OreKind.ANY, 10);
			}
			return new BeatGameJob(false);
		}
		if (mission.equals("diamond")) {
			if (role.equals("scout") && brain.logs() < 8) {
				return new ChopJob(12);
			}
			if (brain.pickTier() < 3) {
				return new AutoJob();
			}
			return new MineJob(WorldScan.OreKind.DIAMOND, 12);
		}
		// "harvest" or general squad role
		return switch (role) {
			case "vanguard" -> HuntJob.hasPreyNearby(level, p) ? new HuntJob(24) : new AutoJob();
			case "miner" -> new MineJob(WorldScan.OreKind.ANY, 12);
			default -> new ChopJob(16);
		};
	}

	private static void forgeAndShareTeamGear(PartnerBrain brain, ServerPlayer owner, PartnerEntity leader) {
		PartnerEntity p = brain.p;
		var bag = p.backpack();

		// Smelt any raw iron/gold in the backpack
		AutoJob.craftUpgrades(brain);
		for (Item up : AutoJob.plannedCrafts(brain)) {
			Crafting.craft(p, up);
		}
		Gear.equipArmor(p);

		// If we have diamonds (or during a diamond armor mission, our miners hit a rich vein), forge Diamond Armor for the owner & team!
		Item[] diamondSet = {Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS, Items.DIAMOND_SWORD};
		EquipmentSlot[] slots = {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.HEAD, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

		for (int idx = 0; idx < diamondSet.length; idx++) {
			Item item = diamondSet[idx];
			EquipmentSlot slot = slots[idx];
			boolean ownerNeeds = !ownerHasDiamondInSlot(owner, slot);
			boolean leaderNeeds = leader != null && !entityHasDiamondInSlot(leader, slot);
			if (!ownerNeeds && !leaderNeeds) continue;

			if (Gear.count(bag, item) == 0) {
				Crafting.craft(p, item);
			}
			int foundSlot = -1;
			for (int i = 0; i < bag.getContainerSize(); i++) {
				if (bag.getItem(i).is(item)) {
					foundSlot = i;
					break;
				}
			}
			if (foundSlot >= 0) {
				ItemStack gift = bag.removeItem(foundSlot, 1);
				if (ownerNeeds) {
					p.giveTo(owner, gift.copy());
					p.speak("squad_armor_gift", gift.getHoverName());
				} else if (leader != null) {
					leader.backpack().addItem(gift.copy());
					Gear.equipArmor(leader);
				}
				break;
			}
		}

		// Check if the player and team are now fully outfitted in Diamond Armor!
		if (p.squadMission().equals("diamond") && isPlayerInFullDiamond(owner)
				&& !Boolean.TRUE.equals(PERMANENT.get(owner.getUUID()))
				&& !Boolean.TRUE.equals(ANNOUNCED_DIAMOND_DONE.get(owner.getUUID()))) {
			ANNOUNCED_DIAMOND_DONE.put(owner.getUUID(), true);
			PartnerEntity speaker = leader != null ? leader : p;
			PartnerChat.ask(owner, speaker,
					Component.translatable("partner.local_partner.say.squad_diamond_complete"),
					List.of(
							PartnerChat.button(
									Component.translatable("partner.local_partner.btn.squad_keep"),
									"/partner squad keep",
									Component.translatable("partner.local_partner.btn.squad_keep.hover"),
									PartnerChat.FOLLOW),
							PartnerChat.button(
									Component.translatable("partner.local_partner.btn.squad_nether"),
									"/partner squad nether",
									Component.translatable("partner.local_partner.btn.squad_nether.hover"),
									PartnerChat.WOOD),
							PartnerChat.button(
									Component.translatable("partner.local_partner.btn.squad_disband"),
									"/partner squad disband",
									Component.translatable("partner.local_partner.btn.squad_disband.hover"),
									PartnerChat.GUARD)
					));
		}
	}

	private static boolean ownerHasDiamondInSlot(ServerPlayer owner, EquipmentSlot slot) {
		ItemStack eq = owner.getItemBySlot(slot);
		if (isDiamondOrBetter(eq)) return true;
		for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
			ItemStack s = owner.getInventory().getItem(i);
			if (isDiamondOrBetter(s) && slotMatches(s, slot)) return true;
		}
		return false;
	}

	private static boolean entityHasDiamondInSlot(PartnerEntity e, EquipmentSlot slot) {
		return isDiamondOrBetter(e.getItemBySlot(slot));
	}

	private static boolean isDiamondOrBetter(ItemStack s) {
		if (s.isEmpty()) return false;
		String path = Gear.id(s).getPath();
		return path.startsWith("diamond_") || path.startsWith("netherite_") || path.startsWith("uru_");
	}

	private static boolean slotMatches(ItemStack s, EquipmentSlot slot) {
		if (slot == EquipmentSlot.MAINHAND) return Gear.isSword(s);
		var eq = s.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		return eq != null && eq.slot() == slot;
	}

	public static boolean isPlayerInFullDiamond(ServerPlayer owner) {
		return ownerHasDiamondInSlot(owner, EquipmentSlot.HEAD)
				&& ownerHasDiamondInSlot(owner, EquipmentSlot.CHEST)
				&& ownerHasDiamondInSlot(owner, EquipmentSlot.LEGS)
				&& ownerHasDiamondInSlot(owner, EquipmentSlot.FEET);
	}

	private static boolean hasGoldArmor(PartnerEntity p) {
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (Gear.id(p.getItemBySlot(slot)).getPath().startsWith("golden_")) return true;
		}
		return false;
	}

	private PartnerSquad() {}
}
