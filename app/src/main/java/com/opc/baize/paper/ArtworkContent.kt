package com.opc.baize.paper

/** Bounds and reference policy apply before parsing, persistence or rendering. */
object ArtworkContent {
    val types=setOf("svg","text","image")
    private val checkedSvg=object:LinkedHashMap<String,Boolean>(8,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Boolean>?)=size>8
    }
    @Synchronized private fun checkSvgStructure(content:String) {
        if(checkedSvg.containsKey(content)) return
        require(content.toByteArray().size<=128000) {"SVG limit 128 KB"}
        try {
            val parser=javax.xml.parsers.SAXParserFactory.newInstance().apply {isNamespaceAware=true}.newSAXParser()
            parser.parse(org.xml.sax.InputSource(java.io.StringReader(content)),object:org.xml.sax.helpers.DefaultHandler() {
                private var depth=0;private var count=0
                override fun startElement(uri:String,localName:String,qName:String,attributes:org.xml.sax.Attributes) {
                    depth++;count++
                    require(depth<=64 && count<=1000) {"SVG nesting or element limit exceeded"}
                    require(uri.isEmpty() || uri=="http://www.w3.org/2000/svg") {"Unsupported SVG namespace"}
                    require(count!=1 || localName=="svg") {"SVG root required"}
                    require(localName.lowercase() !in setOf("script","foreignobject","image","use","animate","set"))
                    for(i in 0 until attributes.length) require(!attributes.getLocalName(i).lowercase().startsWith("on")) {"SVG event handlers are not supported"}
                }
                override fun endElement(uri:String,localName:String,qName:String) {depth--}
            })
        } catch(e:Exception) {throw IllegalArgumentException("Invalid SVG: ${e.message}",e)}
        checkedSvg[content]=true
    }
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
                checkSvgStructure(content)
            }
        }
    }
}
