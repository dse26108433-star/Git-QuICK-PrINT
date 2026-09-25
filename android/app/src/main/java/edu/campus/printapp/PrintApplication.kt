package edu.campus.printapp

import android.app.Application
import com.razorpay.Checkout

class PrintApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Loads Razorpay's payment screen in the background so "Pay" opens fast.
        Checkout.preload(applicationContext)
    }
}
