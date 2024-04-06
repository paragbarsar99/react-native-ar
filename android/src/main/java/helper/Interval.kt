import android.util.Log
import java.util.*

class IntervalRunner() {
    private var timer: Timer? = null

    fun start(intervalMillis: Long, action: () -> Unit) {
        Log.d("IntervalRunner","start")

        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                action()
            }
        }, 0, intervalMillis)
    }

    fun stop() {
        Log.d("IntervalRunner","stop")
        timer?.cancel()
        timer?.purge()
        timer = null
    }
}