
package com.kAIS.KAIMyEntity.urdf.vmd;

import com.kAIS.KAIMyEntity.urdf.control.URDFMotion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class VMDLoader {
    private static final Logger logger = LogManager.getLogger();
    
    /**
     * VMD 파일을 URDFMotion으로 변환
     * @param vmdFile VMD 파일 경로
     * @return URDFMotion 객체 (키프레임 애니메이션)
     */
    public static URDFMotion load(File vmdFile) {
        try {
            byte[] vmdData = Files.readAllBytes(vmdFile.toPath());
            List<VMDParser.VMDFrame> frames = VMDParser.parse(vmdData);
            
            URDFMotion motion = new URDFMotion();
            motion.name = vmdFile.getName();
            motion.fps = 30f;  // VMD는 30fps
            motion.loop = true;
            
            // 프레임을 키프레임으로 변환
            for (VMDParser.VMDFrame frame : frames) {
                URDFMotion.Key key = new URDFMotion.Key();
                key.t = frame.frameNum / 30f;  // 프레임 → 시간(초)
                key.pose = frame.jointAngles;
                key.interp = "cubic";  // VMD는 큐빅 보간
                motion.keys.add(key);
            }
            
            logger.info("✅ Loaded VMD: {} ({} keyframes)", vmdFile.getName(), motion.keys.size());
            return motion;
            
        } catch (Exception e) {
            logger.error("❌ Failed to load VMD: {}", vmdFile.getName(), e);
            return null;
        }
    }
}