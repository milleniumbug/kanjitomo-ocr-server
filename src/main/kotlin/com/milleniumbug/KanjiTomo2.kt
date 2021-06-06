package com.milleniumbug

import net.kanjitomo.*
import net.kanjitomo.area.AreaDetector
import net.kanjitomo.area.AreaTask
import net.kanjitomo.area.Column
import net.kanjitomo.area.SubImage
import net.kanjitomo.dictionary.MultiSearchResult
import net.kanjitomo.dictionary.SearchManager
import net.kanjitomo.dictionary.SearchMode
import net.kanjitomo.ocr.OCRManager
import net.kanjitomo.ocr.OCRTask
import net.kanjitomo.ocr.ReferenceMatrixCacheLoader
import net.kanjitomo.util.Parameters
import net.kanjitomo.util.PrintLevel
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage


/**
 * Main class of the KanjiTomo OCR library
 */
class KanjiTomo2 {
    private val par = Parameters.getInstance()
    private var ocr: OCRManager? = null
    private var searchManager: SearchManager? = null

    /**
     * Loads data structures into memory. This should be called first on it's own
     * thread as the program starts because loading data can take couple of seconds.
     * It's allowed to call this multiple times, results are cached and further calls
     * don't take any more time unless dictionary is changed.
     */
    @Throws(Exception::class)
    fun loadData() {
        if (ocr == null) {
            ocr = OCRManager()
            ocr!!.loadReferenceData()
            ReferenceMatrixCacheLoader().load()
            searchManager = SearchManager()
        }
        if (par.primaryDictionary != null) {
            searchManager!!.loadData()
            searchManager!!.waitForIndexing()
        }
    }

    /**
     * Sets the target image. This can be a screenshot around target characters or a whole page.
     */
    @Throws(Exception::class)
    fun setTargetImage(image: BufferedImage): AreaTask {
        if (ocr == null) {
            loadData()
        }
        val started = System.currentTimeMillis()
        val areas = detectAreas(image)
        val time = System.currentTimeMillis() - started
        if (par.isPrintDebug) {
            println("Target image processed, $time ms\n")
        }
        if (par.isPrintOutput && !par.isPrintDebug) {
            println("Target image processed\n")
        }
        return areas
    }

    /**
     * Gets columns detected from target image
     */
    fun getColumns(areaTask: AreaTask) : List<net.kanjitomo.Column> {
        val simpleColumns: MutableList<net.kanjitomo.Column> = ArrayList()
        for (column in areaTask.columns) {
            val simpleColumn = column.simpleColumn
            if (column.previousColumn != null) {
                simpleColumn.previousColumn = column.previousColumn.simpleColumn
            }
            if (column.nextColumn != null) {
                simpleColumn.nextColumn = column.nextColumn.simpleColumn
            }
            simpleColumns.add(simpleColumn)
        }
        return simpleColumns
    }

    /**
     * Runs OCR starting from a point.
     *
     * @param point Coordinates inside target image. Closest character near this point
     * will be selected as the first target character. Point should correspond to mouse
     * cursor position relative to target image.
     *
     * @return null if no characters found near point
     */
    @Throws(Exception::class)
    fun runOCR(areaTask: AreaTask, point: Point): OCRResults? {
        if (par.isPrintOutput) {
            println("Run OCR at point:" + point.getX().toInt() + "," + point.getY().toInt())
        }

        // select areas near point
        val subImages = areaTask.getSubImages(point)
        return runOCR(subImages)
    }

    /**
     * Runs OCR inside pre-defined areas where each rectangle contains single characters.
     * This can be used if area detection is done externally and KanjiTomo is only used for final OCR.
     */
    @Throws(Exception::class)
    fun runOCR(areaTask: AreaTask, areas: List<Rectangle?>?): OCRResults? {
        if (par.isPrintOutput) {
            println("Run OCR rectangle list")
        }

        // build subimages from argument areas
        val subImages = areaTask.getSubImages(areas)
        return runOCR(subImages)
    }

