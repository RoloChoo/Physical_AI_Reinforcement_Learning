package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.urdf.vmd.VMDLoader;
import com.kAIS.KAIMyEntity.webots.WebotsController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MuJoCo 스타일 RL Control GUI
 * - 기존 VMDPlayer 유지
 * - RL 환경 모니터링 추가
 * - 관절 제어 패널 추가
 * - 센서 시각화 추가
 */
public final class MotionEditorScreen {
    private static final Logger logger = LogManager.getLogger();

    private MotionEditorScreen() {}

    public static void open(URDFModelOpenGLWithSTL renderer) {
        Minecraft.getInstance().setScreen(
            new RLControlGUI(Minecraft.getInstance().screen, renderer));
    }

    /**
     * 매 틱마다 호출 - VMD 재생 및 Webots 전송
     */
    public static void tick(URDFModelOpenGLWithSTL renderer) {
        VMDPlayer.getInstance().tick(renderer, 1f / 20f);
    }

    /* ======================== RLControlGUI ======================== */
    
    public static class RLControlGUI extends Screen {
        private static final Logger logger = LogManager.getLogger();
        
        // 색상 (MuJoCo 다크 테마)
        private static final int BG_PANEL = 0xE0202020;
        private static final int BG_SECTION = 0xE0303030;
        private static final int BG_HEADER = 0xE0404040;
        private static final int BORDER = 0xFF505050;
        private static final int TEXT = 0xFFE0E0E0;
        private static final int TEXT_DIM = 0xFF909090;
        private static final int ACCENT = 0xFF4CAF50;
        private static final int WARNING = 0xFFFF9800;
        private static final int ERROR = 0xFFF44336;
        private static final int HIGHLIGHT = 0xFF2196F3;
        
        // 레이아웃
        private static final int PANEL_WIDTH = 260;
        private static final int PANEL_MARGIN = 10;
        private static final int PADDING = 8;
        private static final int LINE_HEIGHT = 16;
        private static final int SECTION_GAP = 5;
        
        private final Screen parent;
        private final URDFModelOpenGLWithSTL renderer;
        
        // 상태
        private SimState simState = SimState.STOPPED;
        private float simTime = 0f;
        private float simSpeed = 1.0f;
        private int stepCount = 0;
        private float lastReward = 0f;
        private float episodeReward = 0f;
        
        // 패널 접힘 상태
        private boolean simPanelOpen = true;
        private boolean rlPanelOpen = true;
        private boolean jointPanelOpen = true;
        private boolean sensorPanelOpen = true;
        private boolean renderPanelOpen = false;
        
        // 렌더링 옵션
        private boolean showContactPoints = true;
        private boolean showCOM = true;
        private boolean showJointAxes = false;
        
        // 관절 데이터
        private final LinkedHashMap<String, JointData> joints = new LinkedHashMap<>();
        private String selectedJoint = null;
        private boolean draggingSlider = false;
        
        // 로그
        private final List<LogEntry> logs = new ArrayList<>();
        private static final int MAX_LOGS = 50;
        
        // UI 컴포넌트
        private Button playBtn, pauseBtn, resetBtn, stepBtn;
        private Button serverBtn;
        private EditBox portInput;
        private Button loadVmdBtn;
        
        // 서버 상태 (임시)
        private boolean serverRunning = false;
        private boolean pythonConnected = false;
        
        public RLControlGUI(Screen parent, URDFModelOpenGLWithSTL renderer) {
            super(Component.literal("RL Control"));
            this.parent = parent;
            this.renderer = renderer;
            loadJointData();
            log(LogLevel.INFO, "RL Control Panel opened");
        }
        
        // 패널 X 위치를 동적으로 계산
        private int getPanelX() {
            return this.width - PANEL_WIDTH - PANEL_MARGIN;
        }
        
