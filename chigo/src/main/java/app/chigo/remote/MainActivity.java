package app.chigo.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

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

    private static final String PREFS = "chigo_state";
    private static final int REPEAT = 2;      // полное состояние — повтор безопасен и надёжнее
    private static final int GAP_MS = 150;

    private final ChigoCodec.State st = new ChigoCodec.State();
    private boolean protocolB = false;

    private ConsumerIrManager ir;
    private final ExecutorService irThread = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private DialView dial;
    private TextView powerBtn, statusLine, protoA, protoB, sendFlash;
    private TextView[] modeChips, fanChips;
    private TextView swingChip, windChip, sleepChip, turboChip;

    private static final int[] MODES = {
            ChigoCodec.MODE_AUTO, ChigoCodec.MODE_COOL, ChigoCodec.MODE_HEAT,
            ChigoCodec.MODE_DRY, ChigoCodec.MODE_FAN};
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
                    .setMessage("Система не сообщила о наличии ИК-порта. Интерфейс работает, "
                            + "но сигнал не отправится.")
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
        st.mode = p.getInt("mode", ChigoCodec.MODE_COOL);
        st.temp = p.getInt("temp", 24);
        st.fan = p.getInt("fan", ChigoCodec.FAN_AUTO);
        st.swing = p.getInt("swing", ChigoCodec.SWING_FIXED);
        st.sleep = p.getBoolean("sleep", false);
        st.turbo = p.getBoolean("turbo", false);
        protocolB = p.getBoolean("protocolB", false);
    }

    private void save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("power", st.power)
                .putInt("mode", st.mode)
                .putInt("temp", st.temp)
                .putInt("fan", st.fan)
                .putInt("swing", st.swing)
                .putBoolean("sleep", st.sleep)
                .putBoolean("turbo", st.turbo)
                .putBoolean("protocolB", protocolB)
                .apply();
    }

    // ------------------------------------------------------------------ отправка ИК

    private void send() {
        final int[] pattern = ChigoCodec.build(st, protocolB);
        save();
        flash();
        if (ir == null || !ir.hasIrEmitter()) {
            toast("ИК-передатчик недоступен");
            return;
        }
        irThread.execute(() -> {
            try {
                for (int i = 0; i < REPEAT; i++) {
                    ir.transmit(ChigoCodec.FREQ, pattern);
                    Thread.sleep(GAP_MS);
                }
            } catch (Exception e) {
                ui.post(() -> toast("Ошибка ИК: " + e.getMessage()));
            }
        });
    }

    private void sendSetting() {
        if (!st.power) {
            toast("Сохранено. Применится при включении");
            return;
        }
        send();
    }

    // ------------------------------------------------------------------ действия

    private void onPower(View v) {
        haptic(v);
        st.power = !st.power;
        if (!st.power) st.turbo = false;
        send();
        refresh();
    }

    private void onTemp(View v, int delta) {
        haptic(v);
        int t = Math.max(ChigoCodec.minTemp(protocolB),
                Math.min(ChigoCodec.MAX_TEMP, st.temp + delta));
        if (t == st.temp && !st.turbo) return;
        st.temp = t;
        st.turbo = false;
        refresh();
        sendSetting();
    }

    private void onMode(View v, int mode) {
        haptic(v);
        st.mode = mode;
        st.turbo = false;
        refresh();
        sendSetting();
    }

    private void onFan(View v, int fan) {
        haptic(v);
        st.fan = fan;
        st.turbo = false;
        st.sleep = false;
        refresh();
        sendSetting();
    }

    private void onSwing(View v, int which) {
        haptic(v);
        st.swing = st.swing == which ? ChigoCodec.SWING_FIXED : which;
        refresh();
        sendSetting();
    }

    private void onSleep(View v) {
        haptic(v);
        st.sleep = !st.sleep;
        if (st.sleep) st.turbo = false;
        refresh();
        sendSetting();
    }

    private void onTurbo(View v) {
        haptic(v);
        st.turbo = !st.turbo;
        if (st.turbo) st.sleep = false;
        refresh();
        sendSetting();
    }

    private void onProtocol(boolean b) {
        protocolB = b;
        int min = ChigoCodec.minTemp(b);
        if (st.temp < min) st.temp = min;
        save();
        refresh();
        toast(b ? "Протокол B (пульт ZH/JG-01)" : "Протокол A (пульт ZH/LT-01)");
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Как настроить")
                .setMessage("1. Направьте верх телефона (блок камеры) прямо на кондиционер, "
                        + "лучше с расстояния 1–4 м.\n\n"
                        + "2. Нажмите кнопку питания. Если кондиционер пискнул — всё готово.\n\n"
                        + "3. Если реакции нет — внизу переключите протокол и попробуйте снова.\n\n"
                        + "Какой протокол нужен, можно понять по оригинальному пульту: на задней "
                        + "крышке или в батарейном отсеке написано ZH/LT-01 (протокол A) "
                        + "или ZH/JG-01 (протокол B).\n\n"
                        + "Каждая команда передаёт всё состояние сразу (вкл/выкл, режим, "
                        + "температура), поэтому пульт не может «сбиться». Если индикатор в "
                        + "приложении разошёлся с кондиционером — просто нажмите питание ещё раз.")
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

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("CHIGO", 26, TEXT, true);
        brand.setLetterSpacing(0.06f);
        statusLine = text("", 13, TEXT_DIM, false);
        titles.addView(brand);
        titles.addView(statusLine);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView help = pill("?");
        help.setOnClickListener(v -> showHelp());
        header.addView(help, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(header);

        FrameLayout dialBox = new FrameLayout(this);
        dial = new DialView(this);
        dialBox.addView(dial, new FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER));
        sendFlash = text("● сигнал", 12, COOL, true);
        sendFlash.setAlpha(0f);
        dialBox.addView(sendFlash, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(270));
        dl.topMargin = dp(8);
        root.addView(dialBox, dl);

        LinearLayout ctl = row();
        ctl.setGravity(Gravity.CENTER);
        TextView minus = roundBtn("−");
        minus.setOnClickListener(v -> onTemp(v, -1));
        powerBtn = roundBtn("⏻");
        powerBtn.setOnClickListener(this::onPower);
        TextView plus = roundBtn("+");
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

        root.addView(section("Жалюзи"));
        LinearLayout sw = row();
        swingChip = chip("↕ Качание");
        swingChip.setOnClickListener(v -> onSwing(v, ChigoCodec.SWING_ON));
        windChip = chip("≈ Мягкий ветер");
        windChip.setOnClickListener(v -> onSwing(v, ChigoCodec.SWING_WIND));
        sw.addView(swingChip, weighted(0, 48));
        sw.addView(windChip, weighted(1, 48));
        root.addView(sw);

        root.addView(section("Функции"));
        LinearLayout fx = row();
        sleepChip = chip("☾ Сон / Эко");
        sleepChip.setOnClickListener(this::onSleep);
        turboChip = chip("⚡ Турбо");
        turboChip.setOnClickListener(this::onTurbo);
        fx.addView(sleepChip, weighted(0, 48));
        fx.addView(turboChip, weighted(1, 48));
        root.addView(fx);

        root.addView(section("Протокол пульта"));
        LinearLayout proto = row();
        protoA = chip("A · ZH/LT-01");
        protoA.setOnClickListener(v -> onProtocol(false));
        protoB = chip("B · ZH/JG-01");
        protoB.setOnClickListener(v -> onProtocol(true));
        proto.addView(protoA, weighted(0, 48));
        proto.addView(protoB, weighted(1, 48));
        root.addView(proto);
        TextView hint = text("Не реагирует? Переключите протокол и нажмите питание ещё раз. "
                + "Модель пульта указана на его задней крышке.", 12, TEXT_DIM, false);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        root.addView(hint);

        return scroll;
    }

    private void refresh() {
        int accent = modeColor(st.mode);
        dial.set(st, protocolB, accent);

        statusLine.setText((st.power ? "Включён · " : "Выключен · ") + modeName(st.mode)
                + (protocolB ? " · протокол B" : " · протокол A"));

        bg(powerBtn, st.power ? accent : CARD, 999, st.power ? 0 : OFF_RED);
        powerBtn.setTextColor(st.power ? BG : OFF_RED);

        for (int i = 0; i < MODES.length; i++) {
            selectChip(modeChips[i], st.mode == MODES[i], modeColor(MODES[i]));
        }
        for (int i = 0; i < 4; i++) {
            selectChip(fanChips[i], st.fan == i && !st.turbo && !st.sleep, accent);
        }
        selectChip(swingChip, st.swing == ChigoCodec.SWING_ON, accent);
        selectChip(windChip, st.swing == ChigoCodec.SWING_WIND, accent);
        selectChip(sleepChip, st.sleep, accent);
        selectChip(turboChip, st.turbo, accent);
        selectChip(protoA, !protocolB, TEXT);
        selectChip(protoB, protocolB, TEXT);
    }

    // ------------------------------------------------------------------ вспомогательные

    private static int modeColor(int mode) {
        switch (mode) {
            case ChigoCodec.MODE_HEAT: return HEAT;
            case ChigoCodec.MODE_DRY: return DRY;
            case ChigoCodec.MODE_FAN: return FAN;
            case ChigoCodec.MODE_AUTO: return AUTO;
            default: return COOL;
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case ChigoCodec.MODE_HEAT: return "обогрев";
            case ChigoCodec.MODE_DRY: return "осушение";
            case ChigoCodec.MODE_FAN: return "вентиляция";
            case ChigoCodec.MODE_AUTO: return "авто";
            default: return "охлаждение";
        }
    }

    /** Режимы, в которых кондиционер держит фиксированную температуру. */
    private static boolean fixedTemp(int mode) {
        return mode == ChigoCodec.MODE_AUTO || mode == ChigoCodec.MODE_DRY;
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

    private TextView pill(String s) {
        TextView t = text(s, 18, TEXT, true);
        t.setGravity(Gravity.CENTER);
        bg(t, CARD, 999, 0);
        return t;
    }

    private TextView roundBtn(String s) {
        TextView t = text(s, 34, TEXT, false);
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
        private ChigoCodec.State s;
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
            big.setTextAlign(Paint.Align.CENTER);
            big.setTextSize(72 * d);
            big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(15 * d);
            small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void set(ChigoCodec.State s, boolean protocolB, int accent) {
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

            int min = ChigoCodec.minTemp(b);
            int t = fixedTemp(s.mode) ? 25 : s.temp;
            float frac = (t - min) / (float) (ChigoCodec.MAX_TEMP - min);
            arc.setColor(s.power ? accent : 0xFF3A4350);
            c.drawArc(rect, 135, Math.max(2f, 270 * frac), false, arc);

            big.setColor(s.power ? TEXT : TEXT_DIM);
            float cy = h / 2 + big.getTextSize() * 0.32f;
            c.drawText(t + "°", w / 2 + big.getTextSize() * 0.12f, cy, big);

            String top = s.power ? (s.turbo ? "ТУРБО" : modeName(s.mode).toUpperCase()) : "ВЫКЛЮЧЕН";
            small.setColor(s.power ? accent : TEXT_DIM);
            c.drawText(top, w / 2, h / 2 - big.getTextSize() * 0.62f, small);

            small.setColor(TEXT_DIM);
            String fanTxt = s.sleep ? "сон / эко"
                    : "вентилятор: " + (s.turbo ? "турбо" : FAN_NAMES[s.fan].toLowerCase());
            c.drawText(fanTxt, w / 2, cy + small.getTextSize() * 2.0f, small);
        }
    }
}
