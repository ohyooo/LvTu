package com.ohyooo.lvtu

import java.util.concurrent.CancellationException
import kotlin.math.*

internal object CircleArea {
    private const val EARTH_KM = 6371.0088
    private const val MAX_LATITUDE = 85.05112878

    fun plan(centers: List<DoubleArray>, radiusKm: Double, maxZoom: Int): MutableList<MutableSet<Long>> {
        val result = mutableListOf<MutableSet<Long>>()
        val angle = radiusKm / EARTH_KM
        val threshold = cos(angle)
        var total = 0
        for (zoom in 0..maxZoom) {
            val size = 1 shl zoom
            val tiles = mutableSetOf<Long>()
            for (center in centers) {
                val latitude = Math.toRadians(center[0])
                val longitude = Math.toRadians(center[1])
                val sinLatitude = sin(latitude)
                val cosLatitude = cos(latitude)
                val south = max(-Math.PI / 2, latitude - angle)
                val north = min(Math.PI / 2, latitude + angle)
                val span = if (south <= -Math.PI / 2 || north >= Math.PI / 2) Math.PI
                else asin((sin(angle) / cosLatitude).coerceIn(-1.0, 1.0))
                val centerX = (longitude + Math.PI) / (2 * Math.PI) * size
                val left = floor(centerX - span / (2 * Math.PI) * size).toInt()
                val right = floor(centerX + span / (2 * Math.PI) * size).toInt()
                val top = tileY(Math.toDegrees(north), size)
                val bottom = tileY(Math.toDegrees(south), size)
                for (y in top..bottom) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException("机场范围计算已取消")
                    val tileSouth = atan(sinh(Math.PI * (1 - 2.0 * (y + 1) / size)))
                    val tileNorth = atan(sinh(Math.PI * (1 - 2.0 * y / size)))
                    for (x in left..right) {
                        val wrappedX = Math.floorMod(x, size)
                        val west = wrappedX.toDouble() / size * 2 * Math.PI - Math.PI
                        val east = (wrappedX + 1.0) / size * 2 * Math.PI - Math.PI
                        val delta = if (longitude in west..east) 0.0 else min(
                            abs(atan2(sin(west - longitude), cos(west - longitude))),
                            abs(atan2(sin(east - longitude), cos(east - longitude)))
                        )
                        // Maximize the spherical dot product over the tile's latitude interval
                        val longitudeFactor = cosLatitude * cos(delta)
                        val closest = atan2(sinLatitude, longitudeFactor).coerceIn(tileSouth, tileNorth)
                        fun dot(lat: Double) = sinLatitude * sin(lat) + longitudeFactor * cos(lat)
                        if (maxOf(dot(closest), dot(tileSouth), dot(tileNorth)) + 1e-15 < threshold) continue
                        tiles += (wrappedX.toLong() shl 32) or (y.toLong() and 0xffffffffL)
                        require(total + tiles.size <= 250000) { "范围超过 25 万张瓦片，请减小半径或 Zoom" }
                    }
                }
            }
            total += tiles.size
            result += tiles
        }
        return result
    }

    private fun tileY(latitude: Double, size: Int): Int {
        val angle = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return floor((1 - ln(tan(angle) + 1 / cos(angle)) / Math.PI) / 2 * size).toInt().coerceIn(0, size - 1)
    }
}
