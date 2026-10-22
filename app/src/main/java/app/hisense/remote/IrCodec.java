package app.hisense.remote;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Кодировщик ИК-команд для кондиционеров Hisense / Kelon.
 *
 * Протокол A — "Kelon 48 бит" (кнопка питания работает как переключатель).
 * Протокол B — "Kelon/Hisense 168 бит" (пульты DG11R2-01, RCH-R0Y3 и др.,
 *              передаёт полное состояние, включая явное ВКЛ/ВЫКЛ).
 *
 * Тайминги и раскладка бит основаны на открытой библиотеке IRremoteESP8266
 * (ir_Kelon.cpp), контрольная сумма 168-битного протокола сверена
 * с реальной записью пульта.
 */
public final class IrCodec {

    private IrCodec() {}

    public static final int FREQ = 38000;

    // Тайминги, мкс
    private static final int HDR_MARK = 9000;
    private static final int HDR_SPACE = 4600;
    private static final int BIT_MARK = 560;
    private static final int ONE_SPACE = 1680;
    private static final int ZERO_SPACE = 600;
    private static final int SECTION_GAP = 8000;

    // Режимы (нумерация как в самом протоколе)
    public static final int MODE_HEAT = 0;
    public static final int MODE_AUTO = 1;
    public static final int MODE_COOL = 2;
    public static final int MODE_DRY = 3;
    public static final int MODE_FAN = 4;

    // Скорость вентилятора в приложении: 0 = авто, 1 = низкая, 2 = средняя, 3 = высокая
    public static final int FAN_AUTO = 0;
    public static final int FAN_LOW = 1;
    public static final int FAN_MED = 2;
    public static final int FAN_HIGH = 3;

    // Команды протокола B (что именно «нажали» на пульте)
    public static final int CMD_LIGHT = 0x00;
    public static final int CMD_POWER = 0x01;
    public static final int CMD_TEMP = 0x02;
    public static final int CMD_SLEEP = 0x03;
    public static final int CMD_SUPER = 0x04;
    public static final int CMD_MODE = 0x06;
    public static final int CMD_SWING = 0x07;
    public static final int CMD_IFEEL = 0x0D;
    public static final int CMD_FAN = 0x11;

    /** Состояние кондиционера, как его «помнит» пульт. */
    public static final class State {
        public boolean power = false;
        public int mode = MODE_COOL;
        public int temp = 24;
        public int fan = FAN_AUTO;
        public boolean swing = false;
        public boolean sleep = false;
        public boolean turbo = false;
        public boolean light = true;
    }

