package com.ohyooo.lvtu

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.cos
import kotlin.math.hypot

internal object AccountRoutes {

    fun read(context: Context, loader: ClassLoader): Snapshot {
        val type = loader.loadClass("com.tencent.mmkv.MMKV")
        val open = method(type, "mmkvWithID", type, true, String::class.java, Int::class.javaPrimitiveType!!)
        val decode = type.getMethod("getString", String::class.java, String::class.java)
        val multi = open.invoke(null, context.packageName + "_multi", 2)
        val weex = open.invoke(null, context.packageName + "_weex", 2)
        var uid = decode.invoke(multi, "USER_ID", "") as String?
        if (uid == null || !uid.matches("[0-9]+".toRegex())) {
            val id = type.getMethod("getLong", String::class.java, Long::class.javaPrimitiveType)
                .invoke(multi, "USER_ID", 0L) as Long
            uid = if (id > 0) id.toString() else ""
        }
        check(uid.isNotEmpty()) { "无法确认当前登录账号，未读取航线" }
        val trips = decode.invoke(weex, "public2_activityList_unused_" + uid, null) as String?
        checkNotNull(trips) { "当前账号尚无已同步的航线列表，不能按空列表清空" }
        val flights = linkedMapOf<String, String>()
        val keys = method(type, "allKeys", Array<String>::class.java, false).invoke(multi) as Array<*>
        for (entry in keys) {
            val key = entry as String
            if (key.startsWith("OFFLINE_FLIGHT_DATA_CACHE")) {
                val value = decode.invoke(multi, key, null) as String?
                if (value != null) flights[key] = value
            }
        }
        return Snapshot(trips, flights, context.getSharedPreferences("lvtu-offline", 0).getString("route", "[]"))
    }

    private fun method(
        type: Class<*>,
        name: String,
        result: Class<*>,
        isStatic: Boolean,
        vararg arguments: Class<*>
    ): Method {
        try {
            return type.getMethod(name, *arguments)
        } catch (missing: NoSuchMethodException) {
            val matches = mutableListOf<Method>()
            for (candidate in type.declaredMethods) if (candidate.returnType == result && Modifier.isStatic(
                    candidate.modifiers
                ) == isStatic && candidate.parameterTypes.contentEquals(arguments)
            ) matches.add(candidate)
            if (matches.size != 1) throw NoSuchMethodException(name + " signature candidates=" + matches)
            val selected = matches.get(0)
            selected.isAccessible = true
            return selected
        }
    }

    fun parse(snapshot: Snapshot): MutableList<Route> {
        val data = JSONObject(snapshot.trips)
        val items = data.getJSONArray("mixActivitySheet")
        val routes = mutableListOf<Route>()
        val seen = mutableSetOf<String>()
        val captured = JSONArray(snapshot.captured)
        for (i in 0..<items.length()) {
            val item = items.getJSONObject(i)
            if (item.optInt("activityType", -1) != 0) continue
            val query = findFlightQuery(item)
            checkNotNull(query) { "账号航线字段不完整，已停止清理计算" }
            val parameters = Uri.parse("lvtu://flight?" + query)
            val dep = parameters.getQueryParameter("deptAirportCode")
            val arr = parameters.getQueryParameter("destAirportCode")
            val flight = parameters.getQueryParameter("flightNo")
            val date = parameters.getQueryParameter("deptFlightDate")
            check(!(dep == null || arr == null || flight == null)) { "航线缺少机场信息" }
            if (!seen.add(flight + date + dep + arr)) continue
            var path = mutableListOf<DoubleArray>()
            for (raw in snapshot.flights.values) {
                val info = JSONObject(raw).getJSONObject("flightInfo")
                val a = info.optString("deptCode")
                val b = info.optString("destCode")
                if (!(dep == a && arr == b || dep == b && arr == a)) continue
                val stops = info.getJSONObject("realWayPoint").getJSONArray("flightStopList")
                val begin = coordinate(stops.getJSONObject(0))
                val end = coordinate(stops.getJSONObject(stops.length() - 1))
                for (j in 0..<captured.length()) {
                    val candidate = captured.getJSONArray(j)
                    if (candidate.length() < 2) continue
                    val first = pair(candidate.getJSONArray(0))
                    val last = pair(candidate.getJSONArray(candidate.length() - 1))
                    if (distance(first, begin) + distance(last, end) < 20 || distance(first, end) + distance(
                            last,
                            begin
                        ) < 20
                    ) {
                        val selected = mutableListOf<DoubleArray>()
                        for (k in 0..<candidate.length()) selected.add(pair(candidate.getJSONArray(k)))
                        path = selected
                        break
                    }
                }
                if (path.isNotEmpty()) break
            }
            routes.add(
                Route(
                    flight + " · " + date + " · " + dep + " → " + arr + (if (path.isEmpty()) "（缺少已缓存航迹）" else ""),
                    path
                )
            )
        }
        return routes
    }

