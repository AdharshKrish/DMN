package com.example.graph

import androidx.compose.ui.geometry.Offset
import com.example.data.vault.GraphEdge
import com.example.data.vault.GraphNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class PositionedNode(
    val node: GraphNode,
    val position: Offset,
    val radius: Float
)

object ForceGraphEngine {

    fun computeLayout(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        iterations: Int = 95
    ): Map<String, PositionedNode> {
        if (nodes.isEmpty()) return emptyMap()
        if (nodes.size == 1) {
            val single = nodes.first()
            return mapOf(single.id to PositionedNode(single, Offset.Zero, nodeRadius(single)))
        }

        val positions = LinkedHashMap<String, Offset>(nodes.size)
        val velocities = LinkedHashMap<String, Offset>(nodes.size)

        // Deterministic golden-angle spiral initialization for repeatable, crisp clustering
        val goldenAngle = PI * (3.0 - sqrt(5.0))
        nodes.forEachIndexed { index, node ->
            val r = 90f * sqrt(index + 1f)
            val theta = index * goldenAngle
            positions[node.id] = Offset(
                x = (r * cos(theta)).toFloat(),
                y = (r * sin(theta)).toFloat()
            )
            velocities[node.id] = Offset.Zero
        }

        val nodeIds = nodes.map { it.id }
        val repulsion = 14000f
        val springRestLength = 155f
        val springStiffness = 0.045f
        val centerGravity = 0.018f
        val damping = 0.82f

        repeat(iterations) {
            val forces = HashMap<String, Offset>(nodes.size)
            nodeIds.forEach { forces[it] = Offset.Zero }

            // Pairwise Coulomb repulsion
            for (i in nodeIds.indices) {
                val idA = nodeIds[i]
                val posA = positions[idA]!!
                for (j in i + 1 until nodeIds.size) {
                    val idB = nodeIds[j]
                    val posB = positions[idB]!!
                    val dx = posA.x - posB.x
                    val dy = posA.y - posB.y
                    val distSq = max(dx * dx + dy * dy, 225f)
                    val dist = sqrt(distSq)
                    val forceMag = repulsion / distSq
                    val fx = (dx / dist) * forceMag
                    val fy = (dy / dist) * forceMag
                    forces[idA] = forces[idA]!! + Offset(fx, fy)
                    forces[idB] = forces[idB]!! - Offset(fx, fy)
                }
            }

            // Spring attraction along [[WikiLink]] edges
            for (edge in edges) {
                val posS = positions[edge.sourceId] ?: continue
                val posT = positions[edge.targetId] ?: continue
                val dx = posT.x - posS.x
                val dy = posT.y - posS.y
                val dist = max(sqrt(dx * dx + dy * dy), 1f)
                val displacement = dist - springRestLength
                val forceMag = displacement * springStiffness
                val fx = (dx / dist) * forceMag
                val fy = (dy / dist) * forceMag
                forces[edge.sourceId] = forces[edge.sourceId]!! + Offset(fx, fy)
                forces[edge.targetId] = forces[edge.targetId]!! - Offset(fx, fy)
            }

            // Center gravity + velocity integration
            for (id in nodeIds) {
                val pos = positions[id]!!
                val gravity = Offset(-pos.x * centerGravity, -pos.y * centerGravity)
                val totalForce = forces[id]!! + gravity
                val nextVel = (velocities[id]!! + totalForce) * damping
                velocities[id] = nextVel
                positions[id] = pos + nextVel
            }
        }

        return nodes.associate { node ->
            node.id to PositionedNode(
                node = node,
                position = positions[node.id] ?: Offset.Zero,
                radius = nodeRadius(node)
            )
        }
    }

    private fun nodeRadius(node: GraphNode): Float {
        val base = if (node.existsAsFile) 18f else 13f
        return (base + node.connectionCount * 3.2f).coerceAtMost(38f)
    }
}
