package com.ambient.tvclock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var textClock: TextView
    private lateinit var textSeconds: TextView
    private lateinit var calendarBinder: CalendarScreenBinder
    private lateinit var calendarPoller: CalendarPoller
    private val handler = Handler(Looper.getMainLooper())
    private var calendarDayOffset = 0
    private var calendarNavigationRequest = 0

    private val calendarListener: (CalendarSnapshot) -> Unit = { snapshot ->
        handler.post {
            if (calendarDayOffset == 0) calendarBinder.bind(snapshot)
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textClock = findViewById(R.id.textMainClock)
        textSeconds = findViewById(R.id.textMainSeconds)
        calendarBinder = CalendarScreenBinder(findViewById(android.R.id.content))
        calendarPoller = CalendarPoller(this)

        updateClock()
    }

    override fun onStart() {
        super.onStart()
        CalendarCenter.addListener(calendarListener)
        calendarPoller.start()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
    }

    override fun onStop() {
        handler.removeCallbacks(clockTick)
        calendarPoller.stop()
        CalendarCenter.removeListener(calendarListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        calendarPoller.publishNow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                calendarBinder.scrollBy(-scrollStep())
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                calendarBinder.scrollBy(scrollStep())
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showCalendarDay(calendarDayOffset - 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showCalendarDay(calendarDayOffset + 1)
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        textClock.text = LocalizedDateTime.formatClockTime(this, now)
        textSeconds.text = LocalizedDateTime.formatClockSeconds(this, now)
        calendarBinder.updateDateLine()
    }

    private fun showCalendarDay(dayOffset: Int) {
        calendarDayOffset = dayOffset
        calendarBinder.setDisplayedDayOffset(dayOffset)
        val request = ++calendarNavigationRequest

        Thread {
            val snapshot = CalendarRepository.refresh(applicationContext, dayOffset)
            handler.post {
                if (request == calendarNavigationRequest && calendarDayOffset == dayOffset) {
                    calendarBinder.bind(snapshot)
                }
            }
        }.start()
    }

    private fun scrollStep(): Int =
        (resources.displayMetrics.density * 140f).toInt()
}
