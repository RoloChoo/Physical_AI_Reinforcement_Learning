
package com.kAIS.KAIMyEntity.urdf.vmd;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.*;

public class VMDParser {
    
    // VMD → URDF 본 매핑 (기존 WebotsController와 호환)
    private static final Map<String, String> VMD_TO_URDF = new HashMap<>();
    static {
        // 머리/목
        VMD_TO_URDF.put("首", "Neck");       // Neck → head_pan으로 변환 필요
        VMD_TO_URDF.put("頭", "Head");       // Head → head_tilt
        
        // 왼팔
        VMD_TO_URDF.put("左肩", "LeftShoulder");     // → l_sho_pitch
        VMD_TO_URDF.put("左腕", "LeftUpperArm");     // → l_sho_roll
        VMD_TO_URDF.put("左ひじ", "LeftLowerArm");   // → l_el
        VMD_TO_URDF.put("左手首", "LeftHand");
        
        // 오른팔
        VMD_TO_URDF.put("右肩", "RightShoulder");    // → r_sho_pitch
        VMD_TO_URDF.put("右腕", "RightUpperArm");    // → r_sho_roll
        VMD_TO_URDF.put("右ひじ", "RightLowerArm");  // → r_el
        VMD_TO_URDF.put("右手首", "RightHand");
        
        // 하체 (나중에 추가)
        VMD_TO_URDF.put("下半身", "Hips");
        VMD_TO_URDF.put("上半身", "Spine");
        VMD_TO_URDF.put("上半身2", "Chest");
    }
    
    /**
     * VMD 프레임 데이터
     */
    public static class VMDFrame {
        public int frameNum;
        public Map<String, Float> jointAngles = new HashMap<>();
        
        @Override
        public String toString() {
            return "Frame#" + frameNum + ": " + jointAngles.size() + " joints";
        }
    }
    
    /**
     * VMD 바이너리 파일 파싱
     * @param vmdData VMD 파일 바이트 배열
     * @return 프레임별 URDF 관절 각도 리스트
     */
    public static List<VMDFrame> parse(byte[] vmdData) {
        ByteBuffer buffer = ByteBuffer.wrap(vmdData).order(ByteOrder.LITTLE_ENDIAN);
        
        try {
            // 1. VMD 헤더 (30바이트 매직 + 20바이트 이름)
            byte[] magic = new byte[30];
            buffer.get(magic);
            String magicStr = new String(magic, Charset.forName("Shift-JIS")).trim();
            
            if (!magicStr.startsWith("Vocaloid Motion Data")) {
                throw new IllegalArgumentException("Not a valid VMD file");
            }
            
            // 모델 이름 (20바이트)
            byte[] modelName = new byte[20];
            buffer.get(modelName);
            
            // 2. 모션 프레임 개수
            int motionCount = buffer.getInt();
            
            Map<Integer, VMDFrame> frameMap = new HashMap<>();
            
            // 3. 각 모션 프레임 파싱
            for (int i = 0; i < motionCount; i++) {
                // 본 이름 (15바이트, Shift-JIS)
                byte[] nameBytes = new byte[15];
                buffer.get(nameBytes);
                String boneName = new String(nameBytes, Charset.forName("Shift-JIS")).trim();
                
                // 프레임 번호
                int frameNum = buffer.getInt();
                
                // 위치 (X, Y, Z) - 일단 읽기만
                float px = buffer.getFloat();
                float py = buffer.getFloat();
                float pz = buffer.getFloat();
                
                // 회전 (Quaternion: X, Y, Z, W)
                float qx = buffer.getFloat();
                float qy = buffer.getFloat();
                float qz = buffer.getFloat();
                float qw = buffer.getFloat();
                
                // 보간 파라미터 (64바이트) - 무시
                buffer.position(buffer.position() + 64);
                
                // URDF 관절 이름으로 변환
                String urdfBone = VMD_TO_URDF.get(boneName);
                if (urdfBone == null) continue;
                
                // Quaternion → Euler 변환
                Quaternionf q = new Quaternionf(qx, qy, qz, qw);
                Vector3f euler = new Vector3f();
                q.getEulerAnglesXYZ(euler);
                
                // ✅ MMD → URDF 좌표계 변환
                Map<String, Float> angles = convertMMDtoURDF(urdfBone, euler, px, py, pz);
                
                // 프레임 맵에 추가
                VMDFrame frame = frameMap.computeIfAbsent(frameNum, k -> {
                    VMDFrame f = new VMDFrame();
                    f.frameNum = k;
                    return f;
                });
                
                frame.jointAngles.putAll(angles);
            }
            
            // 4. 프레임 번호 순으로 정렬
            List<VMDFrame> frames = new ArrayList<>(frameMap.values());
            frames.sort(Comparator.comparingInt(f -> f.frameNum));
            
            return frames;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse VMD", e);
        }
    }
    
    /**
     * MMD 좌표계 → URDF 좌표계 변환
     * (vmd2vrmanim.ts의 fixPositions 로직 참고)
     */
    private static Map<String, Float> convertMMDtoURDF(String mmdBone, Vector3f euler, 
                                                       float px, float py, float pz) {
        Map<String, Float> result = new HashMap<>();
        
        // MMD는 Z-up, URDF는 Y-up (부호 반전 필요)
        euler.x = -euler.x;  // Pitch 반전
        euler.y = -euler.y;  // Yaw 반전
        // Z는 유지
        
        switch (mmdBone) {
            case "Neck":
                result.put("head_pan", euler.y);   // 좌우 회전
                break;
                
            case "Head":
                result.put("head_tilt", euler.x);  // 상하 회전
                break;
                
            case "LeftShoulder":
                result.put("l_sho_pitch", euler.x);
                break;
                
            case "LeftUpperArm":
                // ✅ MMD는 A-Pose (팔 벌림), URDF는 T-Pose
                // 30도 오프셋 추가 (vmd2vrmanim.ts의 Z_30_DEG_CW 로직)
                float leftRoll = euler.z + (float)Math.toRadians(30);
                result.put("l_sho_roll", leftRoll);
                break;
                
            case "LeftLowerArm":
                result.put("l_el", euler.x);
                break;
                
            case "RightShoulder":
                result.put("r_sho_pitch", euler.x);
                break;
                
            case "RightUpperArm":
                // 오른팔도 30도 오프셋 (반대 방향)
                float rightRoll = euler.z - (float)Math.toRadians(30);
                result.put("r_sho_roll", rightRoll);
                break;
                
            case "RightLowerArm":
                result.put("r_el", euler.x);
                break;
                
            case "Hips":
                // 골반은 위치 정보도 사용 (루트 모션)
                result.put("hips_x", px * 0.1f);  // MMD → URDF 스케일 (1/10)
                result.put("hips_y", py * 0.1f);
                result.put("hips_z", pz * 0.1f);
                result.put("hips_rot", euler.z);
                break;
                
            default:
                // 기타 관절은 Z축 회전만
                result.put(mmdBone.toLowerCase(), euler.z);
        }
        
        return result;
    }
}