    /**
     * Runs OCR for target areas (SubImages)
     */
    @Throws(Exception::class)
    private fun runOCR(subImages : List<SubImage>): OCRResults? {
        var multiSearchResult: MultiSearchResult? = null
        val started = System.currentTimeMillis()
        var verticalOrientation = true
        // get target locations
        val locations = ArrayList<Rectangle>()
        for (subImage in subImages) {
            locations.add(subImage.location)
            verticalOrientation = subImage.isVertical
        }
        if (subImages.isEmpty()) {
            if (par.isPrintOutput) {
                println("No characters identified")
            }
            return null
        }

        // run ocr for each character
        val ocrTasks = ArrayList<OCRTask>()
        var charIndex = 0
        var lastColumn: Column? = null
        for (subImage in subImages) {
            val ocrTask = OCRTask(subImage.image)
            ocrTask.charIndex = charIndex++
            ocrTasks.add(ocrTask)
            if (lastColumn == null) {
                lastColumn = subImage.column
            } else {
                if (lastColumn !== subImage.column) {
                    ocrTask.columnChanged = true
                }
            }
            ocr!!.addTask(ocrTask)
        }
        ocr!!.waitUntilDone()

        // collect identified characters
        val characters = ArrayList<String>()
        val ocrScores = ArrayList<List<Int>>()
        for (ocrTask in ocrTasks) {
            characters.add(ocrTask.resultString)
            val scores: MutableList<Int> = ArrayList()
            for (result in ocrTask.results) {
                scores.add(result.score)
            }
            ocrScores.add(scores)
        }
        if (par.primaryDictionary != null) {
            // cut off characters from other columns
            // This is used to restrict multisearch to characters within single column, multi-column
            // multisearch is too unreliable since it can often connect unrelated characters into single word.
            var maxWidth = 0
            for (task in ocrTasks) {
                if (task.columnChanged) {
                    break
                }
                ++maxWidth
            }
            multiSearchResult = searchManager!!.multiSearch(characters, ocrScores, maxWidth)
        }

        // wrap results into final object
        val results = if (par.primaryDictionary != null) {
            OCRResults(
                characters, locations, ocrScores,
                multiSearchResult!!.words, multiSearchResult.searchStr, verticalOrientation
            )
        } else {
            OCRResults(characters, locations, ocrScores, null, null, verticalOrientation)
        }
        val time = System.currentTimeMillis() - started
        if (par.isPrintOutput) {
            println(
                """
                    ${results.toString()}
                    
                    """.trimIndent()
            )
        }
        if (par.isPrintDebug) {
            println("OCR runtime $time ms\n")
        }
        return results
    }

    /**
     * Analyzes the image and detects areas that might contain characters.
     */
    @Throws(Exception::class)
    private fun detectAreas(image: BufferedImage): AreaTask {
        val areaTask = AreaTask(image)
        AreaDetector().run(areaTask)
        return areaTask
    }

    /**
     * Searches words from selected dictionary.
     *
     * @param searchString Search term supplied by the user or read from OCR results
     *
     * @param startsWith If true, only words starting with the searchString are
     * considered. If false, searchString can appear anywhere in the word (kanji or
     * kana fields, search from description field is not supported)
     *
     * @return List of maching words sorted by increasing length of kanji/kana field
     */
    @Throws(Exception::class)
    fun searchDictionary(searchString: String?, startsWith: Boolean): List<Word> {
        return searchManager!!.search(searchString, if (startsWith) SearchMode.STARTS_WITH else SearchMode.CONTAINS)
    }

    /**
     * Sets which dictionary is used. It's recommended to call loadData() after changing the dictionary
     * in background thread before user interaction. It's possible to turn off dictionary search
     * by setting primaryDictionary to null but this is not recommended since search is used to
     * refine OCR results.
     *
     * @param primaryDictionary First dictionary used for searching
     * @param secondaryDictionary If a match is not found from primary dictionary, secondary is used for searching
     */
    fun setDictionary(primaryDictionary: DictionaryType, secondaryDictionary: DictionaryType) {
        par.primaryDictionary = primaryDictionary
        par.secondaryDictionary = secondaryDictionary
        if (primaryDictionary == DictionaryType.CHINESE || secondaryDictionary == DictionaryType.CHINESE) {
            throw Error("Chinese dictionary is not implemented")
        }
    }

    /**
     * Sets the reading direction. Default is automatic.
     *
     * Target image needs to be re-analyzed after changing the orientation by calling setTargetImage again.
     */
    fun setOrientation(orientation: Orientation?) {
        par.orientationTarget = orientation
    }

    /**
     * Sets character and background color. Black and white characters work best,
     * but coloured characters might also work if there's enough contrast.
     *
     * Target image needs to be re-analyzed after changing the color by calling setTargetImage again.
     *
     * Default: CharacterColor.AUTOMATIC
     */
    fun setCharacterColor(color: CharacterColor?) {
        par.colorTarget = color
    }

    /**
     * If true, OCR results are printed to stdout.
     * If false, nothing is printed.
     * Default: false
     */
    fun setPrintOutput(printOutput: Boolean) {
        if (printOutput == false) {
            par.printLevel = PrintLevel.OFF
        } else {
            par.printLevel = PrintLevel.BASIC
        }
    }

    /**
     * Stops all threads. This should be called before closing the program.
     */
    fun close() {
        ocr!!.stopThreads()
    }
}