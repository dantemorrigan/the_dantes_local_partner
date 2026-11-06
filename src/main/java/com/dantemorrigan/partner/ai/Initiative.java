package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerChat;
import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerOrder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The partner thinks about what the two of you are missing. Critical problems it solves right away on its own
 * (it is starving → goes hunting); everything else becomes a short question in chat with clickable answers.
 * Questions are rare: at most one every two minutes, each topic at most every eight, never during a fight,
 * "Later" postpones for five minutes and {@code /partner quiet} mutes questions for twenty.
 */
public final class Initiative {
	private static final int ASK_GAP = 20 * 120, TOPIC_GAP = 20 * 60 * 8;
	private final PartnerBrain brain;
	private final Map<String, Integer> topicAt = new HashMap<>();
	private int nextAsk = 20 * 90, quietUntil, tick;

	public Initiative(PartnerBrain brain) {
		this.brain = brain;
	}

	public void later() {
		nextAsk = tick + 20 * 60 * 5;
		brain.p.speak("later_ok");
	}

	public void quiet() {
		quietUntil = tick + 20 * 60 * 20;
		brain.p.speak("quiet_ok");
	}

	/**
	 * Called once a second when nothing urgent is going on. Returns a job when the partner decides to fix something
	 * itself right now (the brain starts it), otherwise null.
	 */
	public @Nullable Job think(ServerLevel level, ServerPlayer owner, boolean busy) {
		tick += 20;
		PartnerEntity p = brain.p;
		var bag = p.backpack();

		// Critical, solved without asking: the partner itself is starving with nothing to eat.
		if (p.foodLevel() <= 6 && Gear.foodPoints(bag) == 0 && !busy && ready("starving", 20 * 60 * 3)) {
			p.speak("starving");
			return new HuntJob(20);
		}
		// The owner is hungry, has no food, and neither has the partner: go get some.
		if (!owner.isCreative() && owner.getFoodData().getFoodLevel() <= 10 && ownerFood(owner) == 0 && Gear.foodPoints(bag) == 0
				&& !busy && ready("owner_food", 20 * 60 * 3)) {
			p.speak("fix_food");
			return new HuntJob(30);
		}

		if (tick < nextAsk || tick < quietUntil || busy || brain.inFight()) return null;
		if (p.order() == PartnerOrder.GUARD || p.order() == PartnerOrder.PATROL) return null;

		// Backpack almost full: offer the haul.
		int free = 0;
		for (int i = 0; i < bag.getContainerSize(); i++) if (bag.getItem(i).isEmpty()) free++;
		if (free <= 3 && ready("full")) {
			ask(owner, "ask_full", List.of(
					PartnerChat.button(Component.translatable("partner.local_partner.btn.take"), "/partner give",
							Component.translatable("partner.local_partner.btn.take.hover"), PartnerChat.HELP_COLOR),
					PartnerChat.button(Component.translatable("partner.local_partner.btn.open"), "/partner inventory",
							Component.translatable("partner.local_partner.btn.open.hover"), PartnerChat.ACCENT),
					PartnerChat.laterButton()));
			return null;
		}
		// Plenty of resources: offer them.
		int logs = Gear.count(bag, s -> s.is(ItemTags.LOGS)), ores = Gear.count(bag, s -> Gear.id(s).getPath().startsWith("raw_") || s.is(net.minecraft.world.item.Items.DIAMOND));
		if ((logs >= 24 || ores >= 8) && ready("share")) {
			ask(owner, "ask_share", List.of(
					PartnerChat.button(Component.translatable("partner.local_partner.btn.take"), "/partner give",
							Component.translatable("partner.local_partner.btn.take.hover"), PartnerChat.HELP_COLOR),
					PartnerChat.laterButton()), logs, ores);
			return null;
		}
		// The owner walks around without armor.
		boolean ownerNaked = true;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if (!owner.getItemBySlot(slot).isEmpty()) ownerNaked = false;
		}
		if (ownerNaked && !owner.isCreative() && level.getGameTime() > 20 * 60 * 10 && ready("armor")) {
			ask(owner, "ask_armor", List.of(PartnerChat.orderButton("ore", PartnerChat.ORE), PartnerChat.orderButton("free", PartnerChat.FREE),
					PartnerChat.laterButton()));
			return null;
		}
		// Night is coming and we are out in the open.
		long dayTime = level.getOverworldClockTime() % 24000;
		if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD && dayTime > 11500 && dayTime < 12500
				&& level.canSeeSky(owner.blockPosition()) && ready("night")) {
			ask(owner, "ask_night", List.of(PartnerChat.orderButton("guard", PartnerChat.GUARD), PartnerChat.orderButton("patrol", PartnerChat.PATROL),
					PartnerChat.laterButton()));
			return null;
		}
		// Nothing left to do on its own: ask what next.
		if (p.order() == PartnerOrder.FOLLOW && !p.strictFollow() && brain.nothingToImprove() && ready("idle")) {
			ask(owner, "ask_what", List.of(PartnerChat.orderButton("wood", PartnerChat.WOOD), PartnerChat.orderButton("ore", PartnerChat.ORE),
					PartnerChat.orderButton("guard", PartnerChat.GUARD), PartnerChat.orderButton("free", PartnerChat.FREE), PartnerChat.laterButton()));
		}
		return null;
	}

	private boolean ready(String topic) {
		return ready(topic, TOPIC_GAP);
	}

	private boolean ready(String topic, int gap) {
		Integer at = topicAt.get(topic);
		if (at != null && tick - at < gap) return false;
		topicAt.put(topic, tick);
		return true;
	}

	private void ask(ServerPlayer owner, String key, List<MutableComponent> buttons, Object... args) {
		nextAsk = tick + ASK_GAP;
		PartnerChat.ask(owner, brain.p, Component.translatable("partner.local_partner.say." + key, args), new ArrayList<>(buttons));
		brain.p.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_AMBIENT, 0.4f, 1.4f);
	}

	private static int ownerFood(ServerPlayer owner) {
		int n = 0;
		for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
			ItemStack s = owner.getInventory().getItem(i);
			if (Gear.isFood(s)) n += s.getCount();
		}
		return n;
	}
}
