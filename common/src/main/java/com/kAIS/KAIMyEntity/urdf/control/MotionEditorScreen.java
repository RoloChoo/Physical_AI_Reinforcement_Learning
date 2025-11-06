package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFJoint;
import com.kAIS.KAIMyEntity.urdf.URDFRobotModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.*;

/**
 * MotionEditorScreen (경량, 리플렉션 버전)
 * - 좌: URDF 조인트 리스트 (현재값/리밋)
 * - 우: 선택 조인트 슬라이더 (setJointPreview 호출)
 * - 상단: [Refresh] [Zero] [Close]
 *
 * renderer: getRobotModel(), setJointPreview(String,float) 를 가진 객체여야 함.
 * (리플렉션으로 호출하므로 타입 캐스팅 없이 동작)
 */
public class MotionEditorScreen extends Screen {

    // 색상
    private static final int BG = 0xFF0E0E10;
    private static final int PANEL = 0xFF1D1F24;
    private static final int TITLE = 0xFFFFD770;
    private static final int TEXT  = 0xFFE6E6E6;
    private static final int SUB   = 0xFF98B6FF;
    private static final int OK    = 0xFF90EE90;
    private static final int WARN  = 0xFFFFB070;

    private final Screen parent;
    private final Object renderer; // 리플렉션 대상

    // 레이아웃
    private int margin = 8;
    private int listTop;
    private int listHeight;
    private int colWidth;
    private int leftX, rightX;

    // 데이터
    private final List<Row> rows = new ArrayList<>();
    private String selectedJoint = null;
    private String status = "";

    // 페이지
    private int perPage = 20;
    private int page = 0;

    // 버튼/컨트롤
    private Button refreshBtn, zeroBtn, closeBtn;
    private FloatSlider slider;

    private int tickCtr = 0;
    private boolean mouseDownCache = false;

    public MotionEditorScreen(Screen parent, Object renderer) {
        super(Component.literal("URDF Motion Editor"));
        this.parent = parent;
        this.renderer = renderer;
    }

    @Override
    protected void init() {
        super.init();
        layout();
        buildButtons();
        rebuildRows();
        ensureSelection();
        buildSliderForSelection(); // 선택되었으면 슬라이더 구성
    }

    private void layout() {
        listTop = margin + 24;
        listHeight = this.height - listTop - 60;
        colWidth = (this.width - margin * 3) / 2;
        leftX = margin;
        rightX = leftX + colWidth + margin;
        perPage = Math.max(8, listHeight / 14);
    }

