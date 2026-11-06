package com.dantemorrigan.partner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;

/**
 * The partner's chat style (design pack palette): a colored bar, the name in bold mint, "»", and the line in a tone
 * color. Questions come with clickable answer buttons that run {@code /partner ...} for the owner.
 */
public final class PartnerChat {
	public enum Tone {
		INFO(0xE9F5E6), GOOD(0x92F0BD), HELP(0xFFD182), ALERT(0xFF9A85), ASK(0xC2C8FF);

		public final int color;

		Tone(int color) {
			this.color = color;
		}
	}

	public static final int ACCENT = 0x92F0BD, MUTED = 0xAAC3B5, HELP_COLOR = 0xFFD182;
	/** Accent colors of the wheel sectors, reused for answer buttons. */
	public static final int FOLLOW = 0x92F0BD, WOOD = 0xF2BB84, ORE = 0xA2DFED, GUARD = 0xF4A594, PATROL = 0xC2C8FF, FREE = 0xE0EABA;

	private static final Set<String> ALERT = Set.of("retreat", "escaped_fire", "escaped_void", "fight", "fight_boss", "recovering", "starving", "full");
	private static final Set<String> HELP = Set.of("stalled", "auto_switch", "plan", "gave_food", "recovered", "brought", "gave_all", "thanks", "owner_respawned", "fix_food", "fix_tools");
	private static final Set<String> GOOD = Set.of("ore_found", "ore_mined", "tree_done", "hunt_done", "smelt_done", "era_up", "table_packed", "good_morning", "hello", "back", "back_from_death", "crafted", "done_wood", "done_ore", "table_placed",
			"order_follow", "order_wood", "order_ore", "order_guard", "order_patrol", "order_free", "order_give");

	public static Tone toneOf(String key) {
		if (ALERT.contains(key)) return Tone.ALERT;
		if (HELP.contains(key)) return Tone.HELP;
		if (GOOD.contains(key)) return Tone.GOOD;
		if (key.startsWith("ask_")) return Tone.ASK;
		return Tone.INFO;
	}

	/** "▍ Name » message". */
	public static Component line(Component name, Tone tone, Component message) {
		return Component.empty()
				.append(Component.literal("▍ ").withStyle(Style.EMPTY.withColor(tone.color)))
				.append(name.copy().withStyle(Style.EMPTY.withColor(ACCENT).withBold(true)))
				.append(Component.literal(" » ").withStyle(Style.EMPTY.withColor(MUTED)))
				.append(message.copy().withStyle(Style.EMPTY.withColor(tone.color)));
	}

	public static Component line(PartnerEntity p, Tone tone, Component message) {
		return line(p.getDisplayName(), tone, message);
	}

	/** A clickable "[label]" that runs a command for the clicking player. */
	public static MutableComponent button(Component label, String command, Component hover, int color) {
		return Component.literal("[").append(label).append("]").withStyle(Style.EMPTY.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	public static MutableComponent orderButton(String order, int color) {
		return button(Component.translatable("local_partner.command." + order), "/partner order " + order,
				Component.translatable("local_partner.command." + order + ".desc"), color);
	}

	public static MutableComponent laterButton() {
		return button(Component.translatable("partner.local_partner.btn.later"), "/partner later",
				Component.translatable("partner.local_partner.btn.later.hover"), MUTED);
	}

	/** Sends a line and, under it, a row of answer buttons. */
	public static void ask(ServerPlayer owner, PartnerEntity p, Component question, List<MutableComponent> buttons) {
		owner.sendSystemMessage(line(p, Tone.ASK, question));
		MutableComponent row = Component.literal("   ").withStyle(ChatFormatting.RESET);
		for (int i = 0; i < buttons.size(); i++) {
			if (i > 0) row.append(Component.literal("  "));
			row.append(buttons.get(i));
		}
		owner.sendSystemMessage(row);
	}

	private PartnerChat() {}
}
