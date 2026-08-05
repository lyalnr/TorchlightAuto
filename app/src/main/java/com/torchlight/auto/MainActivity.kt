package com.torchlight.auto
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import androidx.recyclerview.widget.*

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: LogAdapter
    private lateinit var totalText: TextView
    private lateinit var pathInput: EditText
    private var isMonitoring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        totalText = findViewById(R.id.totalText)
        pathInput = findViewById(R.id.pathInput)
        val startStop = findViewById<Button>(R.id.startStopButton)
        val floatToggle = findViewById<Button>(R.id.floatToggleButton)
        adapter = LogAdapter()
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        pathInput.setText("/storage/emulated/0/Android/data/com.xindong.torchlight/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log")

        startStop.setOnClickListener {
            if (isMonitoring) {
                stopService(Intent(this, LogMonitorService::class.java))
                isMonitoring = false
                startStop.text = "开始监控"
                Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
            } else {
                if (!Shizuku.pingBinder()) {
                    Toast.makeText(this, "请先启动 Shizuku", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                startService(Intent(this, LogMonitorService::class.java).putExtra("log_path", pathInput.text.toString()))
                isMonitoring = true
                startStop.text = "停止监控"
                Toast.makeText(this, "开始监控", Toast.LENGTH_SHORT).show()
            }
        }

        floatToggle.setOnClickListener {
            Toast.makeText(this, "悬浮窗功能需要单独实现", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateUI(entry: LogEntry) {
        runOnUiThread {
            adapter.addEntry(entry)
            totalText.text = "总火值: ${adapter.getTotalFire()}"
        }
    }
}
