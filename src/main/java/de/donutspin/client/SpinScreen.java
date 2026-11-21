package de.donutspin.client;

import de.donutspin.core.Amounts;
import de.donutspin.core.GameEngine;
import de.donutspin.core.PayoutTable;
import de.donutspin.core.Tier;
import de.donutspin.net.ActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Die Casino-Oberflaeche: Walze, Countdown, Quoten, Einsatz, Verlauf, Statistik. */
public class SpinScreen extends Screen {
    private static final int[] CYCLE = PayoutTable.REEL_ORDER;
    private static final int GOLD = 0xFFFFC83D, WHITE = 0xFFFFFFFF, GRAY = 0xFF9AA3B8, GREEN = 0xFF4ADE5A, RED = 0xFFE04A4A;
    private static String lastText = "1M";

    private final ItemStack[] icons = {
        new ItemStack(Items.BARRIER), new ItemStack(Items.COAL), new ItemStack(Items.COPPER_INGOT),
        new ItemStack(Items.IRON_INGOT), new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.EMERALD),
        new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.DIAMOND), new ItemStack(Items.NETHERITE_INGOT),
        new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), new ItemStack(Items.NETHER_STAR)
    };

    private int tab;
    private EditBox amountBox;
    private Button betBtn;
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> quickButtons = new ArrayList<>();
    private int px, py, pw;
    private static final int PH = 262;

    private double pos;
    private long lastNano = System.nanoTime();
    private int seenSerial = -1;
    private double spinFrom, spinTarget;
    private boolean hasTarget;

    public SpinScreen() {
        super(Component.literal("DonutSMP Spin Casino"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // wichtig: im Einzelspieler darf der Server (und damit der Rundentakt) weiterlaufen
    }

    @Override
    protected void init() {
        pw = Math.min(400, this.width - 12);
        px = (this.width - pw) / 2;
        py = Math.max(4, (this.height - PH) / 2);
        tabButtons.clear();
        quickButtons.clear();

        String[] names = {"Spiel", "Verlauf", "Statistik"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            tabButtons.add(this.addWidget(Button.builder(Component.literal(names[i]), b -> tab = idx)
                .bounds(px + 10 + i * 72, py + 22, 68, 16).build()));
        }

        int betW = 110;
        int betX = px + pw - 10 - betW;
        amountBox = new EditBox(this.font, px + 52, py + 201, betX - 6 - (px + 52), 18, Component.literal("Einsatz"));
        amountBox.setMaxLength(16);
        amountBox.setFilter(s -> s.matches("[0-9.,kKmMbB_]*"));
        amountBox.setValue(lastText);
        this.addWidget(amountBox);
        betBtn = this.addWidget(Button.builder(Component.literal("Wetten"), b -> onBet())
            .bounds(betX, py + 201, betW, 18).build());

        String[] q = {"1M", "5M", "10M", "50M", "100M", "500M", "MAX"};
        int gap = 3;
        int qw = (pw - 20 - gap * (q.length - 1)) / q.length;
        for (int i = 0; i < q.length; i++) {
            final String label = q[i];
            quickButtons.add(this.addWidget(Button.builder(Component.literal(label), b -> {
                if (label.equals("MAX")) {
                    amountBox.setValue(Long.toString(Math.max(0, Math.min(GameEngine.MAX_BET, ClientState.bal))));
                } else {
                    amountBox.setValue(label);
                }
            }).bounds(px + 10 + i * (qw + gap), py + 223, qw, 16).build()));
        }
    }

    @Override
    public void removed() {
        lastText = amountBox == null ? lastText : amountBox.getValue();
        send("{\"a\":\"close\"}");
        super.removed();
    }

    private void send(String json) {
        ClientPlayNetworking.send(new ActionPayload(json));
    }

    private void onBet() {
        if (ClientState.phase == 0 && ClientState.bet > 0) {
            send("{\"a\":\"cancel\"}");
            return;
        }
        long amt = Amounts.parse(amountBox.getValue());
        if (amt < 0) {
            ClientState.msg = "Ung\u00fcltiger Betrag (z. B. 1M, 2.5M)";
            ClientState.msgOk = false;
            ClientState.msgUntil = System.currentTimeMillis() + 3000;
            return;
        }
        send("{\"a\":\"bet\",\"amt\":" + amt + "}");
    }

    // ------------------------------------------------------------------ Hilfen
    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int alpha(int rgb, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    private static int solid(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    private void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(this.font, s, x, y, color, true);
    }

    private void ctext(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, color, true);
    }

    private void rtext(GuiGraphics g, String s, int right, int y, int color) {
        g.drawString(this.font, s, right - this.font.width(s), y, color, true);
    }

    private static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static double ease(double p) {
        return 1 - Math.pow(1 - p, 3);
    }

    private static int indexInCycle(int tier) {
        for (int i = 0; i < CYCLE.length; i++) if (CYCLE[i] == tier) return i;
        return 0;
    }

    private static double landingOffset(long round) {
        return ((Math.floorMod(round * 7919L, 61L)) - 30) / 100.0;   // -0.30 .. +0.30: Marker landet nie exakt mittig
    }

    // ------------------------------------------------------------------ Zeichnen
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);   // Hintergrund (Widgets werden unten selbst gezeichnet, damit die Reihenfolge stimmt)

        long nowNano = System.nanoTime();
        double dt = Math.min(0.25, (nowNano - lastNano) / 1e9);
        lastNano = nowNano;
        long nowMs = System.currentTimeMillis();
        updatePos(dt, nowMs);
        float pulse = 0.5f + 0.5f * (float) Math.sin(nowMs / 230.0);

        g.fill(0, 0, this.width, this.height, 0x88000000);
        g.fill(px, py, px + pw, py + PH, 0xF20B0F1C);
        g.renderOutline(px, py, pw, PH, 0xFF3A4270);
        g.fill(px, py, px + pw, py + 20, 0xFF1B2140);
        g.fill(px, py + 20, px + pw, py + 21, 0xFF3A4270);
        text(g, "DONUTSMP \u00b7 SPIN CASINO", px + 10, py + 6, GOLD);
        rtext(g, "Guthaben " + Amounts.fmt(ClientState.bal), px + pw - 10, py + 6, GREEN);
        rtext(g, "Runde #" + ClientState.round, px + pw - 10, py + 27, GRAY);

        boolean game = tab == 0;
        for (int i = 0; i < tabButtons.size(); i++) {
            Button b = tabButtons.get(i);
            b.active = tab != i;
            b.render(g, mouseX, mouseY, partial);
        }
        amountBox.visible = game; amountBox.active = game;
        betBtn.visible = game; betBtn.active = game;
        for (Button b : quickButtons) { b.visible = game; b.active = game; }

        if (tab == 0) drawGame(g, mouseX, mouseY, partial, pulse, nowMs);
        else if (tab == 1) drawHistory(g);
        else drawStats(g);
    }

    private void updatePos(double dt, long nowMs) {
        int phase = ClientState.phase;
        if (phase == 1 && ClientState.spinSerial != seenSerial) {
            seenSerial = ClientState.spinSerial;
            if (ClientState.res >= 0) {
                long base = (long) Math.floor(pos) + 66;
                long d = Math.floorMod(indexInCycle(ClientState.res) - base, (long) CYCLE.length);
                spinFrom = pos;
                spinTarget = base + d + landingOffset(ClientState.round);
                hasTarget = true;
            } else {
                hasTarget = false;
            }
        }
        if (phase == 1 && hasTarget) {
            double total = GameEngine.SPIN_TICKS * 50.0;
            double rem = Math.max(0, ClientState.endMs - nowMs);
            double p = Math.max(0, Math.min(1, 1 - rem / total));
            pos = spinFrom + (spinTarget - spinFrom) * ease(p);
        } else if (phase == 2 && hasTarget && ClientState.res >= 0) {
            pos = spinTarget;
        } else {
            pos += dt * (phase == 1 ? 3.0 : 0.45);
        }
    }

    private void drawGame(GuiGraphics g, int mx, int my, float partial, float pulse, long nowMs) {
        int phase = ClientState.phase;
        long rem = Math.max(0, ClientState.endMs - nowMs);
        int total = phase == 0 ? GameEngine.BET_TICKS : phase == 1 ? GameEngine.SPIN_TICKS : GameEngine.RESULT_TICKS;
        int cx = px + pw / 2;
        boolean showResult = phase == 2 && ClientState.res >= 0;
        Tier resTier = ClientState.res >= 0 ? PayoutTable.TIERS[ClientState.res] : null;

        // Statuszeile
        String status;
        int statusColor = WHITE;
        if (nowMs < ClientState.msgUntil && !ClientState.msg.isEmpty()) {
            status = ClientState.msg;
            statusColor = ClientState.msgOk ? GREEN : RED;
        } else if (phase == 0) {
            status = "N\u00e4chste Runde in " + clock(rem) + "  \u00b7  " + ClientState.bettors + " Spieler gesetzt";
            statusColor = GOLD;
        } else if (phase == 1) {
            status = ClientState.bet > 0 ? "Dein Spin \u00b7 " + Amounts.fmt(ClientState.bet) + " Einsatz \u00b7 dreht\u2026" : "Runde l\u00e4uft \u2013 du hast nicht gesetzt";
        } else {
            status = "Neue Runde in " + clock(rem);
            statusColor = GRAY;
        }
        ctext(g, status, cx, py + 46, statusColor);

        // Walze
        int rx = px + 8, rw = pw - 16, ry = py + 62, rh = 62;
        g.fill(rx, ry, rx + rw, ry + rh, 0xFF0E1320);
        int cw = Math.max(28, (rw - 6 * 6) / 7), pitch = cw + 6, ch = rh - 10;
        g.enableScissor(rx, ry, rx + rw, ry + rh);
        int centerIdx = (int) Math.round(pos);
        int span = rw / pitch / 2 + 2;
        for (int i = centerIdx - span; i <= centerIdx + span; i++) {
            int tier = CYCLE[Math.floorMod(i, CYCLE.length)];
            double dx = (i - pos) * pitch;
            int x = (int) Math.round(cx + dx - cw / 2.0);
            float near = (float) Math.max(0, 1 - Math.abs(i - pos) / 3.0);
            boolean hot = showResult && i == centerIdx && tier == ClientState.res;
            drawCard(g, x, ry + 5, cw, ch, tier, near, hot, pulse);
        }
        g.disableScissor();

        // Mittelmarker + Leuchten
        int markerRgb = showResult ? resTier.color : 0xFFC83D;
        int mx0 = cx - cw / 2 - 2;
        for (int k = 1; k <= 4; k++) {
            int a = (int) ((showResult ? 120 : 60) * (0.45 + 0.55 * pulse) * (5 - k) / 4.0);
            g.renderOutline(mx0 - k, ry + 3 - k, cw + 4 + 2 * k, ch + 4 + 2 * k, alpha(markerRgb, a));
        }
        g.renderOutline(mx0, ry + 3, cw + 4, ch + 4, solid(markerRgb));
        for (int k = 0; k < 5; k++) g.fill(cx - 4 + k, ry - 8 + k, cx + 5 - k, ry - 7 + k, solid(markerRgb));

        // Fortschrittsbalken
        float frac = Math.max(0f, Math.min(1f, rem / (total * 50f)));
        g.fill(rx, py + 127, rx + rw, py + 130, 0xFF1B2140);
        g.fill(rx, py + 127, rx + (int) (rw * frac), py + 130, solid(phase == 0 ? 0xFFC83D : phase == 1 ? 0xA45CFF : 0x4ADE5A));

        // Ergebnis-/Infoleiste
        int by = py + 133;
        int barRgb = showResult ? resTier.color : 0x3A4270;
        g.fill(rx, by, rx + rw, by + 16, alpha(barRgb, showResult ? 60 : 64));
        g.renderOutline(rx, by, rw, 16, alpha(barRgb, showResult ? (int) (150 + 100 * pulse) : 255));
        String info;
        int infoColor = WHITE;
        if (showResult) {
            long profit = ClientState.pay - ClientState.bet;
            info = resTier.label() + "  \u00b7  Auszahlung " + Amounts.fmt(ClientState.pay) + "  (" + Amounts.signed(profit) + ")";
            infoColor = solid(resTier.color);
        } else if (phase == 2) {
            info = "Runde beendet";
            infoColor = GRAY;
        } else if (phase == 1) {
            info = "Spin l\u00e4uft\u2026";
            infoColor = mix(GOLD, WHITE, pulse);
        } else if (ClientState.bet > 0) {
            info = "Dein Einsatz: " + Amounts.fmt(ClientState.bet) + "  \u00b7  max. Auszahlung " + Amounts.fmt(PayoutTable.payout(ClientState.bet, 10));
            infoColor = GREEN;
        } else {
            long typed = Amounts.parse(amountBox.getValue());
            info = typed > 0 ? "Eingabe " + Amounts.fmt(typed) + "  \u00b7  max. Auszahlung " + Amounts.fmt(PayoutTable.payout(typed, 10)) + " (24x)"
                : "Einsatz " + Amounts.fmt(GameEngine.MIN_BET) + " \u2013 " + Amounts.fmt(GameEngine.MAX_BET);
        }
        ctext(g, info, cx, by + 4, infoColor);

        // Quoten
        int chipW = (rw - 5 * 4) / 6;
        for (int i = 0; i < PayoutTable.TIERS.length; i++) {
            int row = i < 6 ? 0 : 1;
            int inRow = i < 6 ? i : i - 6;
            int count = row == 0 ? 6 : 5;
            int startX = rx + (rw - (count * chipW + (count - 1) * 4)) / 2;
            int x = startX + inRow * (chipW + 4), y = py + 153 + row * 24;
            Tier t = PayoutTable.TIERS[i];
            boolean won = showResult && ClientState.res == i;
            g.fill(x, y, x + chipW, y + 21, won ? alpha(t.color, 90) : 0xFF121828);
            g.renderOutline(x, y, chipW, 21, won ? solid(t.color) : alpha(t.color, 90));
            ctext(g, t.label(), x + chipW / 2, y + 2, solid(t.color));
            ctext(g, t.chance(), x + chipW / 2, y + 11, won ? WHITE : GRAY);
        }

        // Steuerung
        text(g, "Einsatz", px + 10, py + 206, GRAY);
        betBtn.setMessage(Component.literal(phase == 0 && ClientState.bet > 0 ? "Stornieren" : "Wetten"));
        amountBox.render(g, mx, my, partial);
        betBtn.render(g, mx, my, partial);
        for (Button b : quickButtons) b.render(g, mx, my, partial);

        // Fu\u00dfzeile
        String hash = ClientState.commit.length() >= 16 ? ClientState.commit.substring(0, 16) : ClientState.commit;
        ctext(g, String.format(Locale.ROOT, "R\u00fcckzahlung %.3f%% \u00b7 Hash Runde #%d: %s\u2026", PayoutTable.rtpPercent(), ClientState.round, hash),
            cx, py + 245, 0xFF6B7490);
    }

    private void drawCard(GuiGraphics g, int x, int y, int w, int h, int tier, float near, boolean hot, float pulse) {
        Tier t = PayoutTable.TIERS[tier];
        int bg = mix(0xFF141A28, solid(t.color), 0.10f + 0.20f * near + (hot ? 0.15f * pulse : 0f));
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x + 1, y + 1, x + w - 1, y + 4, alpha(t.color, (int) (130 + 110 * near)));
        g.renderOutline(x, y, w, h, alpha(t.color, (int) (110 + 120 * near)));
        g.renderItem(icons[tier], x + (w - 16) / 2, y + 12);
        String l = t.label();
        g.drawString(this.font, l, x + (w - this.font.width(l)) / 2, y + h - 14, mix(0xFFB0B8C8, solid(t.color), near), true);
    }

    private void drawHistory(GuiGraphics g) {
        int colW = pw / 2 - 14;
        int lx = px + 10, rx = px + pw / 2 + 4;
        int y0 = py + 46;
        text(g, "Deine letzten Spins", lx, y0, GOLD);
        if (ClientState.hist.isEmpty()) text(g, "Noch keine Runden gespielt.", lx, y0 + 14, GRAY);
        for (int i = 0; i < ClientState.hist.size(); i++) {
            ClientState.Row r = ClientState.hist.get(i);
            Tier t = PayoutTable.TIERS[r.tier()];
            int y = y0 + 14 + i * 12;
            long profit = r.payout() - r.stake();
            String left = "#" + r.round() + "  " + t.label() + "  " + Amounts.fmt(r.stake());
            text(g, this.font.plainSubstrByWidth(left, colW - 44), lx, y, solid(t.color));
            rtext(g, Amounts.signed(profit), lx + colW, y, profit > 0 ? GREEN : profit < 0 ? RED : GRAY);
        }
        text(g, "Letzte Runde (alle Spieler)", rx, y0, GOLD);
        if (ClientState.feed.isEmpty()) text(g, "Noch keine Ergebnisse.", rx, y0 + 14, GRAY);
        for (int i = 0; i < ClientState.feed.size(); i++) {
            ClientState.Row r = ClientState.feed.get(i);
            Tier t = PayoutTable.TIERS[r.tier()];
            int y = y0 + 14 + i * 12;
            text(g, this.font.plainSubstrByWidth(r.name(), 70), rx, y, WHITE);
            text(g, t.label(), rx + 74, y, solid(t.color));
            rtext(g, Amounts.fmt(r.payout()), rx + colW, y, GRAY);
        }
        int by = y0 + 14 + 7 * 12 + 4;
        text(g, "Gr\u00f6\u00dfte Gewinne", rx, by, GOLD);
        if (ClientState.big.isEmpty()) text(g, "Noch keine.", rx, by + 14, GRAY);
        for (int i = 0; i < ClientState.big.size(); i++) {
            ClientState.Row r = ClientState.big.get(i);
            int y = by + 14 + i * 12;
            text(g, (i + 1) + ". " + this.font.plainSubstrByWidth(r.name(), 60), rx, y, WHITE);
            rtext(g, Amounts.fmt(r.payout()) + " (" + PayoutTable.TIERS[r.tier()].label() + ")", rx + colW, y, GOLD);
        }
    }

    private void line(GuiGraphics g, int x, int right, int y, String label, String value, int color) {
        text(g, label, x, y, GRAY);
        rtext(g, value, right, y, color);
    }

    private void drawStats(GuiGraphics g) {
        int colW = pw / 2 - 14;
        int lx = px + 10, rx = px + pw / 2 + 4;
        int y = py + 46;
        text(g, "Deine Statistik", lx, y, GOLD);
        double mine = ClientState.wag == 0 ? 0 : ClientState.ret * 100.0 / ClientState.wag;
        long net = ClientState.ret - ClientState.wag;
        line(g, lx, lx + colW, y + 14, "Runden", Long.toString(ClientState.rounds), WHITE);
        line(g, lx, lx + colW, y + 26, "Gewinn/Verlust/gleich", ClientState.wins + "/" + ClientState.losses + "/" + ClientState.pushes, WHITE);
        line(g, lx, lx + colW, y + 38, "Gesamteinsatz", Amounts.fmt(ClientState.wag), WHITE);
        line(g, lx, lx + colW, y + 50, "Gesamtauszahlung", Amounts.fmt(ClientState.ret), WHITE);
        line(g, lx, lx + colW, y + 62, "Netto", Amounts.signed(net), net > 0 ? GREEN : net < 0 ? RED : WHITE);
        line(g, lx, lx + colW, y + 74, "Bester Gewinn", Amounts.fmt(ClientState.best), GOLD);
        line(g, lx, lx + colW, y + 86, "Eigene R\u00fcckzahlung", String.format(Locale.ROOT, "%.2f%%", mine), WHITE);

        text(g, "Betreiber (virtuell)", rx, y, GOLD);
        double houseRtp = ClientState.hWag == 0 ? 0 : ClientState.hPaid * 100.0 / ClientState.hWag;
        long houseNet = ClientState.hWag - ClientState.hPaid;
        line(g, rx, rx + colW, y + 14, "Runden", Long.toString(ClientState.hRounds), WHITE);
        line(g, rx, rx + colW, y + 26, "Eins\u00e4tze", Amounts.fmt(ClientState.hWag), WHITE);
        line(g, rx, rx + colW, y + 38, "Auszahlungen", Amounts.fmt(ClientState.hPaid), WHITE);
        line(g, rx, rx + colW, y + 50, "Bilanz", Amounts.signed(houseNet), houseNet >= 0 ? GREEN : RED);
        line(g, rx, rx + colW, y + 62, "Tats\u00e4chl. R\u00fcckzahlung", String.format(Locale.ROOT, "%.2f%%", houseRtp), WHITE);
        line(g, rx, rx + colW, y + 74, "Soll-R\u00fcckzahlung", String.format(Locale.ROOT, "%.3f%%", PayoutTable.rtpPercent()), WHITE);
        line(g, rx, rx + colW, y + 86, "Ausgegebene Punkte", Amounts.fmt(ClientState.hIssued), WHITE);

        int fy = y + 108;
        text(g, "Fairness (nachpr\u00fcfbar)", lx, fy, GOLD);
        text(g, this.font.plainSubstrByWidth("Hash Runde #" + ClientState.round + ": " + ClientState.commit, pw - 20), lx, fy + 13, GRAY);
        if (ClientState.prevRound > 0) {
            text(g, this.font.plainSubstrByWidth("Seed Runde #" + ClientState.prevRound + ": " + ClientState.prevSeed, pw - 20), lx, fy + 25, GRAY);
        }
        text(g, "Wurf = SHA-256(Seed:UUID) mod 10000 \u2013 im Chat: /spin verify <Runde>", lx, fy + 40, 0xFF6B7490);
        text(g, "Punkte sind virtuell und nicht \u00fcbertragbar.", lx, fy + 52, 0xFF6B7490);
    }
}
