package org.maplibre.navigation.android.navigation.ui.v5.utils.span

class TextSpanItem(private val span: Any, val spanText: String) : SpanItem {
    override fun getSpan(): Any = span
}
