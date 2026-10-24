package app.rovex.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * ИК-протоколы кондиционеров Rovex.
 *
 * Rovex — бренд без собственного протокола: разные серии собирают разные заводы.
 * Поэтому здесь собраны протоколы всех этих заводов. Реализация — порт открытой
 * библиотеки IRremoteESP8266 (ir_Gree, ir_Electra, ir_Midea, ir_Coolix, ir_Haier,
 * функции IRac::*) и Arduino-HeatpumpIR (Chigo). Каждый протокол сверен бит в бит
 * с оригинальным C++ кодом на всех комбинациях настроек.
 */
public final class RovexCodec {

    private RovexCodec() {}

    public static final int FREQ = 38000;

    // Режимы приложения
    public static final int MODE_AUTO = 0;
    public static final int MODE_COOL = 1;
    public static final int MODE_HEAT = 2;
    public static final int MODE_DRY = 3;
    public static final int MODE_FAN = 4;

    // Вентилятор: 0 авто, 1 низкий, 2 средний, 3 высокий
    public static final int FAN_AUTO = 0;

    // Протоколы
    public static final int P_GREE = 0;
    public static final int P_GREE_YAW1F = 1;
    public static final int P_AUX = 2;
    public static final int P_MIDEA = 3;
    public static final int P_COOLIX = 4;
    public static final int P_HAIER = 5;
    public static final int P_CHIGO_LT = 6;
    public static final int P_CHIGO_JG = 7;
    public static final int COUNT = 8;

    public static final String[] NAMES = {
            "Gree", "Gree · YAW1F", "AUX", "Midea", "Midea · Coolix", "Haier",
            "Chigo · ZH/LT-01", "Chigo · ZH/JG-01"};
    public static final String[] REMOTES = {
            "пульты YX1F, YAN1F1, YB1F2, YAA1FB",
            "пульты YAW1F (RusClimate и др.)",
            "пульты YKR-H/002E, YKR-H/009E, YKR-P, YKR-T",
            "пульты RG57, RG66 (полное состояние)",
            "пульты RG51, RG52, R51 (Coolix)",
            "пульты YR-W02 (Haier)",
            "пульт ZH/LT-01",
            "пульт ZH/JG-01"};

    /** true — у протокола качание/турбо/сон передаются отдельными «нажатиями». */
    public static boolean usesToggles(int p) {
        return p == P_MIDEA || p == P_COOLIX;
    }

    public static int minTemp(int p) {
        switch (p) {
            case P_MIDEA:
            case P_COOLIX:
            case P_CHIGO_JG: return 17;
            default: return 16;
        }
    }

    public static int maxTemp(int p) {
        switch (p) {
            case P_AUX:
            case P_CHIGO_LT:
            case P_CHIGO_JG: return 32;
            default: return 30;
        }
    }

    public static final class State {
        public boolean power = false;
        public int mode = MODE_COOL;
        public int temp = 24;
        public int fan = FAN_AUTO;
        public boolean swing = false;
        public boolean turbo = false;
        public boolean sleep = false;
    }

    /**
     * Готовые ИК-кадры для отправки (каждый передаётся отдельным вызовом transmit).
     * Флаги *Pressed нужны протоколам с переключателями (Midea, Coolix): там такие
     * кадры шлются, только когда пользователь нажал соответствующую кнопку.
     */
    public static List<int[]> frames(int p, State s,
                                     boolean swingPressed, boolean turboPressed, boolean sleepPressed) {
        List<int[]> out = new ArrayList<>();
        switch (p) {
            case P_GREE: out.add(gree(s, false)); break;
            case P_GREE_YAW1F: out.add(gree(s, true)); break;
            case P_AUX: out.add(aux(s)); break;
            case P_MIDEA: midea(s, swingPressed, turboPressed, out); break;
            case P_COOLIX: coolix(s, swingPressed, turboPressed, sleepPressed, out); break;
            case P_HAIER: out.add(haier(s)); break;
            case P_CHIGO_LT:
            case P_CHIGO_JG: out.add(chigo(s, p == P_CHIGO_JG)); break;
        }
        return out;
    }