        private void loadJointData() {
            joints.clear();
            var robot = renderer.getRobotModel();
            if (robot == null || robot.joints == null) {
                log(LogLevel.WARN, "No robot model loaded");
                return;
            }
            
            for (var joint : robot.joints) {
                if (joint.isMovable()) {
                    float lower = (joint.limit != null) ? joint.limit.lower : -3.14f;
                    float upper = (joint.limit != null) ? joint.limit.upper : 3.14f;
                    joints.put(joint.name, new JointData(
                        joint.name,
                        joint.currentPosition,
                        lower,
                        upper,
                        0f // velocity
                    ));
                }
            }
            log(LogLevel.INFO, "Loaded " + joints.size() + " joints");
        }
        
        @Override
        protected void init() {
            super.init();
            
            int panelX = getPanelX();
            int x = panelX + PADDING;
            int y = 35;
            int btnW = 50;
            int btnH = 16;
            int gap = 4;
            
            // === Simulation Control Buttons ===
            playBtn = Button.builder(Component.literal("▶"), b -> play())
                .bounds(x, y, btnW, btnH).build();
            addRenderableWidget(playBtn);
            
            pauseBtn = Button.builder(Component.literal("⏸"), b -> pause())
                .bounds(x + btnW + gap, y, btnW, btnH).build();
            addRenderableWidget(pauseBtn);
            
            resetBtn = Button.builder(Component.literal("↺"), b -> reset())
                .bounds(x + (btnW + gap) * 2, y, btnW, btnH).build();
            addRenderableWidget(resetBtn);
            
            stepBtn = Button.builder(Component.literal("→|"), b -> step())
                .bounds(x + (btnW + gap) * 3, y, btnW, btnH).build();
            addRenderableWidget(stepBtn);
            
            // === VMD Load Button ===
            loadVmdBtn = Button.builder(Component.literal("Load VMD"), b -> openVmdDialog())
                .bounds(x, y + btnH + gap, 100, btnH).build();
            addRenderableWidget(loadVmdBtn);
            
            // === RL Server Controls ===
            int rlY = y + 120;
            
            portInput = new EditBox(font, x + 40, rlY, 50, 14, Component.literal("Port"));
            portInput.setValue("5555");
            portInput.setMaxLength(5);
            addRenderableWidget(portInput);
            
            serverBtn = Button.builder(
                Component.literal(serverRunning ? "Stop" : "Start"),
                b -> toggleServer()
            ).bounds(x + 100, rlY, 50, 14).build();
            addRenderableWidget(serverBtn);
        }
        
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            // 반투명 배경
            renderBackground(g, mouseX, mouseY, delta);
            
            // 오른쪽 컨트롤 패널
            renderControlPanel(g, mouseX, mouseY);
            
            // 하단 로그 패널
            renderLogPanel(g);
            
            // 상단 타이틀
            g.drawCenteredString(font, "§lRL Control Panel", this.width / 2, 5, TEXT);
            
            // 상태 바
            renderStatusBar(g);
            
            super.render(g, mouseX, mouseY, delta);
        }
        
