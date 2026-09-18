import com.waze.wazeology.*;
import java.util.List;

/** Off-bike assertion of the frame byte layouts. */
public class FramesCheck {
    static int failures = 0;

    static void eq(String what, String got, String want) {
        boolean ok = got.equals(want);
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            System.out.println("   got:  " + got);
            System.out.println("   want: " + want);
            failures++;
        }
    }

    static void true_(String what, boolean cond) {
        System.out.println((cond ? "PASS " : "FAIL ") + what);
        if (!cond) failures++;
    }

    public static void main(String[] args) {
        byte[] tbt = Frames.turnByTurn(FlagMode.NAVIGATING, TurnType.LEFT, DistanceUnit.M, 1900);
        eq("turnByTurn(NAVIGATING,LEFT,M,1900)", Frames.hex(tbt),
            "14 08 00 FF FF 05 70 00 FF FF FF FF FF 10 FF 05 71 01 00 07 6C FF FF FF FF FF FF FF FF FF FF FF FF FF FF");
        true_("turnByTurn length 35", tbt.length == 35);

        byte[] clear = Frames.turnByTurn(FlagMode.NO_NAVIGATION, TurnType.RESERVE, DistanceUnit.NOT_AVAILABLE, 0);
        true_("clear byte7=0x30", (clear[7] & 0xFF) == 0x30);
        true_("clear byte13=0xF0", (clear[13] & 0xFF) == 0xF0);
        true_("clear byte17=0xFF", (clear[17] & 0xFF) == 0xFF);
        true_("clear dist 00 00 00", (clear[18] & 0xFF) == 0 && (clear[19] & 0xFF) == 0 && (clear[20] & 0xFF) == 0);

        byte[] meter = Frames.meterIndication();
        eq("meterIndication()", Frames.hex(meter), "13 0C 00 FF FF 05 00 FF 30 FF FF FF FF FF FF");

        // battery segment buckets (mirror Rideology): charging overrides level; only 4 discharge steps.
        true_("batteryNibble unknown -> F", Frames.batteryNibble(-1, false) == 0x0F);
        true_("batteryNibble 0 -> F", Frames.batteryNibble(0, false) == 0x0F);
        true_("batteryNibble 5 -> 0", Frames.batteryNibble(5, false) == 0x00);
        true_("batteryNibble 10 -> 0", Frames.batteryNibble(10, false) == 0x00);
        true_("batteryNibble 25 -> 1", Frames.batteryNibble(25, false) == 0x01);
        true_("batteryNibble 30 -> 1", Frames.batteryNibble(30, false) == 0x01);
        true_("batteryNibble 50 -> 2", Frames.batteryNibble(50, false) == 0x02);
        true_("batteryNibble 70 -> 2", Frames.batteryNibble(70, false) == 0x02);
        true_("batteryNibble 85 -> 3", Frames.batteryNibble(85, false) == 0x03);
        true_("batteryNibble 100 -> 3", Frames.batteryNibble(100, false) == 0x03);
        true_("batteryNibble charging overrides low", Frames.batteryNibble(5, true) == 0x07);
        true_("batteryNibble charging overrides full", Frames.batteryNibble(100, true) == 0x07);
        eq("meterIndication(2,15,3,0)", Frames.hex(Frames.meterIndication(2, 15, 3, 0)),
            "13 0C 00 FF FF 05 00 2F 30 FF FF FF FF FF FF");

        byte[] pn = Frames.phoneName("Pixel 8");
        true_("phoneName length 35", pn.length == 35);
        eq("phoneName header+block0", Frames.hex(java.util.Arrays.copyOfRange(pn, 0, 14)),
            "0B 20 00 FF FF 05 01 50 69 78 65 6C 20 38");

        // long name (>24 bytes) must clamp to 24 and fill 3 blocks
        byte[] pnLong = Frames.phoneName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        true_("phoneName clamps to 35", pnLong.length == 35);
        true_("phoneName block2 tag at 25", (pnLong[25] & 0xFF) == 0x05 && (pnLong[26] & 0xFF) == 0x03);

        List<Command> seq = Frames.initSequence("Test");
        int[] wantOps = {0x03,0x40,0x1A,0x1D,0x47,0x0B,0x41,0x1B,0x48,0x1E,0x08,0x45,0x42};
        boolean seqOk = seq.size() == wantOps.length;
        for (int i = 0; seqOk && i < wantOps.length; i++) {
            int first = seq.get(i).frame[0] & 0xFF;
            int exp = seq.get(i).expects;
            if (first != wantOps[i] || exp != wantOps[i]) seqOk = false;
        }
        true_("initSequence order + expects", seqOk);
        true_("initSequence 42 has arg 01", (seq.get(12).frame[2] & 0xFF) == 0x01);

        true_("isResponseTo direct 1A", Frames.isResponseTo(0x1A, new byte[]{(byte)0x1A, 0, 0}));
        true_("isResponseTo ACK phoneName", Frames.isResponseTo(0x0B, new byte[]{(byte)0x20, 0, 0, 0}));
        true_("isResponseTo ACK echo 40", Frames.isResponseTo(0x40, new byte[]{(byte)0x20, 0, 0, (byte)0x40}));
        true_("isResponseTo ACK mismatch 41!=40", !Frames.isResponseTo(0x40, new byte[]{(byte)0x20, 0, 0, (byte)0x41}));

        // roundabout CCW block ids the mapping indexes into (base 30, exit n -> 30+n-1)
        true_("CCW_EXIT_1 id 30", TurnType.ROUNDABOUT_CCW_EXIT_1.id == 30);
        true_("CCW_EXIT_3 id 32", TurnType.fromId(TurnType.ROUNDABOUT_CCW_EXIT_1.id + 3 - 1) == TurnType.ROUNDABOUT_CCW_EXIT_3);
        true_("CCW_EXIT_12 id 41", TurnType.ROUNDABOUT_CCW_EXIT_12.id == 41);

        System.out.println(failures == 0 ? "\nALL FRAME CHECKS PASSED" : ("\n" + failures + " CHECK(S) FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
