package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.PartnerNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Esc → Local Partner…: the partner panel of the current world. Off: "Call partner". On: "Dismiss partner",
 * which asks for confirmation first. Esc in the confirmation goes back to the panel, Esc in the panel back to pause.
 */
public final class PartnerPanelScreen extends Screen {
	private static final int W = 252, H = 146;
	private final Screen parent;
	private boolean confirming;
	private int x, y;

	public PartnerPanelScreen(Screen parent) {
		super(Component.translatable("local_partner.pause.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		x = (width - W) / 2;
		y = (height - H) / 2;
	}

	@Override
	public boolean isPauseScreen() {
		return false; // let the world run so the partner can be seen arriving or leaving behind the panel
	}

	private boolean on() {
		return ClientState.state != 0;
	}

	// Button rects: left (Back/Cancel) and right (main action).
	private int[] left() { return new int[]{x + 18, y + H - 34, 78, 20}; }
	private int[] right() { return new int[]{x + 102, y + H - 34, W - 120, 20}; }
	private int[] backpack() { return new int[]{x + 18, y + H - 58, W - 36, 20}; }

	private boolean backpackShown() {
		return !confirming && ClientState.state == 1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, width, height, 0x99060D0C);
		Font font = Minecraft.getInstance().font;
		Ui.frame(g, "panel", x, y, W, H);
		Ui.icon(g, "partner", x + 18, y + 14, 12);
		g.text(font, Component.translatable("local_partner.title").withStyle(ChatFormatting.BOLD), x + 36, y + 16, Ui.ACCENT, true);
		g.fill(x + 18, y + 32, x + W - 18, y + 33, Ui.BORDER);

		if (!confirming) {
			g.text(font, Component.translatable("local_partner.pause.title").withStyle(ChatFormatting.BOLD), x + 18, y + 44, Ui.TEXT, true);
			boolean on = on();
			g.fill(x + 20, y + 63, x + 24, y + 67, on ? Ui.ACCENT : Ui.MUTED);
			Component status = Component.translatable(on ? "local_partner.pause.on" : "local_partner.pause.off");
			if (on && ClientState.state == 3) status = Component.translatable("local_partner.pause.respawning");
			else if (on && ClientState.state == 2) status = Component.translatable("local_partner.pause.waiting");
			g.text(font, status, x + 30, y + 61, Ui.MUTED, false);
			if (on) {
				g.text(font, Component.translatable("local_partner.pause.hint", PartnerKeys.WHEEL.getTranslatedKeyMessage()), x + 18, y + 74, Ui.MUTED, false);
			}
			if (!ClientState.modOnServer) {
				g.textWithWordWrap(font, Component.translatable("local_partner.pause.no_server"), x + 18, y + 78, W - 36, Ui.ALERT, false);
			}
			if (backpackShown()) {
				int[] b = backpack();
				Ui.button(g, Component.translatable("local_partner.pause.backpack"), b[0], b[1], b[2], b[3], Ui.Style.NORMAL, Ui.inside(mx, my, b[0], b[1], b[2], b[3]));
			}
			int[] l = left(), r = right();
			Ui.button(g, Component.translatable("local_partner.pause.back"), l[0], l[1], l[2], l[3], Ui.Style.NORMAL, Ui.inside(mx, my, l[0], l[1], l[2], l[3]));
			Ui.Style style = !ClientState.modOnServer ? Ui.Style.DISABLED : on ? Ui.Style.DANGER : Ui.Style.PRIMARY;
			Ui.button(g, Component.translatable(on ? "local_partner.pause.dismiss" : "local_partner.pause.enable"),
					r[0], r[1], r[2], r[3], style, Ui.inside(mx, my, r[0], r[1], r[2], r[3]));
		} else {
			g.text(font, Component.translatable("local_partner.pause.dismiss_title").withStyle(ChatFormatting.BOLD), x + 18, y + 44, Ui.TEXT, true);
			g.textWithWordWrap(font, Component.translatable("local_partner.pause.dismiss_body"), x + 18, y + 60, W - 36, Ui.MUTED, false);
			g.textWithWordWrap(font, Component.translatable("local_partner.pause.dismiss_items"), x + 18, y + 82, W - 36, Ui.MUTED, false);
			int[] l = left(), r = right();
			Ui.button(g, Component.translatable("local_partner.pause.cancel"), l[0], l[1], l[2], l[3], Ui.Style.NORMAL, Ui.inside(mx, my, l[0], l[1], l[2], l[3]));
			Ui.button(g, Component.translatable("local_partner.pause.dismiss"), r[0], r[1], r[2], r[3], Ui.Style.DANGER, Ui.inside(mx, my, r[0], r[1], r[2], r[3]));
		}
	}

	private void click() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
	}

	private void back() {
		click();
		if (confirming) confirming = false;
		else Minecraft.getInstance().setScreen(parent);
	}

	private void main() {
		if (!ClientState.modOnServer) return;
		click();
		if (confirming) {
			ClientPlayNetworking.send(new PartnerNetwork.Toggle(false));
			ClientState.state = 0;
			confirming = false;
		} else if (on()) {
			confirming = true;
		} else {
			ClientPlayNetworking.send(new PartnerNetwork.Toggle(true));
			ClientState.state = 1; // optimistic; the next status packet confirms
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) return super.mouseClicked(event, doubleClick);
		int[] l = left(), r = right(), b = backpack();
		if (backpackShown() && Ui.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
			click();
			ClientPlayNetworking.send(new PartnerNetwork.Command("inventory"));
			return true;
		}
		if (Ui.inside(event.x(), event.y(), l[0], l[1], l[2], l[3])) back();
		else if (Ui.inside(event.x(), event.y(), r[0], r[1], r[2], r[3])) main();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			back();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			main();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}
}
