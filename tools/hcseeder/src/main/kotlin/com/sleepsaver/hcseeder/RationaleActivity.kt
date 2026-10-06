package com.sleepsaver.hcseeder

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Health Connect requires a rationale screen before it will grant permissions. */
class RationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Debug tool: writes sample sleep sessions for testing Sleep Saver." })
    }
}
