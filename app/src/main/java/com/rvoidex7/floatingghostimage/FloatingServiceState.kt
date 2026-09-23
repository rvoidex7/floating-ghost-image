package com.rvoidex7.floatingghostimage

import android.content.Context
import android.content.SharedPreferences

object FloatingServiceState {

    private const val PREFS = "fgi_state"
    private const val KEY_RUNNING = "service_running"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRunning(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RUNNING, false)

    fun setRunning(ctx: Context, running: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RUNNING, running).apply()
    }
}