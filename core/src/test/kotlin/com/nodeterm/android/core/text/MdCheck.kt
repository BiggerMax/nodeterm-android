import com.nodeterm.android.core.text.Markdown
import com.nodeterm.android.core.text.MdKind
fun main() {
    for (s in listOf("###### Deep", "####### Deep", "######## Deep")) {
        val r = Markdown.render(s)
        println("$s -> lines=${r.size} kind=${r.first().kind} level=${r.first().level}")
    }
}
