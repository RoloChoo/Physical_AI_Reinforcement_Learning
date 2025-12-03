// common/src/main/java/com/kAIS/KAIMyEntity/urdf/control/MotionEditorScreen.java
package com.kAIS.KAIMyEntity.urdf.control;

import com.kAIS.KAIMyEntity.urdf.URDFModelOpenGLWithSTL;
import com.kAIS.KAIMyEntity.webots.WebotsController; // ✅ 추가됨
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;

import static java.lang.Math.abs;

/**
 * 2025.11.20 VMC Direct Control (Fixed with Atomic Snapshot + Bone Mapping Fix)
 * - Atomic Snapshot 적용으로 Tearing 및 떨림/멈춤 방지
 * - World Coordinate 기반 역학 계산
 * - UpperChest 매핑 문제 해결 (VSeeFace/VMagicMirror 완벽 호환)
 * 
 * ✅ 2025.11.21 Webots 연동 추가
 * - VMC 데이터가 URDF에 반영된 직후, 그 값을 그대로 Webots로 전송
 */
public final class MotionEditorScreen {
    private MotionEditorScreen() {}

    static {
        VMCListenerController.VmcListener listener = VMCListenerController.VmcListener.getInstance();
        listener.setBoneNameNormalizer(original -> {
            if (original == null) return null;
            String lower = original.toLowerCase().trim();

            return switch (lower) {
                // 팔
                case "leftupperarm", "leftarm", "left_arm", "upperarm_left", "arm.l", "leftshoulder", "larm" -> "LeftUpperArm";
                case "leftlowerarm", "leftforearm", "lowerarm_left", "forearm.l", "leftelbow" -> "LeftLowerArm";
                case "lefthand", "hand.l", "hand_left", "left_wrist", "left_hand" -> "LeftHand";
                case "rightupperarm", "rightarm", "right_arm", "upperarm_right", "arm.r", "rightshoulder", "rarm" -> "RightUpperArm";
                case "rightlowerarm", "rightforearm", "lowerarm_right", "forearm.r", "rightelbow" -> "RightLowerArm";
                case "righthand", "hand.r", "hand_right", "right_wrist", "right_hand" -> "RightHand";

                // ★★★ Chest 매핑 확장 (VSeeFace/VMagicMirror UpperChest 대응) ★★★
                case "chest", "upperchest", "spine", "spine1", "spine2", "spine3", "torso", "upper_chest", "chest2" -> "Chest";
                
                // 머리 매핑 (혹시 몰라 추가)
                case "head", "neck", "neck1", "neck2" -> "Head";

                default -> original;
            };
        });
    }

    public static void open(URDFModelOpenGLWithSTL renderer) {
        open(renderer, 39539);
    }

    public static void open(URDFModelOpenGLWithSTL renderer, int vmcPort) {
        VMCListenerController.VmcListener listener = VMCListenerController.VmcListener.getInstance();
        listener.start("0.0.0.0", vmcPort);
        Minecraft.getInstance().setScreen(new VMCListenerController(Minecraft.getInstance().screen, renderer));
    }

    public static void tick(URDFModelOpenGLWithSTL renderer) {
        VmcDrive.tick(renderer);
    }
}

/* ======================== VmcDrive (Atomic Snapshot 적용 + Webots 연동) ======================== */
final class VmcDrive {

    static void tick(URDFModelOpenGLWithSTL renderer) {
        var listener = VMCListenerController.VmcListener.getInstance();
        
        // [핵심] Atomic Snapshot 사용
        Map<String, VMCListenerController.VmcListener.Transform> bones = listener.getSnapshot();

        if (bones.isEmpty()) return;

        // 부모(Chest) 찾기
        VMCListenerController.VmcListener.Transform chest = bones.get("Chest");
        if (chest == null) chest = bones.get("Spine");
        if (chest == null) chest = bones.get("Hips");

        if (chest == null) {
            // System.out.println("[VMC] ERROR: Chest bone not found! Available: " + bones.keySet());
            return;
        }

        // ✅ 기존 로직: 팔 데이터 계산 및 URDF 업데이트
        processArmQuaternion(renderer, bones, chest, true);  // 왼팔
        processArmQuaternion(renderer, bones, chest, false); // 오른팔
        
        // ✅ 기존 로직 추가: 머리 데이터 처리 (Head)
        processHeadQuaternion(renderer, bones, chest);

        // ✅ 추가 로직: Webots 전송 (기존 로직 완전히 끝난 후 실행)
        sendToWebots(renderer);
    }

