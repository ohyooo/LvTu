package com.ohyooo.lvtu

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.database.Cursor
import android.database.sqlite.*
import android.graphics.*
import android.graphics.drawable.*
import android.net.*
import android.net.ConnectivityManager.NetworkCallback
import android.os.*
import android.os.PowerManager.WakeLock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.*
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import android.view.View.OnTouchListener
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import android.widget.SeekBar.OnSeekBarChangeListener
import com.ohyooo.lvtu.AccountRoutes.Route
import com.ohyooo.lvtu.AccountRoutes.Airport
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.*
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.*
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.function.*
import kotlin.math.*
import kotlinx.coroutines.Job
import org.json.*

class ModuleMain : XposedModule() {
    private val maps: MutableMap<Any?, ModuleMain.State> =
        WeakHashMap<Any?, ModuleMain.State>()
    private var loader: ClassLoader? = null
    private lateinit var application: Application
    private lateinit var downloadJobs: MapJobs

    @Volatile
    private var activeTask: DownloadTask? = null
    private val downloadStateLock = Any()

    @Volatile
    private var database: SQLiteDatabase? = null

    @Volatile
    private var cachedData = false
    private var notificationUnavailable = false
    private var cacheReady: Job? = null

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.umetrip.android.msky.app") return
        System.loadLibrary("lvtu_startup")
        try {
            val stub: Class<*> = param.classLoader.loadClass("com.stub.StubApp")
            hook(stub.getDeclaredMethod("interface5", Application::class.java)).intercept({ chain ->
                val result: Any? = chain.proceed()
                Log.i(
                    "LvTu",
                    "interface5 completed entry=" + stub.getField("strEntryApplication").get(null)
                )
                result
            })
        } catch (e: ReflectiveOperationException) {
            Log.e("LvTu", "Application startup validation unavailable", e)
        }
        Log.i("LvTu", "API 102 模块已加载，延迟安装地图 Hook=" + ENABLE_MAP_HOOKS)
        if (!ENABLE_MAP_HOOKS) return
        Handler(Looper.getMainLooper()).postDelayed(Runnable {
            try {
                val application: Application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null) as Application
                Log.i("LvTu", "Application initialized=" + application.javaClass.name)
                val stub: Class<*> = application.classLoader.loadClass("com.stub.StubApp")
                Log.i("LvTu", "strEntryApplication=" + stub.getField("strEntryApplication").get(null))
                this.application = application
                downloadJobs = MapJobs()
                if (Application.getProcessName() == application.packageName) prepareDownloads()
                install(application.classLoader)
            } catch (e: ReflectiveOperationException) {
                log(6, "LvTu", "初始化失败", e)
            }
        }, 10000)
    }

    private fun install(cl: ClassLoader) {
        if (loader != null) return
        loader = cl
        val map = cl.loadClass("com.amap.api.maps.AMap")
        val view = cl.loadClass("com.amap.api.maps.MapView")
        for (method in map.declaredMethods) {
            if (method.name == "addPolyline") hook(method).intercept({ chain ->
                val result: Any? = chain.proceed()
                var captured: MutableList<DoubleArray> =
                    points(call(chain.getArg(0), "getPoints"))
                if (true == call(chain.getArg(0), "isGeodesic")) captured =
                    geodesic(captured)
                val state = maps.get(chain.getThisObject())
                if (state != null) state.routes.put(result, captured)
                result
            })
            if (method.name == "clear") hook(method).intercept({ chain ->
                val result: Any? = chain.proceed()
                val state = maps.get(chain.getThisObject())
                if (state != null) {
                    state.routes.clear()
                    state.view.post(Runnable { state.attachTiles() })
                }
                result
            })
            if (method.name == "setMapType") hook(method).intercept({ chain ->
                val result: Any? = chain.proceed()
                val state = maps.get(chain.getThisObject())
                if (state != null) state.updateOfflineVisibility()
                result
            })
        }
        for (method in cl.loadClass("com.amap.api.maps.model.Polyline").declaredMethods) {
            if (method.name == "setPoints") hook(method).intercept({ chain ->
                val result: Any? = chain.proceed()
                var value: MutableList<DoubleArray> =
                    points(chain.getArg(0))
                if (true == call(chain.getThisObject(), "isGeodesic")) value =
                    geodesic(value)
                for (state in maps.values) if (state.routes.containsKey(chain.getThisObject())) state.routes.put(
                    chain.getThisObject(),
                    value
                )
                result
            })
            if (method.name == "remove") hook(method).intercept({ chain ->
                val result: Any? = chain.proceed()
                for (state in maps.values) state.routes.remove(chain.getThisObject())
                result
            })
        }
        hook(view.getDeclaredMethod("getMap")).intercept({ chain ->
            val result: Any? = chain.proceed()
            if (result != null && !maps.containsKey(result)) {
                val state: ModuleMain.State =
                    State(chain.getThisObject() as View, result)
                maps.put(result, state)
                state.view.post(Runnable { state.attach() })
            }
            result
        })
        hook(view.getDeclaredMethod("onDestroy")).intercept({ chain ->
            val states = maps.values.iterator()
            while (states.hasNext()) {
                val state = states.next()
                if (state.view !== chain.getThisObject()) continue
                state.destroyed = true
                if (state.settingsDialog != null) state.settingsDialog!!.dismiss()
                if (state.cacheDialog != null) state.cacheDialog!!.dismiss()
                if (state.cleanupDialog != null) state.cleanupDialog!!.dismiss()
                if (state.connectivity != null) state.networkCallback?.let {
                    state.connectivity!!.unregisterNetworkCallback(
                        it
                    )
                }
                if (state.connectivityReceiver != null) state.activity.unregisterReceiver(state.connectivityReceiver)
                state.jobs.close()
                state.button?.let { (it.parent as? ViewGroup)?.removeView(it) }
                states.remove()
            }
            chain.proceed()
        })
        try {
            val thread = Class.forName("android.app.ActivityThread")
            val current = thread.getMethod("currentActivityThread").invoke(null)
            val activities = thread.getDeclaredField("mActivities")
            activities.setAccessible(true)
            var recovered = 0
            for (record in (activities.get(current) as MutableMap<*, *>).values) {
                val field = record!!.javaClass.getDeclaredField("activity")
                field.setAccessible(true)
                val activity: Activity? = field.get(record) as Activity?
                if (activity != null && !activity.isFinishing()) recovered += recoverMapViews(
                    activity.window.decorView,
                    view
                )
            }
            Log.i("LvTu", "Existing map views recovered=" + recovered)
        } catch (error: ReflectiveOperationException) {
            Log.w("LvTu", "Existing map view recovery unavailable", error)
        } catch (error: RuntimeException) {
            Log.w("LvTu", "Existing map view recovery unavailable", error)
        }
        log(4, "LvTu", "API 102 地图 Hook 已加载")
        Log.i("LvTu", "API 102 地图 Hook 已加载")
    }

    private inner class State(view: View, map: Any) {
        val view: View
        val map: Any
        val routes: MutableMap<Any?, MutableList<DoubleArray>> =
            LinkedHashMap<Any?, MutableList<DoubleArray>>()
        val jobs = MapJobs()

        @Volatile
        var db: SQLiteDatabase? = null

        @Volatile
        var hasTiles: Boolean = false

        @Volatile
        var offlineStyle: Int = 6
        var overlay: Any? = null
        var annotationOverlay: Any? = null
        var button: TextView? = null
        var idleCaption: CharSequence? = null
        var preview: View? = null
        var settingsDialog: Dialog? = null
        var cacheDialog: Dialog? = null
        var cleanupDialog: Dialog? = null
        var cacheMutation: Boolean = false
        val cachedLayers: MutableMap<Int, CacheLayer> =
            LinkedHashMap<Int, CacheLayer>()
        val markingJobs: MutableMap<Int, Job> = HashMap<Int, Job>()
        var cachePreview: View? = null
        var cacheCameraWatcher: Job? = null
        var mapInteracted: Boolean = false
        var mapTouched: Boolean = false
        var savedRouteFramed: Boolean = false
        var touchZoom: Float = 0f
        var onMapGesture: Runnable? = null
        lateinit var activity: Activity
        var connectivity: ConnectivityManager? = null
        var networkCallback: NetworkCallback? = null
        var connectivityReceiver: BroadcastReceiver? = null

        @Volatile
        var online: Boolean = false
        var destroyed: Boolean = false

        @Volatile
        var highestFallbackZoom: Int = -1

        init {
            this.view = view
            this.map = map
        }

        fun attach() {
            if (destroyed) return
            var context = view.context
            while (context is ContextWrapper && context !is Activity) context =
                context.baseContext
            if (context !is Activity) return
            activity = context
            val button = TextView(activity)
            this.button = button
            idleCaption = "离线"
            button.setText(idleCaption)
            button.setIncludeFontPadding(false)
            button.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            button.setPadding(0, 0, 0, 0)
            button.setContentDescription("离线地图")
            val probe = View(activity)
            probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
            probe.setOnTouchListener(OnTouchListener { v: View?, event: MotionEvent ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    mapTouched = true
                    touchZoom = cameraZoom()
                    if (onMapGesture != null) onMapGesture!!.run()
                }
                false
            })
            (view as ViewGroup).addView(probe, ViewGroup.LayoutParams(-1, -1))
            button.setEnabled(false)
            button.setOnClickListener(View.OnClickListener { v: View? -> settings() })
            val root: FrameLayout = activity.window.decorView as FrameLayout
            val lp: FrameLayout.LayoutParams = FrameLayout.LayoutParams(dp(36f), dp(40f), Gravity.TOP or Gravity.END)
            lp.topMargin = dp(230f)
            lp.rightMargin = dp(12f)
            root.addView(button, lp)
            view.viewTreeObserver.addOnGlobalLayoutListener(OnGlobalLayoutListener {
                button.setVisibility(if (view.isShown()) View.VISIBLE else View.GONE)
                if (view.isShown()) alignButton(root)
            })
            connectivity =
                activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            online = hasInternetConnection()
            networkCallback = object : NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, value: NetworkCapabilities) {
                    updateConnection()
                }

                override fun onLost(network: Network) {
                    updateConnection()
                }
            }
            val request: NetworkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            connectivity!!.registerNetworkCallback(request, networkCallback!!)
            connectivityReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateConnection()
                }
            }
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            activity.registerReceiver(
                connectivityReceiver, filter,
                if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
            )
            jobs.after(
                cacheReady,
                Runnable {
                    db = database
                    hasTiles = cachedData
                    jobs.onMain(Runnable {
                        button.setEnabled(true)
                        updateDownloadButton()
                        attachTiles()
                        updateOfflineVisibility()
                    })
                },
                Consumer { error: Exception? -> report("离线缓存打开失败：" + error!!.message) })
        }

        fun hasInternetConnection(): Boolean {
            for (network in connectivity!!.getAllNetworks()) {
                val capabilities: NetworkCapabilities? = connectivity!!.getNetworkCapabilities(network)
                if (capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) return true
            }
            return false
        }

        fun updateConnection() {
            view.post(Runnable {
                if (destroyed) return@Runnable
                val connected = hasInternetConnection()
                if (online == connected) return@Runnable
                online = connected
                Log.i("LvTu", "Offline map network online=" + online)
                updateOfflineVisibility()
                if (overlay != null) call(overlay!!, "clearTileCache")
                if (annotationOverlay != null) call(annotationOverlay!!, "clearTileCache")
            })
        }

        fun updateOfflineVisibility() {
            val mapType = (call(map, "getMapType") as Number).toInt()
            val previousStyle = offlineStyle
            offlineStyle = if (mapType == 2) 6 else 7
            if (overlay != null && previousStyle != offlineStyle) {
                call(overlay!!, "remove")
                if (annotationOverlay != null) call(annotationOverlay!!, "remove")
                overlay = null
                annotationOverlay = null
                highestFallbackZoom = -1
                attachTiles()
                return
            }
            if (button != null) {
                styleButton(mapType)
            }
            if (overlay != null) {
                val visible = !online
                call(overlay!!, "setVisible", visible)
                if (annotationOverlay != null) call(annotationOverlay!!, "setVisible", visible)
                Log.i(
                    "LvTu",
                    "Offline overlay visible=" + visible + " mapType=" + mapType + " online=" + online
                )
            }
        }

        fun attachTiles() {
            if (db == null || destroyed || !hasTiles) return
            try {
                overlay = createOfflineOverlay(offlineStyle, -1000f)
                annotationOverlay = if (offlineStyle == 6) createOfflineOverlay(8, -999f) else null
                updateOfflineVisibility()
            } catch (error: ReflectiveOperationException) {
                log(6, "LvTu", "离线图层加载失败", error)
            } catch (error: RuntimeException) {
                log(6, "LvTu", "离线图层加载失败", error)
            }
        }

        fun createOfflineOverlay(tileStyle: Int, zIndex: Float): Any? {
            val provider = loader!!.loadClass("com.amap.api.maps.model.TileProvider")
            val tile = loader!!.loadClass("com.amap.api.maps.model.Tile")
            val proxy = Proxy.newProxyInstance(
                loader,
                arrayOf<Class<*>>(provider),
                InvocationHandler { p: Any?, method: Method?, args: Array<Any?>? ->
                    when (method!!.name) {
                        "getTileWidth", "getTileHeight" -> return@InvocationHandler 256
                        "getTile" -> {
                            if (online) return@InvocationHandler provider.getField("NO_TILE").get(null)
                            val bytes = tile(
                                args!![0] as Int,
                                args[1] as Int,
                                args[2] as Int,
                                tileStyle
                            )
                            return@InvocationHandler if (bytes == null) provider.getField("NO_TILE")
                                .get(null) else tile.getConstructor(
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                ByteArray::class.java
                            ).newInstance(256, 256, bytes)
                        }

                        "hashCode" -> return@InvocationHandler System.identityHashCode(p)
                        "equals" -> return@InvocationHandler p === args!![0]
                        else -> return@InvocationHandler "LvTuTiles" + tileStyle
                    }
                })
            val options: Any =
                loader!!.loadClass("com.amap.api.maps.model.TileOverlayOptions").getConstructor().newInstance()
            call(options, "tileProvider", proxy)
            call(options, "zIndex", zIndex)
            call(options, "diskCacheEnabled", false)
            call(options, "visible", !online)
            return call(map, "addTileOverlay", options)
        }

        fun cacheInfo(
            planned: MutableList<MutableSet<Long>>?,
            style: Int = offlineStyle
        ): CacheInfo {
            if (!activity.getDatabasePath("lvtu-offline.db").isFile()) return CacheInfo(0, 0, 0)
            if (planned == null) {
                db!!.rawQuery("SELECT COUNT(*),COALESCE(SUM(LENGTH(data)),0) FROM tiles", null).use { cursor ->
                    cursor.moveToFirst()
                    return CacheInfo(cursor.getLong(0), cursor.getLong(1), 0)
                }
            }
            var count: Long = 0
            var bytes: Long = 0
            var matching: Long = 0
            db!!.rawQuery("SELECT z,x,y,LENGTH(data),style FROM tiles", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (Thread.currentThread()
                            .isInterrupted()
                    ) throw CancellationException("过期缓存检查已取消")
                    count++
                    bytes += cursor.getLong(3)
                    val zoom: Int = cursor.getInt(0)
                    val key = (cursor.getInt(1).toLong() shl 32) or (cursor.getInt(2).toLong() and 0xffffffffL)
                    if ((cursor.getInt(4) == style || style == 6 && cursor.getInt(4) == 8) && zoom < planned.size && planned.get(
                            zoom
                        ).contains(key)
                    ) matching++
                }
            }
            return CacheInfo(count, bytes, matching)
        }

        fun exact(x: Int, y: Int, z: Int, style: Int): ByteArray? {
            db!!.rawQuery(
                "SELECT data FROM tiles WHERE style=? AND z=? AND x=? AND y=?",
                arrayOf<String>("" + style, "" + z, "" + x, "" + y)
            ).use { c ->
                return if (c.moveToFirst()) c.getBlob(0) else null
            }
        }

        fun tile(x: Int, y: Int, z: Int, style: Int = offlineStyle): ByteArray? {
            for (parent in z downTo 0) {
                val d = z - parent
                val data = exact(x shr d, y shr d, parent, style)
                if (data == null) continue
                if (d == 0) return data
                if (!online && z > highestFallbackZoom) {
                    highestFallbackZoom = z
                    Log.i("LvTu", "Offline parent fallback requestedZoom=" + z + " cachedZoom=" + parent)
                }
                return upscaleTile(data, x, y, d, style == 8)
            }
            return null
        }

        // Original MapButtons uses a 750-unit viewport and 72x78 PNGs including the label.
        fun alignButton(root: FrameLayout) {
            val button = button ?: return
            val scale: Float = root.width / 750f
            val anchor: View? = findMapControl(root, 72 * scale, 78 * scale)
            if (anchor == null) return
            val position = IntArray(2)
            val origin = IntArray(2)
            anchor.getLocationOnScreen(position)
            root.getLocationOnScreen(origin)
            val lp: FrameLayout.LayoutParams = button.layoutParams as FrameLayout.LayoutParams
            val left = position[0] - origin[0]
            val top: Int = position[1] - origin[1] + anchor.height + Math.round(20 * scale)
            if (lp.leftMargin == left && lp.topMargin == top && lp.width == anchor.width && lp.height == anchor.height) return
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.leftMargin = left
            lp.topMargin = top
            lp.width = anchor.width
            lp.height = anchor.height
            button.setLayoutParams(lp)
            styleButton((call(map, "getMapType") as Number).toInt())
        }

        fun findMapControl(item: View, width: Float, height: Float): View? {
            var result: View? = null
            if (!item.isShown()) return null
            if (item is ImageView && abs(item.width - width) < 4 && abs(item.height - height) < 4) {
                val location = IntArray(2)
                item.getLocationOnScreen(location)
                if (location[0] > activity.resources
                        .displayMetrics.widthPixels * .75f && location[0] + item.width <= activity.resources
                        .displayMetrics.widthPixels && location[1] >= 0 && location[1] < activity.resources
                        .displayMetrics.heightPixels * .45f
                ) result = item
            }
            if (item is ViewGroup) {
                val group = item
                for (i in 0..<group.childCount) {
                    val candidate: View? = findMapControl(group.getChildAt(i), width, height)
                    if (candidate != null) {
                        val a = IntArray(2)
                        val b = IntArray(2)
                        candidate.getLocationOnScreen(a)
                        if (result != null) result.getLocationOnScreen(b)
                        if (result == null || a[1] > b[1]) result = candidate
                    }
                }
            }
            return result
        }

        fun styleButton(mapType: Int) {
            val button = button ?: return
            val scale = activity.resources.displayMetrics.widthPixels / 750f
            val dark = mapType == 2
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18 * scale)
            button.setTextColor(if (dark) Color.WHITE else -0xcccccd)
            val background = GradientDrawable()
            background.setColor(if (dark) -0xf5ead6 else Color.WHITE)
            background.setCornerRadius(16 * scale)
            button.setBackground(background)
            val icon: Drawable = object : Drawable() {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val path = Path()

                override fun draw(canvas: Canvas) {
                    canvas.save()
                    canvas.scale(getBounds().width() / 72f, getBounds().height() / 50f)
                    paint.setStyle(Paint.Style.STROKE)
                    paint.setStrokeWidth(2.5f)
                    paint.setStrokeCap(Paint.Cap.ROUND)
                    paint.setStrokeJoin(Paint.Join.ROUND)
                    paint.setColor(if (dark) -0x3d3930 else -0x99999a)
                    path.rewind()
                    path.moveTo(36f, 12f)
                    path.lineTo(36f, 33f)
                    path.moveTo(28f, 26f)
                    path.lineTo(36f, 34f)
                    path.lineTo(44f, 26f)
                    path.moveTo(22f, 33f)
                    path.lineTo(22f, 42f)
                    path.lineTo(50f, 42f)
                    path.lineTo(50f, 33f)
                    canvas.drawPath(path, paint)
                    canvas.restore()
                }

                override fun setAlpha(alpha: Int) {
                    paint.setAlpha(alpha)
                }

                override fun setColorFilter(filter: ColorFilter?) {
                    paint.setColorFilter(filter)
                }

                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
            icon.setBounds(0, 0, Math.round(72 * scale), Math.round(50 * scale))
            button.setCompoundDrawables(null, icon, null, null)
        }

        fun dp(value: Float): Int {
            return Math.round(value * activity.resources.displayMetrics.density)
        }

        fun surface(color: Int, radius: Int): GradientDrawable {
            val shape = GradientDrawable()
            shape.setColor(color)
            shape.setCornerRadius(dp(radius.toFloat()).toFloat())
            return shape
        }

        fun label(text: String, size: Int, color: Int): TextView {
            val result = TextView(activity)
            result.setText(text)
            result.setTextSize(size.toFloat())
            result.setTextColor(color)
            return result
        }

        fun cameraZoom(): Float {
            val position: Any = call(map, "getCameraPosition")!!
            try {
                return position.javaClass.getField("zoom").getFloat(position)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("读取当前地图缩放失败", e)
            }
        }

        fun setCameraZoom(value: Float) {
            try {
                val factory = loader!!.loadClass("com.amap.api.maps.CameraUpdateFactory")
                val focus = Point(
                    view.width / 2,
                    min(view.height / 2, activity.resources.displayMetrics.heightPixels / 4)
                )
                call(
                    map,
                    "moveCamera",
                    factory.getMethod(
                        "zoomBy",
                        Float::class.javaPrimitiveType,
                        Point::class.java
                    ).invoke(null, value - cameraZoom(), focus)
                )
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("设置地图缩放失败", e)
            }
        }

        fun settings() {
            if (cacheMutation) {
                report("正在清理缓存")
                return
            }
            if (settingsDialog != null && settingsDialog!!.isShowing()) return
            val prefs: SharedPreferences = activity.getSharedPreferences("lvtu-offline", 0)
            val snapshot: MutableList<MutableList<DoubleArray>> =
                ArrayList<MutableList<DoubleArray>>(routes.values)
            val usingSavedRoute = snapshot.isEmpty()
            if (usingSavedRoute) {
                try {
                    val paths = JSONArray(prefs.getString("route", "[]"))
                    for (i in 0..<paths.length()) {
                        val path: MutableList<DoubleArray> =
                            ArrayList<DoubleArray>()
                        val points: JSONArray = paths.getJSONArray(i)
                        for (j in 0..<points.length()) path.add(
                            doubleArrayOf(
                                points.getJSONArray(j).getDouble(0), points.getJSONArray(j).getDouble(1)
                            )
                        )
                        if (path.size >= 2) snapshot.add(path)
                    }
                } catch (error: JSONException) {
                    report("上次航线读取失败：" + error.message)
                    return
                }
            }
            if (snapshot.isEmpty()) {
                report("尚未捕获航线，请重新打开航线地图")
                return
            }
            if (usingSavedRoute && !savedRouteFramed) {
                try {
                    val coordinate = loader!!.loadClass("com.amap.api.maps.model.LatLng")
                    val bounds = loader!!.loadClass("com.amap.api.maps.model.LatLngBounds")
                    val builder = bounds.getMethod("builder").invoke(null)
                    val point: Constructor<*> = coordinate.getConstructor(
                        Double::class.javaPrimitiveType,
                        Double::class.javaPrimitiveType
                    )
                    for (path in snapshot) for (value in path) call(
                        builder,
                        "include",
                        point.newInstance(value[0], value[1])
                    )
                    val update = loader!!.loadClass("com.amap.api.maps.CameraUpdateFactory")
                        .getMethod("newLatLngBounds", bounds, Int::class.javaPrimitiveType)
                        .invoke(null, call(builder, "build"), dp(40f))
                    call(map, "moveCamera", update)
                    savedRouteFramed = true
                } catch (error: ReflectiveOperationException) {
                    report("上次航线定位失败：" + error.message)
                }
            }
            val currentZoom = cameraZoom()
            val minZoom = (call(map, "getMinZoomLevel") as Number).toFloat()
            val maxZoom = (call(map, "getMaxZoomLevel") as Number).toFloat()
            val dialog = Dialog(activity)
            settingsDialog = dialog
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCanceledOnTouchOutside(false)
            val box = LinearLayout(activity)
            box.setOrientation(LinearLayout.VERTICAL)
            box.setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            box.setBackground(surface(-0x80604, 22))
            val header = LinearLayout(activity)
            val title: TextView = label("离线地图 · " + styleName(offlineStyle), 20, -0xe9d4b7)
            title.setTypeface(null, Typeface.BOLD)
            if (usingSavedRoute) title.setText(title.getText().toString() + " · 上次航线")
            header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            val close: TextView = label("关闭", 14, -0x9a8972)
            close.setPadding(dp(12f), dp(4f), 0, dp(4f))
            close.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
            header.addView(close)
            box.addView(header)
            val subtitle: TextView = label(
                String.format(
                    Locale.CHINA,
                    "当前地图 Zoom %.2f · 蓝色虚线内为预览范围",
                    currentZoom
                ), 12, -0x9a8972
            )
            subtitle.setPadding(0, dp(8f), 0, dp(12f))
            box.addView(subtitle)
            var area = prefs.getInt("draft_area", prefs.getInt("download_area", 0))
            var airports = emptyList<Airport>()
            var airportError: String? = null
            var airportsLoaded = false
            val areaGroup = RadioGroup(activity).apply {
                orientation = RadioGroup.HORIZONTAL
                setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
                background = surface(-0x181108, 10)
            }
            val areaNames = listOf("沿航线", "出发机场", "到达机场", "两端机场")
            val areaButtons = areaNames.map { name ->
                RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = name
                    textSize = 12f
                    gravity = Gravity.CENTER
                    buttonDrawable = null
                    minWidth = 0
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                    areaGroup.addView(this, RadioGroup.LayoutParams(0, dp(36f), 1f))
                }
            }
            areaGroup.check(areaButtons[area].id)
            box.addView(areaGroup)
            val airportInfo = label("正在读取行程缓存中的机场坐标…", 11, -0x9a8972)
            airportInfo.setPadding(0, dp(6f), 0, dp(6f))
            box.addView(airportInfo)
            val fields = LinearLayout(activity)
            fields.setPadding(0, dp(8f), 0, 0)
            val distance = EditText(activity)
            val zoom = EditText(activity)
            val inputs: Array<EditText> = arrayOf<EditText>(distance, zoom)
            val sliders: Array<SeekBar> = arrayOf<SeekBar>(SeekBar(activity), SeekBar(activity))
            val labels = arrayOf<String>("航线每侧距离 · km", "下载 Zoom · 双向同步")
            val distanceLabel = label(labels[0], 12, -0x9a8972)
            for (i in inputs.indices) {
                val field = LinearLayout(activity)
                field.setOrientation(LinearLayout.VERTICAL)
                field.setPadding(dp(12f), dp(10f), dp(12f), dp(4f))
                field.setBackground(surface(Color.WHITE, 12))
                field.addView(if (i == 0) distanceLabel else label(labels[i], 12, -0x9a8972))
                inputs[i].setSingleLine(true)
                inputs[i].setTextColor(-0xe9d4b7)
                inputs[i].setTextSize(22f)
                inputs[i].setPadding(0, dp(4f), 0, dp(6f))
                inputs[i].setBackgroundTintList(ColorStateList.valueOf(-0xd78414))
                field.addView(inputs[i], LinearLayout.LayoutParams(-1, -2))
                sliders[i].setMin(if (i == 0) 1 else Math.round(minZoom * 100))
                sliders[i].setMax(if (i == 0) 1000 else Math.round(maxZoom * 100))
                sliders[i].setProgressTintList(ColorStateList.valueOf(-0xd78414))
                sliders[i].setThumbTintList(ColorStateList.valueOf(-0xd78414))
                sliders[i].setPadding(dp(8f), 0, dp(8f), 0)
                sliders[i].setContentDescription(if (i == 0) "航线每侧距离" else "地图缩放")
                field.addView(sliders[i], LinearLayout.LayoutParams(-1, dp(32f)))
                val param: LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                if (i == 0) param.rightMargin = dp(10f)
                fields.addView(field, param)
            }
            distance.setInputType(8194)
            distance.setText(
                if (area == 0) prefs.getString("draft_distance", prefs.getString("distance", "2"))
                else prefs.getString("draft_airport_radius", prefs.getString("airport_radius", "2"))
            )
            zoom.setInputType(8194)
            zoom.setText(
                prefs.getString(
                    "draft_zoom",
                    prefs.getString("zoom", String.format(Locale.ROOT, "%.2f", currentZoom))
                )
            )
            box.addView(fields)
            val taskRow = LinearLayout(activity)
            taskRow.setGravity(Gravity.CENTER_VERTICAL)
            val progressArea = FrameLayout(activity)
            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
            val fill: ClipDrawable =
                ClipDrawable(surface(-0x59370d, 4), Gravity.LEFT, ClipDrawable.HORIZONTAL)
            val track = LayerDrawable(arrayOf<Drawable>(surface(-0x181108, 4), fill))
            track.setId(0, android.R.id.background)
            track.setId(1, android.R.id.progress)
            progress.setProgressDrawable(track)
            progressArea.addView(progress, FrameLayout.LayoutParams(-1, dp(18f), Gravity.CENTER))
            val progressText: TextView = label("", 11, -0xe9d4b7)
            progressText.setGravity(Gravity.CENTER)
            progressArea.addView(progressText, FrameLayout.LayoutParams(-1, -1))
            taskRow.addView(progressArea, LinearLayout.LayoutParams(0, dp(32f), 1f))
            val pauseTask: TextView = label("暂停", 12, -0xd78414)
            val deleteTask: TextView = label("删除", 12, -0x47b6b7)
            pauseTask.setGravity(Gravity.CENTER)
            deleteTask.setGravity(Gravity.CENTER)
            taskRow.addView(pauseTask, LinearLayout.LayoutParams(dp(38f), dp(32f)))
            taskRow.addView(deleteTask, LinearLayout.LayoutParams(dp(38f), dp(32f)))
            box.addView(taskRow)
            val syncing = booleanArrayOf(false)
            val draggingZoom = booleanArrayOf(false)
            val lastZoomInput = longArrayOf(0)
            val scheduledZoom = arrayOf<Runnable?>(null)
            val estimate: TextView = label("正在计算本地下载范围…", 14, -0xd78414)
            estimate.setPadding(0, dp(12f), 0, dp(4f))
            box.addView(estimate)
            val cachedInfo: TextView = label("正在读取本地缓存…", 12, -0x9a8972)
            val cachePlanReady = booleanArrayOf(false)
            cachedInfo.setMinLines(2)
            cachedInfo.setPadding(0, 0, 0, dp(4f))
            box.addView(cachedInfo)
            val note: TextView = label(
                "下载至地图 Zoom 向上取整的层级，最高 18 级\n实际下载范围沿瓦片边缘扩展，超出缓存级别后放大显示",
                11,
                -0x9a8972
            )
            note.setPadding(0, 0, 0, dp(12f))
            if (prefs.contains("route")) note.setText(
                "上次下载：" + styleName(
                    prefs.getInt(
                        "download_style",
                        6
                    )
                ) + " · " + areaNames[prefs.getInt("download_area", 0)] +
                        (if (prefs.getInt("download_area", 0) == 0) " 每侧 " + prefs.getString("distance", "2")
                        else " 半径 " + prefs.getString("airport_radius", "2")) + " km · Zoom " + prefs.getString(
                    "zoom",
                    "—"
                ) + "\n" + note.getText()
            )
            box.addView(note)
            val actions = LinearLayout(activity)
            val stored: TextView = label("缓存列表", 14, -0xd78414)
            stored.setGravity(Gravity.CENTER)
            stored.setOnClickListener(View.OnClickListener { v: View? ->
                dialog.dismiss()
                cacheList()
            })
            actions.addView(stored, LinearLayout.LayoutParams(0, dp(46f), 1f))
            val start: TextView = label("开始下载", 15, Color.WHITE)
            start.setGravity(Gravity.CENTER)
            start.setTypeface(null, Typeface.BOLD)
            start.setBackground(surface(-0xd78414, 12))
            actions.addView(start, LinearLayout.LayoutParams(0, dp(46f), 1f))
            box.addView(actions)
            box.setOnTouchListener(OnTouchListener { v: View?, event: MotionEvent ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val focused: View? = dialog.currentFocus
                    if (focused is EditText) focused.clearFocus()
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(
                        box.windowToken,
                        0
                    )
                }
                false
            })
            dialog.setContentView(box)
            val window: Window = dialog.window!!
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.setGravity(Gravity.BOTTOM)
            dialog.show()
            window.setLayout(-1, -2)
            jobs.io(Runnable {
                val cache = cacheInfo(null)
                jobs.onMain(Runnable {
                    if (dialog.isShowing() && !cachePlanReady[0]) cachedInfo.setText(
                        String.format(
                            Locale.CHINA,
                            "已缓存 %,d 张，共 %.2f MB", cache.count, cache.bytes / 1048576.0
                        )
                    )
                })
            }, Consumer { error: Exception? ->
                if (dialog.isShowing()) cachedInfo.setText("缓存统计失败：" + error!!.message)
            })
            val handler = Handler(Looper.getMainLooper())

            class Selection {
                var tiles: MutableList<MutableSet<Long>>? = null
                var km: Double = 0.0
                var max: Int = 0
                var style: Int = 0

                var requestedKm: Double = Double.NaN
                var requestedMax: Int = -1
                var requestedStyle: Int = -1
                var previewKm: Double = Double.NaN
                var requestedRegion: Pair<Int, List<Airport>>? = null
                var previewRegion: Pair<Int, List<Airport>>? = null

                var cacheRefreshing: Boolean = false
                var needed: Long = -1

                fun selectedAirports(): List<Airport> = when (area) {
                    1 -> airports.filter { it.departure }
                    2 -> airports.filter { !it.departure }
                    3 -> airports
                    else -> emptyList()
                }

                fun showArea() {
                    for ((index, button) in areaButtons.withIndex()) {
                        button.setTextColor(if (index == area) Color.WHITE else -0x9a8972)
                        button.background = if (index == area) surface(-0xd78414, 8) else null
                    }
                    distanceLabel.text = if (area == 0) "航线每侧距离 · km" else "机场周围半径 · km"
                    sliders[0].contentDescription = distanceLabel.text
                    airportInfo.visibility = if (area == 0) View.GONE else View.VISIBLE
                    airportInfo.text = if (!airportsLoaded) "正在读取行程缓存中的机场坐标…"
                    else airportError ?: selectedAirports().joinToString(" · ") {
                        (if (it.departure) "出发 " else "到达 ") + it.code
                    }.ifEmpty { "没有匹配当前航线的机场坐标，请先打开对应行程" }
                }

                fun displayCache(cache: CacheInfo, count: Long) {
                    cachePlanReady[0] = true
                    needed = count - cache.matching
                    cachedInfo.setText(
                        String.format(
                            Locale.CHINA,
                            "已缓存 %,d 张，共 %.2f MB\n本次已缓存 %,d / %,d 张，还需下载 %,d 张",
                            cache.count, cache.bytes / 1048576.0, cache.matching, count, needed
                        )
                    )
                    val canStart = needed > 0 && (activeTask == null || activeTask!!.phase != "downloading")
                    start.setText(if (needed == 0L) "已全部缓存" else "开始下载")
                    start.setEnabled(canStart)
                    start.setAlpha(if (canStart) 1f else .45f)
                }

                fun refreshCache() {
                    if (tiles == null || cacheRefreshing) return
                    cacheRefreshing = true
                    val planned: MutableList<MutableSet<Long>> = tiles!!
                    val selectedStyle = style
                    jobs.io(Runnable {
                        val info = cacheInfo(planned, selectedStyle)
                        jobs.onMain(Runnable {
                            cacheRefreshing = false
                            if (dialog.isShowing() && tiles === planned) displayCache(
                                info,
                                planned.stream()
                                    .mapToLong { obj: MutableSet<Long>? -> obj!!.size.toLong() }
                                    .sum() * (if (selectedStyle == 6) 2 else 1))
                        })
                    }, Consumer { error: Exception? ->
                        cacheRefreshing = false
                        if (dialog.isShowing()) cachedInfo.setText("缓存检查失败：" + error!!.message)
                    })
                }

                fun update() {
                    try {
                        km = distance.getText().toString().toDouble()
                        val level = zoom.getText().toString().toDouble()
                        require(
                            km.isFinite() && km > 0 && km <= 100 && level.isFinite() && level >= minZoom && level <= maxZoom
                        ) { (if (area == 0) "每侧距离" else "机场半径") + " 0～100 km，地图 Zoom " + minZoom + "～" + maxZoom }
                        max = max(1, min(18, ceil(level).toInt()))
                        style = offlineStyle
                        sliders[0].setProgress(Math.round(km * 10).toInt())
                        sliders[1].setProgress(Math.round(level * 100).toInt())
                        val selectedAirports = selectedAirports()
                        val region = area to selectedAirports
                        require(area == 0 || selectedAirports.isNotEmpty()) { airportInfo.text.toString() }
                        if (km == requestedKm && max == requestedMax && style == requestedStyle && region == requestedRegion && tiles != null) return
                        requestedKm = km
                        requestedMax = max
                        requestedStyle = style
                        requestedRegion = region
                        tiles = null
                        start.setEnabled(false)
                        start.setAlpha(.45f)
                        estimate.setText("停止调整 1 秒后计算下载范围…")
                        val chosenKm = km
                        val chosenMax = max
                        val chosenStyle = style
                        val rebuildPreview = previewKm != chosenKm || previewRegion != region
                        val circle = area != 0
                        jobs.submit<PreviewPlan?>(
                            Callable {
                                Log.i(
                                    "LvTu",
                                    "Plan started distance=" + chosenKm + " maxZoom=" + chosenMax + " thread=" + Thread.currentThread()
                                        .name
                                )
                                PreviewPlan(
                                    if (circle) CircleArea.plan(selectedAirports.map { it.point }, chosenKm, chosenMax)
                                    else plan(snapshot, chosenKm, chosenMax),
                                    if (!rebuildPreview) mutableListOf() else if (circle)
                                        buildCirclePreview(selectedAirports, chosenKm)
                                    else buildPreview(snapshot, chosenKm),
                                    null
                                )
                            },
                            UnaryOperator { computed: PreviewPlan? ->
                                PreviewPlan(
                                    computed!!.tiles,
                                    computed.boundaries,
                                    cacheInfo(computed.tiles, chosenStyle)
                                )
                            },
                            Consumer { result: PreviewPlan? ->
                                tiles = result!!.tiles
                                val count = tiles!!.stream()
                                    .mapToLong { obj: MutableSet<Long>? -> obj!!.size.toLong() }
                                    .sum() * (if (chosenStyle == 6) 2 else 1)
                                if (previewKm != chosenKm || previewRegion != region) {
                                    removePreview()
                                    showPreview(result.boundaries)
                                    previewKm = chosenKm
                                    previewRegion = region
                                }
                                estimate.setText(
                                    String.format(
                                        Locale.CHINA,
                                        (if (circle) "半径 %.1f km" else "宽 %.1f km") + " · 0–%d 级 · %,d 张 · 约 %.1f MB",
                                        if (circle) chosenKm else chosenKm * 2,
                                        chosenMax,
                                        count,
                                        count * (if (chosenStyle == 6) 17.5 else 30.0) / 1024
                                    )
                                )
                                displayCache(result.cache!!, count)
                                Log.i(
                                    "LvTu",
                                    "Plan completed tiles=" + count + " cached=" + result.cache.matching
                                )
                            },
                            Consumer { error: Exception? -> estimate.setText(error!!.message) })
                    } catch (e: IllegalArgumentException) {
                        jobs.cancel()
                        requestedKm = Double.NaN
                        requestedMax = -1
                        tiles = null
                        needed = -1
                        removePreview()
                        previewKm = Double.NaN
                        previewRegion = null
                        start.setEnabled(false)
                        start.setAlpha(.45f)
                        estimate.setText(if (e is NumberFormatException) "输入距离和地图 Zoom 后自动计算" else e.message)
                    }
                }
            }

            val selection = Selection()
            areaGroup.setOnCheckedChangeListener { _, checkedId ->
                if (syncing[0]) return@setOnCheckedChangeListener
                area = areaButtons.indexOfFirst { it.id == checkedId }
                prefs.edit().putInt("draft_area", area).apply()
                selection.showArea()
                distance.setText(
                    if (area == 0) prefs.getString("draft_distance", prefs.getString("distance", "2"))
                    else prefs.getString("draft_airport_radius", prefs.getString("airport_radius", "2"))
                )
                selection.update()
            }
            selection.showArea()
            val airportRead = jobs.load(
                Callable { AccountRoutes.read(activity, loader!!) },
                Function { stored -> AccountRoutes.airports(stored, snapshot) },
                Consumer { found ->
                    airports = found
                    airportsLoaded = true
                    selection.showArea()
                    selection.update()
                },
                Consumer { error ->
                    airportError = "机场坐标读取失败：" + error.message
                    airportsLoaded = true
                    selection.showArea()
                    if (area != 0) selection.update()
                }
            )
            val restoreTask = Runnable {
                val task = activeTask
                if (task == null) return@Runnable
                syncing[0] = true
                area = task.area
                areaGroup.check(areaButtons[area].id)
                if (area != 0) {
                    airportRead.cancel()
                    airports = AccountRoutes.decodeAirports(task.airports)
                    airportsLoaded = true
                    airportError = null
                }
                selection.showArea()
                prefs.edit().putInt("draft_area", area).apply()
                distance.setText(java.math.BigDecimal.valueOf(task.km).stripTrailingZeros().toPlainString())
                zoom.setText(String.format(Locale.ROOT, "%.2f", task.zoom))
                syncing[0] = false
                call(map, "setMapType", if (task.style == 6) 2 else 1)
                mapInteracted = true
                setCameraZoom(task.zoom)
                distance.clearFocus()
                zoom.clearFocus()
            }
            progressArea.setOnClickListener(View.OnClickListener { v: View? -> restoreTask.run() })
            pauseTask.setOnClickListener(View.OnClickListener { v: View? ->
                val task = activeTask
                if (task == null) return@OnClickListener
                if (task.phase == "downloading") pauseDownload(task)
                else {
                    restoreTask.run()
                    resumeDownload(task)
                }
            })
            deleteTask.setOnClickListener(View.OnClickListener { v: View? ->
                if (activeTask != null) deleteDownload(activeTask)
            })
            for (i in inputs.indices) {
                val index = i
                inputs[i].addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        a: Int,
                        c: Int,
                        n: Int
                    ) {
                    }

                    override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
                        if (index == 1 && !syncing[0]) {
                            mapInteracted = true
                            lastZoomInput[0] = SystemClock.uptimeMillis()
                            if (scheduledZoom[0] != null) handler.removeCallbacks(scheduledZoom[0]!!)
                            scheduledZoom[0] = null
                            try {
                                val target = s.toString().toFloat()
                                if (target.isFinite() && target >= minZoom && target <= maxZoom) {
                                    scheduledZoom[0] = Runnable {
                                        scheduledZoom[0] = null
                                        if (!destroyed) setCameraZoom(target)
                                    }
                                    handler.postDelayed(scheduledZoom[0]!!, 250)
                                }
                            } catch (ignored: NumberFormatException) {
                                // The user may temporarily clear the field while editing.
                            }
                        }
                        prefs.edit().putString(
                            if (index == 1) "draft_zoom" else if (area == 0) "draft_distance" else "draft_airport_radius",
                            s.toString()
                        ).apply()
                        selection.update()
                    }

                    override fun afterTextChanged(s: Editable?) {
                    }
                })
                sliders[i].setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        syncing[0] = true
                        inputs[index].setText(
                            if (index == 0) String.format(
                                Locale.ROOT,
                                "%.1f",
                                value / 10.0
                            ) else String.format(Locale.ROOT, "%.2f", value / 100.0)
                        )
                        syncing[0] = false
                        inputs[index].setSelection(inputs[index].length())
                        if (index == 1) {
                            mapInteracted = true
                            setCameraZoom(value / 100f)
                        }
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {
                        if (index == 1) {
                            draggingZoom[0] = true
                            if (scheduledZoom[0] != null) handler.removeCallbacks(scheduledZoom[0]!!)
                            scheduledZoom[0] = null
                        }
                        inputs[index].clearFocus()
                        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(inputs[index].windowToken, 0)
                    }

                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        if (index == 1) draggingZoom[0] = false
                    }
                })
            }
            val cameraUpdate: Runnable = object : Runnable {
                var previousCamera: String = ""
                var previousZoom: Float = Float.NaN
                var previousStyle: Int = offlineStyle
                var previousProgress: String = ""
                var inspectedProgress: String = ""
                var inspectedAt: Long = 0

                override fun run() {
                    if (!dialog.isShowing() || destroyed) return
                    if (previousStyle != offlineStyle) {
                        previousStyle = offlineStyle
                        title.setText("离线地图 · " + styleName(offlineStyle) + (if (usingSavedRoute) " · 上次航线" else ""))
                        selection.update()
                    }
                    val task = activeTask
                    taskRow.setVisibility(if (task == null) View.GONE else View.VISIBLE)
                    if (task != null) {
                        val status =
                            (if (task.labelsOnly) "卫星地名" else styleName(task.style)) + " " + task.done + "/" + task.total + " " + task.percent() + "%"
                        val stateKey = task.token + status + task.phase
                        if ((stateKey != inspectedProgress) && (SystemClock.elapsedRealtime() - inspectedAt >= 2000 || task.phase != "downloading") && selection.tiles != null && !selection.cacheRefreshing) {
                            inspectedProgress = stateKey
                            inspectedAt = SystemClock.elapsedRealtime()
                            selection.refreshCache()
                        }
                        if (stateKey != previousProgress) {
                            previousProgress = stateKey
                            progress.setMax(max(1, task.total))
                            progress.setProgress(task.done)
                            progressText.setText(status)
                            pauseTask.setText(if (task.phase == "downloading") "暂停" else "继续")
                            pauseTask.setEnabled(task.phase != "done")
                            pauseTask.setAlpha(if (task.phase == "done") .4f else 1f)
                            progressArea.setContentDescription(areaNames[task.area] + (if (task.area == 0) " 每侧 " else " 半径 ") + task.km + " 公里，Zoom " + task.zoom + "，" + status)
                        }
                    }
                    val canStart =
                        selection.tiles != null && selection.needed > 0 && (task == null || task.phase != "downloading")
                    start.setEnabled(canStart)
                    start.setAlpha(if (canStart) 1f else .45f)
                    val live = cameraZoom()
                    if (java.lang.Float.compare(previousZoom, live) != 0) {
                        previousZoom = live
                        subtitle.setText(
                            String.format(
                                Locale.CHINA,
                                "当前地图 Zoom %.2f · 蓝色虚线内为预览范围",
                                live
                            )
                        )
                    }
                    val camera = call(map, "getCameraPosition").toString()
                    if (camera != previousCamera) {
                        previousCamera = camera
                        if (preview != null) preview!!.invalidate()
                    }
                    val target = String.format(Locale.ROOT, "%.2f", live)
                    if (mapTouched && abs(live - touchZoom) > .005f) mapInteracted = true
                    if (mapInteracted && !zoom.hasFocus() && !draggingZoom[0] && SystemClock.uptimeMillis() - lastZoomInput[0] >= 800 && !target.contentEquals(
                            zoom.getText()
                        )
                    ) {
                        syncing[0] = true
                        zoom.setText(target)
                        syncing[0] = false
                    }
                }
            }
            onMapGesture = Runnable {
                distance.clearFocus()
                zoom.clearFocus()
                lastZoomInput[0] = 0
                if (scheduledZoom[0] != null) {
                    handler.removeCallbacks(scheduledZoom[0]!!)
                    scheduledZoom[0] = null
                }
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(
                    box.windowToken,
                    0
                )
            }
            val cameraWatcher: Job = jobs.observe(cameraUpdate)
            start.setOnClickListener(View.OnClickListener { v: View? ->
                if (cacheMutation || selection.tiles == null || activeTask != null && activeTask!!.phase == "downloading") return@OnClickListener
                if (selection.style != offlineStyle) {
                    selection.update()
                    return@OnClickListener
                }
                val saved = JSONArray()
                for (path in snapshot) {
                    val segment = JSONArray()
                    for (point in path) segment.put(
                        JSONArray(
                            Arrays.asList<Double?>(
                                point[0],
                                point[1]
                            )
                        )
                    )
                    saved.put(segment)
                }
                val savedAirports = AccountRoutes.encodeAirports(airports)
                prefs.edit().putString("route", saved.toString())
                    .putString(if (area == 0) "distance" else "airport_radius", "" + selection.km)
                    .putInt("download_area", area)
                    .putString("zoom", "" + selection.max).putInt("download_style", selection.style).apply()
                val selected = selection.tiles
                dialog.dismiss()
                val task = DownloadTask(
                    selection.km,
                    zoom.getText().toString().toFloat(),
                    selection.style,
                    selected!!.stream().mapToInt { obj: MutableSet<Long>? -> obj!!.size }
                        .sum(),
                    saved.toString(), area = area, airports = savedAirports)
                if (!activity.getSystemService<NotificationManager>(NotificationManager::class.java)
                        .canNotifyAsPackage(DownloadNotifications.MODULE)
                ) activity.startActivity(
                    Intent().setClassName(
                        DownloadNotifications.MODULE,
                        "com.ohyooo.lvtu.NotificationPermissionActivity"
                    ).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
                launchDownload(task, selected)
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener { d: DialogInterface? ->
                jobs.cancel()
                airportRead.cancel()
                cameraWatcher.cancel(null)
                onMapGesture = null
                if (scheduledZoom[0] != null) scheduledZoom[0]!!.run()
                handler.removeCallbacksAndMessages(null)
                removePreview()
                settingsDialog = null
            })
            selection.update()
        }

        fun removePreview() {
            if (preview != null) {
                (preview!!.parent as ViewGroup).removeView(preview)
                preview = null
            }
        }

        fun buildCirclePreview(airports: List<Airport>, km: Double): MutableList<MutableList<Any?>> {
            val point = loader!!.loadClass("com.amap.api.maps.model.LatLng")
                .getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            return airports.mapTo(mutableListOf()) { airport ->
                (0 until 120).mapTo(mutableListOf()) { step ->
                    offset(point, airport.point, step * Math.PI / 60, km)
                }
            }
        }

        fun buildPreview(
            paths: MutableList<MutableList<DoubleArray>>,
            km: Double
        ): MutableList<MutableList<Any?>> {
            val boundaries: MutableList<MutableList<Any?>> =
                ArrayList<MutableList<Any?>>()
            try {
                val latLng: Constructor<*> = loader!!.loadClass("com.amap.api.maps.model.LatLng")
                    .getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                for (path in paths) {
                    if (path.size < 2) continue
                    val sampled: MutableList<DoubleArray> =
                        geodesic(path)
                    val boundary: MutableList<Any?> = ArrayList<Any?>()
                    val bearings = DoubleArray(sampled.size)
                    for (i in sampled.indices) {
                        val a: DoubleArray = sampled.get(max(0, i - 1))
                        val b: DoubleArray = sampled.get(min(sampled.size - 1, i + 1))
                        val delta = Math.toRadians(b[1] - a[1])
                        bearings[i] = atan2(
                            sin(delta) * cos(Math.toRadians(b[0])),
                            cos(Math.toRadians(a[0])) * sin(Math.toRadians(b[0])) -
                                    sin(Math.toRadians(a[0])) * cos(
                                Math.toRadians(
                                    b[0]
                                )
                            ) * cos(delta)
                        )
                    }
                    for (i in sampled.indices) boundary.add(
                        offset(
                            latLng,
                            sampled.get(i),
                            bearings[i] - Math.PI / 2,
                            km
                        )
                    )
                    val last = sampled.size - 1
                    for (i in 1..12) boundary.add(
                        offset(
                            latLng,
                            sampled.get(last),
                            bearings[last] - Math.PI / 2 + Math.PI * i / 12,
                            km
                        )
                    )
                    for (i in last downTo 0) boundary.add(
                        offset(
                            latLng,
                            sampled.get(i),
                            bearings[i] + Math.PI / 2,
                            km
                        )
                    )
                    for (i in 1..12) boundary.add(
                        offset(
                            latLng,
                            sampled.get(0),
                            bearings[0] + Math.PI / 2 + Math.PI * i / 12,
                            km
                        )
                    )
                    boundaries.add(boundary)
                }
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("创建范围预览失败", e)
            }
            return boundaries
        }

        fun showPreview(boundaries: MutableList<MutableList<Any?>>) {
            val projection: Any = call(map, "getProjection")!!
            val project: Method
            try {
                project = projection.javaClass.getMethod(
                    "toScreenLocation",
                    loader!!.loadClass("com.amap.api.maps.model.LatLng")
                )
            } catch (error: ReflectiveOperationException) {
                throw IllegalStateException("读取地图投影失败", error)
            }
            preview = object : View(activity) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val outline = Path()
                val dash = DashPathEffect(floatArrayOf(dp(7f).toFloat(), dp(5f).toFloat()), 0f)
                val mapLocation = IntArray(2)
                val ownLocation = IntArray(2)

                override fun onDraw(canvas: Canvas) {
                    view.getLocationOnScreen(mapLocation)
                    getLocationOnScreen(ownLocation)
                    canvas.save()
                    canvas.translate(
                        (mapLocation[0] - ownLocation[0]).toFloat(),
                        (mapLocation[1] - ownLocation[1]).toFloat()
                    )
                    canvas.clipRect(0, 0, view.width, view.height)

                    for (boundary in boundaries) {
                        outline.rewind()
                        for (i in boundary.indices) {
                            val point: Point
                            try {
                                point = project.invoke(projection, boundary.get(i)) as Point
                            } catch (error: ReflectiveOperationException) {
                                throw IllegalStateException("投影范围预览失败", error)
                            }
                            if (i == 0) outline.moveTo(point.x.toFloat(), point.y.toFloat())
                            else outline.lineTo(point.x.toFloat(), point.y.toFloat())
                        }
                        outline.close()
                        paint.setStyle(Paint.Style.FILL)
                        paint.setColor(0x302d9bff)
                        paint.setPathEffect(null)
                        canvas.drawPath(outline, paint)
                        paint.setStyle(Paint.Style.STROKE)
                        paint.setStrokeWidth(dp(1.5f).toFloat())
                        paint.setColor(-0x964501)
                        paint.setPathEffect(dash)
                        canvas.drawPath(outline, paint)
                    }
                    canvas.restore()
                }
            }
            preview!!.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
            (view as ViewGroup).addView(preview, ViewGroup.LayoutParams(-1, -1))
        }

        fun offset(
            constructor: Constructor<*>,
            point: DoubleArray,
            bearing: Double,
            km: Double
        ): Any? {
            val lat = Math.toRadians(point[0])
            val lon = Math.toRadians(point[1])
            val angle = km / 6371.0088
            val nextLat = asin(
                sin(lat) * cos(angle) + cos(lat) * sin(angle) * cos(
                    bearing
                )
            )
            val nextLon = lon + atan2(
                sin(bearing) * sin(angle) * cos(lat),
                cos(angle) - sin(lat) * sin(nextLat)
            )
            return constructor.newInstance(
                Math.toDegrees(nextLat),
                (Math.toDegrees(nextLon) + 540) % 360 - 180
            )
        }

        fun report(text: String) {
            jobs.onMain(Runnable {
                updateDownloadButton()
                Toast.makeText(activity, text, 1).show()
            })
        }

        fun styleName(style: Int): String {
            return if (style == 6) "卫星" else if (style == 8) "卫星标注" else "二维"
        }

        fun levelKey(style: Int, zoom: Int): Int {
            return style * 32 + zoom
        }

        fun cacheColor(zoom: Int): Int {
            return Color.HSVToColor(floatArrayOf(zoom * 137.5f % 360, .75f, .78f))
        }

        fun readCacheTiles(): MutableList<CacheCleaner.Tile> {
            val tiles: MutableList<CacheCleaner.Tile> = ArrayList<CacheCleaner.Tile>()
            db!!.rawQuery("SELECT rowid,z,x,y,LENGTH(data) FROM tiles", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (Thread.currentThread()
                            .isInterrupted()
                    ) throw CancellationException("清理检查已取消")
                    tiles.add(
                        CacheCleaner.Tile(
                            cursor.getLong(0),
                            cursor.getInt(1),
                            cursor.getInt(2),
                            cursor.getInt(3),
                            cursor.getLong(4)
                        )
                    )
                }
            }
            return tiles
        }

        fun cleanByRoutes() {
            val dialog = Dialog(activity)
            cleanupDialog = dialog
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCanceledOnTouchOutside(false)
            val box = LinearLayout(activity)
            box.setOrientation(LinearLayout.VERTICAL)
            box.setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            box.setBackground(surface(-0x80604, 22))
            box.addView(label("按账号航线清理", 20, -0xe9d4b7))
            val hint: TextView = label("正在读取当前账号已同步航线…", 12, -0x9a8972)
            hint.setPadding(0, dp(8f), 0, dp(8f))
            box.addView(hint)
            val distanceRow = LinearLayout(activity)
            distanceRow.setGravity(Gravity.CENTER_VERTICAL)
            distanceRow.addView(label("保留每侧距离 · km", 13, -0x9a8972), LinearLayout.LayoutParams(0, -2, 1f))
            val keepKm = EditText(activity)
            keepKm.setInputType(8194)
            keepKm.setSingleLine(true)
            keepKm.setText(activity.getSharedPreferences("lvtu-offline", 0).getString("cleanup_km", "2"))
            distanceRow.addView(keepKm, LinearLayout.LayoutParams(dp(90f), -2))
            box.addView(distanceRow)
            val choices = LinearLayout(activity)
            val selectAll: TextView = label("全选", 13, -0xd78414)
            val selectNone: TextView = label("全不选 / 清空全部", 13, -0x47b6b7)
            selectAll.setGravity(Gravity.CENTER)
            selectNone.setGravity(Gravity.CENTER)
            choices.addView(selectAll, LinearLayout.LayoutParams(0, dp(36f), 1f))
            choices.addView(selectNone, LinearLayout.LayoutParams(0, dp(36f), 1f))
            box.addView(choices)
            val list = ListView(activity)
            list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE)
            box.addView(list, LinearLayout.LayoutParams(-1, dp(180f)))
            val estimate: TextView = label("先读取航线，再计算清理范围", 13, -0x9a8972)
            estimate.setPadding(0, dp(8f), 0, dp(8f))
            box.addView(estimate)
            val actions = LinearLayout(activity)
            val close: TextView = label("关闭", 14, -0x9a8972)
            val clean: TextView = label("清理范围外缓存", 14, Color.WHITE)
            close.setGravity(Gravity.CENTER)
            clean.setGravity(Gravity.CENTER)
            clean.setBackground(surface(-0xd78414, 12))
            clean.setEnabled(false)
            actions.addView(close, LinearLayout.LayoutParams(0, dp(44f), 1f))
            actions.addView(clean, LinearLayout.LayoutParams(0, dp(44f), 2f))
            box.addView(actions)
            dialog.setContentView(box)
            val window: Window = dialog.window!!
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            window.setGravity(Gravity.BOTTOM)
            dialog.show()
            window.setLayout(-1, -2)
            close.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
            val routes: MutableList<Route> = ArrayList<Route>()
            val checked: MutableSet<Int> = HashSet<Int>()
            val loaded = booleanArrayOf(false)
            val preview = arrayOf<CacheCleaner.Result?>(null)

            class CleanupSelection {
                fun selected(): MutableList<Route> {
                    val result: MutableList<Route> = ArrayList<Route>()
                    for (i in checked) result.add(routes.get(i))
                    return result
                }

                fun update() {
                    preview[0] = null
                    clean.setEnabled(false)
                    if (!loaded[0]) return
                    try {
                        val km = keepKm.getText().toString().toDouble()
                        require(km.isFinite() && km > 0 && km <= 1000) { "保留距离需在 0～1000 km" }
                        val selected: MutableList<Route> = selected()
                        clean.setText(if (selected.isEmpty()) "清空全部缓存" else "清理范围外缓存")
                        clean.setBackground(surface(if (selected.isEmpty()) -0x47b6b7 else -0xd78414, 12))
                        estimate.setText("停止调整 1 秒后计算，覆盖全部样式和级别…")
                        activity.getSharedPreferences("lvtu-offline", 0).edit()
                            .putString("cleanup_km", keepKm.getText().toString()).apply()
                        jobs.calculate<MutableList<CacheCleaner.Tile>, CacheCleaner.Result?>(
                            Callable { this@State.readCacheTiles() },
                            Function { tiles: MutableList<CacheCleaner.Tile> ->
                                CacheCleaner.plan(
                                    tiles,
                                    selected,
                                    km
                                )
                            },
                            Consumer { result: CacheCleaner.Result? ->
                                if (!dialog.isShowing()) return@Consumer
                                preview[0] = result
                                estimate.setText(
                                    String.format(
                                        Locale.CHINA,
                                        "将清理 %,d 张 · %.2f MB，保留 %,d 张",
                                        result!!.remove.size,
                                        result.bytes / 1048576.0,
                                        result.keep
                                    )
                                )
                                clean.setEnabled(true)
                            },
                            Consumer { error: Exception? -> estimate.setText("无法计算：" + error!!.message) })
                    } catch (error: IllegalArgumentException) {
                        jobs.cancel()
                        estimate.setText(error.message)
                    }
                }
            }

            val selection = CleanupSelection()
            keepKm.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    selection.update()
                }

                override fun afterTextChanged(text: Editable?) {
                }
            })
            selectAll.setOnClickListener(View.OnClickListener { v: View? ->
                checked.clear()
                for (i in routes.indices) {
                    checked.add(i)
                    list.setItemChecked(i, true)
                }
                selection.update()
            })
            selectNone.setOnClickListener(View.OnClickListener { v: View? ->
                checked.clear()
                list.clearChoices()
                list.invalidateViews()
                selection.update()
            })
            list.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>, item: View?, position: Int, id: Long ->
                if (list.isItemChecked(position)) checked.add(position)
                else checked.remove(position)
                selection.update()
            })
            jobs.load<AccountRoutes.Snapshot, MutableList<Route>>(Callable {
                AccountRoutes.read(
                    activity,
                    loader!!
                )
            }, Function { snapshot: AccountRoutes.Snapshot ->
                try {
                    return@Function AccountRoutes.parse(snapshot)
                } catch (error: JSONException) {
                    throw IllegalStateException(error)
                }
            }, Consumer { result: MutableList<Route> ->
                if (!dialog.isShowing()) return@Consumer
                routes.addAll(result)
                val labels: MutableList<String> = ArrayList<String>()
                for (i in routes.indices) {
                    labels.add(routes.get(i).label)
                    checked.add(i)
                }
                list.setAdapter(
                    ArrayAdapter<String>(
                        activity,
                        android.R.layout.simple_list_item_multiple_choice,
                        labels
                    )
                )
                for (i in routes.indices) list.setItemChecked(i, true)
                loaded[0] = true
                hint.setText("账号已同步 " + routes.size + " 条航线 · 不按 Zoom 区分\n未勾选航线即清空全部缓存")
                selection.update()
                Log.i("LvTu", "Account routes loaded count=" + routes.size)
            }, Consumer { error: Exception? ->
                hint.setText("航线读取失败，未执行任何清理：" + error!!.message)
                clean.setEnabled(false)
            })
            clean.setOnClickListener(View.OnClickListener { v: View? ->
                if (preview[0] == null || cacheMutation) return@OnClickListener
                cacheMutation = true
                clean.setEnabled(false)
                close.setEnabled(false)
                dialog.setCancelable(false)
                estimate.setText("正在清理缓存…")
                val task = activeTask
                if (task != null && task.phase == "downloading") pauseDownload(task)
                val km = keepKm.getText().toString().toDouble()
                val selected: MutableList<Route> = selection.selected()
                jobs.loadAfter<MutableList<CacheCleaner.Tile>, CacheCleaner.Result?>(
                    if (task == null) null else task.job,
                    Callable { this@State.readCacheTiles() },
                    Function { tiles: MutableList<CacheCleaner.Tile> ->
                        CacheCleaner.plan(
                            tiles,
                            selected,
                            km
                        )
                    },
                    Consumer { result: CacheCleaner.Result? ->
                        jobs.io(Runnable {
                            var removed: Long = 0
                            db!!.beginTransaction()
                            try {
                                db!!.compileStatement("DELETE FROM tiles WHERE rowid=?").use { delete ->
                                    for (id in result!!.remove) {
                                        if (Thread.currentThread()
                                                .isInterrupted()
                                        ) throw CancellationException("清理已取消")
                                        delete.bindLong(1, id)
                                        removed += delete.executeUpdateDelete().toLong()
                                    }
                                    db!!.setTransactionSuccessful()
                                }
                            } finally {
                                db!!.endTransaction()
                            }
                            if (removed > 0) db!!.execSQL("VACUUM")
                            hasTiles = cacheInfo(null).count > 0
                            cachedData = hasTiles
                            val count = removed
                            jobs.onMain(Runnable {
                                cacheMutation = false
                                cachedLayers.clear()
                                updateCacheMarks()
                                if (overlay != null) call(overlay!!, "clearTileCache")
                                if (annotationOverlay != null) call(
                                    annotationOverlay!!,
                                    "clearTileCache"
                                )
                                activity.getSharedPreferences("lvtu-offline", 0).edit().putLong("verify_cursor", 0)
                                    .putLong("verify_scanned", 0).putLong("verify_invalid", 0).apply()
                                estimate.setText("已清理 " + count + " 张，保留 " + result!!.keep + " 张")
                                close.setEnabled(true)
                                dialog.setCancelable(true)
                                preview[0] = null
                                clean.setEnabled(false)
                            })
                        }, Consumer { error: Exception? ->
                            cacheMutation = false
                            close.setEnabled(true)
                            dialog.setCancelable(true)
                            estimate.setText("清理失败：" + error!!.message)
                        })
                    },
                    Consumer { error: Exception? ->
                        cacheMutation = false
                        close.setEnabled(true)
                        dialog.setCancelable(true)
                        estimate.setText("清理检查失败：" + error!!.message)
                    })
            })
            box.setOnTouchListener(OnTouchListener { v: View?, event: MotionEvent ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    keepKm.clearFocus()
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(
                        box.windowToken,
                        0
                    )
                }
                false
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener { d: DialogInterface? ->
                jobs.cancel()
                cleanupDialog = null
            })
        }

        fun cacheList() {
            val dialog = Dialog(activity)
            cacheDialog = dialog
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCanceledOnTouchOutside(false)
            val box = LinearLayout(activity)
            box.setOrientation(LinearLayout.VERTICAL)
            box.setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            box.setBackground(surface(-0x80604, 22))
            box.addView(label("缓存列表", 20, -0xe9d4b7))
            val hint: TextView = label("多选叠加标记，再次点击取消该级别", 12, -0x9a8972)
            hint.setPadding(0, dp(8f), 0, dp(8f))
            box.addView(hint)
            val list = ListView(activity)
            list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE)
            box.addView(list, LinearLayout.LayoutParams(-1, dp(280f)))
            val actions = LinearLayout(activity)
            val clear: TextView = label("清除全部标记", 14, -0xd78414)
            val close: TextView = label("完成", 14, -0xd78414)
            clear.setGravity(Gravity.CENTER)
            close.setGravity(Gravity.CENTER)
            actions.addView(clear, LinearLayout.LayoutParams(0, dp(46f), 1f))
            val cleanRoutes: TextView = label("按航线清理", 14, -0xd78414)
            cleanRoutes.setGravity(Gravity.CENTER)
            cleanRoutes.setOnClickListener(View.OnClickListener { v: View? ->
                dialog.dismiss()
                cleanByRoutes()
            })
            actions.addView(cleanRoutes, LinearLayout.LayoutParams(0, dp(46f), 1f))
            actions.addView(close, LinearLayout.LayoutParams(0, dp(46f), 1f))
            box.addView(actions)
            dialog.setContentView(box)
            val window: Window = dialog.window!!
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            window.setGravity(Gravity.BOTTOM)
            dialog.show()
            window.setLayout(-1, -2)
            close.setOnClickListener(View.OnClickListener { v: View? -> dialog.dismiss() })
            clear.setOnClickListener(View.OnClickListener { v: View? ->
                for (job in markingJobs.values) job.cancel(null)
                markingJobs.clear()
                cachedLayers.clear()
                updateCacheMarks()
                list.clearChoices()
                list.invalidateViews()
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener { d: DialogInterface? -> cacheDialog = null })
            jobs.io(
                Runnable {
                    val levels: MutableList<CacheLevel> = ArrayList<CacheLevel>()
                    if (activity.getDatabasePath("lvtu-offline.db").isFile()) {
                        db!!.rawQuery(
                            "SELECT style,z,COUNT(*),SUM(LENGTH(data)) FROM tiles GROUP BY style,z ORDER BY style,z",
                            null
                        ).use { cursor ->
                            while (cursor.moveToNext()) levels.add(
                                CacheLevel(
                                    cursor.getInt(0),
                                    cursor.getInt(1),
                                    cursor.getLong(2),
                                    cursor.getLong(3)
                                )
                            )
                        }
                    }
                    val verified = activity.getSharedPreferences("lvtu-offline", 0)
                        .getString("verify_status", "尚未核验；仅充电时每天最多一次，最低优先级")
                    jobs.onMain(Runnable {
                        if (!dialog.isShowing()) return@Runnable
                        hint.setText(if (levels.isEmpty()) "暂无缓存" else "多选叠加标记，再次点击取消该级别\n" + verified)
                        val rows: MutableList<String> = ArrayList<String>()
                        for (level in levels) rows.add(
                            String.format(
                                Locale.CHINA,
                                "%s Z%d · %,d 张 · %.2f MB",
                                styleName(level.style),
                                level.zoom,
                                level.count,
                                level.bytes / 1048576.0
                            )
                        )
                        list.setAdapter(object : ArrayAdapter<String>(
                            activity,
                            android.R.layout.simple_list_item_multiple_choice,
                            rows
                        ) {
                            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                                val level = levels.get(position)
                                val row = LinearLayout(activity)
                                row.setGravity(Gravity.CENTER_VERTICAL)
                                row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)
                                val text: CheckedTextView = LayoutInflater.from(activity).inflate(
                                    android.R.layout.simple_list_item_multiple_choice,
                                    row,
                                    false
                                ) as CheckedTextView
                                text.setText(rows.get(position))
                                text.setTextColor(-0xe9d4b7)
                                val swatch: GradientDrawable = surface(cacheColor(levelKey(level.style, level.zoom)), 4)
                                swatch.setBounds(0, 0, dp(8f), dp(8f))
                                text.setCompoundDrawables(swatch, null, null, null)
                                text.setCompoundDrawablePadding(dp(8f))
                                text.setCheckMarkTintList(ColorStateList.valueOf(-0xd78414))
                                text.setTextSize(13f)
                                text.setChecked(
                                    cachedLayers.containsKey(
                                        levelKey(
                                            level.style,
                                            level.zoom
                                        )
                                    ) || markingJobs.containsKey(levelKey(level.style, level.zoom))
                                )
                                row.addView(text, LinearLayout.LayoutParams(0, dp(48f), 1f))
                                val delete: TextView = label("删除", 12, -0x47b6b7)
                                delete.setGravity(Gravity.CENTER)
                                delete.setFocusable(false)
                                row.addView(delete, LinearLayout.LayoutParams(dp(40f), dp(48f)))
                                delete.setOnClickListener(View.OnClickListener { v: View? ->
                                    delete.setEnabled(false)
                                    val task = activeTask
                                    if (task != null && task.phase == "downloading") pauseDownload(task)
                                    val pending: Job? = markingJobs.remove(levelKey(level.style, level.zoom))
                                    if (pending != null) pending.cancel(null)
                                    cachedLayers.remove(levelKey(level.style, level.zoom))
                                    updateCacheMarks()
                                    jobs.after(if (task == null) null else task.job, Runnable {
                                        val removed = db!!.delete(
                                            "tiles",
                                            "style=? AND z=?",
                                            arrayOf<String>("" + level.style, "" + level.zoom)
                                        )
                                        hasTiles = cacheInfo(null).count > 0
                                        cachedData = hasTiles
                                        jobs.onMain(Runnable {
                                            val index = levels.indexOf(level)
                                            if (index >= 0) {
                                                levels.removeAt(index)
                                                rows.removeAt(index)
                                            }
                                            (list.adapter as BaseAdapter).notifyDataSetChanged()
                                            hint.setText("已删除 Zoom " + level.zoom + " 的 " + removed + " 张缓存，其他级别保留")
                                            if (overlay != null) call(overlay!!, "clearTileCache")
                                            if (annotationOverlay != null) call(
                                                annotationOverlay!!,
                                                "clearTileCache"
                                            )
                                        })
                                    }, Consumer { error: Exception? ->
                                        delete.setEnabled(true)
                                        report("缓存删除失败：" + error!!.message)
                                    })
                                })
                                return row
                            }
                        })
                        for (i in levels.indices) list.setItemChecked(
                            i,
                            cachedLayers.containsKey(
                                levelKey(
                                    levels.get(i).style,
                                    levels.get(i).zoom
                                )
                            ) || markingJobs.containsKey(levelKey(levels.get(i).style, levels.get(i).zoom))
                        )
                        list.setOnItemClickListener(OnItemClickListener { parent: AdapterView<*>, row: View?, position: Int, id: Long ->
                            val level = levels.get(position)
                            val zoom = level.zoom
                            val key = levelKey(level.style, zoom)
                            if (cachedLayers.containsKey(key) || markingJobs.containsKey(key)) {
                                val pending: Job? = markingJobs.remove(key)
                                if (pending != null) pending.cancel(null)
                                cachedLayers.remove(key)
                                list.setItemChecked(position, false)
                                updateCacheMarks()
                            } else {
                                list.setItemChecked(position, true)
                                markingJobs.put(
                                    key,
                                    jobs.load<LongArray?, CacheLayer?>(
                                        Callable {
                                            db!!.rawQuery(
                                                "SELECT x,y FROM tiles WHERE style=? AND z=? ORDER BY y,x",
                                                arrayOf<String>("" + level.style, "" + zoom)
                                            ).use { cursor ->
                                                val keys = LongArray(cursor.count)
                                                var index = 0
                                                while (cursor.moveToNext()) {
                                                    if (Thread.currentThread()
                                                            .isInterrupted()
                                                    ) throw CancellationException("缓存标记已取消")
                                                    keys[index++] =
                                                        (cursor.getInt(0).toLong() shl 32) or (cursor.getInt(1)
                                                            .toLong() and 0xffffffffL)
                                                }
                                                return@use keys
                                            }
                                        },
                                        Function { keys: LongArray? ->
                                            buildCoverage(
                                                level.style,
                                                zoom,
                                                keys!!
                                            )
                                        },
                                        Consumer { layer: CacheLayer? ->
                                            markingJobs.remove(key)
                                            cachedLayers.put(key, layer!!)
                                            updateCacheMarks()
                                            list.invalidateViews()
                                            Log.i(
                                                "LvTu",
                                                "Cache marks added zoom=" + zoom + " selected=" + cachedLayers.keys
                                            )
                                        },
                                        Consumer { error: Exception? ->
                                            markingJobs.remove(key)
                                            list.setItemChecked(position, false)
                                            report("缓存标记失败：" + error!!.message)
                                        })
                                )
                            }
                            list.invalidateViews()
                        })
                    })
                },
                Consumer { error: Exception? -> hint.setText("缓存列表读取失败：" + error!!.message) })
        }

        fun buildCoverage(style: Int, zoom: Int, keys: LongArray): CacheLayer {
            val n = 1 shl zoom
            if (keys.size.toLong() == n.toLong() * n) return CacheLayer(
                style,
                zoom,
                true,
                mutableListOf<CacheRect>()
            )
            val merged: MutableList<Rect> = ArrayList<Rect>()
            val active: MutableMap<Long, Rect?> = HashMap<Long, Rect?>()
            var left = -1
            var right = -1
            var row = -1
            for (key in keys) {
                if (Thread.currentThread()
                        .isInterrupted()
                ) throw CancellationException("缓存标记已取消")
                val x = (key shr 32).toInt()
                val y = key.toInt()
                if (row == y && x == right) right++
                else {
                    if (row >= 0) mergeCacheRun(merged, active, left, right, row)
                    left = x
                    right = x + 1
                    row = y
                }
            }
            if (row >= 0) mergeCacheRun(merged, active, left, right, row)
            val rectangles: MutableList<CacheRect> = ArrayList<CacheRect>()
            try {
                val latLng: Constructor<*> = loader!!.loadClass("com.amap.api.maps.model.LatLng")
                    .getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
                for (rect in merged) {
                    val north =
                        Math.toDegrees(atan(sinh(Math.PI * (1 - 2.0 * rect.top / n))))
                    val south =
                        Math.toDegrees(atan(sinh(Math.PI * (1 - 2.0 * rect.bottom / n))))
                    val parts = max(1, ceil(rect.width() * 4.0 / n).toInt())
                    for (p in 0..<parts) {
                        val west: Double = (rect.left + rect.width() * p / parts.toDouble()) / n * 360 - 180
                        val east: Double =
                            (rect.left + rect.width() * (p + 1) / parts.toDouble()) / n * 360 - 180
                        rectangles.add(
                            CacheRect(
                                west,
                                south,
                                east,
                                north,
                                listOf(
                                    latLng.newInstance(north, west),
                                    latLng.newInstance(north, east),
                                    latLng.newInstance(south, east),
                                    latLng.newInstance(south, west)
                                )
                            )
                        )
                    }
                }
            } catch (error: ReflectiveOperationException) {
                throw IllegalStateException(error)
            }
            return CacheLayer(style, zoom, false, rectangles)
        }

        fun mergeCacheRun(
            result: MutableList<Rect>,
            active: MutableMap<Long, Rect?>,
            left: Int,
            right: Int,
            y: Int
        ) {
            val key = (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)
            val above: Rect? = active.get(key)
            if (above != null && above.bottom == y) above.bottom++
            else {
                val rect = Rect(left, y, right, y + 1)
                result.add(rect)
                active.put(key, rect)
            }
        }

        fun updateCacheMarks() {
            if (cachedLayers.isEmpty()) {
                if (cacheCameraWatcher != null) {
                    cacheCameraWatcher!!.cancel(null)
                    cacheCameraWatcher = null
                }
                if (cachePreview != null) {
                    (cachePreview!!.parent as ViewGroup).removeView(cachePreview)
                    cachePreview = null
                }
                return
            }
            if (cachePreview == null) {
                cachePreview = CacheCoverageView()
                (view as ViewGroup).addView(cachePreview, ViewGroup.LayoutParams(-1, -1))
                cacheCameraWatcher = jobs.observe(object : Runnable {
                    var previous: String = ""

                    override fun run() {
                        val current = call(map, "getCameraPosition").toString()
                        if (current != previous) {
                            previous = current
                            cachePreview!!.invalidate()
                        }
                    }
                })
            }
            cachePreview!!.invalidate()
        }

        inner class CacheCoverageView : View(activity) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val shape = Path()
            val projection: Any = call(map, "getProjection")!!
            val project: Method

            init {
                setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                try {
                    project = projection.javaClass.getMethod(
                        "toScreenLocation",
                        loader!!.loadClass("com.amap.api.maps.model.LatLng")
                    )
                } catch (error: ReflectiveOperationException) {
                    throw IllegalStateException(error)
                }
            }

            override fun onDraw(canvas: Canvas) {
                try {
                    val region: Any = call(projection, "getVisibleRegion")!!
                    val bounds = region.javaClass.getField("latLngBounds").get(region)
                    val visible: MutableList<DoubleArray> = points(
                        listOf(
                            bounds.javaClass.getField("southwest").get(bounds),
                            bounds.javaClass.getField("northeast").get(bounds)
                        )
                    )
                    val south = visible.get(0)[0]
                    val west = visible.get(0)[1]
                    val north = visible.get(1)[0]
                    val east = visible.get(1)[1]
                    for (layer in cachedLayers.values) {
                        val color = cacheColor(levelKey(layer.style, layer.zoom))
                        if (layer.world) {
                            paint.setStyle(Paint.Style.FILL)
                            paint.setColor((color and 0xffffff) or 0x18000000)
                            canvas.drawRect(0f, 0f, getWidth().toFloat(), getHeight().toFloat(), paint)
                            continue
                        }
                        for (rect in layer.rectangles!!) {
                            if (rect.north < south || rect.south > north) continue
                            if (if (west <= east) rect.east < west || rect.west > east else rect.east < west && rect.west > east) continue
                            shape.rewind()
                            for (i in rect.corners!!.indices) {
                                val point = project.invoke(projection, rect.corners.get(i)) as Point
                                if (i == 0) shape.moveTo(point.x.toFloat(), point.y.toFloat())
                                else shape.lineTo(point.x.toFloat(), point.y.toFloat())
                            }
                            shape.close()
                            paint.setStyle(Paint.Style.FILL)
                            paint.setColor((color and 0xffffff) or 0x28000000)
                            canvas.drawPath(shape, paint)
                            paint.setStyle(Paint.Style.STROKE)
                            paint.setStrokeWidth(dp(1f).toFloat())
                            paint.setColor(color)
                            canvas.drawPath(shape, paint)
                        }
                    }
                } catch (error: ReflectiveOperationException) {
                    throw IllegalStateException("绘制缓存标记失败", error)
                }
            }
        }

        fun offlineViewer() {
            val saved: MutableList<MutableList<DoubleArray>> =
                ArrayList<MutableList<DoubleArray>>()
            try {
                val paths: JSONArray =
                    JSONArray(activity.getSharedPreferences("lvtu-offline", 0).getString("route", "[]"))
                for (i in 0..<paths.length()) {
                    val path: MutableList<DoubleArray> =
                        ArrayList<DoubleArray>()
                    val points: JSONArray = paths.getJSONArray(i)
                    for (j in 0..<points.length()) path.add(
                        doubleArrayOf(
                            points.getJSONArray(j).getDouble(0),
                            points.getJSONArray(j).getDouble(1)
                        )
                    )
                    saved.add(path)
                }
            } catch (e: JSONException) {
                report("保存的航线无法读取")
                return
            }
            if (saved.isEmpty() || saved.get(0).isEmpty()) {
                report("请先选择航线并下载地图")
                return
            }
            val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
            dialog.setContentView(OfflineView(activity, saved))
            dialog.show()
        }

        inner class OfflineView(
            context: Context,
            val paths: MutableList<MutableList<DoubleArray>>
        ) : View(context) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val cache: LruCache<Long, Bitmap?> = LruCache<Long, Bitmap?>(64)
            val pending: MutableSet<Long> =
                ConcurrentHashMap.newKeySet<Long>()
            val missing: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val scale: ScaleGestureDetector
            var cx: Double
            var cy: Double
            var zoom: Double = 6.0
            var lastX: Float = 0f
            var lastY: Float = 0f

            init {
                val middle = paths.get(0).get(paths.get(0).size / 2)
                cx = (middle[1] + 180) / 360
                cy = tileY(middle[0], 1)
                scale = ScaleGestureDetector(context, object : SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val old: Double = 2.0.pow(zoom) * 256
                        val dx: Double = detector.focusX - getWidth() / 2.0
                        val dy: Double = detector.focusY - getHeight() / 2.0
                        zoom = max(
                            0.0,
                            min(
                                20.0,
                                zoom + ln(detector.scaleFactor.toDouble()) / ln(2.0)
                            )
                        )
                        val next: Double = 2.0.pow(zoom) * 256
                        cx += dx / old - dx / next
                        cy += dy / old - dy / next
                        invalidate()
                        return true
                    }
                })
                setContentDescription("离线卫星地图，双指缩放，拖动查看，返回关闭")
            }

            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(35, 43, 50))
                val z = floor(zoom).toInt()
                val n = 1 shl z
                val size = (256 * 2.0.pow(zoom - z)).toFloat()
                val left = floor(cx * n - getWidth() / 2.0 / size).toInt()
                val top = floor(cy * n - getHeight() / 2.0 / size).toInt()
                var y = top
                while (y <= top + getHeight() / size + 1) {
                    var x = left
                    while (x <= left + getWidth() / size + 1) {
                        if (y < 0 || y >= n) {
                            x++
                            continue
                        }
                        val tx = Math.floorMod(x, n)
                        val key = (z.toLong() shl 58) or (tx.toLong() shl 29) or y.toLong()
                        val bitmap: Bitmap? = cache.get(key)
                        if (bitmap == null) {
                            if (pending.add(key)) {
                                val tileX = tx
                                val tileY = y
                                val tileZoom = z
                                jobs.io(Runnable {
                                    val bytes = tile(tileX, tileY, tileZoom)
                                    val decoded: Bitmap? =
                                        if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    cache.put(key, if (decoded == null) missing else decoded)
                                    pending.remove(key)
                                    jobs.onMain(Runnable { this.invalidate() })
                                }, Consumer { error: Exception? ->
                                    pending.remove(key)
                                    cache.put(key, missing)
                                    report("离线瓦片读取失败：" + error!!.message)
                                })
                            }
                            x++
                            continue
                        }
                        if (bitmap == missing) {
                            x++
                            continue
                        }
                        val px = ((x - cx * n) * size + getWidth() / 2.0).toFloat()
                        val py = ((y - cy * n) * size + getHeight() / 2.0).toFloat()
                        canvas.drawBitmap(bitmap, null, RectF(px, py, px + size + 1, py + size + 1), paint)
                        x++
                    }
                    y++
                }
                paint.setColor(Color.CYAN)
                paint.setStrokeWidth(5f)
                paint.setStyle(Paint.Style.STROKE)
                for (path in paths) {
                    val line = Path()
                    var first = true
                    for (p in path) {
                        var dx = (p[1] + 180) / 360 - cx
                        dx -= floor(dx + .5)
                        val x = (dx * n * size + getWidth() / 2.0).toFloat()
                        val y = ((tileY(p[0], 1) - cy) * n * size + getHeight() / 2.0).toFloat()
                        if (first) line.moveTo(x, y)
                        else line.lineTo(x, y)
                        first = false
                    }
                    canvas.drawPath(line, paint)
                }
                paint.setStyle(Paint.Style.FILL)
                paint.setColor(Color.WHITE)
                paint.setTextSize(36f)
                canvas.drawText(
                    "离线地图 · zoom " + String.format(
                        Locale.ROOT,
                        "%.1f",
                        zoom
                    ) + " · 返回关闭", 24f, 60f, paint
                )
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                scale.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !scale.isInProgress()) {
                    val world: Double = 2.0.pow(zoom) * 256
                    cx -= (event.x - lastX) / world
                    cy = max(0.0, min(1.0, cy - (event.y - lastY) / world))
                    cx -= floor(cx)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }

        fun updateDownloadButton() {
            val button = button ?: return
            val task = activeTask
            button.setText(if (task == null || task.deleted) idleCaption else task.percent().toString() + "%")
            button.setContentDescription(if (task == null) "离线地图" else "离线地图，下载进度 " + task.percent() + "%")
        }
    }

    private fun prepareDownloads() {
        val pauseReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val task = activeTask
                if (task != null && task.token == intent.getStringExtra("token")) pauseDownload(task)
            }
        }
        application.registerReceiver(
            pauseReceiver,
            IntentFilter(DownloadNotifications.PAUSE),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        )
        cacheReady = downloadJobs.load<DownloadTask?, RestoredDownload?>(
            Callable {
                LegacyStorage.migrate(application)
                database = application.openOrCreateDatabase("lvtu-offline.db", 0, null)
                database!!.enableWriteAheadLogging()
                database!!.execSQL("CREATE TABLE IF NOT EXISTS tiles(style INTEGER,z INTEGER,x INTEGER,y INTEGER,data BLOB,PRIMARY KEY(style,z,x,y))")
                var styled = false
                database!!.rawQuery("PRAGMA table_info(tiles)", null).use { columns ->
                    while (columns.moveToNext()) if (columns.getString(1) == "style") styled = true
                }
                if (!styled) {
                    database!!.beginTransaction()
                    try {
                        database!!.execSQL("CREATE TABLE tiles_styled(style INTEGER,z INTEGER,x INTEGER,y INTEGER,data BLOB,PRIMARY KEY(style,z,x,y))")
                        database!!.execSQL("INSERT INTO tiles_styled(rowid,style,z,x,y,data) SELECT rowid,6,z,x,y,data FROM tiles")
                        database!!.execSQL("DROP TABLE tiles")
                        database!!.execSQL("ALTER TABLE tiles_styled RENAME TO tiles")
                        database!!.setTransactionSuccessful()
                    } finally {
                        database!!.endTransaction()
                    }
                }
                database!!.rawQuery("SELECT EXISTS(SELECT 1 FROM tiles)", null).use { cursor ->
                    cachedData = cursor.moveToFirst() && cursor.getInt(0) != 0
                }
                val saved = application.getSharedPreferences("lvtu-offline", 0).getString("download_task", null)
                if (saved == null) return@Callable annotationRepair()
                try {
                    val task = DownloadTask(JSONObject(saved))
                    if (task.phase == "done" && !task.manualPaused) {
                        val repair = annotationRepair()
                        if (repair != null) return@Callable repair
                    }
                    if (task.labelsOnly) task.labelPlan = readLabelPlan()
                    return@Callable task
                } catch (error: JSONException) {
                    throw IllegalStateException("下载任务记录读取失败", error)
                }
            },
            Function { task: DownloadTask? ->
                if (task == null || task.manualPaused || task.phase == "done") return@Function RestoredDownload(
                    task,
                    null
                )
                RestoredDownload(task, downloadPlan(task))
            },
            Consumer { restored: RestoredDownload? ->
                CacheVerifier.install(application)
                activeTask = restored!!.task
                if (restored.tiles != null) launchDownload(restored.task!!, restored.tiles)
                else publishDownload(false)
            },
            Consumer { error: Exception? -> reportDownload("下载恢复失败：" + error!!.message) })
    }

    private fun readLabelPlan(): MutableList<MutableSet<Long>> {
        val tiles: MutableList<MutableSet<Long>> =
            ArrayList<MutableSet<Long>>()
        database!!.rawQuery("SELECT z,x,y FROM tiles WHERE style=6 ORDER BY z", null).use { cursor ->
            while (cursor.moveToNext()) {
                val z: Int = cursor.getInt(0)
                while (tiles.size <= z) tiles.add(HashSet<Long>())
                tiles.get(z).add((cursor.getInt(1).toLong() shl 32) or (cursor.getInt(2).toLong() and 0xffffffffL))
            }
        }
        return tiles
    }

    private fun annotationRepair(): DownloadTask? {
        val prefs: SharedPreferences = application.getSharedPreferences("lvtu-offline", 0)
        if (prefs.getBoolean("satellite_annotations_v1", false)) return null
        database!!.rawQuery(
            "SELECT 1 FROM tiles image WHERE image.style=6 AND NOT EXISTS (SELECT 1 FROM tiles labels WHERE labels.style=8 AND labels.z=image.z AND labels.x=image.x AND labels.y=image.y) LIMIT 1",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                prefs.edit().putBoolean("satellite_annotations_v1", true).apply()
                return null
            }
        }
        val tiles = readLabelPlan()
        val task = DownloadTask(
            prefs.getString("distance", "2")!!.toDouble(),
            (tiles.size - 1).toFloat(),
            6,
            tiles.stream().mapToInt { obj: MutableSet<Long>? -> obj!!.size }.sum(),
            prefs.getString("route", "[]")!!,
            true
        )
        task.labelPlan = tiles
        return task
    }

    private fun downloadPlan(task: DownloadTask): MutableList<MutableSet<Long>>? {
        if (task.labelsOnly) return task.labelPlan
        if (task.area != 0) {
            val airports = AccountRoutes.decodeAirports(task.airports).filter {
                task.area == 3 || it.departure == (task.area == 1)
            }
            check(airports.isNotEmpty()) { "恢复任务缺少已保存机场坐标" }
            return CircleArea.plan(airports.map { it.point }, task.km, min(18, ceil(task.zoom.toDouble()).toInt()))
        }
        try {
            val paths = JSONArray(task.route)
            val routes: MutableList<MutableList<DoubleArray>> =
                ArrayList<MutableList<DoubleArray>>()
            for (i in 0..<paths.length()) {
                val values: JSONArray = paths.getJSONArray(i)
                val path: MutableList<DoubleArray> =
                    ArrayList<DoubleArray>()
                for (j in 0..<values.length()) path.add(
                    doubleArrayOf(
                        values.getJSONArray(j).getDouble(0),
                        values.getJSONArray(j).getDouble(1)
                    )
                )
                routes.add(path)
            }
            check(routes.isNotEmpty()) { "恢复任务缺少已保存航线" }
            return plan(
                routes,
                task.km,
                min(18, ceil(task.zoom.toDouble()).toInt())
            )
        } catch (error: JSONException) {
            throw IllegalStateException("恢复下载航线读取失败", error)
        }
    }

    private fun resumeDownload(task: DownloadTask) {
        val previous: Job? = task.job
        synchronized(downloadStateLock) {
            task.manualPaused = false
            task.stopped.set(false)
            task.phase = "downloading"
            task.persist()
        }
        task.job =
            downloadJobs.loadAfter<DownloadTask, MutableList<MutableSet<Long>>>(
                previous,
                Callable {
                    if (task.labelsOnly) task.labelPlan = readLabelPlan()
                    task
                },
                Function { saved: DownloadTask ->
                    this.downloadPlan(saved)!!
                },
                Consumer { tiles: MutableList<MutableSet<Long>>? ->
                    if (activeTask == task && !task.manualPaused) launchDownload(task, tiles!!)
                },
                Consumer { error: Exception? -> reportDownload("恢复下载失败：" + error!!.message) })
    }

    private fun launchDownload(
        task: DownloadTask,
        tiles: MutableList<MutableSet<Long>>
    ) {
        val previous: DownloadTask?
        synchronized(downloadStateLock) {
            previous = activeTask
            if (previous != null && previous != task) previous.deleted = true
            activeTask = task
            task.manualPaused = false
            task.stopped.set(false)
            task.phase = "downloading"
            task.total = tiles.stream().mapToInt { obj: MutableSet<Long>? -> obj!!.size }
                .sum() * (if (task.style == 6 && !task.labelsOnly) 2 else 1)
            task.persist()
        }
        if (previous != null && previous != task) downloadJobs.io(
            Runnable {
                notifyDownload(
                    previous,
                    true
                )
            },
            Consumer { error: Exception? ->
                Log.e(
                    "LvTu",
                    "旧任务通知清理失败",
                    error
                )
            })
        publishDownload(false)
        task.job = downloadJobs.download(
            if (previous == null) null else previous.job,
            Runnable {},
            Runnable { download(task, tiles) },
            Consumer { error: Exception? ->
                Log.e("LvTu", "下载异常，稍后自动重试", error)
                task.persist()
                notifyDownload(task, false)
            })
    }

    private fun publishDownload(refresh: Boolean) {
        downloadJobs.onMain(Runnable {
            for (state in maps.values) {
                if (state.destroyed || state.button == null) continue
                state.hasTiles = cachedData
                state.updateDownloadButton()
                if (refresh) {
                    if (state.overlay == null) state.attachTiles()
                    else if (!state.online) {
                        call(state.overlay!!, "clearTileCache")
                        if (state.annotationOverlay != null) call(
                            state.annotationOverlay!!,
                            "clearTileCache"
                        )
                    }
                }
            }
        })
    }

    private fun reportDownload(text: String) {
        Log.i("LvTu", text)
        downloadJobs.onMain(Runnable {
            for (state in maps.values) if (!state.destroyed && state.button != null) state.report(text)
        })
    }

    private inner class DownloadTask {
        val km: Double
        val zoom: Float
        val style: Int
        val token: String
        val route: String
        val labelsOnly: Boolean
        val area: Int
        val airports: String
        var labelPlan: MutableList<MutableSet<Long>>? = null

        @Volatile
        var manualPaused: Boolean = false
        val stopped = AtomicBoolean()

        @Volatile
        var total: Int

        @Volatile
        var done: Int = 0

        @Volatile
        var failed: Int = 0

        @Volatile
        var phase: String?

        @Volatile
        var deleted: Boolean = false
        var job: Job? = null

        constructor(
            km: Double,
            zoom: Float,
            style: Int,
            total: Int,
            route: String,
            labelsOnly: Boolean = false,
            area: Int = 0,
            airports: String = "[]"
        ) {
            this.labelsOnly = labelsOnly
            this.area = area
            this.airports = airports
            token = UUID.randomUUID().toString()
            this.route = route
            this.km = km
            this.zoom = zoom
            this.style = style
            this.total = total
            phase = "downloading"
        }

        constructor(saved: JSONObject) {
            token = saved.optString("token", UUID.randomUUID().toString())
            route =
                saved.optString("route", application.getSharedPreferences("lvtu-offline", 0).getString("route", "[]")!!)
            manualPaused = saved.optBoolean("manual_paused", false)
            labelsOnly = saved.optBoolean("labels_only", false)
            area = saved.optInt("area", 0)
            airports = saved.optString("airports", "[]")
            km = saved.getDouble("km")
            zoom = saved.getDouble("zoom").toFloat()
            style = saved.optInt("style", 6)
            total = saved.getInt("total")
            done = saved.getInt("done")
            failed = saved.optInt("failed")
            phase = if (done >= total && failed == 0) "done" else if (manualPaused) "paused" else "downloading"
        }

        fun persist() {
            synchronized(downloadStateLock) {
                if (deleted || activeTask != this) return
                try {
                    val saved: JSONObject =
                        JSONObject().put("km", km).put("zoom", zoom.toDouble()).put("style", style).put("total", total)
                            .put("done", done).put("failed", failed).put("token", token).put("route", route)
                            .put("manual_paused", manualPaused).put("labels_only", labelsOnly)
                            .put("area", area).put("airports", airports)
                    application.getSharedPreferences("lvtu-offline", 0).edit()
                        .putString("download_task", saved.toString())
                        .putBoolean(
                            "satellite_annotations_v1",
                            style == 6 || application.getSharedPreferences("lvtu-offline", 0)
                                .getBoolean("satellite_annotations_v1", false)
                        ).apply()
                } catch (error: JSONException) {
                    throw IllegalStateException(error)
                }
            }
        }

        fun percent(): Int {
            return if (total == 0) 0 else min(100, done * 100 / total)
        }
    }

    private fun pauseDownload(task: DownloadTask) {
        synchronized(downloadStateLock) {
            task.manualPaused = true
            task.stopped.set(true)
            task.phase = "paused"
            task.persist()
        }
        if (task.job != null) task.job?.cancel()
        downloadJobs.io(
            Runnable { notifyDownload(task, true) },
            Consumer { error: Exception? ->
                Log.e(
                    "LvTu",
                    "暂停通知更新失败",
                    error
                )
            })
        publishDownload(false)
    }

    private fun deleteDownload(task: DownloadTask?) {
        synchronized(downloadStateLock) {
            task!!.deleted = true
            task.manualPaused = true
            task.stopped.set(true)
            task.phase = "paused"
            if (activeTask == task) activeTask = null
            application.getSharedPreferences("lvtu-offline", 0).edit().remove("download_task")
                .putBoolean("satellite_annotations_v1", true).apply()
        }
        if (task!!.job != null) task.job?.cancel()
        downloadJobs.io(
            Runnable { notifyDownload(task, true) },
            Consumer { error: Exception? -> reportDownload("通知清理失败：" + error!!.message) })
        publishDownload(false)
    }

    private fun notifyDownload(task: DownloadTask, finished: Boolean) {
        if (notificationUnavailable) return
        val state = Bundle()
        state.putString("token", task.token)
        state.putInt("style", task.style)
        state.putInt("total", task.total)
        state.putInt("done", task.done)
        state.putInt("failed", task.failed)
        state.putBoolean("finished", finished)
        state.putBoolean("paused", finished && (task.stopped.get() || task.done < task.total))
        try {
            if (DownloadNotifications.postDelegated(application, task.deleted, state)) return
            if (Build.VERSION.SDK_INT >= 34) {
                val update: Intent =
                    Intent(if (task.deleted) "com.ohyooo.lvtu.CANCEL_NOTIFICATION" else "com.ohyooo.lvtu.DOWNLOAD_PROGRESS")
                        .setClassName("com.ohyooo.lvtu", "com.ohyooo.lvtu.DownloadProgressReceiver")
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtras(state)
                application.sendBroadcast(
                    update,
                    null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                )
                return
            }
            val result: Bundle? = application.contentResolver.call(
                Uri.parse("content://com.ohyooo.lvtu.download"),
                if (task.deleted) "cancel" else "post",
                null,
                state
            )
            if (!finished && task.done == 0 && result != null && !result.getBoolean("allowed")) {
                downloadJobs.onMain(Runnable {
                    application.startActivity(
                        Intent().setClassName(
                            "com.ohyooo.lvtu",
                            "com.ohyooo.lvtu.NotificationPermissionActivity"
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                })
            }
        } catch (error: RuntimeException) {
            notificationUnavailable = true
            Log.e("LvTu", "下载通知不可用", error)
            reportDownload("下载通知不可用：" + error.message)
        }
    }

    private fun download(
        task: DownloadTask,
        plan: MutableList<MutableSet<Long>>
    ) {
        synchronized(downloadStateLock) {
            if (task.deleted || task.manualPaused || activeTask != task) return
            task.done = 0
            task.failed = 0
            task.persist()
        }
        notifyDownload(task, false)
        val progress = Any()
        val updatedAt = longArrayOf(SystemClock.elapsedRealtime())
        val changed = AtomicBoolean()
        try {
            val tiles: MutableList<DownloadTile> = ArrayList<DownloadTile>(task.total)
            for (z in plan.indices) for (key in plan.get(z)) {
                if (!task.labelsOnly) tiles.add(DownloadTile(task.style, z, key))
                if (task.style == 6) tiles.add(DownloadTile(8, z, key))
            }
            val next = AtomicInteger()
            downloadJobs.downloadWorkers(Supplier {
                object : ToLongFunction<BooleanSupplier> {
                    var tile: DownloadTile? = null
                    var attempts: Int = 0
                    val wake: WakeLock = application.getSystemService<PowerManager>(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lvtu:tiles")

                    override fun applyAsLong(running: BooleanSupplier): Long {
                        if (!running.getAsBoolean() || task.stopped.get()) return -1
                        if (tile == null) {
                            val index = next.getAndIncrement()
                            if (index >= tiles.size) return -1
                            tile = tiles.get(index)
                        }
                        val z = tile!!.zoom
                        val x = (tile!!.key shr 32).toInt()
                        val y = tile!!.key.toInt()
                        try {
                            val exists: Boolean
                            database!!.rawQuery(
                                "SELECT 1 FROM tiles WHERE style=? AND z=? AND x=? AND y=?",
                                arrayOf<String>("" + tile!!.style, "" + z, "" + x, "" + y)
                            ).use { cursor ->
                                exists = cursor.moveToFirst()
                            }
                            if (!exists) {
                                val connection =
                                    URL((if (tile!!.style == 7) "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7" else "https://webst01.is.autonavi.com/appmaptile?style=" + tile!!.style) + "&x=" + x + "&y=" + y + "&z=" + z)
                                        .openConnection() as HttpURLConnection
                                connection.setConnectTimeout(12000)
                                connection.setReadTimeout(15000)
                                wake.acquire(120000)
                                try {
                                    connection.getInputStream().use { input ->
                                        ByteArrayOutputStream().use { output ->
                                            val buffer = ByteArray(8192)
                                            var count: Int
                                            while ((input.read(buffer).also { count = it }) != -1) {
                                                if (!running.getAsBoolean() || task.stopped.get()) return -1
                                                output.write(buffer, 0, count)
                                            }
                                            val data = output.toByteArray()
                                            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                                                ?: throw IOException("服务器未返回有效瓦片")
                                            val valid = bitmap.width == 256 && bitmap.height == 256
                                            bitmap.recycle()
                                            if (!valid) throw IOException("服务器未返回256像素瓦片")
                                            if (!running.getAsBoolean() || task.stopped.get()) return -1
                                            database!!.execSQL(
                                                "INSERT OR REPLACE INTO tiles(style,z,x,y,data) VALUES(?,?,?,?,?)",
                                                arrayOf<Any>(tile!!.style, z, x, y, data)
                                            )
                                            cachedData = true
                                            changed.set(true)
                                        }
                                    }
                                } finally {
                                    connection.disconnect()
                                    if (wake.isHeld()) wake.release()
                                }
                            }
                            synchronized(progress) {
                                if (!running.getAsBoolean() || task.stopped.get()) return -1
                                task.done++
                                if (attempts > 0) task.failed--
                                attempts = 0
                                tile = null
                                if (SystemClock.elapsedRealtime() - updatedAt[0] >= 500) {
                                    updatedAt[0] = SystemClock.elapsedRealtime()
                                    task.persist()
                                    publishDownload(changed.getAndSet(false))
                                    notifyDownload(task, false)
                                }
                            }
                            return 0
                        } catch (error: IOException) {
                            if (!running.getAsBoolean() || task.stopped.get()) return -1
                            synchronized(progress) {
                                if (attempts++ == 0) task.failed++
                                task.persist()
                                publishDownload(false)
                                notifyDownload(task, false)
                            }
                            Log.w(
                                "LvTu",
                                "Tile retry z=" + z + " x=" + x + " y=" + y + " attempt=" + attempts,
                                error
                            )
                            return min(60000L, 1000L shl min(attempts - 1, 6))
                        } catch (error: SQLiteException) {
                            if (!running.getAsBoolean() || task.stopped.get()) return -1
                            synchronized(progress) {
                                if (attempts++ == 0) task.failed++
                                task.persist()
                                publishDownload(false)
                                notifyDownload(task, false)
                            }
                            Log.w(
                                "LvTu",
                                "Tile retry z=" + z + " x=" + x + " y=" + y + " attempt=" + attempts,
                                error
                            )
                            return min(60000L, 1000L shl min(attempts - 1, 6))
                        }
                    }
                }
            })
            if (!task.stopped.get()) reportDownload("下载完成，已处理 " + task.done + " 张")
        } finally {
            synchronized(downloadStateLock) {
                task.phase =
                    if (task.manualPaused) "paused" else if (task.done >= task.total && task.failed == 0) "done" else "downloading"
                task.persist()
            }
            notifyDownload(task, task.manualPaused || task.phase == "done")
            publishDownload(true)
        }
    }

    private data class RestoredDownload(
        val task: DownloadTask?,
        val tiles: MutableList<MutableSet<Long>>?
    )

    private data class DownloadTile(val style: Int, val zoom: Int, val key: Long)

    private data class CacheLevel(
        val style: Int,
        val zoom: Int,
        val count: Long,
        val bytes: Long
    )

    private data class CacheRect(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val corners: List<Any?>?
    )

    private data class CacheLayer(
        val style: Int,
        val zoom: Int,
        val world: Boolean,
        val rectangles: MutableList<CacheRect>?
    )

    private data class CacheInfo(val count: Long, val bytes: Long, val matching: Long)

    private data class PreviewPlan(
        val tiles: MutableList<MutableSet<Long>>,
        val boundaries: MutableList<MutableList<Any?>>,
        val cache: CacheInfo?
    )

    companion object {
        private const val ENABLE_MAP_HOOKS = true

        private fun recoverMapViews(root: View?, mapView: Class<*>): Int {
            if (mapView.isInstance(root)) {
                call(root!!, "getMap")
                return 1
            }
            var recovered = 0
            if (root is ViewGroup) for (i in 0..<root.childCount) recovered += recoverMapViews(
                root.getChildAt(i),
                mapView
            )
            return recovered
        }

        private fun call(obj: Any?, name: String, vararg args: Any?): Any? {
            for (m in obj!!.javaClass.methods) if (m.name == name && m.parameterCount == args.size) {
                try {
                    return m.invoke(obj, *args)
                } catch (e: ReflectiveOperationException) {
                    throw IllegalStateException(name, e)
                }
            }
            throw IllegalStateException("方法不存在: " + name)
        }

        private fun points(input: Any?): MutableList<DoubleArray> {
            val result: MutableList<DoubleArray> = ArrayList<DoubleArray>()
            for (p in (input as List<*>?)!!) result.add(
                doubleArrayOf(
                    p!!.javaClass.getField(
                        "latitude"
                    ).getDouble(p), p.javaClass.getField("longitude").getDouble(p)
                )
            )
            return result
        }

        fun upscaleTile(
            data: ByteArray,
            x: Int,
            y: Int,
            zoomGap: Int,
            transparent: Boolean = false
        ): ByteArray? {
            val source: Bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            val dest: Bitmap =
                Bitmap.createBitmap(256, 256, if (transparent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
            val scale = (1 shl zoomGap).toFloat()
            val left = -(x and ((1 shl zoomGap) - 1)) * 256f
            val top = -(y and ((1 shl zoomGap) - 1)) * 256f
            Canvas(dest).drawBitmap(
                source,
                null,
                RectF(left, top, left + scale * 256, top + scale * 256),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            val output = ByteArrayOutputStream()
            dest.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, output)
            source.recycle()
            dest.recycle()
            return output.toByteArray()
        }

        private fun tileY(lat: Double, n: Int): Double {
            val a = Math.toRadians(max(-85.05112878, min(85.05112878, lat)))
            return (1 - ln(tan(a) + 1 / cos(a)) / Math.PI) / 2 * n
        }

        private fun geodesic(input: MutableList<DoubleArray>): MutableList<DoubleArray> {
            val result: MutableList<DoubleArray> = ArrayList<DoubleArray>()
            for (i in 1..<input.size) {
                val a: DoubleArray = input.get(i - 1)
                val b: DoubleArray = input.get(i)
                val lat1 = Math.toRadians(a[0])
                val lon1 = Math.toRadians(a[1])
                val lat2 = Math.toRadians(b[0])
                val lon2 = Math.toRadians(b[1])
                val haversine: Double = sin((lat2 - lat1) / 2)
                    .pow(2.0) + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2)
                    .pow(2.0)
                val angle = 2 * asin(min(1.0, sqrt(haversine)))
                if (angle < 1e-8) {
                    result.add(a)
                    continue
                }
                val steps = max(1, ceil(angle * 6371 / 5).toInt())
                for (s in 0..<steps) {
                    val t = s / steps.toDouble()
                    val u = sin((1 - t) * angle) / sin(angle)
                    val v = sin(t * angle) / sin(angle)
                    val x =
                        u * cos(lat1) * cos(lon1) + v * cos(lat2) * cos(
                            lon2
                        )
                    val y =
                        u * cos(lat1) * sin(lon1) + v * cos(lat2) * sin(
                            lon2
                        )
                    val z = u * sin(lat1) + v * sin(lat2)
                    result.add(
                        doubleArrayOf(
                            Math.toDegrees(
                                atan2(
                                    z,
                                    hypot(x, y)
                                )
                            ), Math.toDegrees(atan2(y, x))
                        )
                    )
                }
            }
            if (!input.isEmpty()) result.add(input.get(input.size - 1))
            return result
        }

        private fun plan(
            routes: MutableList<MutableList<DoubleArray>>,
            km: Double,
            max: Int
        ): MutableList<MutableSet<Long>> {
            val result: MutableList<MutableSet<Long>> =
                ArrayList<MutableSet<Long>>()
            var total: Long = 0
            for (z in 0..max) {
                val n = 1 shl z
                val keys: MutableSet<Long> = HashSet<Long>()
                for (route in routes) for (i in 1..<route.size) {
                    val a: DoubleArray = route.get(i - 1)
                    val b: DoubleArray = route.get(i)
                    val ax = (a[1] + 180) / 360 * n
                    var bx = (b[1] + 180) / 360 * n
                    if (bx - ax > n / 2.0) bx -= n.toDouble()
                    if (ax - bx > n / 2.0) bx += n.toDouble()
                    val ay: Double = tileY(a[0], n)
                    val by: Double = tileY(b[0], n)
                    val steps = max(1, ceil(hypot(bx - ax, by - ay) * 2).toInt())
                    val latitude = max(abs(a[0]), abs(b[0]))
                    val radius =
                        km * 1000 * n / (40075016.686 * cos(Math.toRadians(latitude))) + 1.1
                    for (s in 0..steps) {
                        if (Thread.currentThread()
                                .isInterrupted()
                        ) throw CancellationException("过期规划已取消")
                        val x = ax + (bx - ax) * s / steps
                        val y = ay + (by - ay) * s / steps
                        val bottom = max(0, floor(y - radius).toInt())
                        val top = min(n - 1, floor(y + radius).toInt())
                        for (ty in bottom..top) {
                            val half =
                                sqrt(max(0.0, radius * radius - (ty + .5 - y) * (ty + .5 - y)))
                            for (tx in floor(x - half).toInt()..floor(x + half).toInt()) {
                                keys.add(
                                    (Math.floorMod(tx, n).toLong() shl 32) or (ty.toLong() and 0xffffffffL)
                                )
                                require(keys.size + total <= 250000) { "范围超过 25 万张瓦片，请减小距离或 zoom" }
                            }
                        }
                    }
                }
                total += keys.size.toLong()
                result.add(keys)
            }
            return result
        }
    }
}
