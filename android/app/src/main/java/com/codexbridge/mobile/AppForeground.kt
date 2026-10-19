package com.codexbridge.mobile

/**
 * Process-wide visibility flag owned by MainActivity's lifecycle. The
 * notification service cannot infer this from ActivityManager: appTasks keeps
 * reporting this app's task after it moves to the background.
 */
object AppForeground {
    @Volatile
    var visible: Boolean = false
}
