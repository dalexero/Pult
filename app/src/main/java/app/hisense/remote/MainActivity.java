package app.hisense.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // Палитра
    private static final int BG = 0xFF0F1318;
    private static final int CARD = 0xFF1A2028;
    private static final int CARD_HI = 0xFF252D38;
    private static final int TEXT = 0xFFF2F5F8;
    private static final int TEXT_DIM = 0xFF8A96A6;
    private static final int COOL = 0xFF3DA9FC;
    private static final int HEAT = 0xFFFF8A3D;
    private static final int DRY = 0xFF2EC4B6;
    private static final int FAN = 0xFFA78BFA;
    private static final int AUTO = 0xFF7BD389;
    private static final int OFF_RED = 0xFFE5484D;

    private static final String PREFS = "ac_state";

    private final IrCodec.State st = new IrCodec.State();
    private boolean protocolB = false;
    private boolean precisePower = true;

    private ConsumerIrManager ir;
    private final ExecutorService irThread = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private DialView dial;
    private TextView powerBtn, statusLine, protoA, protoB, sendFlash;
    private TextView[] modeChips, fanChips;
    private TextView swingChip, sleepChip, turboChip, lightChip;
    private LinearLayout lightWrap, preciseWrap;
    private TextView preciseChip;

    private static final int[] MODES = {
            IrCodec.MODE_AUTO, IrCodec.MODE_COOL, IrCodec.MODE_HEAT, IrCodec.MODE_DRY, IrCodec.MODE_FAN};
    private static final String[] MODE_ICONS = {"A", "❄", "☀", "💧", "🌀"};
    private static final String[] MODE_NAMES = {"Авто", "Холод", "Тепло", "Осуш.", "Вент."};
    private static final String[] FAN_NAMES = {"Авто", "Низк.", "Сред.", "Выс."};

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        load();
        setContentView(buildUi());
        refresh();

        if (ir == null || !ir.hasIrEmitter()) {
            new AlertDialog.Builder(this)
                    .setTitle("ИК-передатчик не найден")
                    .setMessage("Система не сообщила о наличии ИК-порта. На iQOO Neo 10 он есть "
                            + "(в блоке камеры) — попробуйте перезапустить приложение. "
                            + "Интерфейс работает, но сигнал не отправится.")
                    .setPositiveButton("Понятно", null)
                    .show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        irThread.shutdown();
    }

    private void load() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        st.power = p.getBoolean("power", false);
        st.mode = p.getInt("mode", IrCodec.MODE_COOL);
        st.temp = p.getInt("temp", 24);
        st.fan = p.getInt("fan", IrCodec.FAN_AUTO);
        st.swing = p.getBoolean("swing", false);
        st.sleep = p.getBoolean("sleep", false);
        st.turbo = p.getBoolean("turbo", false);
        st.light = p.getBoolean("light", true);
        protocolB = p.getBoolean("protocolB", false);
        precisePower = p.getBoolean("precisePower", true);
    }

    private void save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("power", st.power)
                .putInt("mode", st.mode)
                .putInt("temp", st.temp)
                .putInt("fan", st.fan)
                .putBoolean("swing", st.swing)
                .putBoolean("sleep", st.sleep)
                .putBoolean("turbo", st.turbo)
                .putBoolean("light", st.light)
                .putBoolean("protocolB", protocolB)
                .putBoolean("precisePower", precisePower)
                .apply();
    }

    // ------------------------------------------------------------------ отправка ИК

    /** Пачка ИК-кадров: каждый кадр + сколько раз его повторить + пауза после. */
    private static final class Frame {
        final int[] pattern; final int times; final int pauseMs;
        Frame(int[] pattern, int times, int pauseMs) {
            this.pattern = pattern; this.times = times; this.pauseMs = pauseMs;
        }
    }

    private static final int REPEAT = 2;      // одинаковые команды шлём дважды — надёжнее
    private static final int GAP_MS = 150;

    private Frame frameA(IrCodec.State s, boolean togglePower, boolean toggleSwing, int times, int pause) {
        return new Frame(IrCodec.patternKelon48(IrCodec.encodeKelon48(s, togglePower, toggleSwing)), times, pause);
    }

    private void transmit(List<Frame> frames) {
        save();
        flash();
        if (ir == null || !ir.hasIrEmitter()) {
            toast("ИК-передатчик недоступен");
            return;
        }
        irThread.execute(() -> {
            try {
                for (Frame f : frames) {
                    for (int i = 0; i < f.times; i++) {
                        ir.transmit(IrCodec.FREQ, f.pattern);
                        Thread.sleep(i == f.times - 1 ? f.pauseMs : GAP_MS);
                    }
                }
            } catch (Exception e) {
                ui.post(() -> toast("Ошибка ИК: " + e.getMessage()));
            }
        });
    }

    /** Отправляет текущее состояние. cmd — для протокола B, toggleSwing — для A. */
    private void send(int cmd, boolean toggleSwing) {
        List<Frame> frames = new ArrayList<>();
        if (protocolB) {
            frames.add(new Frame(IrCodec.patternKelon168(
                    IrCodec.encodeKelon168(st, cmd, IrCodec.nowMinutes())), REPEAT, GAP_MS));
        } else if (toggleSwing) {
            // переключатель: повтор вернул бы жалюзи обратно, поэтому один раз
            frames.add(frameA(st, false, true, 1, GAP_MS));
        } else {
            frames.add(frameA(st, false, false, REPEAT, GAP_MS));
        }
        transmit(frames);
    }

    /** Отправить изменение настройки: если кондиционер выключен — только запомнить. */
    private void sendSetting(int cmd, boolean toggleSwing) {
        if (!st.power) {
            toast("Сохранено. Применится при включении");
            return;
        }
        send(cmd, toggleSwing);
    }

    /**
     * Питание для протокола A.
     * Точный режим: команда «авто» всегда включает кондиционер, поэтому сначала
     * гарантированно включаем его, возвращаем нужный режим и, если надо выключить,
     * только потом шлём переключатель питания. Результат не зависит от того,
     * дошёл ли прошлый сигнал.
     */
    private void sendPowerA(boolean on) {
        List<Frame> frames = new ArrayList<>();
        if (!precisePower) {
            frames.add(frameA(st, true, false, 1, GAP_MS));
        } else {
            IrCodec.State smart = st.copy();
            smart.mode = IrCodec.MODE_AUTO;
            smart.turbo = false;
            smart.dryGrade = 2;          // сдвиг +2 — чтобы компрессор не стартовал зря
            frames.add(frameA(smart, false, false, REPEAT, 400));
            IrCodec.State actual = st.copy();
            actual.power = true;
            frames.add(frameA(actual, false, false, REPEAT, 600));
            if (!on) frames.add(frameA(actual, true, false, 1, GAP_MS));
        }
        transmit(frames);
    }

    // ------------------------------------------------------------------ действия

    private void onPower(View v) {
        haptic(v);
        st.power = !st.power;
        if (!st.power) st.turbo = false;
        if (protocolB) send(IrCodec.CMD_POWER, false);
        else sendPowerA(st.power);
        refresh();
    }

    private void onTemp(View v, int delta) {
        haptic(v);
        int min = protocolB ? IrCodec.B_MIN_TEMP : IrCodec.A_MIN_TEMP;
        int max = protocolB ? IrCodec.B_MAX_TEMP : IrCodec.A_MAX_TEMP;
        int t = Math.max(min, Math.min(max, st.temp + delta));
        if (t == st.temp && !st.turbo) return;
        st.temp = t;
        st.turbo = false;
        refresh();
        sendSetting(IrCodec.CMD_TEMP, false);
    }

    private void onMode(View v, int mode) {
        haptic(v);
        st.mode = mode;
        st.turbo = false;
        refresh();
        sendSetting(IrCodec.CMD_MODE, false);
    }

    private void onFan(View v, int fan) {
        haptic(v);
        st.fan = fan;
        st.turbo = false;
        refresh();
        sendSetting(IrCodec.CMD_FAN, false);
    }

    private void onSwing(View v) {
        haptic(v);
        st.swing = !st.swing;
        refresh();
        sendSetting(IrCodec.CMD_SWING, true);
    }

    private void onSleep(View v) {
        haptic(v);
        st.sleep = !st.sleep;
        refresh();
        sendSetting(IrCodec.CMD_SLEEP, false);
    }

    private void onTurbo(View v) {
        haptic(v);
        st.turbo = !st.turbo;
        refresh();
        sendSetting(IrCodec.CMD_SUPER, false);
    }

    private void onLight(View v) {
        haptic(v);
        st.light = !st.light;
        refresh();
        sendSetting(IrCodec.CMD_LIGHT, false);
    }

    private void onProtocol(boolean b) {
        protocolB = b;
        int min = b ? IrCodec.B_MIN_TEMP : IrCodec.A_MIN_TEMP;
        if (st.temp < min) st.temp = min;
        save();
        refresh();
        toast(b ? "Протокол B (168 бит)" : "Протокол A (48 бит)");
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Как настроить")
                .setMessage("1. Направьте верх телефона (блок камеры) прямо на кондиционер, "
                        + "лучше с расстояния 1–4 м. ИК-луч у телефона узкий — угол важен.\n\n"
                        + "2. Нажмите кнопку питания. Если кондиционер пискнул — всё готово.\n\n"
                        + "3. Если реакции нет — внизу переключите протокол и попробуйте снова.\n\n"
                        + "«Точное вкл/выкл» (протокол A): кондиционер может пискнуть 2–3 раза "
                        + "и на секунду переключиться в «авто» — так и задумано: зато кнопки "
                        + "«вкл» и «выкл» срабатывают точно, даже если прошлый сигнал не дошёл.\n\n"
                        + "Без точного режима питание — переключатель. Если индикатор разошёлся "
                        + "с реальностью, удерживайте кнопку питания — он исправится без сигнала.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                top = i.top;
                bottom = i.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(18), top + dp(12), dp(18), bottom + dp(24));
            return insets;
        });

        // --- шапка
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("Hisense", 26, TEXT, true);
        brand.setLetterSpacing(0.02f);
        statusLine = text("", 13, TEXT_DIM, false);
        titles.addView(brand);
        titles.addView(statusLine);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView help = pill("?", CARD, TEXT);
        help.setOnClickListener(v -> showHelp());
        header.addView(help, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        // --- круговой индикатор температуры
        FrameLayout dialBox = new FrameLayout(this);
        dial = new DialView(this);
        dialBox.addView(dial, new FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER));
        sendFlash = text("● сигнал", 12, COOL, true);
        sendFlash.setAlpha(0f);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dialBox.addView(sendFlash, fl);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(270));
        dl.topMargin = dp(8);
        root.addView(dialBox, dl);

        // --- − ПИТАНИЕ +
        LinearLayout ctl = row();
        ctl.setGravity(Gravity.CENTER);
        TextView minus = roundBtn("−", 34);
        minus.setOnClickListener(v -> onTemp(v, -1));
        powerBtn = roundBtn("⏻", 34);
        powerBtn.setOnClickListener(this::onPower);
        powerBtn.setOnLongClickListener(v -> {
            st.power = !st.power;
            haptic(v);
            refresh();
            save();
            toast("Индикатор питания исправлен (сигнал не отправлялся)");
            return true;
        });
        TextView plus = roundBtn("+", 34);
        plus.setOnClickListener(v -> onTemp(v, +1));
        ctl.addView(minus, sq(68));
        LinearLayout.LayoutParams pl = sq(92);
        pl.leftMargin = dp(28);
        pl.rightMargin = dp(28);
        ctl.addView(powerBtn, pl);
        ctl.addView(plus, sq(68));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(6);
        root.addView(ctl, cl);

        // --- режим
        root.addView(section("Режим"));
        LinearLayout modes = row();
        modeChips = new TextView[MODES.length];
        for (int i = 0; i < MODES.length; i++) {
            final int m = MODES[i];
            TextView c = chip(MODE_ICONS[i] + "\n" + MODE_NAMES[i]);
            c.setOnClickListener(v -> onMode(v, m));
            modeChips[i] = c;
            modes.addView(c, weighted(i, 64));
        }
        root.addView(modes);

        // --- вентилятор
        root.addView(section("Вентилятор"));
        LinearLayout fans = row();
        fanChips = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int f = i;
            TextView c = chip(FAN_NAMES[i]);
            c.setOnClickListener(v -> onFan(v, f));
            fanChips[i] = c;
            fans.addView(c, weighted(i, 48));
        }
        root.addView(fans);

        // --- функции
        root.addView(section("Функции"));
        LinearLayout fx = row();
        swingChip = chip("↕ Жалюзи");
        swingChip.setOnClickListener(this::onSwing);
        sleepChip = chip("☾ Сон");
        sleepChip.setOnClickListener(this::onSleep);
        turboChip = chip("⚡ Турбо");
        turboChip.setOnClickListener(this::onTurbo);
        fx.addView(swingChip, weighted(0, 48));
        fx.addView(sleepChip, weighted(1, 48));
        fx.addView(turboChip, weighted(2, 48));
        root.addView(fx);

        lightWrap = row();
        lightChip = chip("💡 Подсветка дисплея");
        lightChip.setOnClickListener(this::onLight);
        lightWrap.addView(lightChip, weighted(0, 48));
        LinearLayout.LayoutParams lw = new LinearLayout.LayoutParams(-1, -2);
        lw.topMargin = dp(8);
        root.addView(lightWrap, lw);

        // --- протокол
        root.addView(section("Протокол пульта"));
        LinearLayout proto = row();
        protoA = chip("A · 48 бит");
        protoA.setOnClickListener(v -> onProtocol(false));
        protoB = chip("B · 168 бит");
        protoB.setOnClickListener(v -> onProtocol(true));
        proto.addView(protoA, weighted(0, 48));
        proto.addView(protoB, weighted(1, 48));
        root.addView(proto);
        preciseWrap = row();
        preciseChip = chip("Точное вкл/выкл");
        preciseChip.setOnClickListener(v -> {
            haptic(v);
            precisePower = !precisePower;
            save();
            refresh();
        });
        preciseWrap.addView(preciseChip, weighted(0, 48));
        LinearLayout.LayoutParams pw = new LinearLayout.LayoutParams(-1, -2);
        pw.topMargin = dp(8);
        root.addView(preciseWrap, pw);
        TextView hint = text("Не реагирует? Переключите протокол и нажмите питание ещё раз.",
                12, TEXT_DIM, false);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        root.addView(hint);

        return scroll;
    }

    private void refresh() {
        int accent = modeColor(st.mode);
        if (st.turbo) accent = st.mode == IrCodec.MODE_HEAT ? HEAT : COOL;

        dial.set(st, protocolB, accent);

        statusLine.setText((st.power ? "Включён · " : "Выключен · ") + modeName(st.mode)
                + (protocolB ? " · протокол B" : " · протокол A"));

        bg(powerBtn, st.power ? accent : CARD, 999, st.power ? 0 : OFF_RED);
        powerBtn.setTextColor(st.power ? BG : OFF_RED);

        for (int i = 0; i < MODES.length; i++) {
            selectChip(modeChips[i], st.mode == MODES[i], modeColor(MODES[i]));
        }
        for (int i = 0; i < 4; i++) selectChip(fanChips[i], st.fan == i && !st.turbo, accent);
        selectChip(swingChip, st.swing, accent);
        selectChip(sleepChip, st.sleep, accent);
        selectChip(turboChip, st.turbo, accent);
        selectChip(lightChip, st.light, accent);
        lightWrap.setVisibility(protocolB ? View.VISIBLE : View.GONE);
        preciseWrap.setVisibility(protocolB ? View.GONE : View.VISIBLE);
        selectChip(preciseChip, precisePower, accent);
        preciseChip.setText(precisePower ? "✓ Точное вкл/выкл" : "Точное вкл/выкл: выкл.");
        selectChip(protoA, !protocolB, TEXT);
        selectChip(protoB, protocolB, TEXT);
    }

    // ------------------------------------------------------------------ вспомогательные

    private static int modeColor(int mode) {
        switch (mode) {
            case IrCodec.MODE_HEAT: return HEAT;
            case IrCodec.MODE_DRY: return DRY;
            case IrCodec.MODE_FAN: return FAN;
            case IrCodec.MODE_AUTO: return AUTO;
            default: return COOL;
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case IrCodec.MODE_HEAT: return "обогрев";
            case IrCodec.MODE_DRY: return "осушение";
            case IrCodec.MODE_FAN: return "вентиляция";
            case IrCodec.MODE_AUTO: return "авто";
            default: return "охлаждение";
        }
    }

    private void flash() {
        sendFlash.animate().cancel();
        sendFlash.setAlpha(1f);
        sendFlash.animate().alpha(0f).setStartDelay(350).setDuration(500).start();
    }

    private void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s.toUpperCase(), 12, TEXT_DIM, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    private TextView pill(String s, int bgColor, int fg) {
        TextView t = text(s, 18, fg, true);
        t.setGravity(Gravity.CENTER);
        bg(t, bgColor, 999, 0);
        return t;
    }

    private TextView roundBtn(String s, float sp) {
        TextView t = text(s, sp, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        bg(t, CARD, 999, 0);
        t.setClickable(true);
        return t;
    }

    private TextView chip(String s) {
        TextView t = text(s, 14, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        t.setClickable(true);
        return t;
    }

    private void selectChip(TextView t, boolean on, int accent) {
        bg(t, on ? accent : CARD, 16, 0);
        t.setTextColor(on ? BG : TEXT);
    }

    private void bg(View v, int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(2), strokeColor);
        v.setBackground(g);
    }

    private LinearLayout.LayoutParams sq(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    private LinearLayout.LayoutParams weighted(int index, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(heightDp), 1);
        if (index > 0) p.leftMargin = dp(8);
        return p;
    }

    // ------------------------------------------------------------------ круговой индикатор

    private static final class DialView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private IrCodec.State s;
        private boolean b;
        private int accent = COOL;

        DialView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(14 * d);
            track.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(CARD_HI);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(14 * d);
            arc.setStrokeCap(Paint.Cap.ROUND);
            fill.setColor(CARD);
            big.setColor(TEXT);
            big.setTextAlign(Paint.Align.CENTER);
            big.setTextSize(72 * d);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            small.setColor(TEXT_DIM);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(15 * d);
            small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void set(IrCodec.State s, boolean protocolB, int accent) {
            this.s = s;
            this.b = protocolB;
            this.accent = accent;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (s == null) return;
            float w = getWidth(), h = getHeight();
            float pad = track.getStrokeWidth();
            rect.set(pad, pad, w - pad, h - pad);
            c.drawCircle(w / 2, h / 2, w / 2 - pad * 1.8f, fill);
            c.drawArc(rect, 135, 270, false, track);

            int min = b ? IrCodec.B_MIN_TEMP : IrCodec.A_MIN_TEMP;
            int max = b ? IrCodec.B_MAX_TEMP : IrCodec.A_MAX_TEMP;
            int t = s.turbo ? (s.mode == IrCodec.MODE_HEAT ? max : min) : s.temp;
            float frac = (t - min) / (float) (max - min);
            arc.setColor(s.power ? accent : 0xFF3A4350);
            c.drawArc(rect, 135, Math.max(2f, 270 * frac), false, arc);

            big.setColor(s.power ? TEXT : TEXT_DIM);
            float cy = h / 2 + big.getTextSize() * 0.32f;
            c.drawText(t + "°", w / 2 + big.getTextSize() * 0.12f, cy, big);

            String top = s.power ? (s.turbo ? "ТУРБО" : modeName(s.mode).toUpperCase()) : "ВЫКЛЮЧЕН";
            small.setColor(s.power ? accent : TEXT_DIM);
            c.drawText(top, w / 2, h / 2 - big.getTextSize() * 0.62f, small);

            small.setColor(TEXT_DIM);
            String fanTxt = "вентилятор: " + (s.turbo ? "макс." : FAN_NAMES[s.fan].toLowerCase());
            c.drawText(fanTxt, w / 2, cy + small.getTextSize() * 2.0f, small);
        }
    }
}
