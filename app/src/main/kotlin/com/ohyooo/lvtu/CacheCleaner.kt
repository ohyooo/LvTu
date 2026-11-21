package com.ohyooo.lvtu

import com.ohyooo.lvtu.AccountRoutes.Route
import java.util.concurrent.CancellationException
import kotlin.math.*

internal object CacheCleaner {
    fun plan(tiles: MutableList<Tile>, routes: MutableList<Route>, km: Double): Result {
        val segments: MutableList<Segment> = ArrayList<Segment>()
        for (route in routes) {
            require(route.points.size >= 2) { "缺少航迹：" + route.label }
            for (i in 1..<route.points.size) {
                val a = route.points.get(i - 1)
                val b = route.points.get(i)
                val ax = (a[1] + 180) / 360
                var bx = (b[1] + 180) / 360
                if (bx - ax > .5) bx--
                if (ax - bx > .5) bx++
                val latitude = min(85.05112878, max(abs(a[0]), abs(b[0])))
                segments.add(
                    Segment(
                        ax,
                        tileY(a[0]),
                        bx,
                        tileY(b[0]),
                        km * 1000 / (40075016.686 * cos(Math.toRadians(latitude)))
                    )
                )
            }
        }
        val remove: MutableList<Long> = ArrayList<Long>()
        var bytes: Long = 0
        for (tile in tiles) {
            if (Thread.currentThread().isInterrupted()) throw CancellationException("过期清理计算已取消")
            val n = Math.scalb(1f, tile.zoom).toDouble()
            val size = 1 / n
            val x = tile.x / n
            val y = tile.y / n
            var keep = false
            for (line in segments) {
                val left = x + round((line.ax + line.bx) / 2 - x - size / 2)
                if (max(line.ax, line.bx) + line.radius < left || min(
                        line.ax,
                        line.bx
                    ) - line.radius > left + size || max(line.ay, line.by) + line.radius < y || min(
                        line.ay,
                        line.by
                    ) - line.radius > y + size
                ) continue
                if (distance(line, left, y, left + size, y + size) <= line.radius * line.radius) {
                    keep = true
                    break
                }
            }
            if (!keep) {
                remove.add(tile.id)
                bytes += tile.bytes
            }
        }
        return Result(remove, bytes, (tiles.size - remove.size).toLong())
    }

    private fun tileY(latitude: Double): Double {
        val a = Math.toRadians(max(-85.05112878, min(85.05112878, latitude)))
        return (1 - ln(tan(a) + 1 / cos(a)) / Math.PI) / 2
    }

    private fun distance(s: Segment, left: Double, top: Double, right: Double, bottom: Double): Double {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        var low = 0.0
        var high = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(s.ax - left, right - s.ax, s.ay - top, bottom - s.ay)
        var intersects = true
        for (i in 0..3) {
            if (abs(p[i]) < 1e-15) {
                if (q[i] < 0) {
                    intersects = false
                    break
                }
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0) low = max(low, t)
                else high = min(high, t)
                if (low > high) {
                    intersects = false
                    break
                }
            }
        }
        if (intersects) return 0.0
        val best = min(pointRect(s.ax, s.ay, left, top, right, bottom), pointRect(s.bx, s.by, left, top, right, bottom))
        return min(
            best,
            min(
                min(pointSegment(left, top, s), pointSegment(left, bottom, s)),
                min(pointSegment(right, top, s), pointSegment(right, bottom, s))
            )
        )
    }

    private fun pointRect(x: Double, y: Double, left: Double, top: Double, right: Double, bottom: Double): Double {
        val dx = max(0.0, max(left - x, x - right))
        val dy = max(0.0, max(top - y, y - bottom))
        return dx * dx + dy * dy
    }

    private fun pointSegment(x: Double, y: Double, s: Segment): Double {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        val length = dx * dx + dy * dy
        val t = if (length == 0.0) 0.0 else max(0.0, min(1.0, ((x - s.ax) * dx + (y - s.ay) * dy) / length))
        val a = x - s.ax - t * dx
        val b = y - s.ay - t * dy
        return a * a + b * b
    }

    internal data class Tile(val id: Long, val zoom: Int, val x: Int, val y: Int, val bytes: Long)

    internal data class Result(val remove: MutableList<Long>, val bytes: Long, val keep: Long)

    private data class Segment(val ax: Double, val ay: Double, val bx: Double, val by: Double, val radius: Double)
}
