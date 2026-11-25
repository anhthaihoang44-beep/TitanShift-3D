package com.titanshift.game.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.titanshift.game.engine.GameSimulation
import com.titanshift.game.model.BossEncounter
import com.titanshift.game.model.CrestStyle
import com.titanshift.game.model.GamePhase
import com.titanshift.game.model.GameSnapshot
import com.titanshift.game.model.HeroSkin
import com.titanshift.game.model.ObstacleInstance
import com.titanshift.game.model.ObstacleType
import com.titanshift.game.model.RgbColor
import com.titanshift.game.model.ScaleZone
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TitanGlRenderer(
    private val simulation: GameSimulation
) : GLSurfaceView.Renderer {

    private var programId: Int = 0
    private var aPositionLoc: Int = 0
    private var aNormalLoc: Int = 0
    private var uMvpMatrixLoc: Int = 0
    private var uModelMatrixLoc: Int = 0
    private var uColorLoc: Int = 0
    private var uEmissiveLoc: Int = 0

    private lateinit var cubeMesh: GlMesh
    private lateinit var pyramidMesh: GlMesh
    private lateinit var coinMesh: GlMesh

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var lastFrameNanos: Long = 0L
    private var renderClock: Float = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.08f, 0.18f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        programId = buildShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aNormalLoc = GLES20.glGetAttribLocation(programId, "aNormal")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
        uModelMatrixLoc = GLES20.glGetUniformLocation(programId, "uModelMatrix")
        uColorLoc = GLES20.glGetUniformLocation(programId, "uColor")
        uEmissiveLoc = GLES20.glGetUniformLocation(programId, "uEmissive")

        cubeMesh = GlMeshBuilder.createUnitCube()
        pyramidMesh = GlMeshBuilder.createUnitPyramid()
        coinMesh = GlMeshBuilder.createCoinDisc(14)
        lastFrameNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projMatrix, 0, 52f, aspect, 0.5f, 190f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = now
        renderClock += dt

        simulation.step(dt)
        val snap = simulation.snapshot.value

        // Dynamic sky tone shifts subtly on Boss stages or Overdrive
        if (snap.isOverdriveActive) {
            GLES20.glClearColor(0.14f, 0.09f, 0.24f, 1.0f)
        } else if (snap.isBossStage) {
            GLES20.glClearColor(0.12f, 0.05f, 0.14f, 1.0f)
        } else {
            GLES20.glClearColor(0.05f, 0.09f, 0.20f, 1.0f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(programId)
        setupCamera(snap)

        drawEnvironmentScenery(snap)
        drawTrackAndGaps(snap)
        drawCollectibles(snap)
        drawObstacles(snap)
        drawMultiplierGauntlet(snap)
        snap.boss?.let { drawBoss(snap, it) }
        drawHero(snap)
        drawDebris(snap)
    }

    private fun setupCamera(snap: GameSnapshot) {
        val shakeX = if (snap.cameraShake > 0f) sin(renderClock * 47f) * snap.cameraShake * 0.45f else 0f
        val shakeY = if (snap.cameraShake > 0f) cos(renderClock * 53f) * snap.cameraShake * 0.35f else 0f

        when (snap.phase) {
            GamePhase.HOME -> {
                val heroHeight = snap.currentScale * 1.35f
                val camDist = 6.2f + snap.currentScale * 1.4f
                val angle = sin(snap.homeOrbitAngle) * 0.48f
                val eyeX = sin(angle) * camDist
                val eyeZ = -cos(angle) * camDist
                val eyeY = 2.6f + snap.currentScale * 0.95f
                Matrix.setLookAtM(
                    viewMatrix, 0,
                    eyeX, eyeY, eyeZ,
                    0f, heroHeight.coerceAtLeast(1.1f), 0.5f,
                    0f, 1f, 0f
                )
            }

            GamePhase.BOSS_CLASH -> {
                val bossZ = snap.boss?.bossZ ?: (snap.playerZ + 6f)
                val midZ = (snap.playerZ + bossZ) * 0.5f
                Matrix.setLookAtM(
                    viewMatrix, 0,
                    4.8f + shakeX, 5.4f + shakeY, snap.playerZ - 7.8f,
                    0f, 2.8f, midZ,
                    0f, 1f, 0f
                )
            }

            else -> {
                // Dynamic ScaleMan-style third-person chase camera: pulls higher & further as hero grows!
                val scalePull = (snap.currentScale - 1.0f).coerceAtLeast(-0.4f)
                val eyeX = 0.65f + shakeX
                val eyeY = 4.6f + scalePull * 1.45f + snap.playerY * 0.45f + shakeY
                val eyeZ = snap.playerZ - (8.8f + scalePull * 2.15f)
                val lookY = 1.6f + scalePull * 0.85f + snap.playerY * 0.5f
                val lookZ = snap.playerZ + 8.5f
                Matrix.setLookAtM(
                    viewMatrix, 0,
                    eyeX, eyeY, eyeZ,
                    0f, lookY, lookZ,
                    0f, 1f, 0f
                )
            }
        }

        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)
    }

    private fun drawEnvironmentScenery(snap: GameSnapshot) {
        // Distant cyber skyline pillars flanking the skyway
        val startIdx = ((snap.playerZ - 20f) / 24f).toInt().coerceAtLeast(-1)
        val endIdx = startIdx + 8
        for (idx in startIdx..endIdx) {
            val z = idx * 24f
            val hLeft = 18f + (abs(idx * 37) % 14)
            val hRight = 16f + (abs(idx * 53) % 16)

            drawBox(
                x = -14.5f, y = hLeft * 0.5f - 14f, z = z,
                sx = 3.2f, sy = hLeft, sz = 3.2f,
                r = 0.09f, g = 0.15f, b = 0.32f, alpha = 1f, emissive = 0.12f
            )
            // Neon vertical strip on pillar
            drawBox(
                x = -12.8f, y = hLeft * 0.5f - 14f, z = z,
                sx = 0.18f, sy = hLeft * 0.85f, sz = 0.35f,
                r = 0.0f, g = 0.85f, b = 1.0f, alpha = 1f, emissive = 0.85f
            )

            drawBox(
                x = 14.5f, y = hRight * 0.5f - 14f, z = z + 10f,
                sx = 3.2f, sy = hRight, sz = 3.2f,
                r = 0.10f, g = 0.14f, b = 0.30f, alpha = 1f, emissive = 0.12f
            )
            drawBox(
                x = 12.8f, y = hRight * 0.5f - 14f, z = z + 10f,
                sx = 0.18f, sy = hRight * 0.85f, sz = 0.35f,
                r = 0.95f, g = 0.25f, b = 0.68f, alpha = 1f, emissive = 0.85f
            )
        }
    }

    private fun drawTrackAndGaps(snap: GameSnapshot) {
        val totalEndZ = if (snap.isBossStage) {
            (snap.boss?.bossZ ?: snap.trackLength) + 18f
        } else {
            (snap.multiplierWalls.lastOrNull()?.z ?: snap.trackLength) + 22f
        }

        val visibleMinZ = (snap.playerZ - 16f).coerceAtLeast(-10f)
        val visibleMaxZ = (snap.playerZ + 115f).coerceAtMost(totalEndZ)

        // Carve track around chasm gaps and fragile glass bridges
        val carveObstacles = snap.obstacles.filter {
            it.type == ObstacleType.ABYSS_CHASM_GAP || it.type == ObstacleType.FRAGILE_GLASS_BRIDGE
        }.sortedBy { it.zStart }

        var cursorZ = visibleMinZ
        for (obs in carveObstacles) {
            if (obs.zEnd < visibleMinZ || obs.zStart > visibleMaxZ) continue
            val segEnd = obs.zStart.coerceAtMost(visibleMaxZ)
            if (segEnd > cursorZ + 0.2f) {
                drawSolidTrackSegment(cursorZ, segEnd)
            }
            cursorZ = obs.zEnd.coerceAtLeast(cursorZ)
        }
        if (visibleMaxZ > cursorZ + 0.2f) {
            drawSolidTrackSegment(cursorZ, visibleMaxZ)
        }

        // Draw finish checkered portal at trackLength
        if (snap.trackLength in visibleMinZ..visibleMaxZ) {
            drawFinishPortal(snap.trackLength, snap.isBossStage)
        }
    }

    private fun drawSolidTrackSegment(z0: Float, z1: Float) {
        val len = z1 - z0
        val midZ = (z0 + z1) * 0.5f
        // Main road deck
        drawBox(
            x = 0f, y = -0.35f, z = midZ,
            sx = 5.4f, sy = 0.7f, sz = len,
            r = 0.14f, g = 0.19f, b = 0.30f, alpha = 1f, emissive = 0.08f
        )
        // Center speed lane stripe
        drawBox(
            x = 0f, y = 0.01f, z = midZ,
            sx = 0.38f, sy = 0.03f, sz = len,
            r = 0.20f, g = 0.38f, b = 0.62f, alpha = 1f, emissive = 0.25f
        )
        // Glowing side rails
        drawBox(
            x = -2.75f, y = 0.14f, z = midZ,
            sx = 0.22f, sy = 0.28f, sz = len,
            r = 0.0f, g = 0.82f, b = 1.0f, alpha = 1f, emissive = 0.65f
        )
        drawBox(
            x = 2.75f, y = 0.14f, z = midZ,
            sx = 0.22f, sy = 0.28f, sz = len,
            r = 0.0f, g = 0.82f, b = 1.0f, alpha = 1f, emissive = 0.65f
        )
    }

    private fun drawFinishPortal(finishZ: Float, isBossStage: Boolean) {
        val accentR = if (isBossStage) 1.0f else 0.1f
        val accentG = if (isBossStage) 0.25f else 0.95f
        val accentB = if (isBossStage) 0.35f else 0.55f

        drawBox(-2.6f, 2.8f, finishZ, 0.45f, 5.6f, 0.45f, accentR, accentG, accentB, 1f, 0.6f)
        drawBox(2.6f, 2.8f, finishZ, 0.45f, 5.6f, 0.45f, accentR, accentG, accentB, 1f, 0.6f)
        drawBox(0f, 5.5f, finishZ, 5.7f, 0.65f, 0.5f, accentR, accentG, accentB, 1f, 0.75f)
        // Checkered ground line
        for (k in -4..4) {
            val isWhite = (k % 2 == 0)
            val c = if (isWhite) 0.96f else 0.12f
            drawBox(k * 0.52f, 0.02f, finishZ, 0.5f, 0.04f, 0.9f, c, c, c, 1f, 0.35f)
        }
    }

    private fun drawCollectibles(snap: GameSnapshot) {
        val spin = renderClock * 165f
        for (orb in snap.collectibles) {
            if (orb.collected) continue
            if (orb.z < snap.playerZ - 8f || orb.z > snap.playerZ + 105f) continue
            val bob = sin(renderClock * 4.5f + orb.z * 0.4f) * 0.14f
            if (orb.isShieldCrystal) {
                drawMeshTransformed(
                    mesh = pyramidMesh,
                    x = orb.x, y = orb.y + bob, z = orb.z,
                    rx = 0f, ry = spin, rz = 0f,
                    sx = 0.72f, sy = 0.95f, sz = 0.72f,
                    r = 0.0f, g = 0.92f, b = 1.0f, alpha = 0.92f, emissive = 0.85f
                )
            } else {
                drawMeshTransformed(
                    mesh = coinMesh,
                    x = orb.x, y = orb.y + bob, z = orb.z,
                    rx = 0f, ry = spin, rz = 0f,
                    sx = 0.68f, sy = 0.68f, sz = 0.68f,
                    r = 1.0f, g = 0.82f, b = 0.12f, alpha = 1f, emissive = 0.55f
                )
            }
        }
    }

    private fun drawObstacles(snap: GameSnapshot) {
        for (obs in snap.obstacles) {
            if (obs.zEnd < snap.playerZ - 12f || obs.zStart > snap.playerZ + 110f) continue
            when (obs.type) {
                ObstacleType.LOW_LASER_GATE -> drawLowLaserGate(obs, downOffset = 0f)
                ObstacleType.MOVING_PISTON_GATE -> {
                    val openFactor = 0.5f + 0.5f * sin(renderClock * 3.1f + obs.phaseOffset)
                    drawLowLaserGate(obs, downOffset = openFactor * 1.65f, isPiston = true)
                }
                ObstacleType.HEAVY_BRICK_WALL -> if (!obs.shatteredOrSmashed) drawHeavyBrickWall(obs)
                ObstacleType.FRAGILE_GLASS_BRIDGE -> if (!obs.shatteredOrSmashed) drawGlassBridge(obs)
                ObstacleType.ABYSS_CHASM_GAP -> drawChasmRim(obs)
                ObstacleType.PRECISION_SYNC_ARCH -> drawPrecisionSyncArch(obs, snap.currentScale)
                ObstacleType.RIVAL_ENFORCER -> if (!obs.shatteredOrSmashed) drawRivalEnforcer(obs)
            }
        }
    }

    private fun drawLowLaserGate(obs: ObstacleInstance, downOffset: Float, isPiston: Boolean = false) {
        val z = obs.zStart + obs.zLength * 0.5f
        val beamLowY = 1.48f + downOffset
        // Side towers
        drawBox(-2.45f, 2.3f, z, 0.52f, 4.6f, 0.65f, 0.22f, 0.26f, 0.36f, 1f, 0.1f)
        drawBox(2.45f, 2.3f, z, 0.52f, 4.6f, 0.65f, 0.22f, 0.26f, 0.36f, 1f, 0.1f)

        if (!obs.shatteredOrSmashed) {
            // Overhead heavy barrier + glowing plasma underside
            val r = if (isPiston) 1.0f else 1.0f
            val g = if (isPiston) 0.55f else 0.16f
            val b = if (isPiston) 0.10f else 0.32f
            drawBox(0f, beamLowY + 1.35f, z, 4.8f, 2.2f, 0.62f, 0.85f, 0.18f, 0.24f, 0.95f, 0.35f)
            drawBox(0f, beamLowY + 0.12f, z, 4.85f, 0.22f, 0.72f, r, g, b, 1f, 0.95f)
        }
    }

    private fun drawHeavyBrickWall(obs: ObstacleInstance) {
        val z = obs.zStart + obs.zLength * 0.5f
        for (row in 0 until 4) {
            val y = 0.45f + row * 0.88f
            val stagger = if (row % 2 == 0) -0.25f else 0.25f
            for (col in -1..1) {
                val x = col * 1.42f + stagger
                val shade = if ((row + col) % 2 == 0) 0.92f else 0.78f
                drawBox(
                    x = x.coerceIn(-1.9f, 1.9f), y = y, z = z,
                    sx = 1.32f, sy = 0.80f, sz = 1.15f,
                    r = 0.92f * shade, g = 0.42f * shade, b = 0.18f * shade,
                    alpha = 1f, emissive = 0.18f
                )
            }
        }
    }

    private fun drawGlassBridge(obs: ObstacleInstance) {
        val midZ = (obs.zStart + obs.zEnd) * 0.5f
        // Side crystal rails
        drawBox(-2.2f, 0.02f, midZ, 0.18f, 0.16f, obs.zLength, 0.55f, 0.95f, 1.0f, 0.9f, 0.65f)
        drawBox(2.2f, 0.02f, midZ, 0.18f, 0.16f, obs.zLength, 0.55f, 0.95f, 1.0f, 0.9f, 0.65f)
        // Translucent ice-blue glass deck
        drawBox(0f, -0.06f, midZ, 4.25f, 0.14f, obs.zLength, 0.45f, 0.90f, 1.0f, 0.48f, 0.55f)
    }

    private fun drawChasmRim(obs: ObstacleInstance) {
        // Glowing amber jump ramp lip at zStart & landing lip at zEnd
        drawBox(0f, 0.06f, obs.zStart - 0.35f, 5.2f, 0.18f, 0.75f, 1.0f, 0.72f, 0.08f, 1f, 0.80f)
        drawBox(0f, 0.06f, obs.zEnd + 0.35f, 5.2f, 0.18f, 0.75f, 1.0f, 0.72f, 0.08f, 1f, 0.80f)
    }

    private fun drawPrecisionSyncArch(obs: ObstacleInstance, currentScale: Float) {
        val z = obs.zStart + obs.zLength * 0.5f
        val matched = currentScale in obs.minSafeScale..obs.maxSafeScale
        val r = if (matched) 0.12f else 1.0f
        val g = if (matched) 1.0f else 0.32f
        val b = if (matched) 0.52f else 0.28f

        val targetHeight = obs.maxSafeScale * 1.95f
        drawBox(-2.1f, targetHeight * 0.5f, z, 0.38f, targetHeight, 0.45f, r, g, b, 0.95f, 0.75f)
        drawBox(2.1f, targetHeight * 0.5f, z, 0.38f, targetHeight, 0.45f, r, g, b, 0.95f, 0.75f)
        drawBox(0f, targetHeight, z, 4.58f, 0.38f, 0.48f, r, g, b, 0.95f, 0.85f)
        // Holographic energy curtain inside window
        val minH = obs.minSafeScale * 1.55f
        val bandH = (targetHeight - minH).coerceAtLeast(0.4f)
        drawBox(0f, minH + bandH * 0.5f, z, 3.8f, bandH, 0.10f, r, g, b, 0.26f, 0.65f)
    }

    private fun drawRivalEnforcer(obs: ObstacleInstance) {
        val z = obs.zStart + obs.zLength * 0.5f
        val x = obs.lateralX
        // Heavy crimson patrol mech
        drawBox(x, 1.25f, z, 1.35f, 1.35f, 0.95f, 0.88f, 0.14f, 0.28f, 1f, 0.25f)
        drawBox(x, 2.25f, z, 0.82f, 0.68f, 0.78f, 0.22f, 0.18f, 0.26f, 1f, 0.15f)
        drawBox(x, 2.28f, z - 0.38f, 0.68f, 0.22f, 0.12f, 1.0f, 0.85f, 0.1f, 1f, 0.95f)
        drawBox(x - 0.42f, 0.42f, z, 0.42f, 0.84f, 0.52f, 0.35f, 0.12f, 0.2f, 1f, 0.1f)
        drawBox(x + 0.42f, 0.42f, z, 0.42f, 0.84f, 0.52f, 0.35f, 0.12f, 0.2f, 1f, 0.1f)
    }

    private fun drawMultiplierGauntlet(snap: GameSnapshot) {
        for (wall in snap.multiplierWalls) {
            if (wall.broken) continue
            if (wall.z < snap.playerZ - 8f || wall.z > snap.playerZ + 115f) continue
            val c = wall.color
            drawBox(
                x = 0f, y = 2.0f, z = wall.z,
                sx = 4.9f, sy = 4.0f, sz = 0.65f,
                r = c.r, g = c.g, b = c.b, alpha = 0.82f, emissive = 0.52f
            )
            // Bright crystal frame
            drawBox(0f, 4.05f, wall.z, 5.1f, 0.22f, 0.75f, 1f, 1f, 1f, 0.95f, 0.8f)
        }
    }

    private fun drawBoss(snap: GameSnapshot, boss: BossEncounter) {
        val bz = boss.bossZ + boss.bossFlyOffsetZ
        val by = boss.bossFlyHeight
        val s = boss.bossScale
        val pulse = 0.5f + 0.5f * sin(renderClock * 7f)

        // Colossal Dark-Crimson Mecha Boss facing -Z toward player
        drawBox(0f, by + 1.1f * s, bz, 1.45f * s, 1.35f * s, 0.95f * s, 0.24f, 0.08f, 0.18f, 1f, 0.18f)
        // Boss chest reactor
        drawBox(0f, by + 1.18f * s, bz - 0.5f * s, 0.58f * s, 0.58f * s, 0.15f * s, 1.0f, 0.22f + pulse * 0.4f, 0.15f, 1f, 0.95f)
        // Head + Horns
        drawBox(0f, by + 2.05f * s, bz, 0.78f * s, 0.68f * s, 0.75f * s, 0.85f, 0.14f, 0.26f, 1f, 0.28f)
        drawMeshTransformed(
            pyramidMesh,
            x = -0.45f * s, y = by + 2.58f * s, z = bz,
            rx = 0f, ry = 0f, rz = 18f,
            sx = 0.28f * s, sy = 0.68f * s, sz = 0.28f * s,
            r = 1.0f, g = 0.76f, b = 0.12f, alpha = 1f, emissive = 0.75f
        )
        drawMeshTransformed(
            pyramidMesh,
            x = 0.45f * s, y = by + 2.58f * s, z = bz,
            rx = 0f, ry = 0f, rz = -18f,
            sx = 0.28f * s, sy = 0.68f * s, sz = 0.28f * s,
            r = 1.0f, g = 0.76f, b = 0.12f, alpha = 1f, emissive = 0.75f
        )
        // Giant crusher arms & legs
        val armSwing = sin(renderClock * 6f) * 22f
        drawMeshTransformed(
            cubeMesh,
            x = -1.05f * s, y = by + 1.15f * s, z = bz - 0.25f * s,
            rx = armSwing, ry = 0f, rz = 0f,
            sx = 0.48f * s, sy = 1.25f * s, sz = 0.48f * s,
            r = 0.75f, g = 0.12f, b = 0.22f, alpha = 1f, emissive = 0.22f
        )
        drawMeshTransformed(
            cubeMesh,
            x = 1.05f * s, y = by + 1.15f * s, z = bz - 0.25f * s,
            rx = -armSwing, ry = 0f, rz = 0f,
            sx = 0.48f * s, sy = 1.25f * s, sz = 0.48f * s,
            r = 0.75f, g = 0.12f, b = 0.22f, alpha = 1f, emissive = 0.22f
        )
        drawBox(-0.44f * s, by + 0.42f * s, bz, 0.46f * s, 0.85f * s, 0.52f * s, 0.18f, 0.08f, 0.15f, 1f, 0.12f)
        drawBox(0.44f * s, by + 0.42f * s, bz, 0.46f * s, 0.85f * s, 0.52f * s, 0.18f, 0.08f, 0.15f, 1f, 0.12f)

        // Energy clash beam during BOSS_CLASH
        if (snap.phase == GamePhase.BOSS_CLASH && !boss.bossDefeated) {
            val beamMidZ = (snap.playerZ + bz) * 0.5f
            val beamLen = (bz - snap.playerZ).coerceAtLeast(1f)
            drawBox(
                x = 0f, y = 2.0f, z = beamMidZ,
                sx = 0.65f + pulse * 0.35f, sy = 0.65f + pulse * 0.35f, sz = beamLen,
                r = 1.0f, g = 0.78f, b = 0.15f, alpha = 0.78f, emissive = 0.98f
            )
        }
    }

    private fun drawHero(snap: GameSnapshot) {
        if (snap.invulnerableTimer > 0f && ((renderClock * 18f).toInt() % 2 == 0)) {
            return
        }

        val s = snap.currentScale
        val skin: HeroSkin = snap.activeSkin
        val pz = snap.playerZ
        val py = snap.playerY

        val primary = if (snap.isOverdriveActive) RgbColor(1.0f, 0.82f, 0.16f) else skin.primaryArmor
        val trim = skin.secondaryTrim
        val zoneCore = when {
            snap.isOverdriveActive -> RgbColor(1.0f, 0.95f, 0.35f)
            snap.scaleZone == ScaleZone.MICRO -> RgbColor(0.0f, 0.95f, 1.0f)
            snap.scaleZone == ScaleZone.SYNC -> skin.coreGlow
            else -> RgbColor(1.0f, 0.48f, 0.10f)
        }

        // Ground shadow disc
        drawBox(
            x = 0f, y = 0.03f, z = pz,
            sx = 0.95f * s, sy = 0.02f, sz = 0.95f * s,
            r = 0.02f, g = 0.03f, b = 0.08f, alpha = 0.55f, emissive = 0f
        )

        val isMoving = snap.phase == GamePhase.RUNNING || snap.phase == GamePhase.GOAL_GAUNTLET
        val swingDeg = if (isMoving) sin(snap.runCyclePhase) * 38f else sin(snap.runCyclePhase) * 8f
        val bobY = if (isMoving) abs(cos(snap.runCyclePhase)) * 0.09f * s else 0f
        val baseY = py + bobY

        // Legs (left & right animated around hip height)
        drawMeshTransformed(
            cubeMesh,
            x = -0.22f * s, y = baseY + 0.46f * s, z = pz,
            rx = swingDeg, ry = 0f, rz = 0f,
            sx = 0.26f * s, sy = 0.86f * s, sz = 0.28f * s,
            r = primary.r * 0.82f, g = primary.g * 0.82f, b = primary.b * 0.82f, alpha = 1f, emissive = 0.15f
        )
        drawMeshTransformed(
            cubeMesh,
            x = 0.22f * s, y = baseY + 0.46f * s, z = pz,
            rx = -swingDeg, ry = 0f, rz = 0f,
            sx = 0.26f * s, sy = 0.86f * s, sz = 0.28f * s,
            r = primary.r * 0.82f, g = primary.g * 0.82f, b = primary.b * 0.82f, alpha = 1f, emissive = 0.15f
        )

        // Torso & Shoulder Pauldrons
        drawBox(
            x = 0f, y = baseY + 1.18f * s, z = pz,
            sx = 0.72f * s, sy = 0.78f * s, sz = 0.42f * s,
            r = primary.r, g = primary.g, b = primary.b, alpha = 1f, emissive = 0.22f
        )
        drawBox(
            x = -0.46f * s, y = baseY + 1.46f * s, z = pz,
            sx = 0.28f * s, sy = 0.26f * s, sz = 0.46f * s,
            r = trim.r, g = trim.g, b = trim.b, alpha = 1f, emissive = 0.35f
        )
        drawBox(
            x = 0.46f * s, y = baseY + 1.46f * s, z = pz,
            sx = 0.28f * s, sy = 0.26f * s, sz = 0.46f * s,
            r = trim.r, g = trim.g, b = trim.b, alpha = 1f, emissive = 0.35f
        )

        // Glowing Morph Core on Chest & Back (visible from chase camera!)
        drawBox(
            x = 0f, y = baseY + 1.22f * s, z = pz - 0.23f * s,
            sx = 0.30f * s, sy = 0.32f * s, sz = 0.08f * s,
            r = zoneCore.r, g = zoneCore.g, b = zoneCore.b, alpha = 1f, emissive = 0.95f
        )
        drawBox(
            x = 0f, y = baseY + 1.22f * s, z = pz + 0.23f * s,
            sx = 0.30f * s, sy = 0.32f * s, sz = 0.08f * s,
            r = zoneCore.r, g = zoneCore.g, b = zoneCore.b, alpha = 1f, emissive = 0.95f
        )

        // Swinging Arms
        drawMeshTransformed(
            cubeMesh,
            x = -0.52f * s, y = baseY + 1.12f * s, z = pz,
            rx = -swingDeg, ry = 0f, rz = 0f,
            sx = 0.22f * s, sy = 0.72f * s, sz = 0.24f * s,
            r = primary.r * 0.92f, g = primary.g * 0.92f, b = primary.b * 0.92f, alpha = 1f, emissive = 0.2f
        )
        drawMeshTransformed(
            cubeMesh,
            x = 0.52f * s, y = baseY + 1.12f * s, z = pz,
            rx = swingDeg, ry = 0f, rz = 0f,
            sx = 0.22f * s, sy = 0.72f * s, sz = 0.24f * s,
            r = primary.r * 0.92f, g = primary.g * 0.92f, b = primary.b * 0.92f, alpha = 1f, emissive = 0.2f
        )

        // Helmet & Visor
        val headY = baseY + 1.80f * s
        drawBox(
            x = 0f, y = headY, z = pz,
            sx = 0.46f * s, sy = 0.44f * s, sz = 0.46f * s,
            r = primary.r, g = primary.g, b = primary.b, alpha = 1f, emissive = 0.25f
        )
        drawBox(
            x = 0f, y = headY + 0.03f * s, z = pz + 0.24f * s,
            sx = 0.38f * s, sy = 0.16f * s, sz = 0.08f * s,
            r = zoneCore.r, g = zoneCore.g, b = zoneCore.b, alpha = 1f, emissive = 0.95f
        )

        // Skin-specific 3D Helmet Crest!
        drawSkinCrest(skin.crestStyle, headY, pz, s, trim, zoneCore)

        // Active Shield Aura
        if (snap.hasShield) {
            drawBox(
                x = 0f, y = baseY + 1.05f * s, z = pz,
                sx = 1.25f * s, sy = 2.15f * s, sz = 1.05f * s,
                r = 0.0f, g = 0.88f, b = 1.0f, alpha = 0.20f, emissive = 0.85f
            )
        }
    }

    private fun drawSkinCrest(
        style: CrestStyle,
        headY: Float,
        pz: Float,
        s: Float,
        trim: RgbColor,
        glow: RgbColor
    ) {
        when (style) {
            CrestStyle.FIN -> {
                drawBox(0f, headY + 0.30f * s, pz - 0.04f * s, 0.10f * s, 0.26f * s, 0.42f * s, glow.r, glow.g, glow.b, 1f, 0.88f)
            }
            CrestStyle.HORNS -> {
                drawMeshTransformed(pyramidMesh, -0.22f * s, headY + 0.36f * s, pz, 0f, 0f, 20f, 0.16f * s, 0.42f * s, 0.16f * s, trim.r, trim.g, trim.b, 1f, 0.7f)
                drawMeshTransformed(pyramidMesh, 0.22f * s, headY + 0.36f * s, pz, 0f, 0f, -20f, 0.16f * s, 0.42f * s, 0.16f * s, trim.r, trim.g, trim.b, 1f, 0.7f)
            }
            CrestStyle.VISOR -> {
                drawBox(0f, headY + 0.06f * s, pz, 0.54f * s, 0.16f * s, 0.52f * s, glow.r, glow.g, glow.b, 1f, 0.92f)
            }
            CrestStyle.HALO -> {
                val floatY = headY + (0.44f + sin(renderClock * 4f) * 0.05f) * s
                drawMeshTransformed(coinMesh, 0f, floatY, pz, 90f, 0f, 0f, 0.68f * s, 0.68f * s, 0.25f * s, glow.r, glow.g, glow.b, 0.88f, 0.95f)
            }
            CrestStyle.CROWN -> {
                drawMeshTransformed(pyramidMesh, 0f, headY + 0.38f * s, pz, 0f, 0f, 0f, 0.18f * s, 0.40f * s, 0.18f * s, glow.r, glow.g, glow.b, 1f, 0.85f)
                drawMeshTransformed(pyramidMesh, -0.18f * s, headY + 0.32f * s, pz, 0f, 0f, 14f, 0.14f * s, 0.30f * s, 0.14f * s, trim.r, trim.g, trim.b, 1f, 0.75f)
                drawMeshTransformed(pyramidMesh, 0.18f * s, headY + 0.32f * s, pz, 0f, 0f, -14f, 0.14f * s, 0.30f * s, 0.14f * s, trim.r, trim.g, trim.b, 1f, 0.75f)
            }
            CrestStyle.SOVEREIGN -> {
                drawMeshTransformed(pyramidMesh, 0f, headY + 0.42f * s, pz, 0f, 45f, 0f, 0.22f * s, 0.48f * s, 0.22f * s, glow.r, glow.g, glow.b, 1f, 0.95f)
                drawMeshTransformed(pyramidMesh, -0.26f * s, headY + 0.36f * s, pz, 0f, 0f, 24f, 0.16f * s, 0.40f * s, 0.16f * s, trim.r, trim.g, trim.b, 1f, 0.80f)
                drawMeshTransformed(pyramidMesh, 0.26f * s, headY + 0.36f * s, pz, 0f, 0f, -24f, 0.16f * s, 0.40f * s, 0.16f * s, trim.r, trim.g, trim.b, 1f, 0.80f)
            }
        }
    }

    private fun drawDebris(snap: GameSnapshot) {
        for (d in snap.debris) {
            drawMeshTransformed(
                mesh = cubeMesh,
                x = d.x, y = d.y, z = d.z,
                rx = d.rotX, ry = d.rotY, rz = 0f,
                sx = d.size, sy = d.size, sz = d.size,
                r = d.color.r, g = d.color.g, b = d.color.b,
                alpha = d.life.coerceIn(0f, 1f),
                emissive = 0.45f
            )
        }
    }

    private fun drawBox(
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float,
        alpha: Float, emissive: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        submitMesh(cubeMesh, r, g, b, alpha, emissive)
    }

    private fun drawMeshTransformed(
        mesh: GlMesh,
        x: Float, y: Float, z: Float,
        rx: Float, ry: Float, rz: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float,
        alpha: Float, emissive: Float
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        if (ry != 0f) Matrix.rotateM(modelMatrix, 0, ry, 0f, 1f, 0f)
        if (rx != 0f) Matrix.rotateM(modelMatrix, 0, rx, 1f, 0f, 0f)
        if (rz != 0f) Matrix.rotateM(modelMatrix, 0, rz, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)
        submitMesh(mesh, r, g, b, alpha, emissive)
    }

    private fun submitMesh(
        mesh: GlMesh,
        r: Float, g: Float, b: Float,
        alpha: Float, emissive: Float
    ) {
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModelMatrixLoc, 1, false, modelMatrix, 0)
        GLES20.glUniform4f(uColorLoc, r, g, b, alpha)
        GLES20.glUniform1f(uEmissiveLoc, emissive)

        val strideBytes = 6 * 4
        mesh.buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, strideBytes, mesh.buffer)

        mesh.buffer.position(3)
        GLES20.glEnableVertexAttribArray(aNormalLoc)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, strideBytes, mesh.buffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
    }

    private fun buildShaderProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vWorldNormal;
            varying float vDepth;
            void main() {
                vec4 clipPos = uMvpMatrix * vec4(aPosition, 1.0);
                gl_Position = clipPos;
                vWorldNormal = normalize(mat3(uModelMatrix) * aNormal);
                vDepth = clipPos.w;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uEmissive;
            varying vec3 vWorldNormal;
            varying float vDepth;
            void main() {
                vec3 lightDir = normalize(vec3(0.42, 0.85, -0.35));
                vec3 n = normalize(vWorldNormal);
                float ndotl = max(dot(n, lightDir), 0.0);
                float skyHemi = 0.36 + 0.24 * (n.y * 0.5 + 0.5);
                float lighting = mix(skyHemi + ndotl * 0.62, 1.25, uEmissive);
                vec3 litColor = uColor.rgb * lighting;

                // Subtle distance atmospheric fog into cyber horizon
                float fogFactor = clamp((vDepth - 55.0) / 95.0, 0.0, 0.72);
                vec3 fogColor = vec3(0.06, 0.10, 0.24);
                vec3 finalRgb = mix(litColor, fogColor, fogFactor * (1.0 - uEmissive * 0.6));
                gl_FragColor = vec4(finalRgb, uColor.a);
            }
        """
    }
}
