import de.donutspin.core.*;
import de.donutspin.core.GameEngine.*;
import de.donutspin.core.SaveData.*;

import java.util.*;

public class CoreSelfTest {
    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError("FEHLER: " + what);
    }

    public static void main(String[] args) {
        // 1) Tabelle
        int sum = 0;
        for (Tier t : PayoutTable.TIERS) sum += t.weight;
        check(sum == 10_000, "Gewichte summieren sich auf 100 %");
        check(PayoutTable.rtpNumerator() == 86_825, "RTP-Zaehler = 86825 (86,825 %)");
        check(Math.abs(PayoutTable.rtpPercent() - 86.825) < 1e-9, "RTP 86,825 %");
        String[] labels = {"0x","0.1x","0.4x","1x","1.2x","1.5x","2x","4x","7x","12x","24x"};
        String[] chances = {"3.3%","30%","22%","20%","12%","6.63%","0.5%","4%","1.2%","0.3%","0.07%"};
        for (int i = 0; i < labels.length; i++) {
            check(PayoutTable.TIERS[i].label().equals(labels[i]), "Label " + labels[i] + " war " + PayoutTable.TIERS[i].label());
            check(PayoutTable.TIERS[i].chance().equals(chances[i]), "Chance " + chances[i] + " war " + PayoutTable.TIERS[i].chance());
        }

        // 2) Jeder der 10000 Wuerfe landet genau so oft in einer Stufe wie deren Gewicht
        int[] cnt = new int[11];
        for (int r = 0; r < 10_000; r++) cnt[PayoutTable.forRoll(r).index]++;
        for (int i = 0; i < 11; i++) check(cnt[i] == PayoutTable.TIERS[i].weight, "Verteilung Stufe " + i);

        // 3) Auszahlungen
        check(PayoutTable.payout(1_000_000L, 8) == 7_000_000L, "1M x 7 = 7M");
        check(PayoutTable.payout(1_000_000L, 1) == 100_000L, "1M x 0.1 = 100K");
        check(PayoutTable.payout(500_000_000L, 10) == 12_000_000_000L, "500M x 24 = 12B");
        check(PayoutTable.payout(1_000_001L, 1) == 100_000L, "abgerundet");
        check(PayoutTable.payout(777_000_000L, 0) == 0, "0x = 0");

        // 4) Betragsparser
        check(Amounts.parse("1M") == 1_000_000L, "1M");
        check(Amounts.parse("2,5m") == 2_500_000L, "2,5m");
        check(Amounts.parse("500M") == 500_000_000L, "500M");
        check(Amounts.parse("1.000.000") == 1_000_000L, "1.000.000");
        check(Amounts.parse("abc") == -1 && Amounts.parse("") == -1 && Amounts.parse("-5m") == -1, "ungueltig");
        check(Amounts.fmt(1_500_000L).equals("1.5M") && Amounts.fmt(100_000L).equals("100K")
            && Amounts.fmt(7_000_000L).equals("7M") && Amounts.fmt(12_000_000_000L).equals("12B"), "fmt");

        // 5) Fairness: Wurf ist aus Seed + UUID nachrechenbar, Hash-Commit stimmt
        byte[] seed = Fairness.newSeed();
        String commit = Fairness.commit(seed);
        check(commit.equals(Fairness.commit(Fairness.fromHex(Fairness.hex(seed)))), "Hex-Roundtrip + Commit");
        int r1 = Fairness.roll(seed, "player-a");
        check(r1 == Fairness.roll(Fairness.fromHex(Fairness.hex(seed)), "player-a"), "Wurf reproduzierbar");
        check(r1 >= 0 && r1 < 10_000, "Wurf im Bereich");
        // bekannter Testvektor (selbst nachrechenbar mit sha256sum): seed = 32 Nullbytes, id "x"
        byte[] zero = new byte[32];
        int fixed = Fairness.roll(zero, "x");
        System.out.println("Testvektor: seed=00*32, id=x -> Wurf " + fixed + " -> " + PayoutTable.forRoll(fixed).label());

        // 6) Statistische Pruefung: 2 Mio. Spins mit echten, zufaelligen Seeds
        long n = 2_000_000L, stakeSum = 0, paidSum = 0;
        int[] hits = new int[11];
        for (long i = 0; i < n; i++) {
            byte[] s = Fairness.newSeed();
            Tier t = PayoutTable.forRoll(Fairness.roll(s, "p"));
            hits[t.index]++;
            stakeSum += 1_000_000L;
            paidSum += PayoutTable.payout(1_000_000L, t.index);
        }
        double rtp = paidSum * 100.0 / stakeSum;
        System.out.printf("Simulation %,d Spins: RTP %.3f %% (Soll 86.825 %%)%n", n, rtp);
        check(Math.abs(rtp - 86.825) < 0.6, "simulierter RTP nahe Soll");
        for (int i = 0; i < 11; i++) {
            double emp = hits[i] * 100.0 / n, exp = PayoutTable.TIERS[i].weight / 100.0;
            double tol = Math.max(0.03, 5 * Math.sqrt(exp / 100 * (1 - exp / 100) / n) * 100);
            check(Math.abs(emp - exp) < tol, "Haeufigkeit " + labels[i] + ": " + emp + " vs " + exp);
        }

        // 7) Engine: Grenzen, Phasen, Abrechnung, Buchhaltungs-Invariante
        final List<String> events = new ArrayList<>();
        SaveData data = new SaveData();
        GameEngine e = new GameEngine(data, new Listener() {
            public void onPhase(GameEngine g) { events.add(g.phase().name()); }
            public void onSettled(GameEngine g, List<Bet> b) { events.add("SETTLED" + b.size()); }
        });
        check(e.placeBet("a", "A", 999_999) == BetResult.TOO_LOW, "Mindesteinsatz");
        check(e.placeBet("a", "A", 500_000_001) == BetResult.TOO_HIGH, "Hoechsteinsatz");
        check(e.placeBet("a", "A", 10_000_001) == BetResult.NO_FUNDS, "Startguthaben 10M");
        check(e.placeBet("a", "A", 1_000_000) == BetResult.OK, "Einsatz ok");
        check(e.placeBet("a", "A", 1_000_000) == BetResult.ALREADY_BET, "nur ein Einsatz");
        check(e.available("a") == 9_000_000L, "reserviert");
        check(e.cancelBet("a") && e.available("a") == 10_000_000L, "Storno");
        for (int i = 0; i < GameEngine.BET_TICKS; i++) e.tick();
        check(e.phase() == Phase.BETTING, "ohne Einsaetze keine Spin-Phase");

        Random rnd = new Random(42);
        for (int p = 0; p < 40; p++) e.give("p" + p, "P" + p, 5_000_000_000L);
        for (int rd = 0; rd < 300; rd++) {
            for (int p = 0; p < 40; p++) {
                if (rnd.nextInt(4) == 0) continue;
                long amt = 1_000_000L + (long) (rnd.nextDouble() * 499_000_000L);
                e.placeBet("p" + p, "P" + p, amt);
            }
            int guard = 0;
            long before = e.round();
            while (e.data().roundCounter < before && guard++ < 5000) e.tick();
            check(guard < 5000, "Runde terminiert");
            while (e.phase() != Phase.BETTING) e.tick();
        }
        long bal = 0;
        for (PlayerData pd : data.players.values()) bal += pd.balance;
        check(bal + (data.house.wagered - data.house.paid) == data.house.issued, "Erhaltung: Guthaben + Bilanz = ausgegeben");
        check(data.house.rounds == data.rounds.size() || data.rounds.size() == 200, "Rundenprotokoll");
        // Jede protokollierte Runde ist per Seed nachpruefbar
        for (RoundRecord rr : data.rounds) check(Fairness.commit(Fairness.fromHex(rr.seed)).equals(rr.commit), "Commit Runde " + rr.round);
        double houseRtp = data.house.paid * 100.0 / data.house.wagered;
        System.out.printf("Engine-Simulation: %d Runden, Einsaetze %s, ausgezahlt %s, RTP %.2f %%%n",
            data.house.rounds, Amounts.fmt(data.house.wagered), Amounts.fmt(data.house.paid), houseRtp);
        check(events.contains("SPINNING") && events.contains("RESULT"), "Phasenwechsel gemeldet");

        System.out.println("ALLE " + checks + " PRUEFUNGEN BESTANDEN");
    }
}
