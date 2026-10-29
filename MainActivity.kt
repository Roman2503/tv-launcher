package com.minimal.tvlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

class MainActivity : Activity() {

    data class AppItem(
        val label: String,
        val packageName: String,
        val className: String,
        val icon: Drawable
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var rootLayout: FrameLayout
    private lateinit var gridView: GridView
    private lateinit var statusText: TextView
    private lateinit var bgButton: TextView
    private lateinit var wallpaperButton: TextView
    private lateinit var adapter: AppAdapter

    private val apps = mutableListOf<AppItem>()
    private var movingIndex = -1
    private var longPressTriggered = false
    private val numColumns = 5
    private val pickWallpaperRequest = 1001

    private val presetColors = intArrayOf(
        0xFF000000.toInt(),
        0xFF0B0F19.toInt(),
        0xFF121824.toInt(),
        0xFF071A15.toInt(),
        0xFF1F0B12.toInt(),
        0xFF181818.toInt()
    )

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        buildUi()
        applySavedBackground()
        loadApps()
        registerPackageReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun buildUi() {
        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(36), dp(28), dp(36), dp(28))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
        }

        val bgBtnId = View.generateViewId()
        val wpBtnId = View.generateViewId()
        val gridId = View.generateViewId()

        bgButton = createTopButton("Фон") {
            cyclePresetBackground()
        }.apply {
            id = bgBtnId
            nextFocusRightId = wpBtnId
            nextFocusDownId = gridId
        }

        wallpaperButton = createTopButton("Обои") {
            pickWallpaperFromStorage()
        }.apply {
            id = wpBtnId
            nextFocusLeftId = bgBtnId
            nextFocusDownId = gridId
        }

        statusText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "Удерживайте ОК на иконке для перемещения"
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topBar.addView(bgButton)
        topBar.addView(wallpaperButton)
        topBar.addView(statusText)
        topBar.addView(clock)

        gridView = GridView(this).apply {
            id = gridId
            nextFocusUpId = bgBtnId
            numColumns = this@MainActivity.numColumns
            verticalSpacing = dp(20)
            horizontalSpacing = dp(20)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            selector = GradientDrawable().apply {
                setColor(0x00000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnFocusChangeListener { _, _ ->
                refreshVisibleItems()
            }
        }

        adapter = AppAdapter()
        gridView.adapter = adapter

        gridView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (movingIndex != -1) {
                exitMoveMode()
            } else if (position in apps.indices) {
                launchApp(apps[position])
            }
        }

        gridView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            toggleMoveMode(position)
            true
        }

