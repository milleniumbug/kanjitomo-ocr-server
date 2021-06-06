package com.milleniumbug

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import spark.Service.ignite
import java.io.ByteArrayInputStream
import java.lang.StringBuilder
import javax.imageio.ImageIO

@Serializable
data class SuccessStatus(val status: String, val result: String, val isVertical: Boolean)

@Serializable
data class FailureStatus(val status: String)

fun main(args: Array<String>) {
    val kanjiTomo = KanjiTomo2()
    kanjiTomo.loadData()

    val http = ignite().port(8080)
    http.post("/ocr") { request, response ->
        val bytes = request.bodyAsBytes()
        val image = ByteArrayInputStream(bytes).use { stream ->
            ImageIO.read(stream)
        }
        val areaTask = kanjiTomo.setTargetImage(image)
        val columns = kanjiTomo.getColumns(areaTask)
        val isVertical = columns.all { column -> column.vertical }
        val sortedColumns =
            if (isVertical) {
                columns.sortedByDescending { column -> column.rect.x }
            } else {
                columns.sortedBy { column -> column.rect.y }
            }
        val ocrText = StringBuilder()
        for (column in sortedColumns) {
            val result = kanjiTomo.runOCR(areaTask, column.areas)
            if (result != null) {
                ocrText.appendLine(result.bestMatchingCharacters)
            }
            else {
                response.status(400)
                response.header("Content-Type", "application/json")
                return@post Json.encodeToString(serializer<FailureStatus>(), FailureStatus("failure"))
            }
        }

        response.status(200)
        response.header("Content-Type", "application/json")
        Json.encodeToString(serializer<SuccessStatus>(), SuccessStatus("success", ocrText.toString(), isVertical))
    }
}

