// !JVM_DEFAULT_MODE: all-compatibility
// TARGET_BACKEND: JVM
// IGNORE_BACKEND_FIR: JVM_IR
// JVM_TARGET: 1.8
// WITH_RUNTIME

interface Base {
    fun f(o: String): String = g(o)

    private fun g(o: String): String = o
}

fun interface F : Base {
    fun invoke(o: String): String

    fun result(): String = invoke(f("O"))
}

fun box(): String {
    if (F { o -> o + "K" }.result() != "OK") return "Fail"

    val lambda: (String) -> String = { o -> o + "K" }
    return F(lambda).result()
}
