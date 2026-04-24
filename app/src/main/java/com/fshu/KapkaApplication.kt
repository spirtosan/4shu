package com.fshu

import android.app.Application
import com.fshu.data.local.AppDatabase

class FshuApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}
