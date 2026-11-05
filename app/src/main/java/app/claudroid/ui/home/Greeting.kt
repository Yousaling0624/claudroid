package app.claudroid.ui.home

/** Time-of-day greeting used on the landing screen. Pure so the boundaries are testable. */
fun greetingForHour(hour: Int): String = when (hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}
