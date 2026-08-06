package com.torchlight.auto.util

import android.content.Context
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class CrashLogger(private val ctx: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val log = "[崩溃] ${Date()}\n${sw.toString().take(3000)}"

        try {
            File(ctx.getExternalFilesDir(null), "torch_crash.log").appendText("$log\n\n")
        } catch (_: Exception) {}

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, log.take(300), Toast.LENGTH_LONG).show()
            Thread.sleep(3000)
        }

        defaultHandler?.uncaughtException(t, e)
    }

    companion object {
        fun init(ctx: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashLogger(ctx))
        }
    }
}