        gridView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshVisibleItems()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                refreshVisibleItems()
            }
        }

        mainContainer.addView(topBar)
        mainContainer.addView(gridView)
        rootLayout.addView(mainContainer)
        setContentView(rootLayout)
        gridView.requestFocus()
    }

    private fun toggleMoveMode(position: Int) {
        if (position !in apps.indices) return
        if (movingIndex == position) {
            exitMoveMode()
        } else {
            movingIndex = position
            statusText.setTextColor(0xFFFFD54F.toInt())
            statusText.text = "Перемещение: стрелки — двигать, ОК — закрепить"
            refreshVisibleItems()
        }
    }

    private fun refreshVisibleItems() {
        val first = gridView.firstVisiblePosition
        for (i in 0 until gridView.childCount) {
            val child = gridView.getChildAt(i) ?: continue
            val pos = first + i
            child.background = createTileDrawable(pos)
        }
    }

    private fun createTileDrawable(position: Int): GradientDrawable {
        val isSelected = gridView.hasFocus() && gridView.selectedItemPosition == position
        val isMoving = movingIndex == position
        return GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            when {
                isMoving -> {
                    setColor(0x55FFD54F)
                    setStroke(dp(3), 0xFFFFD54F.toInt())
                }
                isSelected -> {
                    setColor(0x44FFFFFF)
                    setStroke(dp(2), 0xFFFFFFFF.toInt())
                }
                else -> {
                    setColor(0x22000000)
                    setStroke(dp(1), 0x22FFFFFF)
                }
            }
        }
    }

    private fun createTopButton(title: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = createButtonDrawable(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(12)
            }
            setOnFocusChangeListener { _, hasFocus ->
                background = createButtonDrawable(hasFocus)
                if (hasFocus && movingIndex != -1) {
                    exitMoveMode()
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createButtonDrawable(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            if (focused) {
                setColor(0xFF263238.toInt())
                setStroke(dp(2), 0xFF64B5F6.toInt())
            } else {
                setColor(0x33FFFFFF)
                setStroke(dp(1), 0x22FFFFFF)
            }
        }
    }

    private fun cyclePresetBackground() {
        val nextIndex = (prefs.getInt("bg_color_index", 0) + 1) % presetColors.size
        prefs.edit()
            .putInt("bg_color_index", nextIndex)
            .remove("bg_uri")
            .apply()
        rootLayout.background = null
        rootLayout.setBackgroundColor(presetColors[nextIndex])
    }

    private fun pickWallpaperFromStorage() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/" + "*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, pickWallpaperRequest)
        } catch (_: Exception) {
            cyclePresetBackground()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickWallpaperRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            prefs.edit().putString("bg_uri", uri.toString()).apply()
            applySavedBackground()
        }
    }

    private fun applySavedBackground() {
        val savedUri = prefs.getString("bg_uri", null)
        if (!savedUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(savedUri)
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOptions)
                }
                var sampleSize = 1
                val maxWidth = 1920
                val maxHeight = 1080
                while (boundsOptions.outWidth / sampleSize > maxWidth ||
                    boundsOptions.outHeight / sampleSize > maxHeight
                ) {
                    sampleSize *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                }
                if (bitmap != null) {
                    rootLayout.background = BitmapDrawable(resources, bitmap)
                    return
                }
            } catch (_: Exception) {
            }
        }
        val index = prefs.getInt("bg_color_index", 0).coerceIn(0, presetColors.size - 1)
        rootLayout.background = null
        rootLayout.setBackgroundColor(presetColors[index])
    }

    private fun loadApps() {
        val pm = packageManager
        val discovered = LinkedHashMap<String, AppItem>()

        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )

        for (queryIntent in intents) {
            val resolveList = pm.queryIntentActivities(queryIntent, 0)
            for (info in resolveList) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || discovered.containsKey(pkg)) continue
                val label = info.loadLabel(pm)?.toString() ?: pkg
                val cls = info.activityInfo.name
                val icon = info.activityInfo.loadBanner(pm) ?: info.loadIcon(pm)
                discovered[pkg] = AppItem(label, pkg, cls, icon)
            }
        }

        val savedOrderString = prefs.getString("app_order", "") ?: ""
        val savedPackages = if (savedOrderString.isEmpty()) {
            emptyList()
        } else {
            savedOrderString.split("|")
        }

        val orderedList = mutableListOf<AppItem>()
        for (pkg in savedPackages) {
            val item = discovered.remove(pkg)
            if (item != null) {
                orderedList.add(item)
            }
        }

        val remaining = discovered.values.sortedBy { it.label.lowercase() }
        orderedList.addAll(remaining)

        apps.clear()
        apps.addAll(orderedList)
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun saveAppOrder() {
        val orderString = apps.joinToString("|") { it.packageName }
        prefs.edit().putString("app_order", orderString).apply()
    }

    private fun exitMoveMode() {
        movingIndex = -1
        statusText.setTextColor(0xFFB0BEC5.toInt())
        statusText.text = "Удерживайте ОК на иконке для перемещения"
        saveAppOrder()
        refreshVisibleItems()
    }

    private fun launchApp(item: AppItem) {
        try {
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(item.packageName, item.className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(launchIntent)
        } catch (_: Exception) {
            val fallback = packageManager.getLaunchIntentForPackage(item.packageName)
            if (fallback != null) {
                startActivity(fallback)
            }
        }
    }

    private fun isCenterKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (gridView.hasFocus() && isCenterKey(keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    longPressTriggered = false
                } else if (!longPressTriggered) {
                    longPressTriggered = true
                    toggleMoveMode(gridView.selectedItemPosition)
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    val pos = gridView.selectedItemPosition
                    if (movingIndex != -1) {
                        exitMoveMode()
                    } else if (pos in apps.indices) {
                        launchApp(apps[pos])
                    }
                }
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (movingIndex != -1) {
                    exitMoveMode()
                }
                return true
            }

            if (keyCode == KeyEvent.KEYCODE_MENU && gridView.hasFocus()) {
                toggleMoveMode(gridView.selectedItemPosition)
                return true
            }

            if (movingIndex != -1 && gridView.hasFocus()) {
                val targetIndex = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (movingIndex > 0) movingIndex - 1 else movingIndex
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (movingIndex < apps.size - 1) movingIndex + 1 else movingIndex
                    KeyEvent.KEYCODE_DPAD_UP -> if (movingIndex - numColumns >= 0) movingIndex - numColumns else movingIndex
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (movingIndex + numColumns < apps.size) movingIndex + numColumns else movingIndex
                    else -> -1
                }

                if (targetIndex != -1) {
                    if (targetIndex != movingIndex) {
                        val item = apps.removeAt(movingIndex)
                        apps.add(targetIndex, item)
                        movingIndex = targetIndex
                        saveAppOrder()
                        adapter.notifyDataSetChanged()
                        gridView.setSelection(targetIndex)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = apps.size

        override fun getItem(position: Int): Any = apps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: ViewHolder
            val view: LinearLayout

            if (convertView == null) {
                view = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(14), dp(12), dp(12))
                }

                val iconView = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(96), dp(64))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val titleView = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(8)
                    }
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }

                view.addView(iconView)
                view.addView(titleView)
                holder = ViewHolder(view, iconView, titleView)
                view.tag = holder
            } else {
                view = convertView as LinearLayout
                holder = view.tag as ViewHolder
            }

            val item = apps[position]
            holder.iconView.setImageDrawable(item.icon)
            holder.titleView.text = item.label
            holder.container.background = createTileDrawable(position)
            return view
        }
    }

    private class ViewHolder(
        val container: LinearLayout,
        val iconView: ImageView,
        val titleView: TextView
    )
}