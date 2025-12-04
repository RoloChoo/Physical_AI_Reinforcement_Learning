package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.urdf.vmd.VMDLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * VMD 모션 컨트롤러 GUI
 * - K키로 열림
 * - VMD 파일 로드/재생/정지
 */
public class VMDMotionController extends Screen {
    private static final Logger logger = LogManager.getLogger();
    
    private static final int BG_COLOR = 0xFF0E0E10;
    private static final int PANEL_COLOR = 0xFF1D1F24;
    private static final int TITLE_COLOR = 0xFFFFD770;
    private static final int TXT_MAIN = 0xFFFFFFFF;

    private final Screen parent;
    private final URDFModelOpenGLWithSTL renderer;
    private final MotionEditorScreen.VMDPlayer player = MotionEditorScreen.VMDPlayer.getInstance();

    private Button loadButton;
    private Button playButton;
    private Button stopButton;
    private Button testButton;
    
    // VMD 파일 목록
    private List<File> vmdFiles = new ArrayList<>();
    private int selectedIndex = -1;

    public VMDMotionController(Screen parent, URDFModelOpenGLWithSTL renderer) {
        super(Component.literal("VMD Motion Controller"));
        this.parent = parent;
        this.renderer = renderer;
        scanVmdFiles();
    }

    private void scanVmdFiles() {
        vmdFiles.clear();
        File gameDir = Minecraft.getInstance().gameDirectory;
        
        // ./KAIMyEntity/ 폴더 스캔
        File kaiDir = new File(gameDir, "KAIMyEntity");
        if (!kaiDir.exists()) {
            kaiDir.mkdirs();
        }
        
        File[] files = kaiDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".vmd"));
        if (files != null) {
            for (File f : files) {
                vmdFiles.add(f);
            }
        }
        
        logger.info("Found {} VMD files in KAIMyEntity/", vmdFiles.size());
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int startY = 50;

        // VMD 파일 로드 버튼
        loadButton = Button.builder(Component.literal("📁 Load VMD"), b -> loadSelectedVmd())
                .bounds(centerX - 100, startY, 200, 20).build();
        addRenderableWidget(loadButton);

        startY += 30;
        
        // 재생/정지 버튼
        playButton = Button.builder(Component.literal("▶ Play"), b -> {
            player.play();
            updateButtons();
        }).bounds(centerX - 100, startY, 95, 20).build();
        addRenderableWidget(playButton);

        stopButton = Button.builder(Component.literal("■ Stop"), b -> {
            player.stop();
            updateButtons();
        }).bounds(centerX + 5, startY, 95, 20).build();
        addRenderableWidget(stopButton);

        startY += 30;
        
        // 테스트 모션 버튼
        testButton = Button.builder(Component.literal("🧪 Test Motion"), b -> {
            playTestMotion();
            updateButtons();
        }).bounds(centerX - 100, startY, 200, 20).build();
        addRenderableWidget(testButton);

        // 닫기 버튼
        addRenderableWidget(Button.builder(Component.literal("Back"), b ->
                Minecraft.getInstance().setScreen(parent))
                .bounds(centerX - 50, this.height - 30, 100, 20).build());

        updateButtons();
    }

    private void loadSelectedVmd() {
        if (vmdFiles.isEmpty()) {
            minecraft.gui.getChat().addMessage(
                    Component.literal("§e[VMD] No VMD files found in ./KAIMyEntity/"));
            return;
        }

        // 첫 번째 파일 로드 (나중에 선택 UI 추가 가능)
        File vmdFile = vmdFiles.get(0);
        
        // URDF 관절 이름 전달
        URDFMotion motion = VMDLoader.load(vmdFile, renderer.getRobotModel());
        
        if (motion != null) {
            player.loadMotion(motion);
            selectedIndex = 0;
            minecraft.gui.getChat().addMessage(
                    Component.literal("§a[VMD] Loaded: " + vmdFile.getName()));
        } else {
            minecraft.gui.getChat().addMessage(
                    Component.literal("§c[VMD] Failed to load: " + vmdFile.getName()));
        }
        
        updateButtons();
    }

    private void playTestMotion() {
        URDFMotion testMotion = createTestMotion();
        player.loadMotion(testMotion);
        player.play();
        minecraft.gui.getChat().addMessage(
                Component.literal("§a[VMD] Test motion playing!"));
    }

    /**
     * 테스트용 모션 생성
     */
    private URDFMotion createTestMotion() {
        URDFMotion motion = new URDFMotion();
        motion.name = "test_arm_wave";
        motion.fps = 30f;
        motion.loop = true;

        for (int i = 0; i <= 60; i++) {
            URDFMotion.Key key = new URDFMotion.Key();
            key.t = i / 30f;

            float angle = (float) Math.sin(i * 0.15) * 0.8f;

            // 여러 가능한 관절 이름
            key.pose.put("l_sho_pitch", angle);
            key.pose.put("LShoulderPitch", angle);
            key.pose.put("l_sho_roll", 0.5f);
            key.pose.put("LShoulderRoll", 0.5f);
            key.pose.put("r_sho_pitch", -angle);
            key.pose.put("RShoulderPitch", -angle);
            key.pose.put("r_sho_roll", -0.5f);
            key.pose.put("RShoulderRoll", -0.5f);

            motion.keys.add(key);
        }

        return motion;
    }

    private void updateButtons() {
        boolean hasMotion = player.hasMotion();
        boolean playing = player.isPlaying();

        playButton.active = hasMotion && !playing;
        stopButton.active = playing;
    }

    @Override
    public void tick() {
        super.tick();
        // ✅ GUI 열려 있는 동안에도 모션 업데이트
        if (renderer != null) {
            MotionEditorScreen.tick(renderer);
        }
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 배경
        graphics.fill(0, 0, this.width, this.height, BG_COLOR);

        // 패널
        int panelX = this.width / 2 - 150;
        int panelY = 130;
        int panelW = 300;
        int panelH = 150;
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_COLOR);

        super.render(graphics, mouseX, mouseY, partialTicks);

        // 제목
        graphics.drawCenteredString(this.font, "VMD Motion Controller", this.width / 2, 15, TITLE_COLOR);

        // 상태 표시
        var status = player.getStatus();
        List<String> lines = new ArrayList<>();

        lines.add("§7VMD Files: " + vmdFiles.size());
        lines.add("");
        
        if (status.motionName() != null) {
            lines.add("§bMotion: " + status.motionName());
            lines.add("§7Keyframes: " + status.keyframeCount());
            lines.add("§7Duration: " + String.format("%.1fs", status.duration()));
            lines.add("");
            
            if (status.playing()) {
                lines.add("§a▶ PLAYING");
                lines.add(String.format("§7Time: %.2f / %.2fs", status.currentTime(), status.duration()));
                
                // 프로그레스 바
                float progress = status.duration() > 0 ? status.currentTime() / status.duration() : 0;
                lines.add("§7[" + makeProgressBar(progress, 25) + "§7]");
            } else {
                lines.add("§7■ STOPPED");
            }
        } else {
            lines.add("§cNo motion loaded");
            lines.add("§7Click 'Load VMD' or 'Test Motion'");
        }

        int y = panelY + 10;
        for (String line : lines) {
            graphics.drawString(this.font, line, panelX + 10, y, TXT_MAIN, false);
            y += 12;
        }
    }

    private String makeProgressBar(float progress, int width) {
        int filled = (int) (progress * width);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? "§a█" : "§8░");
        }
        return sb.toString();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
