package com.titanshift.game.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GlMesh(verticesAndNormals: FloatArray) {
    // Interleaved 6 floats per vertex: px, py, pz, nx, ny, nz
    val vertexCount: Int = verticesAndNormals.size / 6
    val buffer: FloatBuffer = ByteBuffer
        .allocateDirect(verticesAndNormals.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(verticesAndNormals)
            position(0)
        }
}

object GlMeshBuilder {

    /**
     * Unit cube centered at (0, 0, 0) spanning [-0.5 .. +0.5] on X, Y, Z with crisp per-face normals.
     */
    fun createUnitCube(): GlMesh {
        val data = ArrayList<Float>(36 * 6)
        fun quad(
            nx: Float, ny: Float, nz: Float,
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float
        ) {
            fun v(x: Float, y: Float, z: Float) {
                data.add(x); data.add(y); data.add(z)
                data.add(nx); data.add(ny); data.add(nz)
            }
            v(x0, y0, z0); v(x1, y1, z1); v(x2, y2, z2)
            v(x0, y0, z0); v(x2, y2, z2); v(x3, y3, z3)
        }

        val p = 0.5f
        val n = -0.5f
        // Front (+Z)
        quad(0f, 0f, 1f, n, n, p, p, n, p, p, p, p, n, p, p)
        // Back (-Z)
        quad(0f, 0f, -1f, p, n, n, n, n, n, n, p, n, p, p, n)
        // Right (+X)
        quad(1f, 0f, 0f, p, n, p, p, n, n, p, p, n, p, p, p)
        // Left (-X)
        quad(-1f, 0f, 0f, n, n, n, n, n, p, n, p, p, n, p, n)
        // Top (+Y)
        quad(0f, 1f, 0f, n, p, p, p, p, p, p, p, n, n, p, n)
        // Bottom (-Y)
        quad(0f, -1f, 0f, n, n, n, p, n, n, p, n, p, n, n, p)

        return GlMesh(data.toFloatArray())
    }

    /**
     * 4-sided pyramid pointing +Y, base [-0.5..0.5] at Y=-0.5, apex at Y=+0.5 (great for horns, crowns, fins).
     */
    fun createUnitPyramid(): GlMesh {
        val data = ArrayList<Float>(18 * 6)
        fun tri(
            nx: Float, ny: Float, nz: Float,
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float
        ) {
            fun v(x: Float, y: Float, z: Float) {
                data.add(x); data.add(y); data.add(z)
                data.add(nx); data.add(ny); data.add(nz)
            }
            v(ax, ay, az); v(bx, by, bz); v(cx, cy, cz)
        }

        val p = 0.5f
        val n = -0.5f
        tri(0f, 0.45f, 0.89f, n, n, p, p, n, p, 0f, p, 0f)
        tri(0.89f, 0.45f, 0f, p, n, p, p, n, n, 0f, p, 0f)
        tri(0f, 0.45f, -0.89f, p, n, n, n, n, n, 0f, p, 0f)
        tri(-0.89f, 0.45f, 0f, n, n, n, n, n, p, 0f, p, 0f)
        // Base
        tri(0f, -1f, 0f, n, n, n, p, n, n, p, n, p)
        tri(0f, -1f, 0f, n, n, n, p, n, p, n, n, p)

        return GlMesh(data.toFloatArray())
    }

    /**
     * Faceted coin / crystal disc centered at origin facing Z/Y, radius 0.5, thickness 0.22.
     */
    fun createCoinDisc(segments: Int = 14): GlMesh {
        val data = ArrayList<Float>(segments * 12 * 6)
        val r = 0.5f
        val hz = 0.12f

        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            data.add(x); data.add(y); data.add(z)
            data.add(nx); data.add(ny); data.add(nz)
        }

        for (i in 0 until segments) {
            val a0 = (i.toFloat() / segments) * 2f * PI.toFloat()
            val a1 = ((i + 1).toFloat() / segments) * 2f * PI.toFloat()
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)

            // Front face (+Z)
            v(0f, 0f, hz, 0f, 0f, 1f)
            v(c0 * r, s0 * r, hz, 0f, 0f, 1f)
            v(c1 * r, s1 * r, hz, 0f, 0f, 1f)

            // Back face (-Z)
            v(0f, 0f, -hz, 0f, 0f, -1f)
            v(c1 * r, s1 * r, -hz, 0f, 0f, -1f)
            v(c0 * r, s0 * r, -hz, 0f, 0f, -1f)

            // Rim
            v(c0 * r, s0 * r, hz, c0, s0, 0f)
            v(c0 * r, s0 * r, -hz, c0, s0, 0f)
            v(c1 * r, s1 * r, -hz, c1, s1, 0f)

            v(c0 * r, s0 * r, hz, c0, s0, 0f)
            v(c1 * r, s1 * r, -hz, c1, s1, 0f)
            v(c1 * r, s1 * r, hz, c1, s1, 0f)
        }
        return GlMesh(data.toFloatArray())
    }
}
