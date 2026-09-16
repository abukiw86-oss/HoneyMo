package com.example.HoneyMo

import android.app.Application
import android.util.Log

class HoneyMoApp : Application() {

    companion object {
        private const val TAG = "HoneyMoApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate initialized")
    }
}
