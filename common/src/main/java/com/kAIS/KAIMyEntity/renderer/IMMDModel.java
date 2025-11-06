package com.kAIS.KAIMyEntity.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

/**
 * MC 표준 파이프라인 지원을 위해 VertexConsumer 기반 메서드를 추가.
 * 기존 Render(...)는 하위호환을 위해 유지하고, renderToBuffer의 기본 구현에서 호출하도록 함.
 *
 * ⚠️ 기존 메서드 시그니처는 절대 변경하지 않았습니다.
 *    아래 URDF/VRM 관련 기능은 모두 default 로 추가되어, 기존 구현체에 영향이 없습니다.
 */
public interface IMMDModel {
    /** 레거시 경로(이전 코드 호환). 가능하면 새 renderToBuffer를 구현해서 사용하세요. */
    void Render(Entity entityIn, float entityYaw, float entityPitch,
                Vector3f entityTrans, float tickDelta, PoseStack mat, int packedLight);

    void ChangeAnim(long anim, long layer);
    void ResetPhysics();
    long GetModelLong();
    String GetModelDir();

    /** ✅ 새 경로: VertexConsumer로 버텍스를 기록해서 MC 렌더 파이프라인을 사용 */
    default void renderToBuffer(Entity entityIn,
                                float entityYaw, float entityPitch, Vector3f entityTrans, float tickDelta,
                                PoseStack pose,
                                VertexConsumer consumer,
                                int packedLight,
                                int overlay) {
        // 하위호환: 새 메서드를 아직 구현하지 않았다면 레거시 렌더를 호출
        Render(entityIn, entityYaw, entityPitch, entityTrans, tickDelta, pose, packedLight);
    }

    /** 선택: 텍스처가 있으면 반환(없으면 null). */
    default ResourceLocation getTexture() { return null; }

    // ---------------------------------------------------------------------
    // 아래는 URDF/VRM 매핑·프리뷰용 확장 훅 (모두 default: 기존 구현에 영향 없음)
    // ---------------------------------------------------------------------

    /** (선택) 미리보기용 스켈레톤 주입 (VRM stick/mesh 등) */
    default void setPreviewSkeleton(Object skeleton) {}

    /** (선택) URDF 조인트 프리뷰 값 설정 (즉시 렌더 반영용) */
    default void setJointPreview(String jointName, float value) {}

    /** (선택) URDF 조인트 목표 값 설정 (컨트롤러/시뮬에 전달용) */
    default void setJointTarget(String jointName, float value) {}

    /** (선택) 내부 URDF 로봇 모델 객체를 노출 (매핑/툴에서 참조) */
    default Object getRobotModel() { return null; }

    /** (선택) 매핑(json)이나 캘리브레이션이 갱신됐을 때 통지 */
    default void onMappingUpdated(Object mapping) {}

    /** (선택) 프레임 갱신 훅 (없던 프로젝트도 안전) */
    default void Update(float deltaTime) {}

    /** (선택) 언로드/리소스 정리 훅 (없던 프로젝트도 안전) */
    default void Dispose() {}
}