        private void renderControlPanel(GuiGraphics g, int mouseX, int mouseY) {
            int x = getPanelX();
            int y = 10;
            int h = this.height - 110;
            
            // 패널 배경
            g.fill(x, y, x + PANEL_WIDTH, y + h, BG_PANEL);
            drawBorder(g, x, y, PANEL_WIDTH, h);
            
            int cy = y + PADDING;
            
            // === Simulation 섹션 ===
            cy = renderSection(g, x, cy, "Simulation", simPanelOpen, () -> simPanelOpen = !simPanelOpen);
            if (simPanelOpen) {
                cy += 22; // 버튼 공간
                
                // Speed 슬라이더
                cy += 5;
                g.drawString(font, "Speed", x + PADDING, cy, TEXT_DIM);
                cy = renderSlider(g, x + 60, cy - 2, PANEL_WIDTH - 100, simSpeed, 0.1f, 5f, mouseX, mouseY,
                    v -> simSpeed = v, String.format("%.1fx", simSpeed));
                
                // Time & Steps
                g.drawString(font, String.format("Time: %.2fs", simTime), x + PADDING, cy, TEXT);
                g.drawString(font, String.format("Steps: %d", stepCount), x + PADDING + 90, cy, TEXT);
                cy += LINE_HEIGHT;
                
                // VMD Status
                var vmd = VMDPlayer.getInstance();
                String vmdStatus = vmd.hasMotion() ? 
                    (vmd.isPlaying() ? "§a▶ Playing" : "§e⏸ Loaded") : "§7○ No Motion";
                g.drawString(font, "VMD: " + vmdStatus, x + PADDING, cy, TEXT);
                cy += LINE_HEIGHT;
                
                cy += SECTION_GAP;
            }
            
            // === RL Environment 섹션 ===
            cy = renderSection(g, x, cy, "RL Environment", rlPanelOpen, () -> rlPanelOpen = !rlPanelOpen);
            if (rlPanelOpen) {
                // Server status
                String srvStatus = serverRunning ? "§a● Running" : "§7○ Stopped";
                g.drawString(font, "Server: " + srvStatus, x + PADDING, cy, TEXT);
                cy += LINE_HEIGHT;
                
                // Port input은 init()에서 추가됨
                g.drawString(font, "Port:", x + PADDING, cy + 3, TEXT_DIM);
                cy += 18;
                
                // Python connection
                String pyStatus = pythonConnected ? "§a● Connected" : "§e○ Waiting";
                g.drawString(font, "Python: " + pyStatus, x + PADDING, cy, TEXT);
                cy += LINE_HEIGHT;
                
                // Rewards
                g.drawString(font, String.format("Episode: %.2f", episodeReward), x + PADDING, cy, TEXT);
                cy += LINE_HEIGHT;
                g.drawString(font, String.format("Step: %.3f", lastReward), x + PADDING, cy, 
                    lastReward > 0 ? ACCENT : (lastReward < 0 ? ERROR : TEXT));
                cy += LINE_HEIGHT;
                
                // Obs/Act dims
                g.drawString(font, String.format("Obs: %d  Act: %d", joints.size() * 2, joints.size()), 
                    x + PADDING, cy, TEXT_DIM);
                cy += LINE_HEIGHT;
                
                cy += SECTION_GAP;
            }
            
            // === Joint Control 섹션 ===
            cy = renderSection(g, x, cy, "Joints (" + joints.size() + ")", jointPanelOpen, 
                () -> jointPanelOpen = !jointPanelOpen);
            if (jointPanelOpen) {
                int maxVisible = 8;
                int count = 0;
                
                for (var entry : joints.entrySet()) {
                    if (count >= maxVisible) {
                        g.drawString(font, "§7... +" + (joints.size() - maxVisible) + " more", 
                            x + PADDING, cy, TEXT_DIM);
                        cy += LINE_HEIGHT;
                        break;
                    }
                    
                    JointData jd = entry.getValue();
                    String name = jd.name.length() > 10 ? jd.name.substring(0, 8) + ".." : jd.name;
                    
                    // 이름
                    boolean selected = jd.name.equals(selectedJoint);
                    g.drawString(font, name, x + PADDING, cy, selected ? HIGHLIGHT : TEXT_DIM);
                    
                    // 슬라이더
                    cy = renderSlider(g, x + 75, cy - 2, PANEL_WIDTH - 120, 
                        jd.value, jd.min, jd.max, mouseX, mouseY,
                        v -> updateJoint(jd.name, v), 
                        String.format("%.2f", jd.value));
                    
                    count++;
                }
                cy += SECTION_GAP;
            }
            
            // === Sensors 섹션 ===
            cy = renderSection(g, x, cy, "Sensors", sensorPanelOpen, () -> sensorPanelOpen = !sensorPanelOpen);
            if (sensorPanelOpen) {
                // IMU
                g.drawString(font, "IMU:", x + PADDING, cy, TEXT_DIM);
                g.drawString(font, "[0.0, -9.8, 0.0]", x + 50, cy, TEXT);
                cy += LINE_HEIGHT;
                
                // Contact
                g.drawString(font, "Contact:", x + PADDING, cy, TEXT_DIM);
                g.drawString(font, "L:§a● §rR:§a●", x + 60, cy, TEXT);
                cy += LINE_HEIGHT;
                
                // Force
                g.drawString(font, "Force:", x + PADDING, cy, TEXT_DIM);
                g.drawString(font, "0.0 N", x + 50, cy, TEXT);
                cy += LINE_HEIGHT;
                
                cy += SECTION_GAP;
            }
            
            // === Rendering 섹션 ===
            cy = renderSection(g, x, cy, "Rendering", renderPanelOpen, () -> renderPanelOpen = !renderPanelOpen);
            if (renderPanelOpen) {
                cy = renderCheckbox(g, x, cy, "Contact Points", showContactPoints, 
                    () -> showContactPoints = !showContactPoints, mouseX, mouseY);
                cy = renderCheckbox(g, x, cy, "Center of Mass", showCOM, 
                    () -> showCOM = !showCOM, mouseX, mouseY);
                cy = renderCheckbox(g, x, cy, "Joint Axes", showJointAxes, 
                    () -> showJointAxes = !showJointAxes, mouseX, mouseY);
            }
        }
        