    private void buildButtons() {
        int y = margin;

        refreshBtn = addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            rebuildRows();
            buildSliderForSelection();
        }).bounds(leftX, y, 80, 20).build());

        zeroBtn = addRenderableWidget(Button.builder(Component.literal("Zero"), b -> {
            if (selectedJoint == null) { status = "먼저 조인트를 선택하세요."; return; }
            sendPreview(selectedJoint, 0f);
            rebuildRows();
            buildSliderForSelection();
        }).bounds(leftX + 86, y, 60, 20).build());

        closeBtn = addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(this.width - margin - 70, y, 70, 20).build());
    }

    private void ensureSelection() {
        if (selectedJoint != null) return;
        if (!rows.isEmpty()) selectedJoint = rows.get(0).name;
    }

    private void rebuildRows() {
        rows.clear();
        URDFRobotModel model = reflectGetRobotModel(renderer);
        if (model == null || model.joints == null || model.joints.isEmpty()) {
            rows.add(new Row("(no URDF model)", 0f, Float.NaN, Float.NaN, false));
            return;
        }
        for (URDFJoint j : model.joints) {
            String name = (j.name != null) ? j.name : "(unnamed)";
            float cur = j.currentPosition;
            boolean hasLim = (j.limit != null && j.limit.upper > j.limit.lower);
            float lo = hasLim ? (float) j.limit.lower : Float.NaN;
            float hi = hasLim ? (float) j.limit.upper : Float.NaN;
            rows.add(new Row(name, cur, lo, hi, j.isMovable()));
        }
        rows.sort(Comparator.comparing(r -> r.name.toLowerCase(Locale.ROOT)));
        page = 0;
    }

    private void buildSliderForSelection() {
        if (slider != null) { removeWidget(slider); slider = null; }
        if (selectedJoint == null) return;

        Row r = findRow(selectedJoint);
        float min = - (float)Math.PI, max = (float)Math.PI; // 기본 회전 범위
        if (r != null && r.hasLimit()) { min = r.lo; max = r.hi; }

        float initial = (r != null) ? r.cur : 0f;
        float fmin = (min == max ? - (float)Math.PI : min);
        float fmax = (min == max ? (float)Math.PI  : max);
        if (Float.isNaN(fmin) || Float.isNaN(fmax)) { fmin = - (float)Math.PI; fmax = (float)Math.PI; }

        // 우측 상단에 슬라이더
        slider = new FloatSlider(rightX, margin + 24, colWidth, 20,
                Component.literal("Preview [rad]"),
                fmin, fmax, initial, v -> {
                    if (selectedJoint != null) sendPreview(selectedJoint, v);
                });
        addRenderableWidget(slider);
    }

    private Row findRow(String name) {
        for (Row r : rows) if (Objects.equals(r.name, name)) return r;
        return null;
    }

    private void sendPreview(String name, float value) {
        try {
            Method m = renderer.getClass().getMethod("setJointPreview", String.class, float.class);
            m.invoke(renderer, name, value);
            status = "Preview: " + name + " = " + String.format(Locale.ROOT, "%.3f", value);
        } catch (Throwable t) {
            status = "setJointPreview 호출 실패: " + t.getMessage();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++tickCtr >= 20) { // 약 1초마다
            tickCtr = 0;
            rebuildRows();
            // 슬라이더 현재값을 갱신 (조인트가 외부에서 바뀌었을 수도)
            if (selectedJoint != null && slider != null) {
                Row r = findRow(selectedJoint);
                if (r != null) slider.setValueImmediately(r.cur);
            }
        }
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        layout();
        buildButtons();
        rebuildRows();
        buildSliderForSelection();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // F5 새로고침
        if (keyCode == 294) {
            rebuildRows();
            buildSliderForSelection();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // 배경
        g.fill(0, 0, this.width, this.height, BG);

        // 좌/우 패널
        g.fill(leftX,  listTop, leftX  + colWidth, listTop + listHeight, PANEL);
        g.fill(rightX, listTop, rightX + colWidth, listTop + listHeight, PANEL);

        // 기본 위젯
        super.render(g, mouseX, mouseY, partialTicks);

        // 상단 타이틀
        g.drawString(this.font, "URDF Joints", leftX, margin + 4, TITLE, false);
        g.drawString(this.font, "Selected Joint Preview", rightX, margin + 4, TITLE, false);

        // 리스트(좌측)
        int y = listTop + 4;
        int start = page * perPage;
        int end = Math.min(rows.size(), start + perPage);
        for (int i = start; i < end; i++) {
            Row r = rows.get(i);
            int lineColor = Objects.equals(selectedJoint, r.name) ? OK : TEXT;

            // 이름
            g.drawString(this.font, r.name, leftX + 6, y, lineColor, false);

            // 세부 (현재/리밋)
            String detail = r.hasLimit()
                    ? String.format(Locale.ROOT, "cur=%.3f  lim=[%.3f, %.3f]", r.cur, r.lo, r.hi)
                    : String.format(Locale.ROOT, "cur=%.3f", r.cur);
            g.drawString(this.font, detail, leftX + 6 + this.font.width(r.name) + 6, y, SUB, false);

            // 클릭 선택(간단 히트박스)
            if (mouseY >= y && mouseY < y + 12 &&
                mouseX >= leftX && mouseX < leftX + colWidth && clickOnce()) {
                selectedJoint = r.name;
                buildSliderForSelection();
                status = "선택: " + r.name;
            }
            y += 14;
        }

        // 페이지 인디케이터
        String pg = String.format(Locale.ROOT, "Page %d/%d", (rows.isEmpty()?0:page+1),
                Math.max(1, (int)Math.ceil(rows.size() / (double)perPage)));
        g.drawString(this.font, pg, leftX + colWidth - this.font.width(pg) - 6,
                listTop + listHeight - 12, SUB, false);

        // 상태 메시지
        if (!status.isEmpty()) {
            g.drawString(this.font, status, margin, this.height - 28, WARN, false);
        }
    }

    private boolean clickOnce() {
        boolean now = Minecraft.getInstance().mouseHandler.isLeftPressed();
        boolean ret = now && !mouseDownCache;
        mouseDownCache = now;
        return ret;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    /* ===== 행 모델 ===== */
    private static final class Row {
        final String name;
        final float cur;
        final float lo, hi;
        final boolean movable;
        Row(String n, float c, float lo, float hi, boolean mv) {
            this.name = n; this.cur = c; this.lo = lo; this.hi = hi; this.movable = mv;
        }
        boolean hasLimit() { return !Float.isNaN(lo) && !Float.isNaN(hi) && hi > lo; }
    }

    /* ===== 슬라이더 ===== */
    private static final class FloatSlider extends AbstractSliderButton {
        private final float min, max;
        private final java.util.function.Consumer<Float> onChange;
        private final String base;

        FloatSlider(int x, int y, int w, int h, Component label,
                    float min, float max, float initial, java.util.function.Consumer<Float> onChange) {
            super(x, y, w, h, label, 0.0D);
            this.min = min; this.max = max; this.onChange = onChange; this.base = label.getString();
            setValueImmediately(initial);
        }

        void setValueImmediately(float v) {
            this.value = toSlider(v);
            applyValue();
            updateMessage();
        }

        private double toSlider(float v) { return (v - min) / (double)(max - min); }
        private float fromSlider(double s) { return (float)(min + s * (max - min)); }

        @Override
        protected void updateMessage() {
            float v = fromSlider(this.value);
            this.setMessage(Component.literal(base + ": " + String.format(Locale.ROOT, "%.3f", v)));
        }
        @Override
        protected void applyValue() { onChange.accept(fromSlider(this.value)); }
    }

    /* ===== 리플렉션 ===== */
    private URDFRobotModel reflectGetRobotModel(Object rend) {
        try {
            Method m = rend.getClass().getMethod("getRobotModel");
            return (URDFRobotModel) m.invoke(rend);
        } catch (Throwable ignored) {}
        return null;
    }
}
