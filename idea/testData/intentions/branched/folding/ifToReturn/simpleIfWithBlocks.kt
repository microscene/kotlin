fun test(n: Int): String {
    <caret>if (n == 1) {
        println("***")
        return "one"
    } else {
        println("***")
        return "two"
    }
}