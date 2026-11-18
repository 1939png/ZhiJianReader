package com.qin.paperreader

import android.app.Application
import com.qin.paperreader.data.db.AppDatabase
import com.qin.paperreader.data.library.BackupManager
import com.qin.paperreader.data.library.LibraryRepository
import com.qin.paperreader.data.prefs.SettingsRepository

class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var repo: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var backup: BackupManager
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        repo = LibraryRepository(this, db)
        settings = SettingsRepository(this)
        backup = BackupManager(this, repo, settings)
    }
}