    private static void processHeadQuaternion(URDFModelOpenGLWithSTL renderer,
                                              Map<String, VMCListenerController.VmcListener.Transform> bones,
                                              VMCListenerController.VmcListener.Transform chest) {
        // 머리 본 찾기
        VMCListenerController.VmcListener.Transform head = bones.get("Head");
        if (head == null) return;

        // Chest(몸통) 기준으로 Head(머리)의 로컬 회전 계산
        // Q_local = Q_parent^-1 * Q_child
        Quaternionf parentRot = new Quaternionf(chest.rotation);
        Quaternionf headRot = new Quaternionf(head.rotation);
        Quaternionf localHead = new Quaternionf(parentRot).conjugate().mul(headRot);

        Vector3f headEuler = new Vector3f();
        localHead.getEulerAnglesXYZ(headEuler);

        // URDF 모델에 적용 (WebotsController가 나중에 이 값을 읽어감)
        // head_pan (좌우, Y축 회전)
        renderer.setJointPreview("head_pan", headEuler.y);
        renderer.setJointTarget("head_pan", headEuler.y);

        // head_tilt (상하, X축 회전) - 좌표계에 따라 부호 확인 필요할 수 있음
        renderer.setJointPreview("head_tilt", headEuler.x);
        renderer.setJointTarget("head_tilt", headEuler.x);
    }

    private static void processArmQuaternion(URDFModelOpenGLWithSTL renderer,
                                             Map<String, VMCListenerController.VmcListener.Transform> bones,
                                             VMCListenerController.VmcListener.Transform parentBone,
                                             boolean isLeft) {
        String upperName = isLeft ? "LeftUpperArm" : "RightUpperArm";
        String lowerName = isLeft ? "LeftLowerArm" : "RightLowerArm";

        var upper = bones.get(upperName);
        var lower = bones.get(lowerName);

        if (upper == null) return;

        // === 1. 어깨 관절 (Shoulder) 계산 ===
        Quaternionf parentRot = new Quaternionf(parentBone.rotation);
        Quaternionf childRot  = new Quaternionf(upper.rotation);
        
        Quaternionf localShoulder = new Quaternionf(parentRot).conjugate().mul(childRot);

        Vector3f shoulderEuler = new Vector3f();
        localShoulder.getEulerAnglesXYZ(shoulderEuler);

        // === 2. 팔꿈치 관절 (Elbow) 계산 ===
        Vector3f elbowEuler = new Vector3f();
        if (lower != null) {
            Quaternionf upperRot = new Quaternionf(upper.rotation);
            Quaternionf lowerRot = new Quaternionf(lower.rotation);
            
            Quaternionf localElbow = new Quaternionf(upperRot).conjugate().mul(lowerRot);
            localElbow.getEulerAnglesXYZ(elbowEuler);
        }

        // === 3. URDF 적용 ===
        String pitchJoint = isLeft ? "l_sho_pitch" : "r_sho_pitch";
        String rollJoint  = isLeft ? "l_sho_roll"  : "r_sho_roll";
        String elbowJoint = isLeft ? "l_el"        : "r_el";

        renderer.setJointPreview(pitchJoint, shoulderEuler.x);
        renderer.setJointTarget(pitchJoint, shoulderEuler.x);

        renderer.setJointPreview(rollJoint, shoulderEuler.z);
        renderer.setJointTarget(rollJoint, shoulderEuler.z);

        float elbowAngle = abs(elbowEuler.z);
        if (isLeft) elbowAngle = -elbowAngle;

        renderer.setJointPreview(elbowJoint, elbowAngle);
        renderer.setJointTarget(elbowJoint, elbowAngle);
    }
    
    // ✅ Webots 전송 로직 (심플하게 URDF 상태를 그대로 전송)
    private static void sendToWebots(URDFModelOpenGLWithSTL renderer) {
        // WebotsController가 연결되어 있을 때만 동작
        WebotsController webots = WebotsController.getInstance();
        if (!webots.isConnected()) return;

        // 현재 URDF 모델의 모든 관절 값을 읽어서 Webots로 쏴줌
        // (이미 processArmQuaternion 등에서 계산된 최신 값이 renderer에 들어있음)
        var robot = renderer.getRobotModel();
        if (robot == null || robot.joints == null) return;
        
        for (var joint : robot.joints) {
            // 움직일 수 있는 관절만 전송 (고정 관절 제외)
            if (joint.isMovable()) {
                // WebotsController 내부에서 이름 매핑 및 좌표 변환을 알아서 처리함
                webots.setJoint(joint.name, joint.currentPosition);
            }
        }
    }
}
