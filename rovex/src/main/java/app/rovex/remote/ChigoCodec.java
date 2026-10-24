package app.rovex.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * Кодировщик ИК-команд для кондиционеров Chigo.
 *
 * Протокол A — пульт ZH/LT-01 (12 байт).  Chigo, Eurom, Elgin, Sumikura, Ager и др.
 * Протокол B — пульт ZH/JG-01 (6 байт).    Chigo, Tachiair и др.
 *
 * Оба протокола передают ПОЛНОЕ состояние (вкл/выкл, режим, температура,
 * вентилятор, жалюзи) в каждой команде, поэтому повторная отправка безопасна.
 *
 * Раскладка бит и тайминги — по открытой библиотеке Arduino-HeatpumpIR
 * (ZHLT01HeatpumpIR.cpp, ZHJG01HeatpumpIR.cpp). Каждый байт данных идёт
 * вместе со своей инверсией (контроль ошибок), младший бит первым.
 */
public final class ChigoCodec {

    private ChigoCodec() {}

    public static final int FREQ = 38000;

    public static final int MODE_AUTO = 0;
    public static final int MODE_COOL = 1;
    public static final int MODE_DRY = 2;
    public static final int MODE_FAN = 3;
    public static final int MODE_HEAT = 4;

    public static final int FAN_AUTO = 0;
    public static final int FAN_LOW = 1;
    public static final int FAN_MED = 2;
    public static final int FAN_HIGH = 3;

    public static final int SWING_FIXED = 0;
    public static final int SWING_ON = 1;
    public static final int SWING_WIND = 2;   // «естественный ветер»

    public static final int MAX_TEMP = 32;
    public static int minTemp(boolean protocolB) { return protocolB ? 17 : 16; }

    public static final class State {
        public boolean power = false;
        public int mode = MODE_COOL;
        public int temp = 24;
        public int fan = FAN_AUTO;
        public int swing = SWING_FIXED;
        public boolean turbo = false;
        public boolean sleep = false;   // A: тихий/сон, B: ECO
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------ A: ZH/LT-01

    public static int[] encodeLT01(State s) {
        int[] b = {0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0x2A, 0xD5};

        int mode;
        switch (s.mode) {
            case MODE_COOL: mode = 0x20; break;
            case MODE_DRY:  mode = 0x40; break;
            case MODE_FAN:  mode = 0x60; break;
            case MODE_HEAT: mode = 0x80; break;
            default:        mode = 0x00;
        }
        int temp = clamp(s.temp, 16, MAX_TEMP);
        if (s.mode == MODE_AUTO || s.mode == MODE_DRY) temp = 25;

        int fan;
        switch (s.fan) {
            case FAN_LOW:  fan = 0x60; break;
            case FAN_MED:  fan = 0x40; break;
            case FAN_HIGH: fan = 0x20; break;
            default:       fan = 0x00;
        }
        boolean turbo = s.turbo && (s.mode == MODE_COOL || s.mode == MODE_HEAT);
        if (turbo) {
            b[3] = 0x08;
            fan = 0x00;
        } else if (s.turbo) {
            fan = 0x20;                      // вне холода/тепла турбо = макс. скорость
        }
        if (s.sleep && !turbo) fan = 0x01;   // «тихий» режим

        int swing;
        switch (s.swing) {
            case SWING_ON:   swing = 0x04; break;
            case SWING_WIND: swing = 0x00; break;
            default:         swing = 0x08;
        }
        int hSwingFixed = 0x10;

        b[7] = fan | (s.power ? 0x02 : 0x00) | swing | hSwingFixed;
        b[9] = mode | (temp - 16);
        for (int i = 1; i <= 9; i += 2) b[i - 1] = ~b[i] & 0xFF;
        return b;
    }

    // ------------------------------------------------------------ B: ZH/JG-01

    public static int[] encodeJG01(State s) {
        int[] b = {0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF};

        int mode;
        switch (s.mode) {
            case MODE_COOL: mode = 0x01; break;
            case MODE_DRY:  mode = 0x02; break;
            case MODE_FAN:  mode = 0x03; break;
            case MODE_HEAT: mode = 0x04; break;
            default:        mode = 0x00;
        }
        int temp = clamp(s.temp, 17, MAX_TEMP);
        if (s.mode == MODE_AUTO || s.mode == MODE_DRY) temp = 25;

        int fan;
        switch (s.fan) {
            case FAN_LOW:  fan = 0x60; break;
            case FAN_MED:  fan = 0x40; break;
            case FAN_HIGH: fan = 0x20; break;
            default:       fan = 0x00;
        }
        if (s.turbo) fan = 0x80;
        else if (s.sleep) fan = 0xA0;        // ECO

        int swing;
        switch (s.swing) {
            case SWING_ON:   swing = 0x08; break;
            case SWING_WIND: swing = 0x00; break;
            default:         swing = 0x10;
        }

        b[1] = fan | swing;
        b[3] = ((temp - 17) << 4 & 0xF0) | (s.power ? 0x08 : 0x00) | mode;
        b[5] = 0xD5;
        for (int i = 0; i < 6; i += 2) b[i] = ~b[i + 1] & 0xFF;
        return b;
    }

    // ------------------------------------------------------------ тайминги

    private static int[] pattern(int[] bytes, int hdrMark, int hdrSpace,
                                 int bitMark, int zero, int one) {
        List<Integer> p = new ArrayList<>();
        p.add(hdrMark);
        p.add(hdrSpace);
        for (int v : bytes) {
            for (int bit = 0; bit < 8; bit++) {
                p.add(bitMark);
                p.add(((v >> bit) & 1) == 1 ? one : zero);
            }
        }
        p.add(bitMark);
        p.add(20000);
        int[] a = new int[p.size()];
        for (int i = 0; i < a.length; i++) a[i] = p.get(i);
        return a;
    }

    public static int[] patternLT01(int[] b) { return pattern(b, 6200, 7500, 475, 525, 1650); }

    public static int[] patternJG01(int[] b) { return pattern(b, 6550, 7755, 560, 1530, 3750); }

    public static int[] build(State s, boolean protocolB) {
        return protocolB ? patternJG01(encodeJG01(s)) : patternLT01(encodeLT01(s));
    }

    public static String hex(int[] b) {
        StringBuilder sb = new StringBuilder();
        for (int v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}
