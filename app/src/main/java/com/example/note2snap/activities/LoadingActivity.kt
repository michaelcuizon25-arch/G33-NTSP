package com.example.note2snap.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.note2snap.R

class LoadingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)

        val step1 = findViewById<TextView>(R.id.tvStep1Check)
        val step2 = findViewById<TextView>(R.id.tvStep2Check)
        val step3 = findViewById<TextView>(R.id.tvStep3Check)
        val step4 = findViewById<TextView>(R.id.tvStep4Check)
        val step5 = findViewById<TextView>(R.id.tvStep5Check)

        Handler(Looper.getMainLooper()).postDelayed({ step1?.text = "✔" }, 600)
        Handler(Looper.getMainLooper()).postDelayed({ step2?.text = "✔" }, 1200)
        Handler(Looper.getMainLooper()).postDelayed({ step3?.text = "✔" }, 1800)
        Handler(Looper.getMainLooper()).postDelayed({ step4?.text = "✔" }, 2400)
        Handler(Looper.getMainLooper()).postDelayed({
            step5?.text = "✔"
            startActivity(Intent(this, SaveFolderActivity::class.java))
            finish()
        }, 3000)
    }
}