    /** Биты вентилятора: одинаковы для обоих протоколов (авто=0, мин=3, сред=2, макс=1). */
    private static int fanBits(int fan) {
        switch (fan) {
            case FAN_LOW: return 0b11;
            case FAN_MED: return 0b10;
            case FAN_HIGH: return 0b01;
            default: return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- Протокол A

    public static final int A_MIN_TEMP = 18;
    public static final int A_MAX_TEMP = 32;

    /**
     * Собирает 48-битную команду.
     * @param togglePower true — команда переключит питание (как кнопка ВКЛ/ВЫКЛ)
     * @param toggleSwing true — команда переключит качание жалюзи
     */
    public static long encodeKelon48(State s, boolean togglePower, boolean toggleSwing) {
        int mode = s.mode;
        int temp = clamp(s.temp, A_MIN_TEMP, A_MAX_TEMP);
        int fan = fanBits(s.fan);
        boolean smart = false;
        boolean superCool = false;

        if (s.turbo) {
            mode = MODE_COOL;
            temp = A_MIN_TEMP;
            fan = fanBits(FAN_HIGH);
            superCool = true;
        } else if (mode == MODE_AUTO) {
            smart = true;
        }

        int b2 = (fan & 0b11)
                | (togglePower ? 1 << 2 : 0)
                | (s.sleep ? 1 << 3 : 0)
                | (toggleSwing ? 1 << 7 : 0);
        int b3 = (mode & 0b111) | ((temp - A_MIN_TEMP) & 0xF) << 4;
        int b4 = smart ? 1 << 7 : 0;
        int b5 = superCool ? (1 << 4) | (1 << 7) : 0;

        return 0x83L
                | 0x06L << 8
                | (long) b2 << 16
                | (long) b3 << 24
                | (long) b4 << 32
                | (long) b5 << 40;
    }

    // ---------------------------------------------------------------- Протокол B

    public static final int B_MIN_TEMP = 16;
    public static final int B_MAX_TEMP = 32;

    /** Шаблон снят с реального пульта; неизвестные биты оставлены как есть. */
    private static final int[] B_TEMPLATE = {
            0x83, 0x06, 0x00, 0x00, 0x00, 0x00, 0x80,
            0x00, 0x00, 0x00, 0x00, 0x80, 0x18, 0x00,
            0x20, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00};

    /**
     * Собирает 21-байтную команду.
     * @param cmd          какая кнопка «нажата» (CMD_*)
     * @param minsOfDay    текущее время в минутах от полуночи (для часов на блоке)
     */
    public static int[] encodeKelon168(State s, int cmd, int minsOfDay) {
        int[] b = B_TEMPLATE.clone();

        int mode = s.mode;
        int temp = clamp(s.temp, B_MIN_TEMP, B_MAX_TEMP);
        int fan = s.fan;
        boolean sleep = s.sleep;

        if (s.turbo) {
            fan = FAN_HIGH;
            if (mode == MODE_HEAT) {
                temp = B_MAX_TEMP;
            } else {
                mode = MODE_COOL;
                temp = B_MIN_TEMP;
            }
        } else if (mode == MODE_AUTO) {
            fan = FAN_AUTO;
            temp = 23;
            sleep = false;
            if (cmd == CMD_MODE) cmd = CMD_IFEEL;
        }

        boolean powerPressed = cmd == CMD_POWER;

        b[2] = (fanBits(fan) & 0b11)
                | (powerPressed ? 1 << 2 : 0)
                | (sleep ? 1 << 3 : 0)
                | (s.swing ? 1 << 7 : 0);
        b[3] = (mode & 0b111) | ((temp - B_MIN_TEMP) & 0xF) << 4;
        b[5] = s.turbo ? (1 << 4) | (1 << 7) : 0;

        int hours = (minsOfDay / 60) % 24;
        int mins = minsOfDay % 60;
        b[6] = (b[6] & 0xC0) | (hours & 0x1F) | (s.light ? 0 : 1 << 5);
        b[7] = mins & 0x3F;
        b[8] = s.swing ? 1 << 6 : 0;
        b[15] = cmd & 0xFF;
        b[16] = 0; // доп. бит скорости не нужен: используем 3 скорости (мин/сред/макс)
        b[18] = (b[18] & 0x0F) | (s.power ? 1 << 4 : 0) | (0b001 << 5);

        int sum1 = 0;
        for (int i = 2; i <= 12; i++) sum1 ^= b[i];
        b[13] = sum1;
        int sum2 = 0;
        for (int i = 14; i <= 19; i++) sum2 ^= b[i];
        b[20] = sum2;
        return b;
    }

    public static int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    // ---------------------------------------------------------------- Тайминги

    private static void addBytes(List<Integer> out, int[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            int v = bytes[i];
            for (int bit = 0; bit < 8; bit++) {          // младший бит первым
                out.add(BIT_MARK);
                out.add(((v >> bit) & 1) == 1 ? ONE_SPACE : ZERO_SPACE);
            }
        }
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    public static int[] patternKelon48(long data) {
        int[] bytes = new int[6];
        for (int i = 0; i < 6; i++) bytes[i] = (int) ((data >> (8 * i)) & 0xFF);
        List<Integer> p = new ArrayList<>();
        p.add(HDR_MARK);
        p.add(HDR_SPACE);
        addBytes(p, bytes, 0, 6);
        p.add(BIT_MARK);                                  // завершающий импульс
        p.add(20000);                                     // пауза (чётная длина массива)
        return toArray(p);
    }

    public static int[] patternKelon168(int[] b) {
        List<Integer> p = new ArrayList<>();
        p.add(HDR_MARK);
        p.add(HDR_SPACE);
        addBytes(p, b, 0, 6);
        p.add(BIT_MARK);
        p.add(SECTION_GAP);
        addBytes(p, b, 6, 14);
        p.add(BIT_MARK);
        p.add(SECTION_GAP);
        addBytes(p, b, 14, 21);
        p.add(BIT_MARK);
        p.add(20000);
        return toArray(p);
    }

    public static String hex(int[] b) {
        StringBuilder sb = new StringBuilder();
        for (int v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}
