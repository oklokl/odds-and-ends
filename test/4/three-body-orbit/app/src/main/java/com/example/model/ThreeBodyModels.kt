package com.example.model

import androidx.compose.ui.graphics.Color

data class TrailPoint(
    val x: Float,
    val y: Float,
    val alpha: Float,
    val size: Float
)

data class CelestialStar(
    val id: Int,
    val name: String,
    val koreanName: String,
    val color: Color,
    val glowColor: Color,
    var mass: Float,
    var baseRadius: Float,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var ax: Float = 0f,
    var ay: Float = 0f,
    val trail: MutableList<TrailPoint> = mutableListOf(),
    var proximityIntensity: Float = 0f, // 0f to 1f based on closeness to other stars
    var flareScale: Float = 1f
)

data class CosmicParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float, // 1.0f down to 0f
    val maxLife: Float,
    val color: Color,
    val size: Float
)

data class ShockwaveRipple(
    val x: Float,
    val y: Float,
    var currentRadius: Float,
    val maxRadius: Float,
    var alpha: Float,
    val color: Color
)

enum class TouchMode(val displayName: String, val koreanName: String, val description: String) {
    IMPULSE("Chaos Impulse", "혼돈 충격파", "터치 시 세 별에 서로 다른 무작위 충격량을 가합니다"),
    WELL("Gravity Well", "중력 왜곡원", "터치 지점에 임시 블랙홀을 생성해 별들을 끌어당깁니다"),
    SLING("Star Fling", "별 직접 던지기", "별을 터치하여 원하는 방향으로 직접 가속시킵니다")
}

enum class SystemEra(val title: String, val koreanTitle: String, val description: String, val badgeColor: Color) {
    STABLE("Stable Era", "안정 주기 (안석기)", "규칙적인 준주기 궤도를 유지 중입니다", Color(0xFF00E676)),
    CHAOTIC("Chaotic Era", "난세기 (혼돈기)", "중력 섭동으로 예측 불가능한 난류 궤도입니다", Color(0xFFFF5252)),
    SYZYGY("Syzygy Conjunction", "삼체 합류 (정렬)", "별들이 초근접하여 극한의 조석력이 작용합니다", Color(0xFFFFD600)),
    NOVA("Hyper Chaotic Flare", "초신성 요동", "막대한 에너지가 주입되어 고속 선회 중입니다", Color(0xFFE040FB))
}

enum class OrbitPreset(val title: String, val koreanTitle: String, val description: String) {
    ELLIPTICAL("Elliptical Dance", "타원 궤도", "세 별이 상호 중심을 돌며 부드럽게 순환하는 초기 궤도"),
    FIGURE_EIGHT("Figure-8 Choreography", "8자 궤도 (무어의 해)", "세 천체가 동일한 8자 곡선을 교대로 통과하는 안정해"),
    LAGRANGE("Lagrange Trio", "라그랑주 삼중주", "등변삼각형을 이루며 회전하는 우아한 공전 궤도"),
    CHAOS_WHIRL("Trisolaris Chaos", "삼체 혼돈 소용돌이", "예측 불가능하게 요동치며 휘몰아치는 난류 궤도")
}

data class TelemetryData(
    val distanceAB: Float = 0f,
    val distanceBC: Float = 0f,
    val distanceCA: Float = 0f,
    val minDistance: Float = 0f,
    val avgSpeed: Float = 0f,
    val chaosIndex: Float = 0f, // 0f to 1f
    val currentEra: SystemEra = SystemEra.STABLE
)
