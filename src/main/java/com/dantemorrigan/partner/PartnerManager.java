package com.dantemorrigan.partner;

import com.dantemorrigan.partner.compat.InfinityCompat;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One partner per player. Keeps track of where it is, and keeps it alive across everything a world can do:
 * logout/login, dimension changes (it follows, or waits outside scripted dimensions), the owner's death and its own.
 *
 * <p>The record for each owner is stored on the Overworld (persistent attachment), so it is saved with the world.
 * Keys: {@code entity} (UUID of the live partner), {@code stash} (the partner's saved data while it is not in the
 * world), {@code snapshot} (periodic backup), {@code respawn} (game time of a respawn after death).
 */
public final class PartnerManager {
	private static final AttachmentType<Map<String, CompoundTag>> RECORDS = AttachmentRegistry.<Map<String, CompoundTag>>builder()
			.persistent(Codec.unboundedMap(Codec.STRING, CompoundTag.CODEC)).buildAndRegister(LocalPartner.id("partners"));

	public static final int RESPAWN_TICKS = 20 * 30;
	private static final int SNAPSHOT_EVERY = 20 * 5;
	private static final int LOST_AFTER = 20 * 20;

	private static final Map<UUID, PartnerEntity> LIVE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<UUID, Integer> MISSING = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<UUID, Integer> NO_SPOT = new java.util.concurrent.ConcurrentHashMap<>();
	private static Map<String, CompoundTag> records = new HashMap<>();
	private static MinecraftServer server;
	private static boolean loaded;

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			records = new HashMap<>(s.overworld().getAttachedOrElse(RECORDS, Map.of()));
			LIVE.clear();
			MISSING.clear();
			loaded = true;
			records.forEach((owner, rec) -> LocalPartner.LOG.info("Partner record {}: {}", owner, describe(rec)));
		});
		// The world is about to be written: bring the saved backup up to date first, so even a world that is
		// not closed cleanly later (crash, IDE stop button) has the partner's current backpack.
		ServerLifecycleEvents.BEFORE_SAVE.register((s, flush, force) -> snapshotAll(s));
		ServerLifecycleEvents.SERVER_STOPPING.register(PartnerManager::closing);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> { loaded = false; server = null; LIVE.clear(); records = new HashMap<>(); });
		ServerTickEvents.END_SERVER_TICK.register(PartnerManager::tick);
		// Leaving the game: the partner STAYS in the world and is saved with its chunk like any other mob (the most
		// reliable storage there is); the record only gets a fresh backup and its position.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> leave(handler.getPlayer()));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> ownerChangedLevel(player, to));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer owner) || owner.level().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY)) return;
			PartnerEntity p = find(owner);
			if (p != null && p.level() == owner.level() && p.isAlive()) p.brain().onOwnerDied(owner.blockPosition());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> {
			PartnerEntity p = find(player);
			if (p != null && !p.isRemoved()) p.onOwnerRespawned(player);
		});
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(player instanceof ServerPlayer owner) || !(world instanceof ServerLevel level)) return;
			PartnerEntity p = find(owner);
			if (p != null && p.isAlive() && p.level() == level && p.distanceTo(owner) <= 18) {
				p.brain().onOwnerMinedBlock(level, owner, pos, state);
			}
		});
	}

	// ------------------------------------------------------------------ public API (packets)

	public static void summon(ServerPlayer owner) {
		CompoundTag rec = record(owner);
		if (rec != null) {
			say(owner, "already");
			return;
		}
		if (InfinityCompat.partnerWaitsOutside(owner.level().dimension())) {
			say(owner, "cant_here");
			return;
		}
		ServerLevel level = owner.level();
		PartnerEntity partner = new PartnerEntity(LocalPartner.PARTNER, level);
		partner.setupNew(owner);
		Vec3 spot = PartnerEntity.safeSpotNear(level, owner.blockPosition(), partner);
		partner.snapTo(spot.x, spot.y, spot.z, owner.getYRot() + 180f, 0f);
		if (!level.addFreshEntity(partner)) {
			say(owner, "failed");
			return;
		}
		CompoundTag r = new CompoundTag();
		r.putString("entity", partner.getUUID().toString());
		put(owner.getUUID(), r);
		LIVE.put(owner.getUUID(), partner);
		partner.arrivalFx();
		PartnerFx.toast(partner, "summon");
		partner.speak("hello");
		sendStatus(owner);
	}

	public static void dismiss(ServerPlayer owner) {
		CompoundTag rec = record(owner);
		if (rec == null) {
			say(owner, "none");
			return;
		}
		PartnerEntity partner = find(owner);
		if (partner != null) {
			partner.speak("bye");
			partner.handEverythingTo(owner, true);
			partner.leaveFx();
			partner.discard();
			PartnerNetwork.send(owner, new PartnerNetwork.Toast("dismiss"));
		} else if (rec.contains("stash")) {
			// Waiting outside / dead: whatever it carried still goes to the owner.
			PartnerEntity ghost = load(owner.level(), rec.getCompoundOrEmpty("stash"));
			if (ghost != null) ghost.handEverythingTo(owner, true);
			say(owner, "dismissed");
		}
		LIVE.remove(owner.getUUID());
		remove(owner.getUUID());
		com.dantemorrigan.partner.ai.PartnerSquad.disband(owner);
		sendStatus(owner);
	}

	public static void command(ServerPlayer owner, String order) {
		PartnerEntity partner = find(owner);
		if (partner == null) {
			CompoundTag rec = record(owner);
			say(owner, rec == null ? "none" : rec.contains("respawn") ? "dead_wait" : "waiting");
			return;
		}
		switch (order) {
			case "inventory" -> {
				partner.openInventory(owner);
				return;
			}
			case "status" -> {
				partner.brain().reportStatus(owner);
				return;
			}
			case "give_all", "give_res", "give" -> {
				if (partner.distanceTo(owner) <= 8) {
					// Right here (e.g. the backpack screen is open): hand it over at once.
					var summary = order.equals("give_all") ? partner.giveWholeBackpack(owner) : partner.handEverythingTo(owner, false);
					partner.speak("gave_all", summary);
				} else partner.setOrder(PartnerOrder.GIVE, owner);
				sendStatus(owner);
				return;
			}
			case "later" -> {
				partner.brain().initiative.later();
				return;
			}
			case "quiet" -> {
				partner.brain().initiative.quiet();
				return;
			}
			case "store" -> {
				partner.brain().storeInChest();
				sendStatus(owner);
				return;
			}
			case "home" -> {
				partner.brain().returnToBase(owner);
				sendStatus(owner);
				return;
			}
			case "campfire" -> {
				partner.brain().makeCampfire(owner);
				return;
			}
			case "portal" -> {
				partner.brain().buildPortalNow(owner);
				sendStatus(owner);
				return;
			}
			case "ping" -> {
				partner.brain().onPing(owner);
				sendStatus(owner);
				return;
			}
			case "squad_diamond" -> {
				com.dantemorrigan.partner.ai.PartnerSquad.assembleSquad(owner, partner, "diamond", false);
				return;
			}
			case "squad_nether" -> {
				com.dantemorrigan.partner.ai.PartnerSquad.assembleSquad(owner, partner, "nether", false);
				return;
			}
			case "squad_end" -> {
				com.dantemorrigan.partner.ai.PartnerSquad.assembleSquad(owner, partner, "end", false);
				return;
			}
			case "squad_harvest" -> {
				com.dantemorrigan.partner.ai.PartnerSquad.assembleSquad(owner, partner, "harvest", false);
				return;
			}
			case "squad_keep" -> {
				com.dantemorrigan.partner.ai.PartnerSquad.keepForever(owner);
				return;
			}
			case "squad_disband" -> {
				com.dantemorrigan.partner.ai.PartnerSquad.disband(owner);
				return;
			}
			case "skin_next" -> {
				partner.cycleSkin(1);
				sendStatus(owner);
				return;
			}
			case "skin_prev" -> {
				partner.cycleSkin(-1);
				sendStatus(owner);
				return;
			}
			default -> {}
		}
		PartnerOrder o = PartnerOrder.byId(order);
		if (o != null) partner.setOrder(o, owner);
		sendStatus(owner);
	}

	// ------------------------------------------------------------------ queries used by the entity

	/** True when this entity is the one the owner's record points to (old copies remove themselves). */
	public static boolean isCurrent(PartnerEntity partner) {
		// Before the manager has loaded its records (world still starting) nothing may be thrown away.
		if (server == null || !loaded) return true;
		if (partner.isSquadMember()) return com.dantemorrigan.partner.ai.PartnerSquad.isActiveMember(partner);
		if (partner.getOwnerUUID() == null) return false;
		CompoundTag rec = records.get(partner.getOwnerUUID().toString());
		if (rec == null) return false;
		boolean current = rec.getStringOr("entity", "").equals(partner.getUUID().toString()) && !rec.contains("stash");
		if (current) LIVE.put(partner.getOwnerUUID(), partner);
		return current;
	}

	public static void onPartnerDeath(PartnerEntity partner) {
		if (partner.isSquadMember()) return;
		ServerPlayer owner = partner.getOwnerPlayer();
		UUID ownerId = partner.getOwnerUUID();
		if (ownerId == null) return;
		CompoundTag rec = records.get(ownerId.toString());
		if (rec == null) return;
		rec = rec.copy();
		rec.put("stash", save(partner));
		rec.putLong("respawn", partner.level().getGameTime() + RESPAWN_TICKS);
		put(ownerId, rec);
		LIVE.remove(ownerId);
		if (owner != null) {
			owner.sendSystemMessage(PartnerChat.line(partner, PartnerChat.Tone.ALERT, Component.translatable("partner.local_partner.died", RESPAWN_TICKS / 20)));
			sendStatus(owner);
		}
	}

	// ------------------------------------------------------------------ lifecycle

	private static void tick(MinecraftServer s) {
		if (records.isEmpty()) return;
		long time = s.overworld().getGameTime();
		for (ServerPlayer owner : s.getPlayerList().getPlayers()) {
			CompoundTag rec = records.get(owner.getUUID().toString());
			if (rec == null) continue;
			if (rec.contains("stash")) {
				long respawn = rec.getLongOr("respawn", 0L);
				if (time >= respawn && owner.isAlive() && !owner.isSpectator()
						&& !InfinityCompat.partnerWaitsOutside(owner.level().dimension())) {
					restore(owner, rec, respawn > 0);
				}
			} else {
				PartnerEntity partner = find(owner);
				if (partner == null) {
					int missing = MISSING.merge(owner.getUUID(), 1, Integer::sum);
					if (missing == 1 || missing % 20 == 0) {
						// Not found: it may simply be standing in a chunk that is not loaded (it roams far). Load and ticket it.
						if (loadItsChunk(owner, rec) && missing == 1) LocalPartner.LOG.info("Partner of {} not found, loaded its last chunk", owner.getGameProfile().name());
					}
					if (missing > LOST_AFTER && rec.contains("snapshot")) {
						// Really gone (removed from outside): bring the last backup back.
						LocalPartner.LOG.warn("Partner of {} is gone, restoring the backup: {}", owner.getGameProfile().name(), describe(rec.getCompoundOrEmpty("snapshot")));
						CompoundTag r = rec.copy();
						r.put("stash", r.getCompoundOrEmpty("snapshot"));
						put(owner.getUUID(), r);
						MISSING.remove(owner.getUUID());
					}
				} else {
					MISSING.remove(owner.getUUID());
					if (partner.level() instanceof ServerLevel sl) {
						if (sl.players().isEmpty()) sl.resetEmptyTime();
						if (time % 20 == 0 && sl.getNearestPlayer(partner, 96) == null) {
							sl.getChunkSource().addTicketWithRadius(LocalPartner.ROAM_TICKET, partner.chunkPosition(), 2);
						}
					}
					if (partner.tickCount > 40 && partner.tickCount % SNAPSHOT_EVERY == 0) snapshot(owner, partner);
				}
			}
			if (time % 10 == 0) sendStatus(owner);
		}
	}

	/** The owner leaves: refresh the backup (with the position) and let the partner be saved in the world. */
	private static void leave(ServerPlayer owner) {
		PartnerEntity p = find(owner);
		if (p == null) return;
		p.brain().suspend();
		snapshot(owner, p);
		LocalPartner.LOG.info("Partner of {} stays in the world: {}", owner.getGameProfile().name(), describe(records.get(owner.getUUID().toString())));
		MISSING.remove(owner.getUUID());
	}

	/** Writes the partner's current state (and where it is) into its record as the backup. */
	private static void snapshot(ServerPlayer owner, PartnerEntity partner) {
		CompoundTag rec = records.get(owner.getUUID().toString());
		if (rec == null || rec.contains("stash")) return;
		CompoundTag r = rec.copy();
		r.put("snapshot", save(partner));
		r.putString("dim", partner.level().dimension().identifier().toString());
		r.putIntArray("pos", new int[]{partner.getBlockX(), partner.getBlockY(), partner.getBlockZ()});
		put(owner.getUUID(), r);
	}

	/** The world is closing: wind up every partner's current job (a placed table goes back into the bag), then back up. */
	private static void closing(MinecraftServer s) {
		if (loaded) for (ServerPlayer o : s.getPlayerList().getPlayers()) {
			PartnerEntity p = find(o);
			if (p != null) p.brain().suspend();
		}
		snapshotAll(s);
	}

	private static void snapshotAll(MinecraftServer s) {
		for (ServerPlayer owner : s.getPlayerList().getPlayers()) {
			PartnerEntity p = find(owner);
			if (p != null && p.isAlive() && p.tickCount > 5) snapshot(owner, p);
		}
	}

	/** Loads and tickets the chunk where the partner was last seen (it may roam far from every player). */
	private static boolean loadItsChunk(ServerPlayer owner, CompoundTag rec) {
		if (server == null || !rec.contains("pos") || !rec.contains("dim")) return false;
		int[] pos = rec.getIntArray("pos").orElse(new int[0]);
		if (pos.length != 3) return false;
		String dim = rec.getStringOr("dim", "");
		for (ServerLevel level : server.getAllLevels()) {
			if (!level.dimension().identifier().toString().equals(dim)) continue;
			int cx = pos[0] >> 4, cz = pos[2] >> 4;
			level.getChunkSource().addTicketWithRadius(LocalPartner.ROAM_TICKET, new net.minecraft.world.level.ChunkPos(cx, cz), 2);
			level.getChunk(cx, cz);
			return true;
		}
		return false;
	}

	private static String describe(CompoundTag rec) {
		CompoundTag data = rec.contains("stash") ? rec.getCompoundOrEmpty("stash") : rec.contains("snapshot") ? rec.getCompoundOrEmpty("snapshot") : rec;
		int items = data.getListOrEmpty("Backpack").size();
		return (rec.contains("stash") ? "stashed" : "in world") + ", backpack stacks=" + items + (rec.contains("respawn") ? ", respawning" : "");
	}

	private static void restore(ServerPlayer owner, CompoundTag rec, boolean afterDeath) {
		LocalPartner.LOG.info("Restoring the partner of {}: {}", owner.getGameProfile().name(), describe(rec));
		ServerLevel level = owner.level();
		PartnerEntity partner = load(level, rec.getCompoundOrEmpty("stash"));
		CompoundTag r = rec.copy();
		r.remove("stash");
		r.remove("respawn");
		if (partner == null) {
			remove(owner.getUUID());
			say(owner, "failed");
			return;
		}
		Vec3 spot = PartnerEntity.findSafeSpot(level, owner.blockPosition(), partner);
		if (spot == null) {
			// Chunks around the owner are still loading: try again next tick, land next to them after a second.
			if (NO_SPOT.merge(owner.getUUID(), 1, Integer::sum) < 20) return;
			spot = owner.position(); // the one spot known to be breathable
		}
		NO_SPOT.remove(owner.getUUID());
		boolean travel = rec.getBooleanOr("travel", false);
		r.remove("travel");
		partner.setUUID(UUID.randomUUID());
		partner.revive(afterDeath);
		if (travel && partner.order().anchored()) partner.setOrderQuiet(PartnerOrder.FOLLOW);
		partner.snapTo(spot.x, spot.y, spot.z, owner.getYRot() + 180f, 0f);
		if (!level.addFreshEntity(partner)) return;
		r.putString("entity", partner.getUUID().toString());
		put(owner.getUUID(), r);
		LIVE.put(owner.getUUID(), partner);
		if (travel) {
			PartnerFx.recall(partner); // simply came along through the portal/teleport
		} else {
			partner.arrivalFx();
			PartnerFx.toast(partner, "summon");
			partner.speak(afterDeath ? "back_from_death" : "back");
		}
		sendStatus(owner);
	}

	/** Takes the partner out of the world (owner left or went somewhere the partner waits outside). */
	private static void stash(ServerPlayer owner, boolean announce) {
		stash(owner, announce, false);
	}

	private static void stash(ServerPlayer owner, boolean announce, boolean travel) {
		CompoundTag rec = record(owner);
		if (rec == null || rec.contains("stash")) return;
		PartnerEntity partner = find(owner);
		if (partner == null && !travel && loadItsChunk(owner, rec)) partner = find(owner); // far away, chunk not loaded
		if (partner == null) {
			// Still not found: the newest backup is the best there is (kept as the stash, so nothing is lost).
			if (!travel && rec.contains("snapshot")) {
				LocalPartner.LOG.warn("Partner of {} not found while leaving, stashing the backup: {}", owner.getGameProfile().name(), describe(rec.getCompoundOrEmpty("snapshot")));
				CompoundTag r = rec.copy();
				r.put("stash", r.getCompoundOrEmpty("snapshot"));
				put(owner.getUUID(), r);
			}
			return;
		}
		partner.brain().suspend(); // a half-done job (a placed crafting table...) is wound up first
		CompoundTag r = rec.copy();
		r.put("stash", save(partner));
		if (travel) r.putBoolean("travel", true);
		put(owner.getUUID(), r);
		if (announce) {
			partner.speak("wait_outside");
			partner.leaveFx();
		}
		partner.discard();
		LIVE.remove(owner.getUUID());
	}

	private static void ownerChangedLevel(ServerPlayer owner, ServerLevel to) {
		if (InfinityCompat.partnerWaitsOutside(to.dimension())) {
			stash(owner, true);
			return;
		}
		PartnerEntity partner = find(owner);
		if (partner == null || partner.level() == to || partner.isDeadOrDying()) return;
		if (partner.order() == PartnerOrder.SPEEDRUN) return;
		travel(owner);
	}

	/**
	 * Follows the owner into another dimension: the partner leaves its level right away (so it is never left in a
	 * chunk that unloads behind the owner) and reappears next to them as soon as there is a safe spot.
	 */
	public static void travel(ServerPlayer owner) {
		stash(owner, false, true);
	}

	/**
	 * Moves the partner next to the owner, across dimensions if needed. Returns null (and does nothing) while there is
	 * no safe spot yet, e.g. right after the owner arrived and the chunks are still loading; callers simply retry.
	 * After a few seconds without any safe spot it lands next to the owner anyway, so it never gets left behind.
	 */
	public static @Nullable PartnerEntity teleportToOwner(PartnerEntity partner, ServerPlayer owner) {
		ServerLevel level = owner.level();
		Vec3 spot = PartnerEntity.findSafeSpot(level, owner.blockPosition(), partner);
		if (spot == null) {
			int waited = NO_SPOT.merge(owner.getUUID(), 1, Integer::sum);
			if (waited < 20) return null;
			spot = owner.position(); // the one spot known to be breathable
		}
		NO_SPOT.remove(owner.getUUID());
		if (partner.level() == level) {
			partner.snapTo(spot.x, spot.y, spot.z, partner.getYRot(), partner.getXRot());
			partner.getNavigation().stop();
			partner.setDeltaMovement(Vec3.ZERO);
			partner.resetFallDistance();
			return partner;
		}
		Entity moved = partner.teleport(new TeleportTransition(level, spot, Vec3.ZERO, partner.getYRot(), 0f, TeleportTransition.DO_NOTHING));
		if (moved instanceof PartnerEntity p) {
			if (!p.isSquadMember()) {
				CompoundTag rec = record(owner);
				if (rec != null) {
					CompoundTag r = rec.copy();
					r.putString("entity", p.getUUID().toString());
					put(owner.getUUID(), r);
				}
				LIVE.put(owner.getUUID(), p);
			}
			return p;
		}
		return null;
	}

	/** Moves the partner into another dimension on its own (Nether / The End speedrun) and keeps that dimension active. */
	public static @Nullable PartnerEntity teleportToDimension(PartnerEntity partner, ServerLevel targetLevel, Vec3 spot, float yRot) {
		UUID ownerId = partner.getOwnerUUID();
		targetLevel.resetEmptyTime();
		targetLevel.getChunkSource().addTicketWithRadius(LocalPartner.ROAM_TICKET, new net.minecraft.world.level.ChunkPos(((int) Math.floor(spot.x)) >> 4, ((int) Math.floor(spot.z)) >> 4), 2);
		Entity moved = partner.teleport(new TeleportTransition(targetLevel, spot, Vec3.ZERO, yRot, 0f, TeleportTransition.DO_NOTHING));
		if (moved instanceof PartnerEntity p) {
			if (ownerId != null && !p.isSquadMember()) {
				CompoundTag rec = records.get(ownerId.toString());
				if (rec != null) {
					CompoundTag r = rec.copy();
					r.putString("entity", p.getUUID().toString());
					r.putString("dim", targetLevel.dimension().identifier().toString());
					r.putIntArray("pos", new int[]{p.getBlockX(), p.getBlockY(), p.getBlockZ()});
					put(ownerId, r);
				}
				LIVE.put(ownerId, p);
			}
			return p;
		}
		return null;
	}

	// ------------------------------------------------------------------ helpers

	public static @Nullable PartnerEntity find(ServerPlayer owner) {
		PartnerEntity cached = LIVE.get(owner.getUUID());
		if (cached != null && !cached.isRemoved()) return cached;
		LIVE.remove(owner.getUUID());
		CompoundTag rec = record(owner);
		if (rec == null || rec.contains("stash") || server == null) return null;
		UUID id;
		try {
			id = UUID.fromString(rec.getStringOr("entity", ""));
		} catch (IllegalArgumentException e) {
			return null;
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(id) instanceof PartnerEntity p && !p.isRemoved()) {
				LIVE.put(owner.getUUID(), p);
				return p;
			}
		}
		return null;
	}

	private static @Nullable CompoundTag record(ServerPlayer owner) {
		return records.get(owner.getUUID().toString());
	}

	private static void put(UUID owner, CompoundTag rec) {
		records.put(owner.toString(), rec);
		flush();
	}

	private static void remove(UUID owner) {
		records.remove(owner.toString());
		flush();
	}

	private static void flush() {
		if (server != null) server.overworld().setAttached(RECORDS, new HashMap<>(records));
	}

	static CompoundTag save(PartnerEntity partner) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, partner.registryAccess());
		partner.saveWithoutId(out);
		return out.buildResult();
	}

	private static @Nullable PartnerEntity load(ServerLevel level, CompoundTag tag) {
		if (tag.isEmpty()) return null;
		PartnerEntity partner = LocalPartner.PARTNER.create(level, EntitySpawnReason.LOAD);
		if (partner == null) return null;
		partner.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		return partner;
	}

	public static void sendStatus(ServerPlayer owner) {
		CompoundTag rec = record(owner);
		if (rec == null) {
			PartnerNetwork.send(owner, new PartnerNetwork.Status(0, "", "", "", 0, 0f, 0));
			return;
		}
		if (rec.contains("stash")) {
			boolean dead = rec.contains("respawn");
			PartnerNetwork.send(owner, new PartnerNetwork.Status(dead ? 3 : 2, "", "", "", 0, 0f, 0));
			return;
		}
		PartnerEntity p = find(owner);
		if (p == null) {
			PartnerNetwork.send(owner, new PartnerNetwork.Status(2, "", "", "", 0, 0f, 0));
			return;
		}
		PartnerNetwork.send(owner, new PartnerNetwork.Status(
				1, p.order().id, p.activity(), p.brain().detail, p.brain().era(), p.getHealth(), p.foodLevel(),
				p.getBlockX(), p.getBlockY(), p.getBlockZ(), p.brain().poisSummary(owner)));
	}

	private static void say(ServerPlayer owner, String key) {
		owner.sendSystemMessage(PartnerChat.line(Component.translatable("local_partner.title"), PartnerChat.Tone.INFO,
				Component.translatable("partner.local_partner.system." + key)));
		owner.level().playSound(null, owner.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.3f, 1.2f);
	}

	private PartnerManager() {}
}