    fun airports(snapshot: Snapshot, paths: List<List<DoubleArray>>): List<Airport> {
        val airports = mutableListOf<Airport>()
        for (raw in snapshot.flights.values) {
            val info = JSONObject(raw).getJSONObject("flightInfo")
            val stops = info.getJSONObject("realWayPoint").getJSONArray("flightStopList")
            if (stops.length() < 2) continue
            val departure = coordinate(stops.getJSONObject(0))
            val arrival = coordinate(stops.getJSONObject(stops.length() - 1))
            val path = paths.firstOrNull { path ->
                path.size >= 2 && (distance(path.first(), departure) + distance(path.last(), arrival) < 20 ||
                        distance(path.first(), arrival) + distance(path.last(), departure) < 20)
            } ?: continue
            val forward = distance(path.first(), departure) < distance(path.first(), arrival)
            airports += Airport(info.getString("deptCode"), forward, departure[0], departure[1])
            airports += Airport(info.getString("destCode"), !forward, arrival[0], arrival[1])
        }
        return airports.distinctBy { it.code to it.departure }
    }

    fun encodeAirports(airports: List<Airport>): String = JSONArray().apply {
        for (airport in airports) put(
            JSONObject().put("code", airport.code).put("departure", airport.departure)
                .put("latitude", airport.latitude).put("longitude", airport.longitude)
        )
    }.toString()

    fun decodeAirports(value: String): List<Airport> {
        val data = JSONArray(value)
        return List(data.length()) { index ->
            val item = data.getJSONObject(index)
            Airport(
                item.getString("code"),
                item.getBoolean("departure"),
                item.getDouble("latitude"),
                item.getDouble("longitude")
            )
        }
    }

    private fun findFlightQuery(value: Any?): String? {
        if (value is JSONObject) {
            val session = value.optString("sessionParams")
            if (session.contains("deptAirportCode=") && session.contains("destAirportCode=")) return session
            val keys = value.keys()
            while (keys.hasNext()) {
                val result = findFlightQuery(value.get(keys.next()))
                if (result != null) return result
            }
        } else if (value is JSONArray) {
            for (i in 0..<value.length()) {
                val result = findFlightQuery(value.get(i))
                if (result != null) return result
            }
        } else if (value is String && value.startsWith("{")) {
            try {
                return findFlightQuery(JSONObject(value))
            } catch (ignored: JSONException) { /* Ordinary text is not a nested JSON payload. */
            }
        }
        return null
    }

    private fun coordinate(point: JSONObject): DoubleArray {
        return doubleArrayOf(point.getDouble("lat"), point.getDouble("lon"))
    }

    private fun pair(point: JSONArray): DoubleArray {
        return doubleArrayOf(point.getDouble(0), point.getDouble(1))
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        val x = Math.toRadians(b[1] - a[1]) * cos(Math.toRadians((a[0] + b[0]) / 2))
        return 6371 * hypot(x, Math.toRadians(b[0] - a[0]))
    }

    internal data class Snapshot(val trips: String, val flights: MutableMap<String, String>, val captured: String?)

    internal data class Route(val label: String, val points: MutableList<DoubleArray>)

    internal data class Airport(val code: String, val departure: Boolean, val latitude: Double, val longitude: Double) {
        val point: DoubleArray get() = doubleArrayOf(latitude, longitude)
    }
}
