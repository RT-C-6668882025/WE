package com.opc.baize.paper

import android.net.Uri
import java.io.File
import org.json.JSONObject

/** Local persistence contract, independent of Drive's future file transport. */
interface PaperStore {
    fun importPdf(uri: Uri): String
    fun pdf(id: String): File
    fun load(id: String, page: Int, width: Int, height: Int): PaperPage
    fun save(id: String, page: Int, data: PaperPage)
    fun submission(id: String, page: Int, name: String, data: PaperPage)
    fun result(id: String, page: Int, revision: Long, region: String, answer: String, snapshotName: String)
    fun results(id: String, page: Int): List<JSONObject>
}