    // ================================================================ сборка таймингов

    private static final class Pulses {
        private final List<Integer> d = new ArrayList<>();
        private boolean lastMark = false;

        void mark(int us) {
            if (us <= 0) return;
            if (!d.isEmpty() && lastMark) d.set(d.size() - 1, d.get(d.size() - 1) + us);
            else d.add(us);
            lastMark = true;
        }

        void space(int us) {
            if (us <= 0) return;
            if (d.isEmpty()) return;
            if (!lastMark) d.set(d.size() - 1, d.get(d.size() - 1) + us);
            else d.add(us);
            lastMark = false;
        }

        void bits(long data, int nbits, boolean msbFirst, int bitMark, int one, int zero) {
            for (int i = 0; i < nbits; i++) {
                int bit = msbFirst ? nbits - 1 - i : i;
                mark(bitMark);
                space(((data >> bit) & 1) == 1 ? one : zero);
            }
        }

        void bytes(int[] b, int from, int to, boolean msbFirst, int bitMark, int one, int zero) {
            for (int i = from; i < to; i++) bits(b[i], 8, msbFirst, bitMark, one, zero);
        }

        int[] build() {
            if (lastMark) d.add(20000);          // Android требует чётную длину
            int[] a = new int[d.size()];
            for (int i = 0; i < a.length; i++) a[i] = d.get(i);
            return a;
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ================================================================ Gree

    private static int[] gree(State s, boolean yaw1f) {
        int mode;
        switch (s.mode) {
            case MODE_COOL: mode = 1; break;
            case MODE_DRY:  mode = 2; break;
            case MODE_FAN:  mode = 3; break;
            case MODE_HEAT: mode = 4; break;
            default:        mode = 0;
        }
        int temp = mode == 0 ? 25 : clamp(s.temp, 16, 30);
        int fan = mode == 2 ? 1 : clamp(s.fan, 0, 3);

        int[] b = new int[8];
        b[0] = mode | (s.power ? 1 << 3 : 0) | fan << 4 | (s.swing ? 1 << 6 : 0) | (s.sleep ? 1 << 7 : 0);
        b[1] = temp - 16;
        b[2] = (s.turbo ? 1 << 4 : 0) | 1 << 5 /* подсветка */ | (s.power && yaw1f ? 1 << 6 : 0);
        b[3] = 0x50;
        b[4] = s.swing ? 1 : 0;
        b[5] = 0x20;
        b[6] = 0;
        int sum = 10;
        for (int i = 0; i < 4; i++) sum += b[i] & 0xF;
        for (int i = 4; i < 7; i++) sum += b[i] >> 4;
        b[7] = (sum & 0xF) << 4;

        Pulses p = new Pulses();
        p.mark(9000);
        p.space(4500);
        p.bytes(b, 0, 4, false, 620, 1600, 540);
        p.bits(0b010, 3, false, 620, 1600, 540);
        p.mark(620);
        p.space(19980);
        p.bytes(b, 4, 8, false, 620, 1600, 540);
        p.mark(620);
        p.space(19980);
        return p.build();
    }

    // ================================================================ AUX (Electra)

    private static int[] aux(State s) {
        int mode;
        switch (s.mode) {
            case MODE_COOL: mode = 0b001; break;
            case MODE_DRY:  mode = 0b010; break;
            case MODE_HEAT: mode = 0b100; break;
            case MODE_FAN:  mode = 0b110; break;
            default:        mode = 0b000;
        }
        int fan;
        switch (s.fan) {
            case 1: fan = 0b011; break;
            case 2: fan = 0b010; break;
            case 3: fan = 0b001; break;
            default: fan = 0b101;
        }
        int swing = s.swing ? 0b000 : 0b111;

        int[] b = new int[13];
        b[0] = 0xC3;
        b[1] = swing | (clamp(s.temp, 16, 32) - 8) << 3;
        b[2] = 0b111 << 5;                                    // горизонтальные жалюзи выкл.
        b[4] = fan << 5;
        b[5] = (s.turbo ? 1 << 6 : 0) | (s.sleep ? 1 << 7 : 0);  // сон = тихий режим
        b[6] = mode << 5;
        b[9] = s.power ? 1 << 5 : 0;
        b[11] = 0x08;
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += b[i];
        b[12] = sum & 0xFF;

        Pulses p = new Pulses();
        p.mark(9166);
        p.space(4470);
        p.bytes(b, 0, 13, false, 646, 1647, 547);
        p.mark(646);
        p.space(100000);
        return p.build();
    }

    // ================================================================ Midea

    private static final long MIDEA_SWING = 0xA201FFFFFF7CL;
    private static final long MIDEA_TURBO = 0xA209FFFFFF74L;

    private static int reverse8(int v) {
        return Integer.reverse(v & 0xFF) >>> 24;
    }

    private static void midea(State s, boolean swingPressed, boolean turboPressed, List<int[]> out) {
        int mode;
        switch (s.mode) {
            case MODE_COOL: mode = 0; break;
            case MODE_DRY:  mode = 1; break;
            case MODE_HEAT: mode = 3; break;
            case MODE_FAN:  mode = 4; break;
            default:        mode = 2;
        }
        int[] b = new int[6];
        b[1] = 0xFF;
        b[2] = 0xFF;
        b[3] = (clamp(s.temp, 17, 30) - 17) | 0x40;
        b[4] = mode | clamp(s.fan, 0, 3) << 3 | (s.sleep ? 1 << 6 : 0) | (s.power ? 1 << 7 : 0);
        b[5] = 0xA1;
        int sum = 0;
        for (int i = 1; i < 6; i++) sum += reverse8(b[i]);
        b[0] = reverse8((256 - sum) & 0xFF);
        long state = 0;
        for (int i = 5; i >= 0; i--) state = state << 8 | b[i];

        out.add(mideaFrame(state));
        if (swingPressed) out.add(mideaFrame(MIDEA_SWING));
        if (turboPressed) out.add(mideaFrame(MIDEA_TURBO));
    }

    private static int[] mideaFrame(long data) {
        Pulses p = new Pulses();
        for (int k = 0; k < 2; k++) {
            p.mark(4480);
            p.space(4480);
            p.bits(data & 0xFFFFFFFFFFFFL, 48, true, 560, 1680, 560);
            p.mark(560);
            p.space(5600);
            data = ~data;
        }
        p.space(100000);
        return p.build();
    }

    // ================================================================ Coolix

    private static final int[] COOLIX_TEMP = {
            0b0000, 0b0001, 0b0011, 0b0010, 0b0110, 0b0111, 0b0101,
            0b0100, 0b1100, 0b1101, 0b1001, 0b1000, 0b1010, 0b1011};
    private static final int COOLIX_OFF = 0xB27BE0;
    private static final int COOLIX_SWING = 0xB26BE0;
    private static final int COOLIX_TURBO = 0xB5F5A2;
    private static final int COOLIX_SLEEP = 0xB2E003;

    private static void coolix(State s, boolean swingPressed, boolean turboPressed,
                               boolean sleepPressed, List<int[]> out) {
        if (!s.power) {
            out.add(coolixFrame(COOLIX_OFF));
            return;
        }
        int mode, tempCode = COOLIX_TEMP[clamp(s.temp, 17, 30) - 17];
        switch (s.mode) {
            case MODE_COOL: mode = 0; break;
            case MODE_DRY:  mode = 1; break;
            case MODE_HEAT: mode = 3; break;
            case MODE_FAN:  mode = 1; tempCode = 0b1110; break;   // «вентиляция» = осушение + спецкод
            default:        mode = 2;
        }
        boolean autoOrDry = s.mode == MODE_AUTO || s.mode == MODE_DRY;
        int fan;
        switch (s.fan) {
            case 1: fan = 0b100; break;
            case 2: fan = 0b010; break;
            case 3: fan = 0b001; break;
            default: fan = autoOrDry ? 0b000 : 0b101;
        }
        int raw = 0xB2 << 16 | (0x1F | fan << 5) << 8 | mode << 2 | tempCode << 4;
        out.add(coolixFrame(raw));
        if (swingPressed) out.add(coolixFrame(COOLIX_SWING));
        if (turboPressed) out.add(coolixFrame(COOLIX_TURBO));
        if (sleepPressed) out.add(coolixFrame(COOLIX_SLEEP));
    }

    private static int[] coolixFrame(int data) {
        Pulses p = new Pulses();
        for (int r = 0; r < 2; r++) {
            p.mark(4692);
            p.space(4416);
            for (int i = 2; i >= 0; i--) {
                int seg = (data >> (8 * i)) & 0xFF;
                p.bits(seg, 8, true, 552, 1656, 552);
                p.bits(seg ^ 0xFF, 8, true, 552, 1656, 552);
            }
            p.mark(552);
            p.space(5244);
        }
        p.space(100000);
        return p.build();
    }

    // ================================================================ Haier YR-W02

    private static int[] haier(State s) {
        int mode;
        switch (s.mode) {
            case MODE_COOL: mode = 0b001; break;
            case MODE_DRY:  mode = 0b010; break;
            case MODE_HEAT: mode = 0b100; break;
            case MODE_FAN:  mode = 0b110; break;
            default:        mode = 0b000;
        }
        int fan;
        switch (s.fan) {
            case 1: fan = 0b011; break;
            case 2: fan = 0b010; break;
            case 3: fan = 0b001; break;
            default: fan = 0b101;
        }
        boolean turboOk = mode == 0b001 || mode == 0b100;

        int[] b = new int[14];
        b[0] = 0xA6;
        b[1] = (s.swing ? 0xC : 0x0) | (clamp(s.temp, 16, 30) - 16) << 4;
        b[4] = s.power ? 1 << 6 : 0;
        b[5] = fan << 5;
        b[6] = turboOk && s.turbo ? 1 << 6 : 0;
        b[7] = mode << 5;
        b[8] = s.sleep ? 1 << 7 : 0;
        b[12] = 0b00101;                                      // кнопка «питание»
        int sum = 0;
        for (int i = 0; i < 13; i++) sum += b[i];
        b[13] = sum & 0xFF;

        Pulses p = new Pulses();
        p.mark(3000);
        p.space(3000);
        p.mark(3000);
        p.space(4300);
        p.bytes(b, 0, 14, true, 520, 1650, 650);
        p.mark(520);
        p.space(150000);
        return p.build();
    }

    // ================================================================ Chigo

    private static int[] chigo(State s, boolean jg01) {
        ChigoCodec.State c = new ChigoCodec.State();
        c.power = s.power;
        switch (s.mode) {
            case MODE_COOL: c.mode = ChigoCodec.MODE_COOL; break;
            case MODE_HEAT: c.mode = ChigoCodec.MODE_HEAT; break;
            case MODE_DRY:  c.mode = ChigoCodec.MODE_DRY; break;
            case MODE_FAN:  c.mode = ChigoCodec.MODE_FAN; break;
            default:        c.mode = ChigoCodec.MODE_AUTO;
        }
        c.temp = s.temp;
        c.fan = s.fan;
        c.swing = s.swing ? ChigoCodec.SWING_ON : ChigoCodec.SWING_FIXED;
        c.turbo = s.turbo;
        c.sleep = s.sleep;
        return ChigoCodec.build(c, jg01);
    }
}
