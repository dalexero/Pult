package app.rovex.remote;

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

import java.util.List;
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

    private static final String PREFS = "rovex_state";
    private static final int GAP_MS = 150;

    private final RovexCodec.State st = new RovexCodec.State();
    private int protocol = -1;              // -1 — ещё не подобран

    private ConsumerIrManager ir;
    private final ExecutorService irThread = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private DialView dial;
    private TextView powerBtn, statusLine, protoBtn, sendFlash;
    private TextView[] modeChips, fanChips;
    private TextView swingChip, sleepChip, turboChip;

    private static final int[] MODES = {
            RovexCodec.MODE_AUTO, RovexCodec.MODE_COOL, RovexCodec.MODE_HEAT,
            RovexCodec.MODE_DRY, RovexCodec.MODE_FAN};
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
        } else if (protocol < 0) {
            ui.post(this::startSearch);
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
        st.mode = p.getInt("mode", RovexCodec.MODE_COOL);
        st.temp = p.getInt("temp", 24);
        st.fan = p.getInt("fan", RovexCodec.FAN_AUTO);
        st.swing = p.getBoolean("swing", false);
        st.sleep = p.getBoolean("sleep", false);
        st.turbo = p.getBoolean("turbo", false);
        protocol = p.getInt("protocol", -1);
        if (protocol >= RovexCodec.COUNT) protocol = -1;
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
                .putInt("protocol", protocol)
                .apply();
    }

    private int proto() {
        return protocol < 0 ? RovexCodec.P_GREE : protocol;
    }

    // ------------------------------------------------------------------ отправка ИК

    private void transmit(int p, RovexCodec.State s, boolean swingPressed, boolean turboPressed,
                          boolean sleepPressed) {
        final List<int[]> frames = RovexCodec.frames(p, s, swingPressed, turboPressed, sleepPressed);
        // Протоколы с полным состоянием безопасно дублировать — так надёжнее.
        // У Midea/Coolix есть кнопки-переключатели и встроенный повтор: шлём один раз.
        final int times = RovexCodec.usesToggles(p) ? 1 : 2;
        flash();
        if (ir == null || !ir.hasIrEmitter()) {
            toast("ИК-передатчик недоступен");
            return;
        }
        irThread.execute(() -> {
            try {
                for (int[] f : frames) {
                    for (int i = 0; i < times; i++) {
                        ir.transmit(RovexCodec.FREQ, f);
                        Thread.sleep(GAP_MS);
                    }
                    Thread.sleep(GAP_MS);
                }
            } catch (Exception e) {
                ui.post(() -> toast("Ошибка ИК: " + e.getMessage()));
            }
        });
    }

    private void send(boolean swingPressed, boolean turboPressed, boolean sleepPressed) {
        save();
        transmit(proto(), st, swingPressed, turboPressed, sleepPressed);
    }

    private void sendSetting(boolean swingPressed, boolean turboPressed, boolean sleepPressed) {
        if (!st.power) {
            save();
            toast("Сохранено. Применится при включении");
            return;
        }
        send(swingPressed, turboPressed, sleepPressed);
    }

    // ------------------------------------------------------------------ автоподбор

    private void startSearch() {
        new AlertDialog.Builder(this)
                .setTitle("Подбор протокола")
                .setMessage("Rovex выпускают разные заводы (Gree, AUX, Midea, Haier, Chigo), "
                        + "поэтому сначала подберём сигнал.\n\n"
                        + "Выключите кондиционер родным пультом или кнопкой на блоке. "
                        + "Направьте верх телефона на кондиционер с 1–3 м.\n\n"
                        + "Приложение по очереди отправит команду «включить» в "
                        + RovexCodec.COUNT + " вариантах. После каждой скажите, включился ли кондиционер.")
                .setPositiveButton("Начать", (d, w) -> searchStep(0))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void searchStep(int i) {
        if (i >= RovexCodec.COUNT) {
            new AlertDialog.Builder(this)
                    .setTitle("Не подошёл ни один")
                    .setMessage("Попробуйте ещё раз ближе к кондиционеру и направляя телефон точнее. "
                            + "Если снова не получится — посмотрите модель родного пульта "
                            + "(на задней крышке или в батарейном отсеке).")
                    .setPositiveButton("Ещё раз", (d, w) -> searchStep(0))
                    .setNegativeButton("Закрыть", null)
                    .show();
            return;
        }
        RovexCodec.State test = new RovexCodec.State();
        test.power = true;
        test.mode = st.mode;
        test.temp = st.temp;
        test.fan = st.fan;
        transmit(i, test, false, false, false);

        new AlertDialog.Builder(this)
                .setTitle("Вариант " + (i + 1) + " из " + RovexCodec.COUNT)
                .setMessage(RovexCodec.NAMES[i] + "\n" + RovexCodec.REMOTES[i]
                        + "\n\nКондиционер пискнул или включился?")
                .setCancelable(false)
                .setPositiveButton("Да!", (d, w) -> {
                    protocol = i;
                    st.power = true;
                    st.swing = false;
                    st.turbo = false;
                    st.sleep = false;
                    save();
                    refresh();
                    toast("Готово: " + RovexCodec.NAMES[i]);
                })
                .setNeutralButton("Повторить", (d, w) -> searchStep(i))
                .setNegativeButton("Нет, дальше", (d, w) -> searchStep(i + 1))
                .show();
    }

    private void chooseProtocol() {
        String[] items = new String[RovexCodec.COUNT + 1];
        items[0] = "🔍 Автоподбор";
        for (int i = 0; i < RovexCodec.COUNT; i++) {
            items[i + 1] = (i == protocol ? "✓ " : "") + RovexCodec.NAMES[i] + " — " + RovexCodec.REMOTES[i];
        }
        new AlertDialog.Builder(this)
                .setTitle("Протокол пульта")
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        startSearch();
                    } else {
                        protocol = w - 1;
                        int min = RovexCodec.minTemp(protocol), max = RovexCodec.maxTemp(protocol);
                        st.temp = Math.max(min, Math.min(max, st.temp));
                        save();
                        refresh();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ действия

    private void onPower(View v) {
        haptic(v);
        st.power = !st.power;
        if (!st.power) st.turbo = false;
        send(false, false, false);
        refresh();
    }

    private void onTemp(View v, int delta) {
        haptic(v);
        int p = proto();
        int t = Math.max(RovexCodec.minTemp(p), Math.min(RovexCodec.maxTemp(p), st.temp + delta));
        if (t == st.temp) return;
        st.temp = t;
        refresh();
        sendSetting(false, false, false);
    }

    private void onMode(View v, int mode) {
        haptic(v);
        st.mode = mode;
        if (!RovexCodec.usesToggles(proto())) st.turbo = false;
        refresh();
        sendSetting(false, false, false);
    }

    private void onFan(View v, int fan) {
        haptic(v);
        st.fan = fan;
        refresh();
        sendSetting(false, false, false);
    }

    private void onSwing(View v) {
        haptic(v);
        st.swing = !st.swing;
        refresh();
        sendSetting(true, false, false);
    }

    private void onSleep(View v) {
        haptic(v);
        st.sleep = !st.sleep;
        refresh();
        sendSetting(false, false, proto() == RovexCodec.P_COOLIX);
    }

    private void onTurbo(View v) {
        haptic(v);
        st.turbo = !st.turbo;
        refresh();
        sendSetting(false, true, false);
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Как пользоваться")
                .setMessage("Направьте верх телефона (блок камеры) прямо на кондиционер с 1–4 м.\n\n"
                        + "Если кондиционер не реагирует — нажмите «Протокол» внизу и запустите "
                        + "автоподбор, либо выберите вариант по модели родного пульта.\n\n"
                        + "У протоколов Midea и Coolix жалюзи, турбо и сон работают как на родном "
                        + "пульте — переключателем. Если индикатор в приложении разошёлся "
                        + "с кондиционером, нажмите кнопку ещё раз.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Автоподбор", (d, w) -> startSearch())
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
        TextView brand = text("ROVEX", 26, TEXT, true);
        brand.setLetterSpacing(0.08f);
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

        root.addView(section("Протокол пульта"));
        protoBtn = chip("");
        protoBtn.setOnClickListener(v -> chooseProtocol());
        root.addView(protoBtn, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView hint = text("Нажмите, чтобы запустить автоподбор или выбрать вариант вручную.",
                12, TEXT_DIM, false);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        root.addView(hint);

        return scroll;
    }

    private void refresh() {
        int accent = modeColor(st.mode);
        dial.set(st, proto(), accent);

        statusLine.setText((st.power ? "Включён · " : "Выключен · ") + modeName(st.mode));

        bg(powerBtn, st.power ? accent : CARD, 999, st.power ? 0 : OFF_RED);
        powerBtn.setTextColor(st.power ? BG : OFF_RED);

        for (int i = 0; i < MODES.length; i++) {
            selectChip(modeChips[i], st.mode == MODES[i], modeColor(MODES[i]));
        }
        for (int i = 0; i < 4; i++) selectChip(fanChips[i], st.fan == i, accent);
        selectChip(swingChip, st.swing, accent);
        selectChip(sleepChip, st.sleep, accent);
        selectChip(turboChip, st.turbo, accent);

        protoBtn.setText(protocol < 0 ? "🔍 Не подобран — нажмите для автоподбора"
                : "📡 " + RovexCodec.NAMES[protocol] + "   (сменить)");
        selectChip(protoBtn, false, accent);
    }

    // ------------------------------------------------------------------ вспомогательные

    private static int modeColor(int mode) {
        switch (mode) {
            case RovexCodec.MODE_HEAT: return HEAT;
            case RovexCodec.MODE_DRY: return DRY;
            case RovexCodec.MODE_FAN: return FAN;
            case RovexCodec.MODE_AUTO: return AUTO;
            default: return COOL;
        }
    }

    private static String modeName(int mode) {
        switch (mode) {
            case RovexCodec.MODE_HEAT: return "обогрев";
            case RovexCodec.MODE_DRY: return "осушение";
            case RovexCodec.MODE_FAN: return "вентиляция";
            case RovexCodec.MODE_AUTO: return "авто";
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
        private RovexCodec.State s;
        private int p;
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

        void set(RovexCodec.State s, int protocol, int accent) {
            this.s = s;
            this.p = protocol;
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

            int min = RovexCodec.minTemp(p), max = RovexCodec.maxTemp(p);
            int t = Math.max(min, Math.min(max, s.temp));
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
            String fanTxt = "вентилятор: " + FAN_NAMES[s.fan].toLowerCase() + (s.sleep ? " · сон" : "");
            c.drawText(fanTxt, w / 2, cy + small.getTextSize() * 2.0f, small);
        }
    }
}
