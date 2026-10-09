package com.example.physics

import androidx.compose.ui.graphics.Color
import com.example.model.CelestialStar
import com.example.model.CosmicParticle
import com.example.model.OrbitPreset
import com.example.model.ShockwaveRipple
import com.example.model.SystemEra
import com.example.model.TelemetryData
import com.example.model.TouchMode
import com.example.model.TrailPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ThreeBodySimulation {

    // Canvas dimensions
    var width: Float = 1080f
    var height: Float = 1920f

    // Celestial bodies (Cyan, Magenta, Amber Gold)
    val stars: MutableList<CelestialStar> = mutableListOf()

    // Visual effects
    val particles: MutableList<CosmicParticle> = mutableListOf()
    val shockwaves: MutableList<ShockwaveRipple> = mutableListOf()

    // Configurable parameters
    var gravityG: Float = 2400f
    var chaosTurbulence: Float = 55f
    var simulationSpeed: Float = 1.0f
    var maxTrailPoints: Int = 120
    var touchMode: TouchMode = TouchMode.IMPULSE
    var activePreset: OrbitPreset = OrbitPreset.ELLIPTICAL

    // Active gravity well (when holding finger)
    var gravityWellActive: Boolean = false
    var gravityWellX: Float = 0f
    var gravityWellY: Float = 0f

    // Telemetry state
    var telemetry: TelemetryData = TelemetryData()
        private set

    // Internal simulation time
    private var simTime: Float = 0f
    private val softeningEpsilonSq: Float = 1600f // Epsilon^2 (softening radius = 40px)

    // Close encounter cooldown for haptic
    var onSlingshotEncounter: (() -> Unit)? = null
    private var slingshotCooldown: Float = 0f

    init {
        initDefaultStars()
    }

    private fun initDefaultStars() {
        stars.clear()
        stars.add(
            CelestialStar(
                id = 0,
                name = "Alpha Centauri A",
                koreanName = "알파성 A (창백한 푸른 거성)",
                color = Color(0xFF00E5FF),      // Radiant Cyan
                glowColor = Color(0x6600E5FF),
                mass = 1.05f,
                baseRadius = 24f,
                x = 0f,
                y = 0f,
                vx = 0f,
                vy = 0f
            )
        )
        stars.add(
            CelestialStar(
                id = 1,
                name = "Alpha Centauri B",
                koreanName = "알파성 B (진홍빛 초거성)",
                color = Color(0xFFFF2A85),      // Vibrant Magenta
                glowColor = Color(0x66FF2A85),
                mass = 0.95f,
                baseRadius = 22f,
                x = 0f,
                y = 0f,
                vx = 0f,
                vy = 0f
            )
        )
        stars.add(
            CelestialStar(
                id = 2,
                name = "Proxima Centauri",
                koreanName = "프록시마 (황금빛 항성)",
                color = Color(0xFFFFB300),      // Amber Gold
                glowColor = Color(0x66FFB300),
                mass = 0.85f,
                baseRadius = 20f,
                x = 0f,
                y = 0f,
                vx = 0f,
                vy = 0f
            )
        )
    }

    fun setBounds(w: Float, h: Float) {
        val firstInit = (width == 1080f && height == 1920f && w > 0 && h > 0)
        width = max(100f, w)
        height = max(100f, h)
        if (firstInit || stars.all { it.x == 0f && it.y == 0f }) {
            loadPreset(activePreset)
        }
    }

    fun loadPreset(preset: OrbitPreset) {
        activePreset = preset
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.26f

        // Clear trails and particles
        stars.forEach { it.trail.clear() }
        particles.clear()
        shockwaves.clear()
        simTime = 0f

        when (preset) {
            OrbitPreset.ELLIPTICAL -> {
                // Initial elliptical orbit around common center of mass with gentle angular momentum
                // Star 0: Top right
                val angle0 = 0.0
                val angle1 = 2.0944 // 120 deg
                val angle2 = 4.1888 // 240 deg

                val dist0 = r * 1.15f
                val dist1 = r * 0.95f
                val dist2 = r * 1.05f

                stars[0].x = cx + (cos(angle0) * dist0).toFloat()
                stars[0].y = cy + (sin(angle0) * dist0).toFloat()
                val speed0 = 110f
                stars[0].vx = (-sin(angle0) * speed0).toFloat()
                stars[0].vy = (cos(angle0) * speed0).toFloat()

                stars[1].x = cx + (cos(angle1) * dist1).toFloat()
                stars[1].y = cy + (sin(angle1) * dist1).toFloat()
                val speed1 = 125f
                stars[1].vx = (-sin(angle1) * speed1).toFloat()
                stars[1].vy = (cos(angle1) * speed1).toFloat()

                stars[2].x = cx + (cos(angle2) * dist2).toFloat()
                stars[2].y = cy + (sin(angle2) * dist2).toFloat()
                val speed2 = 115f
                stars[2].vx = (-sin(angle2) * speed2).toFloat()
                stars[2].vy = (cos(angle2) * speed2).toFloat()
            }
            OrbitPreset.FIGURE_EIGHT -> {
                // Cristopher Moore's famous Figure-8 solution approximation
                // Star 1 at center, Stars 0 and 2 placed symmetrically
                val d = r * 1.1f
                val v = 140f
                stars[0].x = cx - d
                stars[0].y = cy
                stars[0].vx = v * 0.46f
                stars[0].vy = v * 0.43f

                stars[1].x = cx + d
                stars[1].y = cy
                stars[1].vx = v * 0.46f
                stars[1].vy = v * 0.43f

                stars[2].x = cx
                stars[2].y = cy
                stars[2].vx = -2f * (v * 0.46f)
                stars[2].vy = -2f * (v * 0.43f)
            }
            OrbitPreset.LAGRANGE -> {
                // Rotating equilateral triangle
                val d = r * 1.05f
                val angVel = 0.9f
                for (i in 0..2) {
                    val theta = (i * 2.0 * Math.PI / 3.0)
                    stars[i].x = cx + (cos(theta) * d).toFloat()
                    stars[i].y = cy + (sin(theta) * d).toFloat()
                    val vTangential = d * angVel
                    stars[i].vx = (-sin(theta) * vTangential).toFloat()
                    stars[i].vy = (cos(theta) * vTangential).toFloat()
                }
            }
            OrbitPreset.CHAOS_WHIRL -> {
                // High asymmetric initial velocities and close offset
                stars[0].x = cx - r * 0.8f
                stars[0].y = cy - r * 0.5f
                stars[0].vx = 160f
                stars[0].vy = -120f

                stars[1].x = cx + r * 0.6f
                stars[1].y = cy - r * 0.7f
                stars[1].vx = -140f
                stars[1].vy = 180f

                stars[2].x = cx + r * 0.2f
                stars[2].y = cy + r * 0.9f
                stars[2].vx = -50f
                stars[2].vy = -90f
            }
        }
    }

    /**
     * User touch impulse injection:
     * Dispatches random variation impulses to each star, spawning shockwave and cosmic sparks.
     */
    fun applyTouchImpulse(touchX: Float, touchY: Float, explicitImpulseMagnitude: Float = 260f) {
        // Spawn visual shockwave ripple
        shockwaves.add(
            ShockwaveRipple(
                x = touchX,
                y = touchY,
                currentRadius = 10f,
                maxRadius = min(width, height) * 0.45f,
                alpha = 0.9f,
                color = Color(0xFF80D8FF)
            )
        )

        // Spawn cosmic dust particles bursting from touch point
        repeat(18) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = 80f + Random.nextFloat() * 220f
            val pColor = when (Random.nextInt(3)) {
                0 -> Color(0xFF00E5FF)
                1 -> Color(0xFFFF2A85)
                else -> Color(0xFFFFB300)
            }
            particles.add(
                CosmicParticle(
                    x = touchX,
                    y = touchY,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    life = 1f,
                    maxLife = 0.6f + Random.nextFloat() * 0.6f,
                    color = pColor,
                    size = 2.5f + Random.nextFloat() * 3.5f
                )
            )
        }

        // Apply distinct randomized impulses to each star
        stars.forEachIndexed { index, star ->
            val dx = star.x - touchX
            val dy = star.y - touchY
            val dist = max(30f, sqrt(dx * dx + dy * dy))
            val baseAngle = atan2(dy, dx)

            // As requested:
            // "세 개의 공에 각각 서로 다른 크기와 방향의 '충격량(Impulse)'을 가해 줘.
            //  약간의 랜덤성(Random variation)을 포함해서 매번 터치할 때마다 공들의 궤도가 다르게 변하도록 해 줘."
            val randomAngleOffset = (Random.nextFloat() - 0.5f) * 1.4f // -0.7 to +0.7 rad
            val randomMagnitudeMultiplier = 0.75f + Random.nextFloat() * 0.85f // 0.75x to 1.6x
            val distanceDecay = max(0.45f, 1f - (dist / (max(width, height) * 0.9f)))

            val effectiveImpulse = explicitImpulseMagnitude * randomMagnitudeMultiplier * distanceDecay
            val finalAngle = baseAngle + randomAngleOffset

            // Add impulse to velocity vector
            star.vx += cos(finalAngle) * effectiveImpulse
            star.vy += sin(finalAngle) * effectiveImpulse

            // Trigger flare reaction on star
            star.flareScale = 1.4f
        }
    }

    /**
     * Handle user dragging or direct star fling
     */
    fun findStarAt(x: Float, y: Float, touchRadius: Float = 80f): CelestialStar? {
        return stars.firstOrNull { star ->
            val dx = star.x - x
            val dy = star.y - y
            (dx * dx + dy * dy) <= (touchRadius * touchRadius)
        }
    }

    fun flingStar(star: CelestialStar, flingVx: Float, flingVy: Float) {
        star.vx = flingVx
        star.vy = flingVy
        star.flareScale = 1.6f
        repeat(12) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = 60f + Random.nextFloat() * 140f
            particles.add(
                CosmicParticle(
                    x = star.x,
                    y = star.y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    life = 1f,
                    maxLife = 0.5f + Random.nextFloat() * 0.4f,
                    color = star.color,
                    size = 3f + Random.nextFloat() * 2f
                )
            )
        }
    }

    /**
     * Physics simulation step executed per frame.
     * dt: delta time in seconds (clamped for stability).
     */
    fun update(dtRaw: Float) {
        val dt = min(0.033f, dtRaw) * simulationSpeed
        simTime += dt
        if (slingshotCooldown > 0f) {
            slingshotCooldown -= dt
        }

        val cx = width / 2f
        val cy = height / 2f

        // Reset accelerations
        for (i in 0 until stars.size) {
            stars[i].ax = 0f
            stars[i].ay = 0f
        }

        // 1. Mutual gravitational interaction between all pairs:
        // F_ij = G * m_i * m_j / (r^2 + epsilon^2)^(3/2) * (r_j - r_i)
        for (i in 0 until stars.size) {
            for (j in i + 1 until stars.size) {
                val starA = stars[i]
                val starB = stars[j]

                val dx = starB.x - starA.x
                val dy = starB.y - starA.y
                val distSq = dx * dx + dy * dy
                val softenedDistSq = distSq + softeningEpsilonSq
                val dist = sqrt(distSq)
                val softenedDistCube = softenedDistSq * sqrt(softenedDistSq)

                // Force magnitude / distance
                val forceFactor = gravityG / softenedDistCube

                // Acceleration on A due to B: a_A = G * m_B / r^3 * dr
                starA.ax += forceFactor * starB.mass * dx
                starA.ay += forceFactor * starB.mass * dy

                // Acceleration on B due to A: a_B = - G * m_A / r^3 * dr
                starB.ax -= forceFactor * starA.mass * dx
                starB.ay -= forceFactor * starA.mass * dy

                // Proximity interaction feedback (tidal heating flare)
                val proximityRatio = max(0f, 1f - (dist / 220f))
                starA.proximityIntensity = max(starA.proximityIntensity, proximityRatio)
                starB.proximityIntensity = max(starB.proximityIntensity, proximityRatio)

                // Check for close slingshot flyby
                if (dist < 85f && slingshotCooldown <= 0f) {
                    slingshotCooldown = 0.4f
                    onSlingshotEncounter?.invoke()
                    // Spawn collision/slingshot sparks between them
                    val midX = (starA.x + starB.x) / 2f
                    val midY = (starA.y + starB.y) / 2f
                    repeat(8) {
                        val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
                        val speed = 80f + Random.nextFloat() * 150f
                        particles.add(
                            CosmicParticle(
                                x = midX,
                                y = midY,
                                vx = cos(angle) * speed,
                                vy = sin(angle) * speed,
                                life = 1f,
                                maxLife = 0.45f,
                                color = Color.White,
                                size = 3f
                            )
                        )
                    }
                }
            }
        }

        // 2. Complex harmonic Sine/Cosine wave turbulence field:
        // As requested:
        // "각 프레임마다 공들의 이동 속도 벡터에 '복잡한 사인/코사인 파동(Complex Sine/Cosine waves)'이나
        //  '펄린 노이즈(Perlin Noise)'를 더해서 움직임이 부드럽지만 불규칙하게 소용돌이치게 만들어 줘.
        //  궤도가 완전히 닫히지 않고 계속 변칙적으로 흔들리는 느낌을 주는 게 중요해."
        val waveAmplitude = chaosTurbulence
        for (star in stars) {
            val t = simTime
            val idOffset = star.id * 2.094f // 120 deg phase shift

            // Spatial and temporal frequency harmonics
            val waveAx = sin(t * 1.35f + idOffset) * 0.45f +
                    cos(star.y * 0.0042f + t * 0.85f) * 0.35f +
                    sin((star.x + star.y) * 0.0028f + t * 1.9f) * 0.20f

            val waveAy = cos(t * 1.25f + idOffset) * 0.45f +
                    sin(star.x * 0.0042f + t * 0.95f) * 0.35f +
                    cos((star.x - star.y) * 0.0028f + t * 1.7f) * 0.20f

            star.ax += waveAx * waveAmplitude
            star.ay += waveAy * waveAmplitude
        }

        // 3. Optional Gravity Well (temporary black hole attractor from user finger hold)
        if (gravityWellActive) {
            val wellG = gravityG * 2.5f
            for (star in stars) {
                val dx = gravityWellX - star.x
                val dy = gravityWellY - star.y
                val distSq = dx * dx + dy * dy + softeningEpsilonSq
                val dist = sqrt(distSq)
                val wellForce = wellG / (distSq * dist)
                star.ax += wellForce * dx * 1.5f
                star.ay += wellForce * dy * 1.5f
            }
        }

        // 4. Soft Cosmic Boundary Well (keeps the orbs smoothly looping in viewport)
        val marginX = width * 0.08f
        val marginY = height * 0.08f
        for (star in stars) {
            // Restore towards center when exceeding boundary margin
            if (star.x < marginX) {
                val pen = (marginX - star.x)
                star.ax += pen * 4.5f
            } else if (star.x > width - marginX) {
                val pen = (star.x - (width - marginX))
                star.ax -= pen * 4.5f
            }

            if (star.y < marginY) {
                val pen = (marginY - star.y)
                star.ay += pen * 4.5f
            } else if (star.y > height - marginY) {
                val pen = (star.y - (height - marginY))
                star.ay -= pen * 4.5f
            }
        }

        // 5. Integration (Semi-implicit Euler / Verlet) and position updates
        for (star in stars) {
            star.vx += star.ax * dt
            star.vy += star.ay * dt

            // Mild cosmic damping so energy doesn't explode infinitely to light speed
            star.vx *= 0.9995f
            star.vy *= 0.9995f

            star.x += star.vx * dt
            star.y += star.vy * dt

            // Decay flare and proximity intensity smoothly
            star.proximityIntensity = max(0f, star.proximityIntensity - dt * 1.5f)
            star.flareScale = max(1f, star.flareScale - dt * 1.2f)

            // Record trail points
            star.trail.add(
                TrailPoint(
                    x = star.x,
                    y = star.y,
                    alpha = 1f,
                    size = star.baseRadius * star.flareScale
                )
            )
            while (star.trail.size > maxTrailPoints) {
                star.trail.removeAt(0)
            }

            // Spawn faint trailing cosmic dust particle occasionally
            if (Random.nextFloat() < 0.25f) {
                val driftAngle = atan2(-star.vy, -star.vx) + (Random.nextFloat() - 0.5f) * 0.6f
                val driftSpeed = Random.nextFloat() * 40f
                particles.add(
                    CosmicParticle(
                        x = star.x + (Random.nextFloat() - 0.5f) * 10f,
                        y = star.y + (Random.nextFloat() - 0.5f) * 10f,
                        vx = cos(driftAngle) * driftSpeed,
                        vy = sin(driftAngle) * driftSpeed,
                        life = 1f,
                        maxLife = 0.4f + Random.nextFloat() * 0.3f,
                        color = star.color,
                        size = 1.8f + Random.nextFloat() * 2f
                    )
                )
            }
        }

        // 6. Update visual particles
        val pIter = particles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt / p.maxLife
            if (p.life <= 0f) {
                pIter.remove()
            }
        }

        // 7. Update shockwave ripples
        val sIter = shockwaves.iterator()
        while (sIter.hasNext()) {
            val shock = sIter.next()
            shock.currentRadius += (shock.maxRadius - shock.currentRadius) * (dt * 8f) + 40f * dt
            shock.alpha = max(0f, 1f - (shock.currentRadius / shock.maxRadius))
            if (shock.alpha <= 0.02f || shock.currentRadius >= shock.maxRadius) {
                sIter.remove()
            }
        }

        // 8. Compute telemetry and dynamic System Era
        computeTelemetry()
    }

    private fun computeTelemetry() {
        if (stars.size < 3) return
        val s0 = stars[0]
        val s1 = stars[1]
        val s2 = stars[2]

        val dAB = sqrt((s0.x - s1.x) * (s0.x - s1.x) + (s0.y - s1.y) * (s0.y - s1.y))
        val dBC = sqrt((s1.x - s2.x) * (s1.x - s2.x) + (s1.y - s2.y) * (s1.y - s2.y))
        val dCA = sqrt((s2.x - s0.x) * (s2.x - s0.x) + (s2.y - s0.y) * (s2.y - s0.y))

        val minDist = min(dAB, min(dBC, dCA))
        val maxDist = max(dAB, max(dBC, dCA))

        val speed0 = sqrt(s0.vx * s0.vx + s0.vy * s0.vy)
        val speed1 = sqrt(s1.vx * s1.vx + s1.vy * s1.vy)
        val speed2 = sqrt(s2.vx * s2.vx + s2.vy * s2.vy)
        val avgSpeed = (speed0 + speed1 + speed2) / 3f

        // Ratio of max distance to min distance reflects orbital asymmetry/eccentricity
        val asymmetry = if (minDist > 1f) (maxDist / minDist) else 10f
        val chaosScore = min(1f, max(0f, (asymmetry - 1.2f) / 4.5f + (chaosTurbulence / 150f) * 0.35f))

        val era = when {
            avgSpeed > 380f -> SystemEra.NOVA
            minDist < 90f -> SystemEra.SYZYGY
            chaosScore > 0.48f -> SystemEra.CHAOTIC
            else -> SystemEra.STABLE
        }

        telemetry = TelemetryData(
            distanceAB = dAB,
            distanceBC = dBC,
            distanceCA = dCA,
            minDistance = minDist,
            avgSpeed = avgSpeed,
            chaosIndex = chaosScore,
            currentEra = era
        )
    }
}
