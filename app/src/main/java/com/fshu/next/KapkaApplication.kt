package com.fshu.next

import android.app.Application
import com.fshu.next.data.local.AppDatabase

class FshuApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}
