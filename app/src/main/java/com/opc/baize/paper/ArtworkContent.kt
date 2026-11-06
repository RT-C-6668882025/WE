package com.opc.baize.paper

/** Bounds and reference policy apply before parsing, persistence or rendering. */
object ArtworkContent {
    val types=setOf("svg","text","image")
    fun validate(type:String,content:String,fontSize:Float=24f) {
        require(fontSize.isFinite() && fontSize in 1f..1000f) { "font_size must be 1..1000" }
        if(type !in types) {require(content.isEmpty());return}
        require(content.isNotBlank()) { "Artwork content is empty" }
        when(type) {
            "text" -> require(content.length<=8000) { "Text limit 8000 characters" }
            "image" -> require(content.length<=350000 && content.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) { "PNG/JPEG base64 limit 350 KB" }
            "svg" -> {
                require(content.toByteArray().size<=128000) { "SVG limit 128 KB" }
                val lower=content.lowercase()
                require(!lower.contains("<!") && !lower.contains("<?") && !lower.contains("@import") && !lower.contains("@font-face") && !lower.contains("\\")) { "SVG declarations and external CSS are not supported" }
                require(!Regex("<\\s*(script|foreignobject|image|use|animate|set)\\b",RegexOption.IGNORE_CASE).containsMatchIn(content)) { "SVG must be static and self-contained" }
                require(!Regex("(?:href|src)\\s*=",RegexOption.IGNORE_CASE).containsMatchIn(content)) { "SVG references must use local url(#id) only" }
                require(Regex("url\\s*\\(",RegexOption.IGNORE_CASE).findAll(content).count()==Regex("url\\s*\\(\\s*['\"]?#[a-zA-Z0-9_.:-]+['\"]?\\s*\\)",RegexOption.IGNORE_CASE).findAll(content).count()) { "SVG external URLs are not supported" }
                require(Regex("<").findAll(content).count()<=2000) { "SVG element limit 2000" }
            }
        }
    }
}
