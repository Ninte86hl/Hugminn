package io.github.ninte86hl.hugminn

import android.os.IBinder

/**
 * Runs as root in its own process (app_process with Hugminn's APK as class path) and calls the system
 * directly – for what the shell commands no longer offer on newer Android versions (moving an app's
 * task to another screen). Prints "ok" or the error.
 *
 *   CLASSPATH=<apk> app_process /system/bin io.github.ninte86hl.hugminn.SystemCall move <task> <display>
 */
class SystemCall {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java).invoke(null, "activity_task") as IBinder
                val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                when (args.getOrNull(0)) {
                    "move" -> {
                        val task = args[1].toInt()
                        val display = args[2].toInt()
                        val m = listOf("moveRootTaskToDisplay", "moveStackToDisplay").firstNotNullOfOrNull { name ->
                            runCatching { atm.javaClass.getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }.getOrNull()
                        } ?: error("keine Methode zum Verschieben")
                        m.invoke(atm, task, display)
                        println("ok ${m.name}")
                    }
                    "remove" -> {
                        val r = atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, args[1].toInt())
                        println("ok $r")
                    }
                    else -> println("unbekannt")
                }
            } catch (t: Throwable) {
                val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                println("Fehler: ${c.javaClass.simpleName}: ${c.message}")
            }
        }
    }
}