        private int renderSection(GuiGraphics g, int x, int y, String title, boolean open, Runnable toggle) {
            // 헤더 배경
            g.fill(x + 4, y, x + PANEL_WIDTH - 4, y + LINE_HEIGHT, BG_HEADER);
            
            // 화살표 + 타이틀
            String arrow = open ? "▼" : "▶";
            g.drawString(font, arrow + " " + title, x + PADDING, y + 3, TEXT);
            
            return y + LINE_HEIGHT + 2;
        }
        
        private int renderSlider(GuiGraphics g, int x, int y, int w, float value, 
                                  float min, float max, int mouseX, int mouseY,
                                  java.util.function.Consumer<Float> onChange, String label) {
            int h = 12;
            
            // 배경
            g.fill(x, y + 2, x + w, y + h, BG_SECTION);
            
            // 값 위치
            float norm = (value - min) / (max - min);
            norm = Math.max(0, Math.min(1, norm));
            int handleX = x + (int)(norm * (w - 6));
            
            // 채워진 부분
            g.fill(x, y + 2, handleX + 3, y + h, 0x80000000 | (ACCENT & 0xFFFFFF));
            
            // 핸들
            boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h + 4;
            int handleColor = hover ? HIGHLIGHT : ACCENT;
            g.fill(handleX, y, handleX + 6, y + h + 2, handleColor);
            
            // 라벨
            g.drawString(font, label, x + w + 5, y + 2, TEXT);
            
            return y + LINE_HEIGHT;
        }
        
        private int renderCheckbox(GuiGraphics g, int x, int y, String label, boolean checked,
                                    Runnable toggle, int mouseX, int mouseY) {
            String box = checked ? "§a[✓]" : "§7[ ]";
            g.drawString(font, box + " " + label, x + PADDING + 5, y, TEXT);
            return y + LINE_HEIGHT;
        }
        
        private void renderLogPanel(GuiGraphics g) {
            int h = 70;
            int y = this.height - h - 20;
            int x = 10;
            int w = this.width - PANEL_WIDTH - 30;
            
            // 배경
            g.fill(x, y, x + w, y + h, BG_PANEL);
            drawBorder(g, x, y, w, h);
            
            // 헤더
            g.drawString(font, "§lConsole", x + PADDING, y + 3, TEXT);
            
            // 로그 라인
            int logY = y + 16;
            int maxLines = (h - 20) / 10;
            int start = Math.max(0, logs.size() - maxLines);
            
            for (int i = start; i < logs.size(); i++) {
                LogEntry entry = logs.get(i);
                int color = switch (entry.level) {
                    case ERROR -> ERROR;
                    case WARN -> WARNING;
                    case INFO -> TEXT;
                    case DEBUG -> TEXT_DIM;
                };
                String prefix = switch (entry.level) {
                    case ERROR -> "§c[E] ";
                    case WARN -> "§e[W] ";
                    case INFO -> "§f> ";
                    case DEBUG -> "§7[D] ";
                };
                g.drawString(font, prefix + entry.msg, x + PADDING, logY, color, false);
                logY += 10;
            }
        }
        
