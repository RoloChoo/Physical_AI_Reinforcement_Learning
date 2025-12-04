package com.kAIS.KAIMyEntity;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ode4j.math.DVector3;
import org.ode4j.ode.DWorld;
import org.ode4j.ode.DHashSpace;
import org.ode4j.ode.DSpace;
import org.ode4j.ode.OdeHelper;

// ODE4J는 순수 자바 라이브러리이므로 NativeFunc 대신 PhysicsManager라는 이름이 더 적절합니다.
public class PhysicsManager {
    public static final Logger logger = LogManager.getLogger();
    private static PhysicsManager inst;

    // ODE4J 핵심 객체
    private DWorld world;
    private DSpace space;
    // 물리 시뮬레이션 스텝 설정 (기본값)
    private static final double STEP_SIZE = 0.05; 

    // 싱글톤 인스턴스 가져오기
    public static PhysicsManager GetInst() {
        if (inst == null) {
            inst = new PhysicsManager();
            inst.Init();
        }
        return inst;
    }

    // 초기화 로직
    private void Init() {
        try {
            logger.info("Initializing ODE4J Physics World...");
            
            // ODE4J 초기화
            OdeHelper.initODE();
            
            // 월드 생성 (강체 역학 시뮬레이션 공간)
            world = OdeHelper.createWorld();
            
            // 중력 설정 (기본값: 지구 중력 -9.8 m/s^2, Y축이 위쪽인 경우)
            world.setGravity(0, -9.81, 0);
            
            // 충돌 감지 공간 생성
            space = new OdeHelper.createHashSpace();
            
            logger.info("ODE4J Initialized Successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize ODE4J", e);
        }
    }

    // 매 틱마다 호출하여 물리 연산 업데이트
    public void updatePhysics() {
        if (world != null) {
            // 퀵스텝 방식이 일반적인 게임 물리 엔진에서 더 빠르고 안정적입니다.
            world.quickStep(STEP_SIZE); 
            
            // 충돌 처리 로직은 여기에 추가 (JointGroup 비우기 등)
        }
    }

    // --- 아래는 외부에서 물리 엔진에 접근하기 위한 Getter 및 설정 메서드들 ---

    public DWorld getWorld() {
        return world;
    }

    public DSpace getSpace() {
        return space;
    }

    // 중력 설정 변경
    public void setGravity(double x, double y, double z) {
        if (world != null) {
            world.setGravity(x, y, z);
        }
    }

    // 시뮬레이션 종료 및 정리
    public void close() {
        if (space != null) {
            space.destroy();
            space = null;
        }
        if (world != null) {
            world.destroy();
            world = null;
        }
        OdeHelper.closeODE();
    }
    
    // URDF 로더에서 바디 생성시 사용할 헬퍼 메서드 예시
    // (실제 URDF 파싱 로직은 별도 클래스에서 수행하고 여기서 바디만 등록하는 식)
    /*
    public DBody createBody() {
        return OdeHelper.createBody(world);
    }
    */
}
