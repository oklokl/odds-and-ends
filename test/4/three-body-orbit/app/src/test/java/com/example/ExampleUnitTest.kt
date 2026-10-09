package com.example

import com.example.model.OrbitPreset
import com.example.model.TouchMode
import com.example.physics.ThreeBodySimulation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testSimulationInitialization() {
        val sim = ThreeBodySimulation()
        assertEquals(3, sim.stars.size)
        sim.setBounds(1080f, 1920f)
        sim.loadPreset(OrbitPreset.ELLIPTICAL)

        val s0 = sim.stars[0]
        val s1 = sim.stars[1]
        val s2 = sim.stars[2]

        // Stars should have non-zero initial positions and speeds
        assertTrue(s0.x > 0f)
        assertTrue(s0.y > 0f)
        assertTrue(s0.vx != 0f || s0.vy != 0f)
        assertTrue(s1.vx != 0f || s1.vy != 0f)
        assertTrue(s2.vx != 0f || s2.vy != 0f)
    }

    @Test
    fun testTouchImpulseInjectsChaos() {
        val sim = ThreeBodySimulation()
        sim.setBounds(1080f, 1920f)
        sim.loadPreset(OrbitPreset.ELLIPTICAL)

        val initialV0x = sim.stars[0].vx
        val initialV0y = sim.stars[0].vy

        sim.applyTouchImpulse(540f, 960f, 250f)

        // Velocity should have changed
        assertTrue(sim.stars[0].vx != initialV0x || sim.stars[0].vy != initialV0y)
        assertTrue(sim.shockwaves.isNotEmpty())
        assertTrue(sim.particles.isNotEmpty())
    }

    @Test
    fun testSimulationStepUpdatesPositionsAndTrails() {
        val sim = ThreeBodySimulation()
        sim.setBounds(1080f, 1920f)
        sim.loadPreset(OrbitPreset.ELLIPTICAL)

        val initialX = sim.stars[0].x
        val initialY = sim.stars[0].y

        // Simulate 10 frames
        repeat(10) {
            sim.update(0.016f)
        }

        assertNotEquals(initialX, sim.stars[0].x, 0.001f)
        assertNotEquals(initialY, sim.stars[0].y, 0.001f)
        assertTrue(sim.stars[0].trail.isNotEmpty())
        assertTrue(sim.telemetry.avgSpeed > 0f)
    }
}