        private void renderStatusBar(GuiGraphics g) {
            int y = this.height - 16;
            g.fill(0, y, this.width, this.height, BG_PANEL);
            
            // 상태
            String status = switch (simState) {
                case RUNNING -> "§a● Running";
                case PAUSED -> "§e● Paused";
                case STOPPED -> "§7○ Stopped";
            };
            g.drawString(font, status, 10, y + 4, TEXT);
            
            // FPS
            g.drawString(font, "FPS: " + Minecraft.getInstance().getFps(), 100, y + 4, TEXT_DIM);
            
            // Webots
            boolean webotsOk = false;
            try {
                webotsOk = WebotsController.getInstance().isConnected();
            } catch (Exception ignored) {}
            g.drawString(font, "Webots: " + (webotsOk ? "§a●" : "§7○"), 170, y + 4, TEXT);
            
            // VMD
            var vmd = VMDPlayer.getInstance();
            if (vmd.hasMotion()) {
                var st = vmd.getStatus();
                g.drawString(font, String.format("VMD: %.1f/%.1fs", st.currentTime(), st.duration()), 
                    260, y + 4, TEXT_DIM);
            }
        }
        
        private void drawBorder(GuiGraphics g, int x, int y, int w, int h) {
            g.fill(x, y, x + w, y + 1, BORDER);
            g.fill(x, y + h - 1, x + w, y + h, BORDER);
            g.fill(x, y, x + 1, y + h, BORDER);
            g.fill(x + w - 1, y, x + w, y + h, BORDER);
        }
        
        // === 섹션 높이 계산 헬퍼 메서드 ===
        
        private int getSimulationSectionHeight() {
            return 22 + 3 * LINE_HEIGHT + SECTION_GAP;
        }
        
        private int getRlSectionHeight() {
            return 5 * LINE_HEIGHT + 18 + SECTION_GAP;
        }
        
        private int getJointSectionHeight() {
            int maxVisible = 8;
            int shown = Math.min(joints.size(), maxVisible);
            int h = shown * LINE_HEIGHT;
            if (joints.size() > maxVisible) {
                h += LINE_HEIGHT;
            }
            return h + SECTION_GAP;
        }
        
        private int getSensorSectionHeight() {
            return 3 * LINE_HEIGHT + SECTION_GAP;
        }
        
        // === Actions ===
        
        private void play() {
            simState = SimState.RUNNING;
            VMDPlayer.getInstance().play();
            log(LogLevel.INFO, "Simulation started");
        }
        
        private void pause() {
            simState = SimState.PAUSED;
            VMDPlayer.getInstance().pause();
            log(LogLevel.INFO, "Simulation paused");
        }
        
        private void reset() {
            simState = SimState.STOPPED;
            simTime = 0f;
            stepCount = 0;
            episodeReward = 0f;
            lastReward = 0f;
            VMDPlayer.getInstance().stop();
            loadJointData();
            log(LogLevel.INFO, "Simulation reset");
        }
        
        private void step() {
            if (simState == SimState.RUNNING) return;
            
            stepCount++;
            simTime += 0.05f;
            
            // 임시 reward
            lastReward = (float)(Math.random() * 0.2 - 0.1);
            episodeReward += lastReward;
            
            log(LogLevel.DEBUG, String.format("Step %d: r=%.3f", stepCount, lastReward));
        }
        
        private void toggleServer() {
            serverRunning = !serverRunning;
            serverBtn.setMessage(Component.literal(serverRunning ? "Stop" : "Start"));
            
            if (serverRunning) {
                log(LogLevel.INFO, "Server started on port " + portInput.getValue());
            } else {
                log(LogLevel.INFO, "Server stopped");
                pythonConnected = false;
            }
        }
        
        private void openVmdDialog() {
            log(LogLevel.INFO, "Open VMD dialog (not implemented)");
        }
        
        private void updateJoint(String name, float value) {
            JointData jd = joints.get(name);
            if (jd != null) {
                jd.value = value;
                renderer.setJointPreview(name, value);
                renderer.setJointTarget(name, value);
            }
        }
        
        private void log(LogLevel level, String msg) {
            logs.add(new LogEntry(level, msg));
            if (logs.size() > MAX_LOGS) logs.remove(0);
            logger.info("[{}] {}", level, msg);
        }
        
        @Override
        public void tick() {
            super.tick();
            
            if (simState == SimState.RUNNING) {
                simTime += 0.05f;
                stepCount++;
            }
            
            // 관절 값 동기화
            var robot = renderer.getRobotModel();
            if (robot != null && robot.joints != null) {
                for (var joint : robot.joints) {
                    if (joint.isMovable() && joints.containsKey(joint.name)) {
                        joints.get(joint.name).value = joint.currentPosition;
                    }
                }
            }
        }
        
        @Override
        public boolean mouseClicked(double mx, double my, int btn) {
            int x = getPanelX();
            int headerH = LINE_HEIGHT;
            
            // 타이틀 아래 첫 번째 섹션 헤더의 y
            int y = 10 + PADDING;
            
            // === Simulation 헤더 ===
            if (isInBounds(mx, my, x, y, PANEL_WIDTH, headerH)) {
                simPanelOpen = !simPanelOpen;
                return true;
            }
            y += headerH + 2;
            if (simPanelOpen) {
                y += getSimulationSectionHeight();
            }
            
            // === RL Environment 헤더 ===
            if (isInBounds(mx, my, x, y, PANEL_WIDTH, headerH)) {
                rlPanelOpen = !rlPanelOpen;
                return true;
            }
            y += headerH + 2;
            if (rlPanelOpen) {
                y += getRlSectionHeight();
            }
            
            // === Joint Control 헤더 ===
            if (isInBounds(mx, my, x, y, PANEL_WIDTH, headerH)) {
                jointPanelOpen = !jointPanelOpen;
                return true;
            }
            y += headerH + 2;
            if (jointPanelOpen) {
                y += getJointSectionHeight();
            }
            
            // === Sensor 헤더 ===
            if (isInBounds(mx, my, x, y, PANEL_WIDTH, headerH)) {
                sensorPanelOpen = !sensorPanelOpen;
                return true;
            }
            y += headerH + 2;
            if (sensorPanelOpen) {
                y += getSensorSectionHeight();
            }
            
            // === Rendering 헤더 ===
            if (isInBounds(mx, my, x, y, PANEL_WIDTH, headerH)) {
                renderPanelOpen = !renderPanelOpen;
                return true;
            }
            
            return super.mouseClicked(mx, my, btn);
        }
        
        private boolean isInBounds(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
        
        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(parent);
        }
        
        @Override
        public boolean isPauseScreen() {
            return false;
        }
        
        // === Inner Types ===
        
        private enum SimState { STOPPED, RUNNING, PAUSED }
        private enum LogLevel { DEBUG, INFO, WARN, ERROR }
        private record LogEntry(LogLevel level, String msg) {}
        
        private static class JointData {
            String name;
            float value, min, max, velocity;
            
            JointData(String name, float value, float min, float max, float velocity) {
                this.name = name;
                this.value = value;
                this.min = min;
                this.max = max;
                this.velocity = velocity;
            }
        }
    }

    /* ======================== VMDPlayer (기존 유지) ======================== */
    
    public static final class VMDPlayer {
        private static final Logger logger = LogManager.getLogger();
        private static volatile VMDPlayer instance;

        private final AtomicBoolean playing = new AtomicBoolean(false);
        private final AtomicReference<URDFMotion> currentMotion = new AtomicReference<>(null);

        private float currentTime = 0f;
        private int activeJointCount = 0;
        private int debugCounter = 0;

        private VMDPlayer() {}

        public static VMDPlayer getInstance() {
            if (instance == null) {
                synchronized (VMDPlayer.class) {
                    if (instance == null) instance = new VMDPlayer();
                }
            }
            return instance;
        }

        public void loadMotion(URDFMotion motion) {
            currentMotion.set(motion);
            currentTime = 0f;
            playing.set(false);
            logger.info("✅ VMD Motion loaded: {} ({} keyframes)", motion.name, motion.keys.size());
        }

        public void loadFromFile(File vmdFile) {
            URDFMotion motion = VMDLoader.load(vmdFile);
            if (motion != null) {
                loadMotion(motion);
            }
        }

        public void play() {
            if (currentMotion.get() != null) {
                playing.set(true);
                logger.info("▶ VMD Playback started");
            }
        }

        public void stop() {
            playing.set(false);
            currentTime = 0f;
        }

        public void pause() {
            playing.set(false);
        }

        public boolean isPlaying() { return playing.get(); }
        public boolean hasMotion() { return currentMotion.get() != null; }

        public void tick(URDFModelOpenGLWithSTL renderer, float deltaTime) {
            if (!playing.get()) return;

            URDFMotion motion = currentMotion.get();
            if (motion == null || motion.keys.isEmpty()) return;

            currentTime += deltaTime;
            float maxTime = motion.keys.get(motion.keys.size() - 1).t;
            if (maxTime <= 0) maxTime = 1f;

            if (motion.loop && currentTime > maxTime) {
                currentTime = currentTime % maxTime;
            } else if (!motion.loop && currentTime > maxTime) {
                playing.set(false);
                return;
            }

            // 키프레임 보간
            URDFMotion.Key prevKey = null, nextKey = null;
            for (URDFMotion.Key key : motion.keys) {
                if (key.t <= currentTime) prevKey = key;
                else { nextKey = key; break; }
            }
            if (prevKey == null) prevKey = motion.keys.get(0);

            float alpha = 0f;
            if (nextKey != null && nextKey.t > prevKey.t) {
                alpha = (currentTime - prevKey.t) / (nextKey.t - prevKey.t);
                if ("cubic".equals(prevKey.interp)) {
                    alpha = alpha * alpha * (3f - 2f * alpha);
                }
            }

            activeJointCount = 0;
            for (Map.Entry<String, Float> entry : prevKey.pose.entrySet()) {
                String jointName = entry.getKey();
                float value = entry.getValue();

                if (nextKey != null && nextKey.pose.containsKey(jointName)) {
                    value = lerp(value, nextKey.pose.get(jointName), alpha);
                }

                renderer.setJointPreview(jointName, value);
                renderer.setJointTarget(jointName, value);
                activeJointCount++;
            }

            if (++debugCounter >= 20) {
                debugCounter = 0;
                logger.debug("🎬 VMD: t={:.2f}/{:.2f}s, joints={}", currentTime, maxTime, activeJointCount);
            }

            sendToWebots(renderer);
        }

        private void sendToWebots(URDFModelOpenGLWithSTL renderer) {
            try {
                WebotsController webots = WebotsController.getInstance();
                if (!webots.isConnected()) return;
                var robot = renderer.getRobotModel();
                if (robot == null || robot.joints == null) return;
                for (var joint : robot.joints) {
                    if (joint.isMovable()) {
                        webots.setJoint(joint.name, joint.currentPosition);
                    }
                }
            } catch (Exception ignored) {}
        }

        private float lerp(float a, float b, float t) { return a + (b - a) * t; }

        public Status getStatus() {
            URDFMotion motion = currentMotion.get();
            if (motion == null) return new Status(null, 0, 0f, 0f, false, 0);
            float maxTime = motion.keys.isEmpty() ? 0f : motion.keys.get(motion.keys.size() - 1).t;
            return new Status(motion.name, motion.keys.size(), maxTime, currentTime, playing.get(), activeJointCount);
        }

        public record Status(String motionName, int keyframeCount, float duration, 
                            float currentTime, boolean playing, int activeJoints) {}
    }
}